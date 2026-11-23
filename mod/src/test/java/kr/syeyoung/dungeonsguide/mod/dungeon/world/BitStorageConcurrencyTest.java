package kr.syeyoung.dungeonsguide.mod.dungeon.world;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// G5 review B3: BitStorage raced DG-LivePathfinder stores against render-thread
// clearBits - unsynchronized read-modify-write on packed longs lost invalidations and
// a lazily allocated page wasn't safely published. Now: atomically published pages,
// per-page serialized RMW, and a generation counter so stale worker stores are dropped.
public class BitStorageConcurrencyTest {

    @Test
    void storeAndClearStillWork() {
        BitStorage s = new BitStorage(4, 4, 8, 3);
        assertTrue(s.store(1, 2, 3, 5));
        assertEquals(5, s.read(1, 2, 3));
        // unchanged write is a no-op
        assertFalse(s.store(1, 2, 3, 5));
        // clearing the cell's z-run zeroes it without touching neighbors
        s.clearBits(1, 2 * 8, 2 * 8 + 7);
        assertEquals(0, s.read(1, 2, 3));
    }

    @Test
    void invalidationBumpsGeneration() {
        BitStorage s = new BitStorage(4, 4, 8, 3);
        long g0 = s.generation();
        s.store(0, 0, 0, 1);
        s.clearBits(0, 0, 31);
        assertTrue(s.generation() > g0);
        long g1 = s.generation();
        s.store(0, 0, 1, 0); // invalidateCache() writes 0
        assertTrue(s.generation() > g1);
    }

    @Test
    void staleStoreIsDropped() {
        BitStorage s = new BitStorage(4, 4, 8, 3);
        long gen = s.generation();
        // value "read" from backing before the invalidation
        s.clearBits(0, 0, 31);
        assertFalse(s.storeIfUnchanged(0, 0, 0, 5, gen), "value computed before invalidation must not commit");
        assertEquals(0, s.read(0, 0, 0));
        // a fresh read after the invalidation still commits
        assertTrue(s.storeIfUnchanged(0, 0, 0, 5, s.generation()));
        assertEquals(5, s.read(0, 0, 0));
    }

    @Test
    void concurrentStoreAndClearLeaveNoTornBits() throws InterruptedException {
        BitStorage s = new BitStorage(8, 4, 16, 3);
        int cells = 4 * 16; // one x-slice's worth of bit indexes
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);

        // worker: store a known value at every cell of slice 0
        Thread writer = new Thread(() -> {
            ready.countDown();
            while (!stop.get()) {
                long gen = s.generation();
                for (int bit = 0; bit < cells; bit++) {
                    s.storeIfUnchanged(0, bit / 16, bit % 16, 5, gen);
                }
            }
        });
        // render thread: repeatedly clear the slice + read every cell back
        Thread clearer = new Thread(() -> {
            ready.countDown();
            while (!stop.get()) {
                s.clearBits(0, 0, cells - 1);
                for (int bit = 0; bit < cells; bit++) {
                    int v = s.read(0, bit / 16, bit % 16);
                    if (v != 0 && v != 5) violations.incrementAndGet();
                }
            }
        });
        writer.start();
        clearer.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Thread.sleep(300);
        stop.set(true);
        writer.join(10_000);
        clearer.join(10_000);
        assertEquals(0, violations.get(), "torn bitfield read");

        // after both threads quiesce, a final clear must win deterministically
        s.clearBits(0, 0, cells - 1);
        for (int bit = 0; bit < cells; bit++) {
            assertEquals(0, s.read(0, bit / 16, bit % 16), "cleared bit resurrected at " + bit);
        }
    }
}
