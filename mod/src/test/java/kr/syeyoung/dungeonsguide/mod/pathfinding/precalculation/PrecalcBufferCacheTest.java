package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// loader-pool behaviour that does not need a real .pfres file
public class PrecalcBufferCacheTest {

    @BeforeAll
    static void setup() {
        // the static loader pool is created through DungeonsGuide.registerExecutorService
        if (DungeonsGuide.getDungeonsGuide() == null) new DungeonsGuide();
    }

    // on-demand loads must run ahead of queued speculative prefetch work, otherwise a
    // whole-dungeon prefetch starves the current room exactly like the render-thread stall
    // it was meant to prevent
    @Test
    void onDemandLoadsJumpAheadOfPrefetch() throws Exception {
        // occupy both loader threads so the test tasks queue instead of running
        CountDownLatch blockersRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> {
                blockersRunning.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        }
        assertTrue(blockersRunning.await(15, TimeUnit.SECONDS), "loader threads did not pick up blockers");

        List<String> order = new CopyOnWriteArrayList<>();
        PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_PREFETCH, () -> {
            // two workers dequeue together when the blockers release; a small delay keeps the
            // append order matching the dequeue order this test asserts
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            order.add("prefetch");
        });
        PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> order.add("ondemand"));
        release.countDown();

        long deadline = System.currentTimeMillis() + 15_000;
        while (order.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(List.of("ondemand", "prefetch"), order,
                "queued prefetch ran before the on-demand load that arrived after it");
    }

    // FIFO within one priority: room precalcs are prefetched in request order
    @Test
    void equalPrioritiesStayFifo() throws Exception {
        CountDownLatch blockersRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> {
                blockersRunning.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        }
        assertTrue(blockersRunning.await(15, TimeUnit.SECONDS), "loader threads did not pick up blockers");

        // dequeue order is serialized by the priority queue, but two workers start their
        // tasks concurrently, so the only stable observable is that the two earliest
        // submissions run before the two latest
        List<String> order = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_PREFETCH, () -> order.add("p" + n));
        }
        release.countDown();

        long deadline = System.currentTimeMillis() + 15_000;
        while (order.size() < 4 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(4, order.size());
        assertEquals(new java.util.HashSet<>(List.of("p0", "p1")),
                new java.util.HashSet<>(order.subList(0, 2)),
                "later submissions started before earlier same-priority work");
        assertEquals(new java.util.HashSet<>(List.of("p2", "p3")),
                new java.util.HashSet<>(order.subList(2, 4)));
    }
}
