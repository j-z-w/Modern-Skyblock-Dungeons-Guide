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

    // occupies both loader threads; releasing workerOne first makes exactly ONE worker
    // drain the test tasks in queue order - no sleeps, no concurrent dequeues, so the
    // asserted order is the queue's dequeue order and nothing else
    private static class LoaderBlock {
        final CountDownLatch running = new CountDownLatch(2);
        final CountDownLatch releaseOne = new CountDownLatch(1);
        final CountDownLatch releaseOther = new CountDownLatch(1);

        LoaderBlock() throws InterruptedException {
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> {
                running.countDown();
                await(releaseOne);
            });
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> {
                running.countDown();
                await(releaseOther);
            });
            if (!running.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("loader threads did not pick up blockers");
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        // drains the queue through the first worker while the second stays parked
        void drainOneWorker(List<String> order, int expected) throws InterruptedException {
            releaseOne.countDown();
            long deadline = System.currentTimeMillis() + 15_000;
            while (order.size() < expected && System.currentTimeMillis() < deadline)
                Thread.sleep(5);
        }

        void close() {
            releaseOne.countDown();
            releaseOther.countDown();
        }
    }

    // on-demand loads must run ahead of queued speculative prefetch work, otherwise a
    // whole-dungeon prefetch starves the current room exactly like the render-thread stall
    // it was meant to prevent
    @Test
    void onDemandLoadsJumpAheadOfPrefetch() throws Exception {
        LoaderBlock block = new LoaderBlock();
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_PREFETCH, () -> order.add("prefetch"));
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_ONDEMAND, () -> order.add("ondemand"));
            block.drainOneWorker(order, 2);
        } finally {
            block.close();
        }
        assertEquals(List.of("ondemand", "prefetch"), order,
                "queued prefetch ran before the on-demand load that arrived after it");
    }

    // FIFO within one priority: room precalcs are prefetched in request order
    @Test
    void equalPrioritiesStayFifo() throws Exception {
        LoaderBlock block = new LoaderBlock();
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                int n = i;
                PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_PREFETCH, () -> order.add("p" + n));
            }
            block.drainOneWorker(order, 4);
        } finally {
            block.close();
        }
        assertEquals(List.of("p0", "p1", "p2", "p3"), order,
                "same-priority loads ran out of submission order");
    }

    // preload(PRIORITY_ONDEMAND) must promote a precalc whose load is still queued at
    // prefetch priority: the active route target can't wait behind every earlier
    // speculative prefetch of the room
    @Test
    void onDemandPromotesAQueuedPrefetch() throws Exception {
        LoaderBlock block = new LoaderBlock();
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            PrecalcBufferCache.submitLoad(PrecalcBufferCache.PRIORITY_PREFETCH, () -> order.add("other"));
            PrecalcBufferCache.PrioritizedLoad needed =
                    PrecalcBufferCache.submitPrioritized(PrecalcBufferCache.PRIORITY_PREFETCH, () -> order.add("needed"));
            // submitted AFTER "other": on FIFO alone "other" would drain first
            PrecalcBufferCache.promoteLoad(needed, PrecalcBufferCache.PRIORITY_ONDEMAND);
            block.drainOneWorker(order, 2);
        } finally {
            block.close();
        }
        assertEquals(List.of("needed", "other"), order,
                "promoted prefetch stayed behind the plain prefetch queued before it");
    }
}
