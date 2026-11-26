package kr.syeyoung.dungeonsguide.mod.pathfinding.preset;

import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

// B2: the per-solve precalculation cache. concurrent DPTSP + heuristic workers
// hit it at once - access must be serialized (every thread must get the same
// executor instance, no duplicated loads), and closeEvalCache must release it
public class RoomPlannerCacheTest {

    private static DungeonRoom room;
    private static RoomPreset preset;

    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
        // need a room whose preset actually lists precalculation hashes, or the
        // concurrent-lookup path can't resolve an executor at all
        for (DungeonRoomInfo info : kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder
                .DungeonRoomInfoRegistry.getRegistered()) {
            RoomPreset p = TestRooms.defaultPreset().getRoomPreset(info.getUuid());
            if (p != null && !p.getPrecalculations().isEmpty()) {
                preset = p;
                room = TestRooms.buildRoom(p, info);
                break;
            }
        }
        if (room == null) {
            DungeonRoomInfo info = TestRooms.anyRoom();
            preset = TestRooms.presetFor(info);
            room = TestRooms.buildRoom(preset, info);
        }
    }

    private static RoomPresetPathPlanner planner() {
        return new RoomPresetPathPlanner(preset);
    }

    @Test
    void concurrentLookupSharesOneExecutor() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(preset != null, "room has no preset");

        RoomPresetPathPlanner planner = planner();
        try {
            // pick the first hash that actually resolves to an executor. the preset
            // stores precalculation ids, but the lookup is keyed by the
            // precalculation's target hash - passing the id made every lookup miss and
            // the assumption silently skipped the test
            String hash = null;
            PathfinderExecutor first = null;
            for (String id : preset.getPrecalculations()) {
                kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation precalc =
                        kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry
                                .getINSTANCE().getById(id);
                if (precalc == null || precalc.getTargetHash() == null) continue;
                String h = precalc.getTargetHash();
                PathfinderExecutor e = planner.loadPrecalculatedByHashCached(h, room);
                if (e != null) { hash = h; first = e; break; }
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(hash != null, "no resolvable precalculation");

            String finalHash = hash;
            PathfinderExecutor expected = first;
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PathfinderExecutor>> futures = new ArrayList<>();
            Set<PathfinderExecutor> seen = new HashSet<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return planner.loadPrecalculatedByHashCached(finalHash, room);
                }));
            }
            start.countDown();
            for (Future<PathfinderExecutor> f : futures) {
                seen.add(assertDoesNotThrow(() -> f.get(60, TimeUnit.SECONDS)));
            }
            pool.shutdownNow();
            assertEquals(1, seen.size(), "concurrent cache hits must share the one executor");
            assertTrue(seen.contains(expected));
        } finally {
            planner.closeEvalCache();
        }
        // second close must be a no-op
        planner.closeEvalCache();
    }

    @Test
    void closeEvalCacheOnEmptyIsNoop() {
        RoomPresetPathPlanner planner = planner();
        // lazy: nothing cached yet - close must not throw on an empty cache
        planner.closeEvalCache();
        planner.closeEvalCache();
    }
}
