package kr.syeyoung.dungeonsguide.mod.communityroute;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FrameDumpTest {
    @BeforeAll
    static void boot() throws Exception {
        McBootstrap.boot();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-rd").toFile());
    }

    private static final String SRM_FILE =
            "C:/Users/justi/AppData/Roaming/PrismLauncher/instances/dungeons/minecraft/config/SecretRoutes/routes/fowroutes.json";
    private static final String ODIN_FILE =
            "C:/Users/justi/AppData/Roaming/PrismLauncher/instances/dungeons/minecraft/config/odin/dungeon-waypoints/Imported.json";

    @Test
    void dumpOdin() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(new java.io.File(ODIN_FILE).isFile(),
                "odin waypoint pack not installed; skipping");
        RoutePack pack;
        try (FileReader r = new FileReader(ODIN_FILE)) {
            pack = new OdinWaypointParser().parse("odin:Imported", "Imported.json", r);
        }
        dumpPack(pack);
    }

    @Test
    void dump() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(new java.io.File(SRM_FILE).isFile(),
                "SRM route pack not installed; skipping");
        RoutePack pack;
        try (FileReader r = new FileReader(SRM_FILE)) {
            pack = new SrmRouteParser().parse("srm:fowroutes", "fowroutes.json", r);
        }
        dumpPack(pack);
    }

    private void dumpPack(RoutePack pack) {
        for (DungeonRoomInfo info : DungeonRoomInfoRegistry.getRegistered()) {
            String key = RouteRoomMapper.matchByName(info, pack);
            if (key == null) continue;
            List<RouteVec3> dg = SecretFingerprint.extract(info);
            List<RouteStep.Secret> secs = new ArrayList<>();
            List<RouteVec3> srm = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (CommunityRoute route : pack.routesFor(key))
                for (RouteStep s : route.steps)
                    if (s.secret != null) { secs.add(s.secret); srm.add(s.secret.pos); types.add(s.secret.type); }
            CanonicalTransform t = CanonicalTransform.fit(dg, secs);
            if (t.matchRate >= 0.75) continue; // only failing rooms
            System.out.println("=== DG " + info.getName() + " <-> SRM " + key
                    + " blocks=" + info.getBlocks().length + "x" + info.getBlocks()[0].length
                    + " dgN=" + dg.size() + " srmN=" + srm.size()
                    + " fit r=" + t.rotation + " T=(" + t.tx + "," + t.ty + "," + t.tz + ") rate=" + t.matchRate);
            for (int i = 0; i < srm.size(); i++) {
                RouteVec3 v = srm.get(i);
                RouteVec3 p = t.apply(v);
                double best = 1e9; RouteVec3 bd = null;
                for (RouteVec3 d : dg) { double ds = p.distSq(d); if (ds < best) { best = ds; bd = d; } }
                System.out.println("   " + String.format("%-9s", types.get(i)) + " srm " + v + " -> " + p
                        + " nearest=" + bd + " d=" + String.format("%.2f", Math.sqrt(best)));
            }
        }
    }
}
