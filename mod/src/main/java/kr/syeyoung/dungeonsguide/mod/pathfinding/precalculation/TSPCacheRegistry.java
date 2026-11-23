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
    public TSPCacheRegistry(File dir) {
        this(dir, true);
    }

    // load=false for tests: skips reading caches and scheduling tasks (scheduling touches the notifications widget)
    TSPCacheRegistry(File dir, boolean load) {
        INSTANCE = this;
        this.dir = dir;
        if (load) load();
    }


    public void load() {
        for (File file : dir.listFiles()) {
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
            TSPCacheCalculationTask task = new TSPCacheCalculationTask(new AtomicInteger(0), value);
            calculationTaskWeakHashMap.put(value, task);

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

    public void registerTSPCache(TSPCache cache) {
        mapping.put(cache.getId(), cache);
    }

    @AllArgsConstructor
    @Getter @Setter
    public class TSPCacheCalculationTask {
        private AtomicInteger cnt = new AtomicInteger(0);
        private RoomPreset roomPreset;

        public void run() throws NoSuchAlgorithmException {
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
                        try {
                            tspCache.addToCache(cachedPathfinder);
                            // the build only needed the buffer transiently: don't leave it
                            // pinned off-heap for the whole session. dungeon use re-loads
                            // through the LRU/prefetch path
                            cachedPathfinder.evictBuffer();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
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
