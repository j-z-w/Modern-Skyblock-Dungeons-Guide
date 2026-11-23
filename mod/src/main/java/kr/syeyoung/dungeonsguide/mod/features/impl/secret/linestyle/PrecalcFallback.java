package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractActionMove;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

// Graceful degradation for "player is somewhere the current-state precalculation cannot reach":
// typically the player already walked past a door the room still reports as closed, so the cost
// field for the current open-mechanic set never populated their cell and getRoute returns null.
// This probes for a precalculation with the same targets but one more blocking door treated as
// open (and, as a last resort, all blocking doors open) and, if the applied preset has one,
// loads it the same way RoomPresetPathPlanner does so the engine can draw a line again.
// It never creates anything for missing precalculations and never emits the
// MISSING-precalculation warning, so probing is silent.
//
// A candidate only counts when its executor can actually route to the player: in multi-door
// rooms the first precalculation that *loads* is not necessarily the door the player crossed,
// so every candidate is validated with getRoute(playerPos) before being remembered. While no
// candidate validated, each call re-probes - preload() dedupes in-flight loads and backs off
// failures internally, so probing retries load-completion races and failed loads for free.
public class PrecalcFallback {
    private static final Cleaner CLEANER = Cleaner.create();

    private final DungeonRoom dungeonRoom;
    private Map<String, PathfindPrecalculation> byHash; // targetHash -> precalc, lazily built, read-only
    private final Map<AbstractActionMove, Probe> probed = new WeakHashMap<>();

    private static class Probe {
        String requestHash; // primary request hash this probe ran against
        PathfinderExecutor executor; // null = probed, nothing found (yet) - re-probe next call
    }

    public PrecalcFallback(DungeonRoom dungeonRoom) {
        this.dungeonRoom = dungeonRoom;
    }

    // a route from the player's position under an alternative door state, or null when there is
    // no fallback precalculation or it also cannot reach the player
    public PathfindResult route(AbstractActionMove move, Vec3 playerPos) {
        PathfinderExecutor executor = getFallbackExecutor(move, playerPos);
        return executor == null ? null : executor.getRoute(playerPos);
    }

    private PathfinderExecutor getFallbackExecutor(AbstractActionMove move, Vec3 playerPos) {
        PathfindRequest request;
        try {
            request = move.getPathfindRequest(dungeonRoom);
        } catch (Exception e) {
            return null;
        }
        if (request == null) return null;
        Probe probe = probed.get(move);
        if (probe != null && probe.requestHash.equals(request.getHash()) && probe.executor != null)
            return probe.executor;
        probe = new Probe();
        probe.requestHash = request.getHash();
        probe(request, playerPos, probe);
        probed.put(move, probe);
        return probe.executor;
    }

    // candidate request hashes for the same move under alternative door states: openMech plus
    // each single currently-blocking door, then (if more than one) all blocking doors at once.
    // TreeSet: candidate order must be deterministic across calls
    public static List<String> alternativeRequestHashes(PathfindRequest request, DungeonRoom room) {
        List<String> out = new ArrayList<>();
        Set<String> blockingDoors = new TreeSet<>();
        for (Map.Entry<String, DungeonMechanicState> entry : room.getMechanics().entrySet()) {
            DungeonMechanicState state = entry.getValue();
            if (!(state instanceof DungeonDoorState) && !(state instanceof DungeonOnewayDoorState)) continue;
            if (request.getOpenMech().contains(entry.getKey())) continue;
            try {
                if (((WorldMutatingMechanicState) state).isBlocking(room)) blockingDoors.add(entry.getKey());
            } catch (Exception ignored) {}
        }
        for (String door : blockingDoors) {
            Set<String> alt = new HashSet<>(request.getOpenMech());
            alt.add(door);
            out.add(new PathfindRequest(request.getAlgorithmSetting(), request.getDungeonRoomInfo(),
                    alt, request.getTarget()).getHash());
        }
        if (blockingDoors.size() > 1) {
            Set<String> alt = new HashSet<>(request.getOpenMech());
            alt.addAll(blockingDoors);
            out.add(new PathfindRequest(request.getAlgorithmSetting(), request.getDungeonRoomInfo(),
                    alt, request.getTarget()).getHash());
        }
        return out;
    }

    private void probe(PathfindRequest request, Vec3 playerPos, Probe probe) {
        Map<String, PathfindPrecalculation> known = byHash();
        if (known.isEmpty()) return;
        if (!(dungeonRoom.getRoomProcessor() instanceof GeneralRoomProcessor)) return;
        for (String hash : alternativeRequestHashes(request, dungeonRoom)) {
            PathfindPrecalculation precalc = known.get(hash);
            if (precalc == null) continue;
            Loaded loaded = load(precalc);
            if (loaded == null) continue; // still loading (async) or failed - retried next call
            // a precalc that exists but can't reach the player is the wrong alternative:
            // in a multi-door room keep probing instead of settling for an unroutable line
            PathfindResult route = loaded.executor.getRoute(playerPos);
            if (route == null || route.getNodeList().isEmpty()) {
                // reject: clean() unregisters + closes the pathfinder now - it used to
                // stay resident until GC, pinning an off-heap buffer per probe
                loaded.cleanable.clean();
                continue;
            }
            probe.executor = loaded.executor;
            return;
        }
    }

    private static class Loaded {
        PathfinderExecutor executor;
        Cleaner.Cleanable cleanable;
    }

    // same non-blocking path as RoomPresetPathPlanner.loadPrecalculatedIfReady, minus the warning
    // side effects: an executor only when the node buffer is already resident, otherwise the async
    // load is kicked (on-demand priority) and the probe retries on a later call. never does file
    // IO on the calling thread
    private Loaded load(PathfindPrecalculation precalculation) {
        IPathfinder pathfinder = precalculation.createPathfinderIfLoaded(dungeonRoom.getRoomMatcher().getRotation());
        if (pathfinder == null) {
            precalculation.preload(); // dedupes in-flight loads; failure backoff is internal
            return null;
        }
        try {
            PathfinderExecutor executor;
            try {
                executor = new PathfinderExecutor(pathfinder, BoundingBox.of(new AABB(0, 0, 0, 0, 0, 0)),
                        ((GeneralRoomProcessor) dungeonRoom.getRoomProcessor()).getPathfinderWorld());
                executor.doStep();
            } catch (Throwable t) {
                pathfinder.close();
                throw t;
            }
            Loaded l = new Loaded();
            l.executor = executor;
            l.cleanable = CLEANER.register(executor, pathfinder::close);
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<String, PathfindPrecalculation> byHash() {
        if (byHash != null) return byHash;
        DungeonRoomInfo info = dungeonRoom.getDungeonRoomInfo();
        RoomPreset preset = info == null || dungeonRoom.getContext() == null || dungeonRoom.getContext().getPreset() == null
                ? null : dungeonRoom.getContext().getPreset().getRoomPreset(info.getUuid());
        // return an empty map without caching it when roomInfo/preset isn't there yet:
        // a cached empty map would permanently disable the fallback for this engine
        // even after the matcher publishes the preset a tick later
        if (preset == null || preset.getPrecalculations() == null) return java.util.Collections.emptyMap();
        Map<String, PathfindPrecalculation> built = new HashMap<>();
        for (String precalcId : preset.getPrecalculations()) {
            PathfindPrecalculation p = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
            if (p != null) built.putIfAbsent(p.getTargetHash(), p);
        }
        return byHash = built;
    }
}
