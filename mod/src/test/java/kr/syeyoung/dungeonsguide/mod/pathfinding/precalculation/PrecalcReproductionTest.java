package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

// T5: prove the local request-writing + C++ generator pipeline reproduces the shipped precalculations.
// -Pdg.configDir=<real DG config dir> -Pdg.precalcOut=<dir for generated pfreq/pfres>
// regenerateRequests writes <out>/<precalcId>.pfreq, to be fed to pathfinder-cli.
// compareRegeneratedPrecalcs diffs <out>/<precalcId>.regen.pfres (and .flipped.pfres) against the shipped file.
public class PrecalcReproductionTest {
    // small 1x1 (64x74x64), a 2x2 room (128x88x128), a 1x2 room with door-1,door-2 open
    private static final String[] PRECALC_IDS = {
            "00a65f9b1814a44f920303b9ef2fa12f",
            "002708609b01476df39ee4fc66950f88",
            "010c8ea6d5f7c3eee01b77ea96f06f87",
    };

    private static PathfindPreset defaultPreset;
    private static File configDir;
    private static File outDir;

    @BeforeAll
    static void setup() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        configDir = new File(configDirProperty);
        outDir = new File(System.getProperty("dg.precalcOut", "build/precalc-verify"));
        outDir.mkdirs();

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();

        new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        new PathfindPresetRegistry(new File(configDir, "presets"));

        defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        assertNotNull(defaultPreset, "default.json preset not found");
        System.out.println("default preset: " + defaultPreset.getPresetName() + " (" + defaultPreset.getPresetId() + ")");

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
    }

    private File shippedFile(String precalcId) {
        return new File(new File(configDir, "precalculations/" + defaultPreset.getPresetId()), precalcId + ".pfres");
    }

    private static class Reconstructed {
        PathfindPrecalculation precalc;
        DungeonRoomInfo info;
        Set<String> openMech;
        List<OffsetVec3> targets;
        PathfindRequest request;
    }

    private static Reconstructed reconstruct(File pfresFile) throws IOException {
        Reconstructed r = new Reconstructed();
        r.precalc = new PathfindPrecalculation(pfresFile);

        String targetId = r.precalc.getTargetId();
        String[] parts = targetId.split(":", 3);
        assertEquals(3, parts.length, "unexpected targetId format " + targetId);
        UUID uuid = UUID.fromString(parts[0]);
        r.openMech = new HashSet<>();
        if (!parts[1].isEmpty()) r.openMech.addAll(Arrays.asList(parts[1].split(",")));
        r.targets = new ArrayList<>();
        for (String t : parts[2].split(";")) {
            String[] c = t.split(",");
            r.targets.add(new OffsetVec3(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2])));
        }

        r.info = DungeonRoomInfoRegistry.getByUUID(uuid);
        assertNotNull(r.info, "room " + uuid + " not in bundled roomdata");
        assertEquals(uuid, r.precalc.getRoomUID());

        r.request = new PathfindRequest(r.precalc.getAlgorithmSetting(), r.info, r.openMech, r.targets);
        return r;
    }

    @Test
    void regenerateRequests() throws Exception {
        for (String precalcId : PRECALC_IDS) {
            File shipped = shippedFile(precalcId);
            assertTrue(shipped.exists(), shipped + " missing");
            Reconstructed r = reconstruct(shipped);

            // request identity: id string and its md5 must equal what the shipped file claims
            assertEquals(r.precalc.getTargetId(), r.request.getId(), "id mismatch for " + precalcId);
            assertEquals(r.precalc.getTargetHash(), r.request.getHash(), "hash mismatch for " + precalcId);

            // diagnostic: does the room preset's effective setting equal the one shipped in the file?
            RoomPreset roomPreset = defaultPreset.getRoomPreset(r.info.getUuid());
            AlgorithmSetting effective = roomPreset.getEffectiveAlgorithmSetting(r.info);
            CompoundTag a = r.precalc.getAlgorithmSetting().serializeToNBT();
            CompoundTag b = effective.serializeToNBT();
            System.out.println(precalcId + " room=" + r.info.getName() + " color=" + r.info.getColor()
                    + " openMech=" + r.openMech + " targets=" + r.targets.size()
                    + " algoSettingEqualsPresetEffective=" + a.equals(b)
                    + " inRoomPreset=" + roomPreset.getPrecalculations().contains(precalcId));

            DRIWorld world = new DRIWorld(r.info, new ArrayList<>(r.openMech), defaultPreset);
            File out = new File(outDir, precalcId + ".pfreq");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                r.request.write(world, dos);
            }
            System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
        }
    }

    private static String typeName(PrecalculatedPathfinder.CachedPathfindNode n) {
        return n.getNodeType() == null ? "UNPOPULATED" : n.getNodeType().name();
    }

    // node-level + route comparison of a regenerated .pfres against the shipped one
    private void comparePair(String precalcId, String regenSuffix) throws IOException {
        File regenFile = new File(outDir, precalcId + "." + regenSuffix + ".pfres");
        Assumptions.assumeTrue(regenFile.exists(), regenFile.getName() + " not generated yet, skipping");

        PathfindPrecalculation shipped = new PathfindPrecalculation(shippedFile(precalcId));
        PathfindPrecalculation regen = new PathfindPrecalculation(regenFile);

        System.out.println("=== " + precalcId + " (" + regenSuffix + ") ===");
        System.out.println("  shipped: bounds=" + shipped.getXStart() + "," + shipped.getYStart() + "," + shipped.getZStart()
                + " +" + shipped.getXLen() + "," + shipped.getYLen() + "," + shipped.getZLen()
                + " hash=" + shipped.getTargetHash() + " src=" + shipped.getGeneratedFrom());
        System.out.println("  regen  : bounds=" + regen.getXStart() + "," + regen.getYStart() + "," + regen.getZStart()
                + " +" + regen.getXLen() + "," + regen.getYLen() + "," + regen.getZLen()
                + " hash=" + regen.getTargetHash() + " src=" + regen.getGeneratedFrom());
        assertEquals(shipped.getTargetHash(), regen.getTargetHash(), "targetHash changed");
        assertEquals(shipped.getTargetId(), regen.getTargetId(), "targetId changed");
        // generator sorts targets differently than the request order; compare as sets
        assertEquals(new HashSet<>(shipped.getTargetLocations()), new HashSet<>(regen.getTargetLocations()), "targets changed");
        assertEquals(shipped.getAlgorithmSetting().serializeToNBT(), regen.getAlgorithmSetting().serializeToNBT(),
                "algo settings changed");

        int x0 = Math.min(shipped.getXStart(), regen.getXStart());
        int y0 = Math.min(shipped.getYStart(), regen.getYStart());
        int z0 = Math.min(shipped.getZStart(), regen.getZStart());
        int x1 = Math.max(shipped.getXStart() + shipped.getXLen(), regen.getXStart() + regen.getXLen());
        int y1 = Math.max(shipped.getYStart() + shipped.getYLen(), regen.getYStart() + regen.getYLen());
        int z1 = Math.max(shipped.getZStart() + shipped.getZLen(), regen.getZStart() + regen.getZLen());

        long total = 0, exact = 0, typeMatch = 0, gMatch = 0, parentMatch = 0, bothPopulated = 0;
        double maxDelta = 0, sumDelta = 0;
        long deltaCount = 0, nanMismatches = 0;
        Map<String, Integer> typeMismatch = new TreeMap<>();
        Map<String, Integer> typeHist = new TreeMap<>();
        List<String> firstMismatches = new ArrayList<>();

        try (PrecalculatedPathfinder sPf = (PrecalculatedPathfinder) shipped.createPathfinder(0);
             PrecalculatedPathfinder rPf = (PrecalculatedPathfinder) regen.createPathfinder(0)) {
            sPf.init2();
            rPf.init2();

            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        PrecalculatedPathfinder.CachedPathfindNode sn = sPf.getNode(x, y, z);
                        PrecalculatedPathfinder.CachedPathfindNode rn = rPf.getNode(x, y, z);
                        total++;
                        String st = typeName(sn), rt = typeName(rn);
                        if (!st.equals("UNPOPULATED")) typeHist.merge(st, 1, Integer::sum);
                        boolean tEq = st.equals(rt);
                        if (tEq) typeMatch++;
                        else {
                            typeMismatch.merge(st + "->" + rt, 1, Integer::sum);
                            if (firstMismatches.size() < 10)
                                firstMismatches.add("    node " + x + "," + y + "," + z + " type " + st + "->" + rt
                                        + " g " + sn.getGScore() + "->" + rn.getGScore());
                        }
                        if (sn.getNodeType() != null && rn.getNodeType() != null) bothPopulated++;

                        float sg = sn.getGScore(), rg = rn.getGScore();
                        boolean gEq = (Float.isInfinite(sg) && Float.isInfinite(rg))
                                || Float.floatToIntBits(sg) == Float.floatToIntBits(rg);
                        if (gEq) gMatch++;
                        else if (Float.isFinite(sg) && Float.isFinite(rg)) {
                            double d = Math.abs(sg - rg);
                            maxDelta = Math.max(maxDelta, d);
                            sumDelta += d;
                            deltaCount++;
                        } else nanMismatches++;

                        boolean pEq = sn.getX() == rn.getX() && sn.getY() == rn.getY() && sn.getZ() == rn.getZ();
                        if (pEq) parentMatch++;

                        if (tEq && gEq && pEq) exact++;
                    }
                }
            }

            // routes: sample ~50 evenly-spaced populated nodes from the shipped result,
            // feed their node coords through the inverse of getRoute's coordinate mapping
            int sampled = 0, bothRoutes = 0, costEq = 0, routeEq = 0;
            List<int[]> populated = new ArrayList<>();
            for (int y = shipped.getYStart(); y < shipped.getYStart() + shipped.getYLen(); y++)
                for (int z = shipped.getZStart(); z < shipped.getZStart() + shipped.getZLen(); z++)
                    for (int x = shipped.getXStart(); x < shipped.getXStart() + shipped.getXLen(); x++)
                        if (sPf.getNode(x, y, z).getNodeType() != null
                                && sPf.getNode(x, y, z).getNodeType() != PathfindResult.PathfindNode.NodeType.DESTINATION)
                            populated.add(new int[]{x, y, z});
            List<Vec3> starts = new ArrayList<>();
            int step = Math.max(1, populated.size() / 50);
            for (int i = 0; i < populated.size() && starts.size() < 50; i += step) {
                int[] n = populated.get(i);
                starts.add(new Vec3(n[0] / 2.0 - 1, n[1] / 2.0, n[2] / 2.0 - 1));
            }
            for (Vec3 from : starts) {
                if (sampled >= 50) break;
                sampled++;
                PathfindResult sr = sPf.getRoute(from);
                PathfindResult rr = rPf.getRoute(from);
                if (sr == null && rr == null) continue;
                if (sr == null || rr == null) {
                    System.out.println("    route " + from + " null mismatch: " + (sr == null) + "/" + (rr == null));
                    continue;
                }
                bothRoutes++;
                if (Math.abs(sr.getCost() - rr.getCost()) < 1e-4) costEq++;
                if (sr.getNodeList().equals(rr.getNodeList())) routeEq++;
            }
            System.out.println("  routes: sampled=" + sampled + " both=" + bothRoutes
                    + " costEq=" + costEq + " nodeListEq=" + routeEq);
        }

        System.out.println("  nodes: total=" + total + " exact=" + exact
                + String.format(" (%.2f%%)", exact * 100.0 / total)
                + " typeMatch=" + String.format("%.2f%%", typeMatch * 100.0 / total)
                + " gMatch=" + String.format("%.2f%%", gMatch * 100.0 / total)
                + " parentMatch=" + String.format("%.2f%%", parentMatch * 100.0 / total));
        System.out.println("  gScore: bothPopulated=" + bothPopulated + " finiteDelta n=" + deltaCount
                + " max=" + maxDelta + " mean=" + (deltaCount == 0 ? 0 : sumDelta / deltaCount)
                + " inf-vs-finite=" + nanMismatches);
        System.out.println("  shipped type hist: " + typeHist);
        System.out.println("  type mismatches: " + typeMismatch);
        for (String s : firstMismatches) System.out.println(s);
    }

    @Test
    void compareRegeneratedPrecalcs() throws IOException {
        for (String precalcId : PRECALC_IDS) {
            comparePair(precalcId, "regen");
        }
    }

    @Test
    void compareFlippedCollisionPrecalcs() throws IOException {
        for (String precalcId : PRECALC_IDS) {
            comparePair(precalcId, "flipped");
        }
    }
}
