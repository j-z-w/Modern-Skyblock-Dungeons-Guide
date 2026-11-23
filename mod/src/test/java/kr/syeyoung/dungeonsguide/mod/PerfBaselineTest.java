package kr.syeyoung.dungeonsguide.mod;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.events.DGEvent;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.annotations.AnnotatedListenerHelper;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.ClientTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.DGTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.listenerlist.BasicEventBus;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// perf harness for the measured optimization pass. not a correctness test; prints timings so
// BEFORE/AFTER numbers live next to the code that produced them. safe to run standalone:
// it does not touch the precalc/preset/tsp singletons the integration test owns.
public class PerfBaselineTest {
    private static long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static long allocBytes() {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()
                ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }

    @Test
    void legacyTableBuild() {
        McBootstrap.boot();
        long start = System.nanoTime();
        BlockState[] table = RoomDataConverter.buildLegacyTable();
        long elapsed = System.nanoTime() - start;
        System.out.println("PERF legacyTableBuild: " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms");
        assertEquals(4096, table.length);
    }

    @Test
    void roomLoadAndConvert() throws Exception {
        McBootstrap.boot();
        System.gc();
        long heapBefore = usedMemory();
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            System.out.println("PERF roomLoadAndConvert run " + run + ": " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms");
        }
        long elapsed = best;
        long chars = 0;
        int worlds = 0, packed = 0;
        for (DungeonRoomInfo dri : DungeonRoomInfoRegistry.getRegistered()) {
            if (dri.getPackedWorld() != null) packed++;
            if (dri.getWorld() != null) {
                chars += dri.getWorld().length;
                worlds++;
            }
        }
        System.gc();
        long heapAfter = usedMemory();
        System.out.println("PERF roomLoadAndConvert: " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms, "
                + worlds + " worlds (" + packed + " packed), " + chars + " world cells, heap delta " + ((heapAfter - heapBefore) >> 20) + "MB");
        assertTrue(worlds > 0);
    }

    public static class Listener {
        public int hits;
        @SubscribeEvent
        public void onTick(ClientTickEvent e) { hits++; }
        @SubscribeEvent(priority = ListenerPriority.LAST)
        public void onTickLast(ClientTickEvent e) { hits++; }
        @SubscribeEvent
        public void onDgTick(DGTickEvent e) { hits++; }
    }

    @Test
    void eventDispatch() {
        BasicEventBus bus = new BasicEventBus();
        Listener a = new Listener();
        Listener b = new Listener();
        AnnotatedListenerHelper.registerListeners(bus, a);
        AnnotatedListenerHelper.registerListeners(bus, b);

        ClientTickEvent tick = new ClientTickEvent(ClientTickEvent.Phase.START);
        // a second event type keeps the superclass walk + cache populated the way it is in game
        DGTickEvent dgTick = new DGTickEvent();

        int warmup = 200_000;
        for (int i = 0; i < warmup; i++) {
            bus.fireEvent(tick);
            bus.fireEvent(dgTick);
        }

        int iterations = 1_000_000;
        long allocBefore = allocBytes();
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            bus.fireEvent(tick);
        }
        long elapsed = System.nanoTime() - start;
        long allocDelta = allocBytes() - allocBefore;
        System.out.println("PERF eventDispatch (fireEvent, 4 listeners): "
                + String.format("%.1f", (double) elapsed / iterations) + "ns/fire, "
                + (allocDelta < 0 ? "alloc n/a" : (double) allocDelta / iterations + "B/fire")
                + ", hits=" + a.hits + "/" + b.hits);
        assertTrue(a.hits >= iterations * 2);

        BasicEventBus emptyBus = new BasicEventBus();
        ClientTickEvent unlistened = new ClientTickEvent(ClientTickEvent.Phase.END);
        for (int i = 0; i < warmup; i++) emptyBus.fireEvent(unlistened);
        allocBefore = allocBytes();
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            emptyBus.fireEvent(unlistened);
        }
        elapsed = System.nanoTime() - start;
        allocDelta = allocBytes() - allocBefore;
        System.out.println("PERF eventDispatch (fireEvent, 0 listeners): "
                + String.format("%.1f", (double) elapsed / iterations) + "ns/fire, "
                + (allocDelta < 0 ? "alloc n/a" : (double) allocDelta / iterations + "B/fire"));
    }
}
