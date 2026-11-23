package kr.syeyoung.dungeonsguide.mod;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.events.DGEvent;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.annotations.AnnotatedListenerHelper;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.ClientTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.DGTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.listenerlist.BasicEventBus;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assumptions;
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

    // the work DungeonFacade.init does: what the DG-Init thread now overlaps with the rest of startup.
    // needs -Pdg.configDir for the real precalculation set; skips otherwise. the config dir is
    // opened read-only (watcher + tsp scheduling disabled) so nothing mutates it
    @Test
    void dungeonFacadeInit() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        File configDir = new File(configDirProperty);

        McBootstrap.boot();
        java.lang.reflect.Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);
        new DungeonsGuide();

        long start = System.nanoTime();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
        long rooms = System.nanoTime();
        // package-private ctors throw when another test in the same jvm already initialized them;
        // singletons created here are cleared again so PrecalcIntegrationTest can init its own
        boolean madePrecalc = false, madePreset = false, madeTsp = false;
        try {
            java.lang.reflect.Constructor<?> ctor = Class.forName("kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry")
                    .getDeclaredConstructor(File.class, boolean.class);
            ctor.setAccessible(true);
            ctor.newInstance(new File(configDir, "precalculations"), false);
            madePrecalc = true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalStateException)) throw e;
        }
        long precalcs = System.nanoTime();
        if (PathfindPresetRegistry.getINSTANCE() == null) {
            new PathfindPresetRegistry(new File(configDir, "presets"));
            madePreset = true;
        }
        long presets = System.nanoTime();
        try {
            java.lang.reflect.Constructor<?> ctor = Class.forName("kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry")
                    .getDeclaredConstructor(File.class, boolean.class);
            ctor.setAccessible(true);
            ctor.newInstance(Files.createTempDirectory("dg-tspcache").toFile(), false);
            madeTsp = true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalStateException)) throw e;
        }
        long end = System.nanoTime();
        clearSingleton("kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry", madePrecalc);
        clearSingleton("kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry", madeTsp);
        clearSingleton("kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry", madePreset);

        System.out.println("PERF dungeonFacadeInit: rooms=" + TimeUnit.NANOSECONDS.toMillis(rooms - start)
                + "ms precalcs=" + TimeUnit.NANOSECONDS.toMillis(precalcs - rooms)
                + "ms presets=" + TimeUnit.NANOSECONDS.toMillis(presets - precalcs)
                + "ms tsp=" + TimeUnit.NANOSECONDS.toMillis(end - presets)
                + "ms total=" + TimeUnit.NANOSECONDS.toMillis(end - start) + "ms");
    }

    // what SkyblockStatus.updateStatus / FeatureDungeonSBTime.getTimeElapsed do per tick:
    // format each sidebar line then strip it. BEFORE: Score.getJustTeam/getVisibleName rebuilt the
    // component->string formatting every call; AFTER they're cached against the team version
    @Test
    void sidebarScan() {
        kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.Team team =
                new kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.Team("dgperfteam");
        team.setPrefix(net.minecraft.network.chat.Component.literal(" §7⏣ The Catacombs (F3)"));
        team.setSuffix(net.minecraft.network.chat.Component.literal(""));
        java.util.List<kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Score> scores = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            team.addTeamMember("perffine" + i);
            scores.add(new kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Score("perffine" + i, i, null, null));
        }
        kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.TeamManager.INSTANCE.createTeam(team);
        try {
            int iterations = 200_000;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                for (kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Score sc : scores) {
                    kr.syeyoung.dungeonsguide.mod.utils.TextUtils.keepScoreboardCharacters(
                            kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor(sc.getJustTeam()));
                }
            }
            long elapsed = System.nanoTime() - start;
            System.out.println("PERF sidebarScan (15 lines, cached getJustTeam): "
                    + String.format("%.1f", (double) elapsed / iterations) + "ns/scan");

            start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                for (kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Score sc : scores) {
                    // the pre-cache cost: getFormattedText on prefix+suffix every line
                    kr.syeyoung.dungeonsguide.mod.utils.TextUtils.keepScoreboardCharacters(
                            kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor(
                                    kr.syeyoung.dungeonsguide.mod.utils.TextUtils.getFormattedText(team.getPrefix())
                                            + kr.syeyoung.dungeonsguide.mod.utils.TextUtils.getFormattedText(team.getSuffix())));
                }
            }
            elapsed = System.nanoTime() - start;
            System.out.println("PERF sidebarScan (15 lines, uncached formatting): "
                    + String.format("%.1f", (double) elapsed / iterations) + "ns/scan");
        } finally {
            kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.TeamManager.INSTANCE.removeTeam("dgperfteam");
        }
    }

    private static void clearSingleton(String className, boolean created) throws Exception {
        if (!created) return;
        java.lang.reflect.Field f = Class.forName(className).getDeclaredField("INSTANCE");
        f.setAccessible(true);
        f.set(null, null);
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
