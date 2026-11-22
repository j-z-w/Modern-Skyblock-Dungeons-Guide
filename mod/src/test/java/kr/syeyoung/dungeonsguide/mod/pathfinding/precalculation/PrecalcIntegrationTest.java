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

        // same order as DungeonFacade.init, minus the filesystem watcher (not needed once everything's loaded)
        new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
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
        for (Throwable t : exceptions) t.printStackTrace();
        assertEquals(0, exceptions.size(), "TSPCacheCalculationTask threw");
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
}
