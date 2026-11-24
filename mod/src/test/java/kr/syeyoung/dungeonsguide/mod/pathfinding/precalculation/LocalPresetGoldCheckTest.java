package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

// P4g gold check: the locally generated preset .pfres must load in DG, keep every
// route the shipped preset can reach, never cost more than walking, and use the
// expected action types (ETHERWARP for walk-etherwarp, STONK_*/DIG_* for breaker).
//
//   -Pdg.configDir=<real DG config dir>
//   -Pdg.genOut=<output root>            (same as LocalPresetGenerationTest)
//   -Pdg.precalcIds=<id1,id2,...>        (default: the 3-room sample)
public class LocalPresetGoldCheckTest {

    private static final String[] PRECALC_IDS = Optional.ofNullable(System.getProperty("dg.precalcIds"))
            .filter(s -> !s.isEmpty()).map(s -> s.split(","))
            .orElseGet(() -> new String[]{
                    "00a65f9b1814a44f920303b9ef2fa12f",
                    "002708609b01476df39ee4fc66950f88",
                    "010c8ea6d5f7c3eee01b77ea96f06f87",
            });

    private static File outDir;
    private static File shippedDir;

    @BeforeAll
    static void setup() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        File configDir = new File(configDirProperty);
        outDir = new File(System.getProperty("dg.genOut", "build/local-presets"));

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();
        if (PathfindPrecalculationRegistry.getINSTANCE() == null)
            new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        PathfindPreset defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        assertNotNull(defaultPreset);
        shippedDir = new File(configDir, "precalculations/" + defaultPreset.getPresetId());
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
    }

    // is the oct-node's cell actually diggable in this mode's world? forbidden mechanic /
    // protected blocks legitimately lose the shipped file's 1-node stonk entries.
    private static boolean cellDiggable(PathfindPrecalculation shipped, int x, int y, int z,
                                        String mode, AlgorithmSetting setting) {
        try {
            String[] parts = shipped.getTargetId().split(":", 3);
            UUID uuid = UUID.fromString(parts[0]);
            Set<String> openMech = new HashSet<>();
            if (!parts[1].isEmpty()) openMech.addAll(Arrays.asList(parts[1].split(",")));
            kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo info =
                    kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry.getByUUID(uuid);
            PathfindPreset preset = new PathfindPreset();
            preset.setAlgorithmSetting(setting);
            kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld world =
                    new kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld(info, new ArrayList<>(openMech), preset);
            CollisionStateCalculatingCoordinateMap.CollisionState state =
                    world.getPathfindWorld().getBlock(x, y, z);
            // stonk cells are the only ones that produce STONK_EXIT parents; a forbidden
            // cell shows up BLOCKED and is intentionally lost
            return state == CollisionStateCalculatingCoordinateMap.CollisionState.STONKING
                    || state == CollisionStateCalculatingCoordinateMap.CollisionState.STONKING_AIR;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void checkMode(String mode, PathfindResult.PathfindNode.NodeType[] expectedTypes, AlgorithmSetting modeSetting) throws Exception {
        long totalChecked = 0, totalUnpopGen = 0, totalUnpopShip = 0, totalRegressions = 0, totalForbiddenLoss = 0;
        long routesCompared = 0, routeWins = 0, routeTies = 0, routeLosses = 0, routeNulls = 0;
        Map<String, Long> typeHist = new TreeMap<>();
        List<String> failures = new ArrayList<>();

        for (String id : PRECALC_IDS) {
            File gen = new File(outDir, "results-" + mode + "/" + id + ".pfres");
            File ship = new File(shippedDir, id + ".pfres");
            Assumptions.assumeTrue(gen.exists(), mode + " not generated for " + id + ", skipping");
            assertTrue(ship.exists());

            PathfindPrecalculation sPrecalc = new PathfindPrecalculation(ship);
            PathfindPrecalculation gPrecalc = new PathfindPrecalculation(gen);
            assertEquals(sPrecalc.getTargetHash(), gPrecalc.getTargetHash(), id + " targetHash");
            assertEquals(sPrecalc.getRoomUID(), gPrecalc.getRoomUID(), id + " roomUID");

            try (PrecalculatedPathfinder sPf = (PrecalculatedPathfinder) sPrecalc.createPathfinder(0);
                 PrecalculatedPathfinder gPf = (PrecalculatedPathfinder) gPrecalc.createPathfinder(0)) {
                sPf.init2();
                gPf.init2();

                // node grid: every node populated in the shipped file must be populated in ours
                int x0 = Math.min(sPrecalc.getXStart(), gPrecalc.getXStart());
                int y0 = Math.min(sPrecalc.getYStart(), gPrecalc.getYStart());
                int z0 = Math.min(sPrecalc.getZStart(), gPrecalc.getZStart());
                int x1 = Math.max(sPrecalc.getXStart() + sPrecalc.getXLen(), gPrecalc.getXStart() + gPrecalc.getXLen());
                int y1 = Math.max(sPrecalc.getYStart() + sPrecalc.getYLen(), gPrecalc.getYStart() + gPrecalc.getYLen());
                int z1 = Math.max(sPrecalc.getZStart() + sPrecalc.getZLen(), gPrecalc.getZStart() + gPrecalc.getZLen());
                List<int[]> populated = new ArrayList<>();
                for (int y = y0; y < y1; y++)
                    for (int z = z0; z < z1; z++)
                        for (int x = x0; x < x1; x++) {
                            PrecalculatedPathfinder.CachedPathfindNode sn = sPf.getNode(x, y, z);
                            PrecalculatedPathfinder.CachedPathfindNode gn = gPf.getNode(x, y, z);
                            totalChecked++;
                            if (gn.getNodeType() != null)
                                typeHist.merge(gn.getNodeType().name(), 1L, Long::sum);
                            else
                                totalUnpopGen++;
                            if (sn.getNodeType() == null) totalUnpopShip++;
                            else populated.add(new int[]{x, y, z});
                                            // shipped populated but generated unreachable = lost route,
                            // unless the cell is in the mode's forbidden (non-diggable) set
                            if (sn.getNodeType() != null && gn.getNodeType() == null) {
                                if (modeSetting != null && !cellDiggable(sPrecalc, x, y, z, mode, modeSetting)) {
                                    totalForbiddenLoss++;
                                } else {
                                    totalRegressions++;
                                    if (failures.size() < 10)
                                        failures.add(id + " lost node " + x + "," + y + "," + z + " type " + sn.getNodeType());
                                }
                            }
                        }

                // routes: generated cost <= shipped cost for every sampled start
                int step = Math.max(1, populated.size() / 60);
                int sampled = 0;
                for (int i = 0; i < populated.size() && sampled < 60; i += step) {
                    sampled++;
                    int[] n = populated.get(i);
                    Vec3 from = new Vec3(n[0] / 2.0 - 1, n[1] / 2.0, n[2] / 2.0 - 1);
                    PathfindResult sr = sPf.getRoute(from);
                    PathfindResult gr = gPf.getRoute(from);
                    routesCompared++;
                    if (sr == null || gr == null) {
                        routeNulls++;
                        if (sr != null)
                            failures.add(id + " route " + from + " shipped=" + sr.getCost() + " gen=null");
                        continue;
                    }
                    if (gr.getCost() < sr.getCost() - 1e-3) routeWins++;
                    else if (gr.getCost() > sr.getCost() + 1e-3) {
                        routeLosses++;
                        failures.add(id + " route " + from + " cost regressed "
                                + sr.getCost() + " -> " + gr.getCost());
                    } else routeTies++;
                }
            }
        }

        System.out.println("=== " + mode + " gold check ===");
        System.out.println("  nodes: " + totalChecked + " genPopulated=" + (totalChecked - totalUnpopGen)
                + " shipPopulated=" + (totalChecked - totalUnpopShip)
                + " lostNodes=" + totalRegressions + " forbiddenLoss=" + totalForbiddenLoss);
        System.out.println("  routes: n=" + routesCompared + " genCheaper=" + routeWins
                + " equal=" + routeTies + " dearer=" + routeLosses + " null=" + routeNulls);
        System.out.println("  gen type hist: " + typeHist);
        for (String f : failures) System.out.println("  " + f);

        assertEquals(0, totalRegressions, mode + " lost reachable nodes vs shipped");
        assertEquals(0, routeLosses, mode + " produced routes dearer than walking");
        for (PathfindResult.PathfindNode.NodeType t : expectedTypes) {
            assertTrue(typeHist.getOrDefault(t.name(), 0L) > 0,
                    mode + " produced no " + t + " nodes");
        }
    }

    @Test
    void etherwarpPreset() throws Exception {
        checkMode("walk-etherwarp", new PathfindResult.PathfindNode.NodeType[]{
                PathfindResult.PathfindNode.NodeType.ETHERWARP},
                null);
    }

    @Test
    void breakerPreset() throws Exception {
        checkMode("breaker-etherwarp", new PathfindResult.PathfindNode.NodeType[]{
                PathfindResult.PathfindNode.NodeType.STONK_WALK},
                new PathfindPrecalculation(new File(shippedDir, PRECALC_IDS[0] + ".pfres"))
                        .getAlgorithmSetting()
                        .withRouteEtherwarp(true)
                        .withDungeonBreaker(true)
                        .withDigBudget(20)
                        .withStonkDown(true)
                        .withMaxStonk(20)
                        .withStonkTeleport(false)
                        .withStonkEChest(false));
    }
}
