package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// T4: runs TSPCacheCalculationTask against a real DG config dir. -Pdg.configDir=... forwards it as a system property
public class PrecalcIntegrationTest {
    private static PathfindPreset defaultPreset;
    private static TSPCacheRegistry tspRegistry;

    @BeforeAll
    static void setup() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        File configDir = new File(configDirProperty);

        McBootstrap.boot();

        // Main.configDir is package-private; PathfindPreset/DungeonsGuide touch it through Main.getConfigDir()
        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        // a DungeonsGuide instance has to exist before FeatureRegistry / TSPCacheRegistry / DungeonRoom statics run
        new DungeonsGuide();

        // same order as DungeonFacade.init, minus the filesystem watcher (not needed once everything's
        // loaded). the registries are strict singletons and the suite shares one JVM, so reuse whichever
        // already exists (LiveFallbackIntegrationTest leaves them set; PerfBaselineTest clears its own)
        if (PathfindPrecalculationRegistry.getINSTANCE() == null)
            new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        if (defaultPreset == null) {
            for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
                if (preset != PathfindPresetRegistry.DEFAULT_PRESET) defaultPreset = preset;
            }
        }
        if (defaultPreset == null) defaultPreset = PathfindPresetRegistry.DEFAULT_PRESET;
        System.out.println("default preset: " + defaultPreset.getPresetName() + " (" + defaultPreset.getPresetId() + "), " + defaultPreset.getPresets().size() + " rooms");

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());

        // the cache dir is a temp dir: run() writes .cache files, and the real config dir must stay read-only.
        // load=false so no tasks are scheduled; the tests run them directly so failures are attributable.
        // FeatureRegistry is never touched: DRIWorld takes roomPreset.getParent() instead of the selected preset
        tspRegistry = new TSPCacheRegistry(Files.createTempDirectory("dg-tspcache").toFile(), false);
    }

    private static long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Test
    void tspCacheBuildsForEveryRoom() throws Exception {
        int ok = 0, missingPrecalc = 0, missingRoom = 0;
        List<String> missingPrecalcReport = new ArrayList<>();
        List<String> missingRoomReport = new ArrayList<>();
        List<Throwable> exceptions = new ArrayList<>();
        List<String> emptyCaches = new ArrayList<>();
        long peak = usedMemory();
        long start = System.currentTimeMillis();

        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            List<String> missing = new ArrayList<>();
            for (String precalcId : roomPreset.getPrecalculations()) {
                if (PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId) == null)
                    missing.add(precalcId);
            }
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null) {
                missingRoom++;
                missingRoomReport.add(roomPreset.getRoomId() + " tspCache=" + roomPreset.getTSPCache());
            }
            if (!missing.isEmpty()) {
                missingPrecalc++;
                missingPrecalcReport.add(info == null ? roomPreset.getRoomId().toString() : info.getName()
                        + " tspCache=" + roomPreset.getTSPCache() + " missing=" + missing.size() + "/" + roomPreset.getPrecalculations().size());
            }
            if (info == null || !missing.isEmpty()) continue;

            try {
                tspRegistry.new TSPCacheCalculationTask(new AtomicInteger(0), roomPreset).run();
                // the task swallows PathfindImpossibleException, so an exception-free run alone proves little
                if (info.getTotalSecrets() > 0 && !roomPreset.getPrecalculations().isEmpty()) {
                    TSPCache cache = tspRegistry.getTSPCache(roomPreset.getTSPCache());
                    if (cache == null || cache.getCache().isEmpty() || cache.getLocationsInCache().isEmpty())
                        emptyCaches.add(info.getName() + " (" + roomPreset.getRoomId() + ")");
                }
                ok++;
            } catch (Throwable t) {
                exceptions.add(t);
                System.out.println("FAILED " + info.getName() + " (" + roomPreset.getRoomId() + "): " + t);
            }
            peak = Math.max(peak, usedMemory());
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("TSP cache computation: " + elapsed + "ms, " + ok + " rooms OK"
                + ", " + missingPrecalc + " rooms with missing precalcs, " + missingRoom + " rooms missing from the room registry"
                + ", " + exceptions.size() + " exceptions, peak heap " + (peak >> 20) + "MB");
        for (String s : missingPrecalcReport) System.out.println("  missing precalcs: " + s);
        for (String s : missingRoomReport) System.out.println("  missing room: " + s);
        for (String s : emptyCaches) System.out.println("  empty tsp cache: " + s);
        for (Throwable t : exceptions) t.printStackTrace();
        assertEquals(0, exceptions.size(), "TSPCacheCalculationTask threw");
        assertTrue(ok > 0, "no room was computed, is dg.configDir right?");
        assertEquals(0, missingRoom, "preset rooms missing from the bundled room data");
        assertEquals(0, missingPrecalc, "preset precalculations missing from precalculations/");
        assertEquals(List.of(), emptyCaches, "rooms with secrets got an empty TSP cache");
    }

    // the off-heap buffer is shared by every pathfinder of a precalculation. buffers are
    // pinned: the last pathfinder closing leaves the buffer resident in the LRU (that is the
    // point of the cache - no disk re-read), and only LRU eviction drops the pin and frees it
    @Test
    void precalculationBufferLifecycle() throws Exception {
        PathfindPrecalculation precalc = null;
        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            for (String precalcId : roomPreset.getPrecalculations()) {
                precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (precalc != null) break;
            }
            if (precalc != null) break;
        }
        assertNotNull(precalc, "no precalculation to test with");
        PrecalcBufferCache.evictAll();

        // a target node, in the same coordinate space precalculatedRoutesReachTargets uses, so reads hit the buffer
        OffsetVec3 target = precalc.getTargetLocations().get(0);
        Vec3 probe = new Vec3(target.xCoord, target.yCoord + 70, target.zCoord);
        Field bufferField = PrecalculatedPathfinder.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);

        int loadsBefore = PathfindPrecalculation.SYNC_FILE_LOADS.get();
        PrecalculatedPathfinder first = (PrecalculatedPathfinder) precalc.createPathfinder(0);
        PrecalculatedPathfinder second = (PrecalculatedPathfinder) precalc.createPathfinder(0);
        first.init2();
        second.init2();
        PathfindPrecalculation.ReferenceCountedByteBufferWrapper shared =
                (PathfindPrecalculation.ReferenceCountedByteBufferWrapper) bufferField.get(first);
        assertSame(shared, bufferField.get(second), "second pathfinder should reuse the loaded buffer");
        assertNotNull(first.getRoute(probe), "probe should resolve to a node");

        first.close();
        first.close(); // closing twice must not release the shared buffer a second time
        assertNotNull(second.getRoute(probe), "buffer freed while the second pathfinder still holds it");
        second.close();
        // pinned: closing the last pathfinder leaves the resident buffer alive
        shared.getByteBuffer().getLong(0);
        assertTrue(precalc.isLoaded(), "resident buffer must survive its last pathfinder closing");

        // the next pathfinder reuses the resident buffer with no disk read
        try (PrecalculatedPathfinder third = (PrecalculatedPathfinder) precalc.createPathfinder(0)) {
            third.init2();
            assertSame(shared, bufferField.get(third), "resident buffer should be reused");
            assertNotNull(third.getRoute(probe));
            assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get(),
                    "a resident buffer was re-read from disk");
        }

        // eviction drops the pin: the buffer is freed and the next pathfinder loads a fresh one
        precalc.evictBuffer();
        assertFalse(precalc.isLoaded());
        assertThrows(IllegalStateException.class, () -> shared.getByteBuffer().getLong(0),
                "evicted buffer with no references must be freed");
        try (PrecalculatedPathfinder fourth = (PrecalculatedPathfinder) precalc.createPathfinder(0)) {
            fourth.init2();
            assertNotSame(shared, bufferField.get(fourth), "a freed buffer must not be reused");
            assertNotNull(fourth.getRoute(probe));
        }
    }

    private static PathfindPrecalculation pickPrecalc() {
        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            for (String precalcId : roomPreset.getPrecalculations()) {
                PathfindPrecalculation precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (precalc != null) return precalc;
            }
        }
        return null;
    }

    // LRU eviction: an evicted buffer frees once its last pathfinder releases, and the precalc
    // reloads from disk on next use
    @Test
    void precalculationBufferEviction() throws Exception {
        PathfindPrecalculation precalc = pickPrecalc();
        assertNotNull(precalc, "no precalculation to test with");
        PrecalcBufferCache.evictAll();

        OffsetVec3 target = precalc.getTargetLocations().get(0);
        Vec3 probe = new Vec3(target.xCoord, target.yCoord + 70, target.zCoord);
        Field bufferField = PrecalculatedPathfinder.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);

        assertTrue(precalc.preload().get(60, java.util.concurrent.TimeUnit.SECONDS), "background load failed");
        assertTrue(precalc.isLoaded());
        assertTrue(PrecalcBufferCache.cachedBytes() > 0, "resident buffer not accounted in the LRU");

        // a pathfinder created before eviction keeps its reference: the buffer must stay readable
        PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinderIfLoaded(0);
        assertNotNull(pf, "resident buffer should hand out a pathfinder without IO");
        pf.init2();
        PathfindPrecalculation.ReferenceCountedByteBufferWrapper shared =
                (PathfindPrecalculation.ReferenceCountedByteBufferWrapper) bufferField.get(pf);

        precalc.evictBuffer();
        assertFalse(precalc.isLoaded());
        assertNotNull(pf.getRoute(probe), "eviction freed the buffer under a live pathfinder");

        pf.close();
        assertThrows(IllegalStateException.class, () -> shared.getByteBuffer().getLong(0),
                "evicted buffer must be freed once the last pathfinder releases");
    }

    // the route-change contract: requesting a route before its precalc is ready must return
    // immediately with no pathfinder and no file IO on the calling thread (the render thread
    // keeps showing the previous line). once the background load finishes, the same request
    // resolves instantly - again with no file IO
    @Test
    void routeChangeNeverReadsDiskOnCaller() throws Exception {
        PathfindPrecalculation precalc = pickPrecalc();
        assertNotNull(precalc, "no precalculation to test with");
        PrecalcBufferCache.evictAll();
        int loadsBefore = PathfindPrecalculation.SYNC_FILE_LOADS.get();

        // "route requested before precalc ready": caller path is what the display engines call
        long start = System.nanoTime();
        kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder cold = precalc.createPathfinderIfLoaded(0);
        // the loader thread may race ahead after preload(), so check the counter before kicking it
        int loadsAfterRequest = PathfindPrecalculation.SYNC_FILE_LOADS.get();
        java.util.concurrent.CompletableFuture<Boolean> pending = precalc.preload();
        long callerNanos = System.nanoTime() - start;

        assertNull(cold, "not-yet-loaded precalc must not produce a pathfinder synchronously");
        assertEquals(loadsBefore, loadsAfterRequest,
                "route request performed file IO on the calling thread");
        assertTrue(callerNanos < 2_000_000L,
                "route request blocked the caller for " + callerNanos + "ns");

        assertTrue(pending.get(60, java.util.concurrent.TimeUnit.SECONDS), "background load failed");

        // "secret found -> next route requested": the buffer is resident, so the request resolves
        // instantly - twice, to model the next target reusing the same precalculation
        for (int i = 0; i < 2; i++) {
            start = System.nanoTime();
            try (PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinderIfLoaded(0)) {
                callerNanos = System.nanoTime() - start;
                assertNotNull(pf);
                assertTrue(callerNanos < 2_000_000L,
                        "ready-route request blocked the caller for " + callerNanos + "ns");
            }
        }
        assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get(),
                "exactly one file load, and it must have happened on the loader thread");
    }

    @Test
    void precalculatedRoutesReachTargets() throws Exception {
        int roomsTested = 0, routesChecked = 0, routesReached = 0;
        List<String> report = new ArrayList<>();

        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            if (roomsTested >= 3) break;
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null) continue;

            PathfindPrecalculation precalc = null;
            for (String precalcId : roomPreset.getPrecalculations()) {
                precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (precalc != null) break;
            }
            if (precalc == null) continue;
            roomsTested++;

            // starts inside the room, in the same coordinate space the pfres data uses: x/z room relative, y = 70 + rel
            List<Vec3> starts = new ArrayList<>();
            for (int y = 72; y <= 79; y += 2) {
                for (int x = 1; x < info.getWidth(); x += Math.max(1, info.getWidth() / 4)) {
                    for (int z = 1; z < info.getLength(); z += Math.max(1, info.getLength() / 4)) {
                        starts.add(new Vec3(x + 0.5, y, z + 0.5));
                    }
                }
            }

            int goodRoutes = 0;
            try (PrecalculatedPathfinder pathfinder = (PrecalculatedPathfinder) precalc.createPathfinder(0)) {
                pathfinder.init2();
                for (Vec3 from : starts) {
                    if (goodRoutes >= 3) break;
                    PathfindResult route = pathfinder.getRoute(from);
                    if (route == null) continue;
                    routesChecked++;
                    assertFalse(route.getNodeList().isEmpty());
                    PathfindResult.PathfindNode end = route.getNodeList().get(route.getNodeList().size() - 1);
                    Vec3 endPos = new Vec3(end.getX(), end.getY(), end.getZ());
                    double nearest = Double.MAX_VALUE;
                    for (OffsetVec3 target : precalc.getTargetLocations()) {
                        nearest = Math.min(nearest, endPos.distanceToSqr(new Vec3(target.xCoord, target.yCoord + 70, target.zCoord)));
                    }
                    if (end.getType() == PathfindResult.PathfindNode.NodeType.DESTINATION && nearest < 25.0) {
                        goodRoutes++;
                        routesReached++;
                    }
                }
            }
            report.add(info.getName() + " precalc=" + precalc.getId() + " targets=" + precalc.getTargetLocations().size() + " goodRoutes=" + goodRoutes);
            assertTrue(goodRoutes > 0, "no route reached a target in " + info.getName());
        }

        for (String s : report) System.out.println("  routes: " + s);
        System.out.println("routes: " + roomsTested + " rooms tested, " + routesChecked + " routes checked, " + routesReached + " reached a target");
        assertTrue(roomsTested > 0, "no rooms had resolvable precalcs");
    }

    private static PathfindPrecalculation pickPrecalcExcept(PathfindPrecalculation exclude) {
        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            for (String precalcId : roomPreset.getPrecalculations()) {
                PathfindPrecalculation precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (precalc != null && precalc != exclude) return precalc;
            }
        }
        return null;
    }

    // LRU invariants: each resident precalc is one entry, its bytes are counted once, a
    // re-touch changes position only, and evictAll over several entries (which used to throw
    // ConcurrentModificationException mid-iteration) leaves both counters at zero
    @Test
    void lruAccountingIsExact() throws Exception {
        PathfindPrecalculation first = pickPrecalc();
        PathfindPrecalculation second = pickPrecalcExcept(first);
        assertNotNull(first);
        assertNotNull(second, "need two distinct precalculations in the preset");
        PrecalcBufferCache.evictAll();
        assertEquals(0, PrecalcBufferCache.cachedCount());
        assertEquals(0, PrecalcBufferCache.cachedBytes());

        assertTrue(first.preload().get(60, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(second.preload().get(60, java.util.concurrent.TimeUnit.SECONDS));
        long expectedBytes = first.getBufferSizeBytes() + second.getBufferSizeBytes();
        assertEquals(2, PrecalcBufferCache.cachedCount());
        assertEquals(expectedBytes, PrecalcBufferCache.cachedBytes());

        // touching an already-resident entry must refresh its position without charging
        // the same bytes a second time
        try (kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder pf = first.createPathfinderIfLoaded(0)) {
            assertNotNull(pf);
        }
        assertEquals(2, PrecalcBufferCache.cachedCount(), "resident entry duplicated in the LRU");
        assertEquals(expectedBytes, PrecalcBufferCache.cachedBytes(), "resident bytes double-counted");

        PrecalcBufferCache.evictAll();
        assertEquals(0, PrecalcBufferCache.cachedCount());
        assertEquals(0, PrecalcBufferCache.cachedBytes(), "evicted buffers still accounted");
        assertFalse(first.isLoaded());
        assertFalse(second.isLoaded());
    }

    // N callers racing preload() on one hash share the in-flight load: the file is read once
    @Test
    void concurrentPreloadReadsDiskOnce() throws Exception {
        PathfindPrecalculation precalc = pickPrecalc();
        assertNotNull(precalc);
        PrecalcBufferCache.evictAll();
        int loadsBefore = PathfindPrecalculation.SYNC_FILE_LOADS.get();

        int callers = 8;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    java.util.concurrent.CompletableFuture<Boolean> f = precalc.preload();
                    assertNotNull(f, "preload() returned null");
                    return f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<Boolean> f : futures) {
                assertTrue(f.get(60, java.util.concurrent.TimeUnit.SECONDS), "concurrent preload failed");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get(),
                "concurrent preloads of the same hash must dedupe to a single file read");
    }

    // the LRU must actually enforce its caps: loading past MAX_ENTRIES evicts the eldest
    // buffers (dropping their pins), and byte accounting stays under MAX_BYTES
    @Test
    void lruEvictsPastEntryCap() throws Exception {
        int maxEntries = Integer.parseInt(System.getProperty("dg.precalcCache.maxEntries", "128"));
        long maxBytes = Long.parseLong(System.getProperty("dg.precalcCache.maxBytes", "268435456"));

        List<PathfindPrecalculation> all = new ArrayList<>(
                PathfindPrecalculationRegistry.getINSTANCE().getLoaded());
        all.removeIf(p -> p.getBufferSizeBytes() > maxBytes / 2);
        Assumptions.assumeTrue(all.size() > maxEntries,
                "not enough precalcs installed to exceed the LRU cap (" + all.size() + " <= " + maxEntries + ")");

        PrecalcBufferCache.evictAll();
        try {
            int target = maxEntries + 2;
            List<PathfindPrecalculation> loaded = new ArrayList<>();
            for (int i = 0; i < target; i++) {
                assertTrue(all.get(i).preload().get(60, java.util.concurrent.TimeUnit.SECONDS),
                        "load failed for " + all.get(i).getId());
                loaded.add(all.get(i));
            }
            assertTrue(PrecalcBufferCache.cachedCount() <= maxEntries,
                    "LRU holds " + PrecalcBufferCache.cachedCount() + " entries > " + maxEntries);
            assertTrue(PrecalcBufferCache.cachedBytes() <= maxBytes,
                    "LRU holds " + PrecalcBufferCache.cachedBytes() + " bytes > " + maxBytes);
            int evicted = 0;
            for (PathfindPrecalculation p : loaded)
                if (!p.isLoaded()) evicted++;
            assertTrue(evicted >= target - maxEntries,
                    "cap was exceeded but " + (target - maxEntries - evicted) + " too few buffers were evicted");
        } finally {
            PrecalcBufferCache.evictAll();
        }
    }

    // a missing .pfres is retried once per backoff window, not once per render tick:
    // SYNC_FILE_LOADS stays flat no matter how often the display engines ask
    @Test
    void failedLoadBacksOffInsteadOfRetryStorming() throws Exception {
        PathfindPrecalculation real = pickPrecalc();
        assertNotNull(real);

        // a second precalculation over the same file, repointed at a path that does not exist
        PathfindPrecalculation broken = new PathfindPrecalculation(new File(real.getFile()));
        Field fileField = PathfindPrecalculation.class.getDeclaredField("file");
        fileField.setAccessible(true);
        fileField.set(broken, real.getFile() + ".missing");

        int loadsBefore = PathfindPrecalculation.SYNC_FILE_LOADS.get();

        // the blocking variant must record its failure too: worker threads hitting a
        // missing/corrupt .pfres before any preload() still arm the backoff
        assertThrows(java.io.IOException.class, () -> broken.createPathfinder(0));
        assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get());
        assertThrows(java.io.IOException.class, () -> broken.createPathfinder(0));
        assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get(),
                "blocking load was retried inside the backoff window");

        // 20 attempts ~ one second of display-engine ticks: none may reach the disk
        for (int i = 0; i < 20; i++) {
            java.util.concurrent.CompletableFuture<Boolean> again = broken.preload();
            assertNotNull(again, "preload() returned null during backoff");
            assertFalse(again.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(loadsBefore + 1, PathfindPrecalculation.SYNC_FILE_LOADS.get(),
                "failed load was retried inside the backoff window");
    }
}
