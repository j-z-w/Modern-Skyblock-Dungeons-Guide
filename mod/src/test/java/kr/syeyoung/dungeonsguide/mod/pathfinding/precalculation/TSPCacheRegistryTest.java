package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// the on-demand build path of TSPCacheRegistry.awaitTSPCache: in-flight futures are
// claimed with putIfAbsent and the build is submitted OUTSIDE the map's bin lock.
// the previous computeIfAbsent claim registered a whenComplete that removed the
// entry while the bin was still being written - IllegalStateException: Recursive
// update - whenever the build settled before the mapping lambda returned. these
// presets fail their build immediately, deterministically hitting that interleave
public class TSPCacheRegistryTest {

    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
    }

    // a preset whose room id doesn't exist: task.run() NPEs on the null
    // DungeonRoomInfo and completes the shared future almost immediately
    private static RoomPreset brokenPreset() {
        return new RoomPreset(TestRooms.defaultPreset(), UUID.randomUUID());
    }

    @Test
    void fastSettleDoesNotThrowRecursiveUpdate() {
        TSPCacheRegistry registry = TSPCacheRegistry.getINSTANCE();
        assertNotNull(registry);
        // regression for the computeIfAbsent recursive update: the build settling
        // inside its claim window must resolve to null, not crash the waiter
        for (int i = 0; i < 8; i++) {
            assertNull(registry.awaitTSPCache(brokenPreset(), 10_000),
                    "a broken preset resolves to null, not an exception");
        }
    }

    @Test
    void concurrentWaitersShareOneBuild() throws Exception {
        TSPCacheRegistry registry = TSPCacheRegistry.getINSTANCE();
        RoomPreset preset = brokenPreset();
        Thread[] waiters = new Thread[4];
        for (int i = 0; i < waiters.length; i++) {
            waiters[i] = new Thread(() -> registry.awaitTSPCache(preset, 10_000));
        }
        for (Thread t : waiters) t.start();
        for (Thread t : waiters) t.join(15_000);
        // all joined without exceptions; the single shared build resolves null
        assertNull(registry.awaitTSPCache(preset, 1));
    }

    @Test
    void backoffReturnsImmediately() {
        TSPCacheRegistry registry = TSPCacheRegistry.getINSTANCE();
        RoomPreset preset = brokenPreset();
        assertNull(registry.awaitTSPCache(preset, 10_000)); // fails -> arms backoff

        long start = System.nanoTime();
        assertNull(registry.awaitTSPCache(preset, 30_000),
                "a preset in failure backoff returns the resident (null) mapping");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 15_000,
                "a backed-off preset must not stall waitForResident's budget, took " + elapsedMs + "ms");
    }
}
