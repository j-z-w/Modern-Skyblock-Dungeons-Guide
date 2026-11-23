/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026  cyoung06 (syeyoung)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// bounds the off-heap .pfres node buffers kept resident for instant route switches.
// previously a SoftReference let the GC decide; now an explicit LRU (bytes + count)
// evicts the oldest buffer - PathfindPrecalculation releases it once its last
// pathfinder closes.
public final class PrecalcBufferCache {
    private static final long MAX_BYTES = Long.parseLong(
            System.getProperty("dg.precalcCache.maxBytes", "268435456")); // 256 MB
    private static final int MAX_ENTRIES = Integer.parseInt(
            System.getProperty("dg.precalcCache.maxEntries", "128"));

    // on-demand route requests always jump ahead of speculative prefetching; equal
    // priorities stay FIFO
    public static final int PRIORITY_ONDEMAND = 0;
    public static final int PRIORITY_PREFETCH = 10;

    private static final AtomicLong LOAD_SEQ = new AtomicLong();

    // package-private: PathfindPrecalculation holds the handle to promote its queued load
    static class PrioritizedLoad implements Runnable, Comparable<PrioritizedLoad> {
        private final int priority;
        private final long seq = LOAD_SEQ.getAndIncrement();
        private final Runnable task;

        private PrioritizedLoad(int priority, Runnable task) {
            this.priority = priority;
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PrioritizedLoad other) {
            int c = Integer.compare(priority, other.priority);
            return c != 0 ? c : Long.compare(seq, other.seq);
        }
    }

    // registered like the other DG executors (registerExecutorService is currently a
    // bookkeeping no-op, no shutdown hook exists). daemon threads so a stuck pool can't
    // hold the game or a test jvm open
    private static final ExecutorService LOADER = DungeonsGuide.getDungeonsGuide().registerExecutorService(
            new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new PriorityBlockingQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                            .setDaemon(true)
                            .setNameFormat("DG-PrecalcLoader-%d").build()) {
                @Override
                public void execute(Runnable command) {
                    // anything that didn't come through submitLoad is treated as on-demand
                    super.execute(command instanceof PrioritizedLoad ? command
                            : new PrioritizedLoad(PRIORITY_ONDEMAND, command));
                }
            });

    // access-ordered LRU of precalcs with a resident buffer. PathfindPrecalculation has
    // identity equals/hashCode - the mutable buffer state can't corrupt the buckets
    private static final LinkedHashMap<PathfindPrecalculation, Long> LRU =
            new LinkedHashMap<>(64, 0.75f, true);
    private static long totalBytes = 0;

    private PrecalcBufferCache() {}

    public static ExecutorService loader() {
        return LOADER;
    }

    public static void submitLoad(int priority, Runnable task) {
        submitPrioritized(priority, task);
    }

    // returns the queued handle so the caller can promote it later (promoteLoad)
    static PrioritizedLoad submitPrioritized(int priority, Runnable task) {
        PrioritizedLoad load = new PrioritizedLoad(priority, task);
        LOADER.execute(load);
        return load;
    }

    // moves a still-queued load ahead of older lower-priority work - e.g. a prefetch the
    // active route turned out to need. a no-op once a loader thread already dequeued it
    static void promoteLoad(PrioritizedLoad load, int priority) {
        if (((ThreadPoolExecutor) LOADER).getQueue().remove(load)) {
            LOADER.execute(new PrioritizedLoad(priority, load.task));
        }
    }

    public static synchronized void touch(PathfindPrecalculation precalc) {
        // re-check residency inside the monitor: an evictBuffer() racing a touch can clear
        // the published buffer between the caller's load and this lock - counting an
        // evicted buffer would leak the entry until the next eviction pass removed it
        if (precalc.residentBuffer() == null) return;
        long bytes = precalc.getBufferSizeBytes();
        if (LRU.put(precalc, bytes) == null) totalBytes += bytes;
        evictIfNeeded();
    }

    private static void evictIfNeeded() {
        Iterator<Map.Entry<PathfindPrecalculation, Long>> it = LRU.entrySet().iterator();
        while ((totalBytes > MAX_BYTES || LRU.size() > MAX_ENTRIES) && it.hasNext()) {
            Map.Entry<PathfindPrecalculation, Long> eldest = it.next();
            it.remove();
            totalBytes -= eldest.getValue();
            // evictBufferInternal, not evictBuffer: the entry is already gone, and untouch
            // re-entering LRU.remove() mid-iteration would trip fail-fast
            eldest.getKey().evictBufferInternal();
        }
    }

    // evictBuffer() repopulates bytes below as entries leave
    public static synchronized void untouch(PathfindPrecalculation precalc) {
        Long bytes = LRU.remove(precalc);
        if (bytes != null) totalBytes -= bytes;
    }

    // drops the precalc from the LRU and its resident buffer in one monitor hold. doing
    // the untouch() and the buffer eviction as separate calls let a concurrent reload
    // publish a fresh keepAlive buffer between them and then lose its LRU entry to the
    // untouch - a permanently leaked, untracked off-heap allocation
    public static synchronized void evict(PathfindPrecalculation precalc) {
        untouch(precalc);
        precalc.evictBufferInternal();
    }

    // prefetch every precalculation a room preset can request, on the loader threads
    public static void prefetch(RoomPreset roomPreset) {
        // snapshot on the calling thread: getPrecalculations() is a live view of a mutable
        // HashSet that preset edits can mutate from the render thread (and may be null)
        if (roomPreset.getPrecalculations() == null) return;
        List<String> precalcIds = new ArrayList<>(roomPreset.getPrecalculations());
        submitLoad(PRIORITY_PREFETCH, () -> {
            for (String precalcId : precalcIds) {
                try {
                    PathfindPrecalculation precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                    if (precalc != null) precalc.preload(PRIORITY_PREFETCH);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    // test hooks
    public static synchronized int cachedCount() {
        return LRU.size();
    }

    public static synchronized long cachedBytes() {
        return totalBytes;
    }

    public static synchronized void evictAll() {
        // copy out first: evictBuffer republishes via untouch(), mutating LRU mid-iteration
        List<PathfindPrecalculation> victims = new ArrayList<>(LRU.keySet());
        LRU.clear();
        totalBytes = 0;
        for (PathfindPrecalculation precalc : victims) {
            precalc.evictBufferInternal();
        }
    }
}
