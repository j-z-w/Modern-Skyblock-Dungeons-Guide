package kr.syeyoung.dungeonsguide.mod.pathfinding.preset;

import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.live.LivePathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import lombok.Getter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // one chat warning per room per run: missing lookups repeat across planner instances and
    // target hashes, but a single line per room is enough. cleared on world unload (new run)
    private static final Set<String> warnedRooms = ConcurrentHashMap.newKeySet();
    public static void resetWarnings() {
        warnedRooms.clear();
    }

    public PathfindPrecalculation getPrecalcByHash(String hash) {
        if (!byHash.containsKey(hash)) {
            if (warnedRooms.add(String.valueOf(preset.getRoomId()))) {
                ChatTransmitter.addToQueue("§eDungeons Guide §7:: §cPrecalculation "+hash+" in room "+dungeonRoomInfo.getName()+" is §4§lMISSING §cin currently applied preset §e"+preset.getParent().getPresetName()+"§c. The route line for this target may be missing.");
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

    // AD8 live fallback: when the request's precalculation is missing from the preset,
    // draw a line anyway from a bounded live A* running on a background worker
    // (kr.syeyoung.dungeonsguide.mod.pathfinding.live). Display engines call this
    // instead of loadPrecalculatedByHash; everything downstream is unchanged.
    // order: resident precalc -> if a precalc exists but isn't loaded yet, kick the async
    // load (on-demand priority) and keep the previous line, never block -> only when no
    // precalc exists for the request at all, live A*.
    public PathfinderExecutor loadPrecalculatedOrLive(PathfindRequest request, DungeonRoom dungeonRoom) {
        if (request == null) return null;
        PathfinderExecutor executor = loadPrecalculatedIfReady(request.getHash(), dungeonRoom);
        if (executor != null) return executor;
        // loadPrecalculatedIfReady is null both while a known precalc loads in the background
        // and when the preset has none; live A* must only cover the second case, or it would
        // race the async load and burn expansions every tick the file takes to arrive
        // a precalc whose .pfres failed IO doesn't count as "exists": backoff keeps
        // retrying it in the background, but a corrupt file must not starve the route
        // line forever - fall through to live A*
        PathfindPrecalculation primary = getPrecalcByHash(request.getHash());
        if (primary != null && !primary.hasLoadFailure()) return null;
        // an alternative-door-state precalc for the same targets also counts as "a precalc
        // exists": PrecalcFallback probes and loads it, so live A* stays the last resort
        for (String altHash : kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.PrecalcFallback
                .alternativeRequestHashes(request, dungeonRoom)) {
            PathfindPrecalculation alt = byHash.get(altHash);
            if (alt != null && !alt.hasLoadFailure()) return null;
        }
        // updateRoomProcessor can still be queued; same guard as loadPrecalculatedIfReady
        if (!(dungeonRoom.getRoomProcessor() instanceof GeneralRoomProcessor)) return null;

        try {
            BoundingBox target = new BoundingBox();
            for (OffsetVec3 offsetPoint : request.getTarget()) {
                Vec3 pos = offsetPoint.getPos(dungeonRoom);
                target.addBoundingBox(new AABB(
                        pos.x - 0.1, pos.y - 0.1, pos.z - 0.1,
                        pos.x + 0.1, pos.y + 0.1, pos.z + 0.1
                ));
            }
            LivePathfinder pathfinder = new LivePathfinder();
            PathfinderExecutor executor1;
            try {
                executor1 = new PathfinderExecutor(pathfinder, target,
                        ((GeneralRoomProcessor) dungeonRoom.getRoomProcessor()).getPathfinderWorld());
                executor1.doStep();
            } catch (Throwable t) {
                // nothing will ever close it otherwise, the cleaner isn't registered yet
                pathfinder.close();
                throw t;
            }
            CLEANER.register(executor1, pathfinder::close);
            return executor1;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private TSPCache tspCache;
    public TSPCache getTSPCache() {
        if (tspCache != null) return tspCache;
        return tspCache = TSPCacheRegistry.getINSTANCE().getTSPCache(preset.getTSPCache());
    }

}
