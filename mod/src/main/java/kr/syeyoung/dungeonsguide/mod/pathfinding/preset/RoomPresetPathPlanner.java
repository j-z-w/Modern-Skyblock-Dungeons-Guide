package kr.syeyoung.dungeonsguide.mod.pathfinding.preset;

import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry;
import lombok.Getter;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RoomPresetPathPlanner {
    // sun.misc.Cleaner is gone after java 8
    private static final Cleaner CLEANER = Cleaner.create();
    private RoomPreset preset;
    private DungeonRoomInfo dungeonRoomInfo;
    @Getter
    private AlgorithmSetting algorithmSetting;

    private Map<String, PathfindPrecalculation> byHash = new HashMap<>();

    public RoomPresetPathPlanner(RoomPreset roomPreset) {
        dungeonRoomInfo = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
        this.algorithmSetting = roomPreset.getEffectiveAlgorithmSetting(dungeonRoomInfo);
        this.preset = roomPreset;

        Set<String> pathfinders = roomPreset.getPrecalculations();
        if (pathfinders != null) {
            for (String precalcId : pathfinders) {
                PathfindPrecalculation cachedPathfinder = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                // a missing or duplicate precalculation skips just that one, the rest of the room still loads
                if (cachedPathfinder == null) continue;
                if (byHash.containsKey(cachedPathfinder.getTargetHash())) continue;
                byHash.put(cachedPathfinder.getTargetHash(), cachedPathfinder);
            }
        }
    }

    private long nextWarning = 0L;

    public PathfindPrecalculation getPrecalcByHash(String hash) {
        if (!byHash.containsKey(hash)) {
            if (nextWarning < System.currentTimeMillis()) {
                ChatTransmitter.addToQueue("§eDungeons Guide §7:: §cPrecalculation "+hash+" in room "+dungeonRoomInfo.getName()+" is §4§lMISSING §cin currently applied preset §e"+preset.getParent().getPresetName()+"§c. There may be some problems in pathfinding. Please add precalculations at /dg -> Pathfinding & Secrets -> Precalculations");
                nextWarning = System.currentTimeMillis() + 30000L;
            }
            return null;
        }
        return byHash.get(hash);
    }

    private PathfinderExecutor buildExecutor(IPathfinder pathfinder, DungeonRoom dungeonRoom) {
        PathfinderExecutor executor1;
        try {
            executor1 = new PathfinderExecutor(pathfinder, BoundingBox.of(new AABB(0,0,0,0,0,0)),
                    ((GeneralRoomProcessor)dungeonRoom.getRoomProcessor()).getPathfinderWorld());
            executor1.doStep();
        } catch (Throwable t) {
            // nothing will ever close it otherwise, the cleaner isn't registered yet
            pathfinder.close();
            throw t;
        }

        CLEANER.register(executor1, pathfinder::close); // WELLLLLL... well... well...

        return executor1;
    }

    // render-thread variant: returns an executor only when the node buffer is already resident,
    // otherwise kicks the asynchronous load and returns null so the caller can retry on a
    // later call. never does file IO on the calling thread
    public PathfinderExecutor loadPrecalculatedIfReady(String hash, DungeonRoom dungeonRoom) {
        PathfindPrecalculation precalculation = getPrecalcByHash(hash);
        if (precalculation == null) return null;

        IPathfinder pathfinder = precalculation.createPathfinderIfLoaded(dungeonRoom.getRoomMatcher().getRotation());
        if (pathfinder == null) {
            precalculation.preload();
            return null;
        }
        if (!(dungeonRoom.getRoomProcessor() instanceof GeneralRoomProcessor)) {
            // updateRoomProcessor still queued - retry next tick rather than crashing the tick handler
            pathfinder.close();
            return null;
        }
        return buildExecutor(pathfinder, dungeonRoom);
    }

    // blocking variant for worker threads (TSP / route computation): reads the file when needed
    public PathfinderExecutor loadPrecalculatedByHash(String hash, DungeonRoom dungeonRoom) {
        PathfindPrecalculation precalculation = getPrecalcByHash(hash);
        if (precalculation == null) return null;

        try {
            IPathfinder pathfinder = precalculation.createPathfinder(dungeonRoom.getRoomMatcher().getRotation());
            return buildExecutor(pathfinder, dungeonRoom);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private TSPCache tspCache;
    public TSPCache getTSPCache() {
        if (tspCache != null) return tspCache;
        return tspCache = TSPCacheRegistry.getINSTANCE().getTSPCache(preset.getTSPCache());
    }

}
