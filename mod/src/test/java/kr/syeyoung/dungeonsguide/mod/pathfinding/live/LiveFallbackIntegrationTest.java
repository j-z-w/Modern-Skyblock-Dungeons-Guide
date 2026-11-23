package kr.syeyoung.dungeonsguide.mod.pathfinding.live;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PrecalculatedPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// AD8: runs the live A* against real bundled rooms (DRIWorld, the same collision
// world the .pfreq was generated from) and compares with the shipped precalc.
// Needs -Pdg.configDir=... like PrecalcIntegrationTest; skipped otherwise.
public class LiveFallbackIntegrationTest {
    private static PathfindPreset defaultPreset;

    @BeforeAll
    static void setup() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        File configDir = new File(configDirProperty);

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();
        // both registries are strict singletons and the suite shares one JVM:
        // reuse whichever already exists (same configDir either way)
        if (PathfindPrecalculationRegistry.getINSTANCE() == null) {
            java.lang.reflect.Constructor<PathfindPrecalculationRegistry> ctor =
                    PathfindPrecalculationRegistry.class.getDeclaredConstructor(File.class, boolean.class);
            ctor.setAccessible(true);
            ctor.newInstance(new File(configDir, "precalculations"), false);
        }
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        if (defaultPreset == null) defaultPreset = PathfindPresetRegistry.DEFAULT_PRESET;

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
    }

    private static List<AABB> doubledGoalBoxes(PathfindPrecalculation precalc) {
        List<AABB> boxes = new ArrayList<>();
        for (OffsetVec3 t : precalc.getTargetLocations()) {
            // OffsetVec3 is canonical room coords; the DRI world frame is (x, y + 70, z)
            double x = t.xCoord, y = t.yCoord + 70, z = t.zCoord;
            boxes.add(new AABB(x - 0.1, y - 0.1, z - 0.1, x + 0.1, y + 0.1, z + 0.1));
        }
        List<AABB> doubled = new ArrayList<>();
        for (AABB b : boxes) {
            doubled.add(new AABB(b.minX * 2, b.minY * 2, b.minZ * 2, b.maxX * 2, b.maxY * 2, b.maxZ * 2));
        }
        return doubled;
    }

    @Test
    void liveFallbackTracksPrecalc() throws Exception {
        int precalcsTested = 0;
        int startsWithPrecalc = 0, liveFound = 0;
        int routeStarts = 0, routeFound = 0; // small (route-style) target sets only
        List<Double> ratios = new ArrayList<>();
        List<Long> searchNanos = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        List<String> report = new ArrayList<>();

        outer:
        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null || info.getTotalSecrets() <= 0) continue;

            for (String precalcId : roomPreset.getPrecalculations()) {
                if (precalcsTested >= 8) break outer;
                PathfindPrecalculation precalc = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (precalc == null) continue;

                List<String> openMechs = precalc.getRoomState() == null || precalc.getRoomState().isEmpty()
                        ? Collections.emptyList()
                        : Arrays.asList(precalc.getRoomState().split(","));

                IPathfindWorld world;
                try {
                    world = new DRIWorld(info, openMechs, defaultPreset).getPathfindWorld();
                } catch (Throwable t) {
                    System.out.println("DRIWorld failed for " + info.getName() + ": " + t);
                    continue;
                }
                List<AABB> goalBoxes = doubledGoalBoxes(precalc);
                precalcsTested++;

                int checked = 0, found = 0;
                try (PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinder(0)) {
                    pf.init2();
                    for (int y = 72; y <= 80 && checked < 6; y += 2) {
                        for (int x = 1; x < info.getWidth() && checked < 6; x += Math.max(1, info.getWidth() / 4)) {
                            for (int z = 1; z < info.getLength() && checked < 6; z += Math.max(1, info.getLength() / 4)) {
                                Vec3 from = new Vec3(x + 0.5, y, z + 0.5);
                                PathfindResult route = pf.getRoute(from);
                                if (route == null || route.getNodeList().isEmpty()) continue;
                                startsWithPrecalc++;
                                checked++;

                                long startCell = LiveAStar.pack(
                                        (int) Math.round(from.x * 2),
                                        (int) Math.round(from.y * 2),
                                        (int) Math.round(from.z * 2));
                                LiveAStar.Result res = LiveAStar.search(world, startCell, goalBoxes,
                                        LiveAStar.DEFAULT_MAX_EXPANSIONS);
                                searchNanos.add(res.nanos);
                                // route-style destinations (what the live fallback is for)
                                // vs giant spread target sets whose precalc coverage leans
                                // on stonk/etherwarp moves the live pathfinder forbids
                                boolean routeStyle = precalc.getTargetLocations().size() <= 8;
                                if (res.status != LiveAStar.Status.FOUND) {
                                    misses.add(info.getName() + " precalc=" + precalcId
                                            + " from=(" + x + "," + y + "," + z + ") status=" + res.status);
                                    if (routeStyle) routeStarts++;
                                    continue;
                                }
                                found++;
                                liveFound++;
                                if (routeStyle) { routeStarts++; routeFound++; }
                                double preCost = route.getCost();
                                if (preCost > 0 && !Double.isNaN(preCost)) {
                                    ratios.add(res.cost / preCost);
                                }
                            }
                        }
                    }
                }
                report.add(info.getName() + " precalc=" + precalcId.substring(0, 8)
                        + " targets=" + precalc.getTargetLocations().size()
                        + " starts=" + checked + " liveFound=" + found);
            }
        }

        for (String s : report) System.out.println("  live: " + s);
        for (String s : misses) System.out.println("  live miss: " + s);

        ratios.sort(Double::compareTo);
        searchNanos.sort(Long::compareTo);
        if (!ratios.isEmpty()) {
            double median = ratios.get(ratios.size() / 2);
            double p90 = ratios.get((int) (ratios.size() * 0.9));
            double min = ratios.get(0), max = ratios.get(ratios.size() - 1);
            System.out.println("cost ratio live/precalc: n=" + ratios.size()
                    + " min=" + String.format("%.3f", min)
                    + " median=" + String.format("%.3f", median)
                    + " p90=" + String.format("%.3f", p90)
                    + " max=" + String.format("%.3f", max));
        }
        if (!searchNanos.isEmpty()) {
            long p50 = searchNanos.get(searchNanos.size() / 2);
            long p99 = searchNanos.get(Math.min(searchNanos.size() - 1, (int) (searchNanos.size() * 0.99)));
            long max = searchNanos.get(searchNanos.size() - 1);
            System.out.println("search time: n=" + searchNanos.size()
                    + " p50=" + (p50 / 1e6) + "ms p99=" + (p99 / 1e6) + "ms max=" + (max / 1e6) + "ms");
        }
        System.out.println("live fallback: " + precalcsTested + " precalcs, "
                + startsWithPrecalc + " precalc-resolvable starts, " + liveFound + " live paths"
                + " (route-style: " + routeFound + "/" + routeStarts + ")");

        assertTrue(precalcsTested > 0, "no precalcs tested");
        assertTrue(startsWithPrecalc > 0, "no precalc-resolvable start positions");
        // on actual route destinations the walk-only fallback must cover nearly
        // everything the precalc covers; spread target sets are report-only
        assertTrue(routeStarts > 0, "no route-style precalcs exercised");
        assertTrue(routeFound >= routeStarts * 0.8,
                "live pathfinder found " + routeFound + "/" + routeStarts + " route-style starts");
        // and its costs should be in the same ballpark as the shipped paths
        for (double r : ratios) {
            assertTrue(r > 0.4 && r < 1.8, "live/precalc cost ratio out of range: " + r);
        }
    }
}
