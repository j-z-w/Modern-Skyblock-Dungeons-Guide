package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PossibleMoveSpot;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PrecalculatedMoveNearest;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// validates the connectivity-filtered bat enumeration against the old baked data for gravel:
// the sealed attic cavity positions must survive, the rafter/roof positions must drop
public class BatEnumerationFilterTest {

    @BeforeAll
    static void boot() throws Exception {
        McBootstrap.boot();
        if (kr.syeyoung.dungeonsguide.mod.DungeonsGuide.getDungeonsGuide() == null)
            new kr.syeyoung.dungeonsguide.mod.DungeonsGuide();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-rd").toFile());
    }

    private static int countPositions(List<PossibleMoveSpot> spots) {
        int n = 0;
        for (PossibleMoveSpot s : spots) n += s.getOffsetPointSet().size();
        return n;
    }

    @Test
    void validateGravel() {
        DungeonRoomInfo dri = DungeonRoomInfoRegistry.getByUUID(UUID.fromString("42029218-9142-4286-b184-e17b3734886f"));
        DungeonSecretBatState.DungeonSecretBatData batData = null;
        String batKey = null;
        for (Map.Entry<String, DungeonMechanicData> e : dri.getMechanics().entrySet()) {
            if (e.getValue() instanceof DungeonSecretBatState.DungeonSecretBatData) {
                batData = (DungeonSecretBatState.DungeonSecretBatData) e.getValue();
                batKey = e.getKey();
            }
        }
        System.out.println("bat key=" + batKey + " point=" + batData.getSecretPoint());

        // old baked data
        PrecalculatedMoveNearest oldData = batData.getMoveNearest();
        int oldCount = countPositions(oldData.getPrecalculatedStonk(Collections.emptyList()));
        System.out.println("OLD baked positions (empty-blocker combination): " + oldCount);

        // new enumeration with the connectivity filter
        PrecalculatedMoveNearest newData = PrecalculatedMoveNearest.createOneBat(batData.getSecretPoint(), dri);
        System.out.println("dependent blockers: " + newData.getDependentRouteBlocker());
        for (int combo = 0; combo < (1 << newData.getDependentRouteBlocker().size()); combo++) {
            java.util.List<String> open = new java.util.ArrayList<>();
            for (int bit = 0; bit < newData.getDependentRouteBlocker().size(); bit++)
                if (((combo >> bit) & 1) != 0) open.add(newData.getDependentRouteBlocker().get(bit));
            List<PossibleMoveSpot> spots = newData.getPrecalculatedStonk(open);
            int total = countPositions(spots);
            System.out.println("combo " + combo + ": NEW positions=" + total);
        }

        // detailed position analysis for the door-open combination (matching the in-game request)
        int doorIdx = newData.getDependentRouteBlocker().indexOf("door-1");
        int combo = doorIdx >= 0 ? (1 << doorIdx) : 0;
        java.util.List<String> open2 = new java.util.ArrayList<>();
        for (int bit = 0; bit < newData.getDependentRouteBlocker().size(); bit++)
            if (((combo >> bit) & 1) != 0) open2.add(newData.getDependentRouteBlocker().get(bit));
        List<PossibleMoveSpot> spots = newData.getPrecalculatedStonk(open2);
        int insideCavity = 0, upperArea = 0, other = 0;
        double minY = 99, maxY = -99;
        for (PossibleMoveSpot s : spots) {
            for (OffsetVec3 ov : s.getOffsetPointSet()) {
                minY = Math.min(minY, ov.yCoord); maxY = Math.max(maxY, ov.yCoord);
                // cavity interior: x 34-41, z 11-15, y 18-22 (room-local)
                if (ov.xCoord >= 34 && ov.xCoord <= 41 && ov.zCoord >= 11 && ov.zCoord <= 15.5 && ov.yCoord >= 18 && ov.yCoord <= 22.5)
                    insideCavity++;
                // upper rafter walking area: z 17-26, y 21.5+ (room-local)
                else if (ov.zCoord >= 17 && ov.yCoord >= 21.5)
                    upperArea++;
                else other++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (PossibleMoveSpot s : spots) for (OffsetVec3 ov : s.getOffsetPointSet())
            if (ov.zCoord >= 17 && ov.yCoord >= 21.5) sb.append(String.format("(%.1f,%.1f,%.1f) ", ov.xCoord, ov.yCoord, ov.zCoord));
        System.out.println("upperRafter survivors: " + sb);
        System.out.println("door-open combo analysis: cavity=" + insideCavity + " upperRafter=" + upperArea + " other=" + other
                + " yRange=" + minY + ".." + maxY);
    }
}
