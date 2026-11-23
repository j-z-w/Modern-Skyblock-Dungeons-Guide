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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// bounds the off-heap .pfres node buffers kept resident for instant route switches.
// previously a SoftReference let the GC decide; now an explicit LRU (bytes + count)
// evicts the oldest buffer - PathfindPrecalculation releases it once its last
// pathfinder closes.
public final class PrecalcBufferCache {
    private static final long MAX_BYTES = Long.parseLong(
            System.getProperty("dg.precalcCache.maxBytes", "268435456")); // 256 MB
    private static final int MAX_ENTRIES = Integer.parseInt(
            System.getProperty("dg.precalcCache.maxEntries", "128"));

    // registered like the other DG executors so it is shut down with the rest of the mod
    private static final ExecutorService LOADER = DungeonsGuide.getDungeonsGuide().registerExecutorService(
            Executors.newFixedThreadPool(2,
                    new ThreadFactoryBuilder()
                            .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                            .setNameFormat("DG-PrecalcLoader-%d").build()));

    // access-ordered LRU of precalcs with a resident buffer
    private static final LinkedHashMap<PathfindPrecalculation, Long> LRU =
            new LinkedHashMap<>(64, 0.75f, true);
    private static long totalBytes = 0;

    private PrecalcBufferCache() {}

    public static ExecutorService loader() {
        return LOADER;
    }

    public static synchronized void touch(PathfindPrecalculation precalc) {
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
            eldest.getKey().evictBuffer();
        }
    }

    // evictBuffer() repopulates bytes below as entries leave
    public static synchronized void untouch(PathfindPrecalculation precalc) {
        Long bytes = LRU.remove(precalc);
        if (bytes != null) totalBytes -= bytes;
    }

    // prefetch every precalculation a room preset can request, on the loader threads
    public static void prefetch(RoomPreset roomPreset) {
        LOADER.execute(() -> {
            for (String precalcId : roomPreset.getPrecalculations()) {
                try {
                    PathfindPrecalculation precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                    if (precalc != null) precalc.preload();
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
        for (Map.Entry<PathfindPrecalculation, Long> entry : LRU.entrySet()) {
            entry.getKey().evictBuffer();
        }
        LRU.clear();
        totalBytes = 0;
    }
}
