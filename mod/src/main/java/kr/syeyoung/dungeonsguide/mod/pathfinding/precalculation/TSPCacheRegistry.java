package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractActionMove;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AtomicAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonMapLayout;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.tooltip.WidgetNotificationProgress;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.precalclist.AdditionalInfoCaculatedDungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class TSPCacheRegistry {
    private static final Logger LOGGER = LogManager.getLogger("DG-TSPCache");
    private static Map<RoomPreset, TSPCacheCalculationTask> calculationTaskWeakHashMap = Collections.synchronizedMap(new WeakHashMap<>());
    // DG-Init registers while the DG-TSPCache-* workers read: needs a happens-before edge
    private static Map<String, TSPCache> mapping = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler = DungeonsGuide.getDungeonsGuide().registerExecutorService(
            Executors.newScheduledThreadPool(4, new ThreadFactoryBuilder()
                    .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                    .setNameFormat("DG-TSPCache-%d").build()));

    // on-demand builds bypass the scheduler: its 4 workers hold up to ~146 delayed
    // room builds at once, and an awaiting route solve would queue behind all of them.
    // bounded + daemon + NORM priority: an unbounded MAX_PRIORITY pool could starve
    // the render thread and a stuck matrix build kept the JVM alive on exit. a real
    // queue, not a SynchronousQueue: with zero queueing every build past the 2
    // workers was rejected and silently dropped the preset's on-demand matrix
    private static final java.util.concurrent.ThreadPoolExecutor onDemandPool =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(16),
                    new ThreadFactoryBuilder()
                            .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                            .setDaemon(true)
                            .setNameFormat("DG-TSPCache-OnDemand-%d").build());
    static {
        onDemandPool.allowCoreThreadTimeOut(true);
    }
    private static final java.util.concurrent.ExecutorService onDemandExecutor =
            DungeonsGuide.getDungeonsGuide().registerExecutorService(onDemandPool);

    // at most one in-flight on-demand build per preset: concurrent route solves for
    // the same room used to stack identical builds on the pool. entries are removed
    // by the future's own whenComplete, not by its waiters - a waiter that abandons
    // early (stale route, spent budget) must not leave a stale completed future
    // behind that hands every later caller its cached null
    private static final java.util.concurrent.ConcurrentMap<RoomPreset, java.util.concurrent.CompletableFuture<TSPCache>> inFlightBuilds =
            new java.util.concurrent.ConcurrentHashMap<>();

    // consecutive on-demand failures per preset -> the time a new build may be
    // submitted again. a failed build is never cached as a completed null future:
    // it can be retried, just not in a hot loop (a permanently broken preset - say a
    // missing pfres - would otherwise burn a worker per route solve)
    private static final java.util.concurrent.ConcurrentMap<RoomPreset, Integer> consecutiveBuildFailures =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<RoomPreset, Long> buildBackoffUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AtomicInteger remaining = new AtomicInteger();


    private UUID tspCacheBuilding = UUID.randomUUID();

    private volatile WidgetNotificationProgress progress;
    private volatile WidgetNotificationProgress.Progress requestProgress;

    public void updateTooltip() {
        int rem = remaining.get();
        if (FeatureRegistry.NOTIFICATIONS.getRootWidget() == null) return;
        if (rem == 0) {
            FeatureRegistry.NOTIFICATIONS.getRootWidget().removeNotification(tspCacheBuilding);
            progress = null;
            requestProgress = null;
            return;
        }
        if (progress == null) {
            progress = new WidgetNotificationProgress(tspCacheBuilding, "TSP Cache Building Progress");
            requestProgress = new WidgetNotificationProgress.Progress ("Remaining... "+remaining.get(),null, null, false);
            progress.addProgress(requestProgress);
            FeatureRegistry.NOTIFICATIONS.getRootWidget().updateNotification(tspCacheBuilding, progress);
        }

        requestProgress.setMessage("Remaining... "+remaining.get());
    }

    private final File dir;
    @Getter
    private static volatile TSPCacheRegistry INSTANCE;
    // drops once the first registry finished scanning its .cache files - worker-side
    // awaitTSPCache callers wait on this rather than rebuilding from .pfres files while
    // the disk caches are still being read on DG-Init
    private static volatile java.util.concurrent.CountDownLatch initLatch = new java.util.concurrent.CountDownLatch(1);
    public static boolean awaitInit(long timeoutMs) {
        try {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return INSTANCE != null;
        }
    }

    // lets a failed registry setup still release awaitTSPCache waiters: without it a
    // crash before the constructor runs left route solves parked for the full cache
    // wait budget. idempotent - countDown on an empty latch is a no-op
    public static void markInitComplete() {
        initLatch.countDown();
    }

    public TSPCacheRegistry(File dir) {
        this(dir, true);
    }

    // load=false for tests: skips reading caches and scheduling tasks (scheduling touches the notifications widget)
    TSPCacheRegistry(File dir, boolean load) {
        this.dir = dir;
        try {
            if (load) load();
        } finally {
            // publish only after the .cache scan finished: an early getINSTANCE() let
            // awaitTSPCache rebuild a matrix from .pfres while its .cache was mid-read
            INSTANCE = this;
            initLatch.countDown();
        }
    }


    public void load() {
        // listFiles returns null on IO errors / a missing dir, not an empty array
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        for (File file : files) {
            CBORMapper cborMapper = new CBORMapper();
            try {
                TSPCache tspCache = cborMapper.readValue(file, TSPCache.class);
                registerTSPCache(tspCache);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        for (PathfindPreset loadedPreset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            loadPreset(loadedPreset);
        }
    }

    public void loadPreset(PathfindPreset preset) {
        for (RoomPreset value : preset.getPresets().values()) {
            if (mapping.containsKey(value.getTSPCache())) continue;
            // computeIfAbsent: a plain put overwrote a task already created by
            // awaitTSPCache, breaking the synchronized(task) build coalescing
            TSPCacheCalculationTask task = calculationTaskWeakHashMap.computeIfAbsent(value,
                    v -> new TSPCacheCalculationTask(new AtomicInteger(0), v));

            if (task.cnt.getAndIncrement() == 0)
                remaining.incrementAndGet();
            scheduler.schedule(() -> {
                if (task.cnt.decrementAndGet() != 0) {
                    return;
                }
                try {
                    task.run();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } finally {
                    remaining.decrementAndGet();
                    updateTooltip();
                }
            }, 5, TimeUnit.SECONDS);
        }
        updateTooltip();
    }

    public void invalidateTSPCache(RoomPreset roomPreset) {
        TSPCacheCalculationTask task = calculationTaskWeakHashMap.computeIfAbsent(roomPreset, (roomPreset1) -> new TSPCacheCalculationTask(new AtomicInteger(0), roomPreset));
        if (task.cnt.getAndIncrement() == 0)
            remaining.incrementAndGet();
        updateTooltip();
        scheduler.schedule(() -> {
            if (task.cnt.decrementAndGet() != 0) {
                return;
            }
            try {
                task.run();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                remaining.decrementAndGet();
                updateTooltip();
            }
        }, 5, TimeUnit.SECONDS);
    }

    public TSPCache getTSPCache(String tspCacheId) {
        return mapping.get(tspCacheId);
    }

    // worker-thread entry point for route solves: returns the room's matrix when it is
    // already resident, otherwise builds it on demand instead of waiting for the
    // 5s-coalesced scheduled task (a solve that starts while the matrix is merely
    // still loading would otherwise fall into a pathfinder build per cost eval -
    // the smart-route ordering hang). bounded by timeoutMs so a degenerate build
    // cannot park a route worker forever; returns null on timeout/failure and the
    // caller degrades to the pfres path.
    public TSPCache awaitTSPCache(RoomPreset roomPreset, long timeoutMs) {
        return awaitTSPCache(roomPreset, timeoutMs, null);
    }

    // `stale` lets a superseded route solve abandon the wait early: the get is sliced
    // so cancellation is observed instead of parking for the rest of a doomed wait
    public TSPCache awaitTSPCache(RoomPreset roomPreset, long timeoutMs, java.util.function.BooleanSupplier stale) {
        String id = roomPreset.getTSPCache();
        TSPCache cached = mapping.get(id);
        if (cached != null) return cached;
        // a spent budget must not even park on the future: get(0) still costs a
        // queue-handoff round-trip the caller asked to skip
        if (timeoutMs <= 0) return null;

        // a recent on-demand build for this preset failed: don't resubmit inside
        // the backoff window AND don't stall the solve waiting on a matrix that
        // may never land - degrade to the per-precalc/heuristic path now. the
        // 5s-coalesced scheduled task can still populate mapping for the next solve
        Long notBefore = buildBackoffUntil.get(roomPreset);
        if (notBefore != null && System.currentTimeMillis() < notBefore) {
            return mapping.get(id);
        }

        TSPCacheCalculationTask task = calculationTaskWeakHashMap.computeIfAbsent(roomPreset,
                rp -> new TSPCacheCalculationTask(new AtomicInteger(0), rp));
        // share one build per preset: concurrent solves for the same room join the
        // same future instead of submitting duplicate task.run()s. the future is
        // claimed with putIfAbsent and the task submitted OUTSIDE the map's bin
        // lock - mutating inFlightBuilds from a whenComplete registered inside
        // computeIfAbsent threw IllegalStateException: Recursive update whenever
        // the build settled before the mapping lambda returned
        java.util.concurrent.CompletableFuture<TSPCache> created =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<TSPCache> future =
                inFlightBuilds.putIfAbsent(roomPreset, created);
        if (future == null) {
            future = created;
            try {
                onDemandExecutor.execute(() -> {
                    TSPCache result = null;
                    try {
                        task.run();
                    } catch (Throwable t) {
                        LOGGER.warn("on-demand tsp cache build for {} failed", roomPreset.getRoomId(), t);
                    } finally {
                        result = mapping.get(id);
                        // backoff is armed (or cleared) BEFORE the slot is freed and
                        // waiters are released, so a released waiter that retries
                        // immediately sees it and can't submit a duplicate build
                        if (result != null) {
                            consecutiveBuildFailures.remove(roomPreset);
                            buildBackoffUntil.remove(roomPreset);
                        } else {
                            int failures = consecutiveBuildFailures.merge(roomPreset, 1, Integer::sum);
                            buildBackoffUntil.put(roomPreset, System.currentTimeMillis()
                                    + Math.min(60_000L, 1_000L << Math.min(failures, 6)));
                        }
                        // the BUILD frees its own slot before completing: a waiter
                        // released by complete() must not re-claim behind a stale entry
                        inFlightBuilds.remove(roomPreset, created);
                        created.complete(result);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // wake any waiter that already joined this future instead of leaving it parked
                inFlightBuilds.remove(roomPreset, created);
                TSPCache fallback = mapping.get(id);
                created.complete(fallback);
                return fallback;
            }
        }
        try {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                if (stale != null && stale.getAsBoolean()) return mapping.get(id);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return mapping.get(id);
                try {
                    return future.get(Math.min(50, Math.max(1, remaining / 1_000_000L)), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException slice) {
                    // re-check stale + budget on the next slice
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return mapping.get(id);
        } catch (java.util.concurrent.ExecutionException e) {
            return mapping.get(id);
        }
    }

    public void registerTSPCache(TSPCache cache) {
        mapping.put(cache.getId(), cache);
    }

    @AllArgsConstructor
    @Getter @Setter
    public class TSPCacheCalculationTask {
        private AtomicInteger cnt = new AtomicInteger(0);
        private RoomPreset roomPreset;

        public void run() throws NoSuchAlgorithmException {
            // coalesce concurrent builders (the 5s-delayed scheduled task, invalidate
            // rebuilds, and on-demand awaits) - the re-check inside makes the second
            // one a no-op
            synchronized (this) {
            try {
                Set<String> precalcIds = new HashSet<>(roomPreset.getPrecalculations());

                String hashIn = precalcIds
                        .stream().sorted()
                        .collect(Collectors.joining(";"));

                MessageDigest md = MessageDigest.getInstance("MD5");
                String hash = Hex.encodeHexString(md.digest(hashIn.getBytes()));

                if (getTSPCache(hash) != null) return;

                DungeonRoomInfo dungeonRoomInfo = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());

                // the preset owning this roomPreset, not whatever happens to be selected (they can differ and
                // the selected one may not contain this room at all)
                DRIWorld driWorld = new DRIWorld(dungeonRoomInfo, roomPreset.getParent());
                DungeonContext fakeContext = new DungeonContext("TEST DG", driWorld, roomPreset.getParent(), false);
                try {
                    DungeonMapLayout dungeonMapLayout = new DungeonMapLayout(
                            new Dimension(16, 16),
                            5,
                            new Point(0, 0),
                            new BlockPos(0, 70, 0)
                    );
                    fakeContext.setScaffoldParser(new DungeonRoomScaffoldParser(dungeonMapLayout, fakeContext));
                    DungeonRoom dungeonRoom = new DungeonRoom(fakeContext);

                    // build tsp cache.
                    ActionDAG dag = AdditionalInfoCaculatedDungeonRoomInfo.buildReferencingAllPossibleThings(dungeonRoom, roomPreset.getEffectiveAlgorithmSetting(dungeonRoomInfo));
                    List<AbstractActionMove> listOfMoves = new ArrayList<>();
                    for (ActionDAGNode actionDAGNode : dag.getAllNodes()) {
                        if (actionDAGNode.getAction() instanceof AtomicAction) {
                            for (AbstractAction actionInAtomicAction : ((AtomicAction) actionDAGNode.getAction()).getActions()) {
                                if (actionInAtomicAction instanceof AbstractActionMove) {
                                    listOfMoves.add((AbstractActionMove) actionInAtomicAction);
                                }
                            }
                        } else if (actionDAGNode.getAction() instanceof AbstractActionMove) {
                            listOfMoves.add((AbstractActionMove) actionDAGNode.getAction());
                        }
                    }

                    List<OffsetVec3> vec3 = new ArrayList<>();
                    for (AbstractActionMove listOfMove : listOfMoves) {
                        vec3.add(listOfMove.getTargetVec3());
                    }

                    TSPCache tspCache = new TSPCache(vec3, hash);

                    long start = System.currentTimeMillis();
                    Set<String> pathfinders = roomPreset.getPrecalculations();
                    for (String pathfinder : pathfinders) {
                        PathfindPrecalculation cachedPathfinder = PathfindPrecalculationRegistry.getINSTANCE().getById(pathfinder);
                        if (cachedPathfinder == null) continue;
                        try {
                            tspCache.addToCache(cachedPathfinder);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            // the build only needed the buffer transiently: don't leave it
                            // pinned off-heap for the whole session. dungeon use re-loads
                            // through the LRU/prefetch path
                            cachedPathfinder.evictBuffer();
                        }
                    }
                    LOGGER.info("TSP cache building for {} took {}ms", roomPreset.getRoomId(), System.currentTimeMillis() - start);

                    mapping.put(hash, tspCache);


                    try {
                        CBORMapper cborMapper = new CBORMapper();
                        cborMapper.writeValue(new File(dir, tspCache.getId() + ".cache"), tspCache);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } finally {
                    fakeContext.cleanup();
                }
            } catch (Throwable t) {
                t.printStackTrace();
                throw t;
            }
            }
        }
    }
}
