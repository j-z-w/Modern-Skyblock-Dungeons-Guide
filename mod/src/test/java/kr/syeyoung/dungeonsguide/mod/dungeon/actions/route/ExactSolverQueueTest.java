package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// "display all" submits one exact solve per room at once: the solver pool needs a
// real queue so those submissions wait instead of rejecting onto the heuristic path
public class ExactSolverQueueTest {

    @Test
    void tenConcurrentSolvesQueueInsteadOfRejecting() throws Exception {
        TestRooms.boot();
        Field f = ActionRoute.class.getDeclaredField("exactSolverPool");
        f.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) f.get(null);

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(10);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                // a RejectedExecutionException here means the submission was dropped
                futures.add(pool.submit(() -> {
                    ran.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            // give the workers a moment to claim tasks; at most 4 can run at once,
            // so the rest must be QUEUED - not rejected, not silently dropped
            Thread.sleep(200);
            assertEquals(10, pool.getQueue().size() + pool.getActiveCount(),
                    "every submitted solve is either running or queued");
            assertTrue(pool.getQueue().size() >= 5,
                    "submissions beyond the 4 workers must wait in the bounded queue");
        } finally {
            release.countDown();
        }
        assertTrue(ran.await(15, TimeUnit.SECONDS), "every queued solve eventually runs");
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
    }
}
