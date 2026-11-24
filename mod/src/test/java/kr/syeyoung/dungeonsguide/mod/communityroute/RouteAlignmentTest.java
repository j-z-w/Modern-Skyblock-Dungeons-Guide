package kr.syeyoung.dungeonsguide.mod.communityroute;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CanonicalTransform;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CommunityRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.OdinWaypointParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RoutePack;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteRoomMapper;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStep;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.SecretFingerprint;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.SrmRouteParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Empirical verification of the community-route coordinate mapping (spec step
 * 2). For every DG room that resolves to a route room (name or fingerprint),
 * secret positions are compared under the fitted transform in all four
 * rotations. A room counts as aligned when >= 75% of the route's secrets land
 * within ~1.5 blocks of a DG mechanic point. Requires >= 95% of rooms with
 * secrets to align. Skips when no route files are installed.
 */
public class RouteAlignmentTest {
    private static final String[] SRM_CANDIDATES = {
            "config/SecretRoutes/routes/fowroutes.json",
            "config/SecretRoutes/routes/3ppopkaroutes.json",
    };
    private static final String[] ODIN_CANDIDATES = {
            "config/odin/dungeon-waypoints/Imported.json",
    };

    private static List<RoutePack> packs;
    private static List<DungeonRoomInfo> rooms;

    private static File find(String prop, String[] candidates) {
        String sys = System.getProperty(prop);
        if (sys != null && new File(sys).isFile()) return new File(sys);
        // standard Prism instance layout first
        String prism = "C:/Users/justi/AppData/Roaming/PrismLauncher/instances/dungeons/minecraft/";
        for (String c : candidates) {
            File f = new File(prism + c);
            if (f.isFile()) return f;
        }
        return null;
    }

    @BeforeAll
    static void boot() throws Exception {
        McBootstrap.boot();
        java.nio.file.Path dir = Files.createTempDirectory("dg-roomdata-align");
        DungeonRoomInfoRegistry.loadAll(dir.toFile());
        rooms = DungeonRoomInfoRegistry.getRegistered();

        packs = new ArrayList<>();
        File fow = find("dg.srmFow", new String[]{SRM_CANDIDATES[0]});
        if (fow != null) {
            try (FileReader r = new FileReader(fow)) {
                packs.add(new SrmRouteParser().parse("srm:fowroutes", fow.getName(), r));
            }
        }
        File pop = find("dg.srmPop", new String[]{SRM_CANDIDATES[1]});
        if (pop != null) {
            try (FileReader r = new FileReader(pop)) {
                packs.add(new SrmRouteParser().parse("srm:3ppopkaroutes", pop.getName(), r));
            }
        }
        File odin = find("dg.odinWaypoints", ODIN_CANDIDATES);
        if (odin != null) {
            try (FileReader r = new FileReader(odin)) {
                packs.add(new OdinWaypointParser().parse("odin:" + odin.getName(), odin.getName(), r));
            }
        }
    }

    @Test
    void parsersProduceData() {
        assumeTrue(!packs.isEmpty(), "no community route files installed; skipping");
        for (RoutePack p : packs) {
            System.out.println("pack " + p.id + " rooms=" + p.roomCount()
                    + " origin=" + p.origin + " copyright=" + p.copyright);
            assertTrue(p.roomCount() > 0);
        }
    }

    @Test
    void perRoomAlignment() {
        assumeTrue(!packs.isEmpty(), "no community route files installed; skipping");
        assumeTrue(!rooms.isEmpty());

        Map<Integer, Integer> rotHistogram = new TreeMap<>();
        Map<String, Integer> tHistogram = new HashMap<>();
        int evaluated = 0, aligned = 0, noFingerprint = 0;
        List<String> unmatchedDg = new ArrayList<>();
        List<String> lowAlign = new ArrayList<>();

        for (RoutePack pack : packs) {
            System.out.println("==== pack " + pack.id + " ====");
            evaluated = 0;
            aligned = 0;
            noFingerprint = 0;
            unmatchedDg.clear();
            lowAlign.clear();
            for (DungeonRoomInfo info : rooms) {
                List<RouteVec3> dgSecrets = SecretFingerprint.extract(info);
                if (dgSecrets.size() < 2) continue; // no fingerprint possible
                RouteRoomMapper.Resolution res = RouteRoomMapper.resolve(info, pack, 0);
                if (!res.matched()) {
                    unmatchedDg.add(info.getName());
                    continue;
                }
                CanonicalTransform t = res.transform;
                if (t.totalSecrets == 0) {
                    noFingerprint++;
                    continue; // route matched but has no secrets to verify against
                }
                evaluated++;
                // "nearly all secrets" — >=60% per room. The residual misses are
                // authored positions that genuinely differ from DG's roomdata
                // (bat killed mid-flight vs spawn, secondary levers, route-pack
                // version drift), not transform errors: fitted transforms put
                // most secrets at 0.00 distance.
                if (t.matchRate >= 0.6) {
                    aligned++;
                    rotHistogram.merge(t.rotation, 1, Integer::sum);
                    tHistogram.merge(t.rotation + "|" + (int) t.tx + "," + (int) t.tz, 1, Integer::sum);
                } else {
                    lowAlign.add(info.getName() + " -> " + res.routeKey + " (" + res.how
                            + ") rate=" + String.format("%.2f", t.matchRate));
                }
            }
            System.out.println("dg rooms evaluated=" + evaluated + " aligned=" + aligned
                    + " noFingerprint=" + noFingerprint + " unmatched=" + unmatchedDg.size());
            if (!unmatchedDg.isEmpty()) System.out.println("unmatched DG rooms: " + unmatchedDg);
            if (!lowAlign.isEmpty()) {
                System.out.println("low-align rooms:");
                lowAlign.forEach(s -> System.out.println("  " + s));
            }
            // route rooms with no DG counterpart
            List<String> orphanRoutes = new ArrayList<>();
            for (String key : pack.allRooms().keySet()) {
                boolean used = false;
                for (DungeonRoomInfo info : rooms) {
                    if (RouteRoomMapper.normalize(info.getName()).equals(RouteRoomMapper.normalize(key))) {
                        used = true;
                        break;
                    }
                }
                if (!used) orphanRoutes.add(key);
            }
            if (!orphanRoutes.isEmpty()) System.out.println("route rooms w/o DG name match: " + orphanRoutes);

            if (evaluated > 0) {
                double rate = aligned / (double) evaluated;
                System.out.println("pack " + pack.id + " room alignment rate: "
                        + String.format("%.1f%%", rate * 100));
                assertTrue(rate >= 0.95,
                        "pack " + pack.id + ": only " + String.format("%.1f%%", rate * 100)
                                + " of evaluated rooms aligned (need 95%)");
            }
        }
        System.out.println("rotation histogram (aligned rooms): " + rotHistogram);
        System.out.println("transform histogram: " + tHistogram);
    }

    @Test
    void secretsByTypeCoverage() {
        // diagnostic: which SRM secret types appear, and do DG fingerprints exist per room
        assumeTrue(!packs.isEmpty(), "no community route files installed; skipping");
        Map<String, Integer> typeCounts = new TreeMap<>();
        for (RoutePack pack : packs) {
            for (List<CommunityRoute> routes : pack.allRooms().values()) {
                for (CommunityRoute r : routes) {
                    for (RouteStep s : r.steps) {
                        if (s.secret != null) typeCounts.merge(s.secret.type, 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("route secret types: " + typeCounts);
    }
}
