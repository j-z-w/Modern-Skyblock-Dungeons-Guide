package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PossibleMoveSpot;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PrecalculatedMoveNearest;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// rebakes moveNearest target data for every bat mechanic in every room with the
// LOS-filtered enumeration, writing the cbor roomdata files back in-place.
// reports per-room survivor counts so degenerate outcomes (near-empty target sets)
// are visible before committing to a full precalc regeneration.
public class BatRoomdataRebakeTest {

    private static final File ROOMDATA_DIR = new File("src/main/resources/roomdata");

    @BeforeAll
    static void boot() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dg.rebakeBatRoomdata"),
                "destructive roomdata rebake is opt-in; pass -Pdg.rebakeBatRoomdata=true");
        McBootstrap.boot();
        if (kr.syeyoung.dungeonsguide.mod.DungeonsGuide.getDungeonsGuide() == null)
            new kr.syeyoung.dungeonsguide.mod.DungeonsGuide();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-rd").toFile());
    }

    private static int countPositions(List<PossibleMoveSpot> spots) {
        if (spots == null) return 0;
        int n = 0;
        for (PossibleMoveSpot s : spots) n += s.getOffsetPointSet().size();
        return n;
    }

    @Test
    void rebakeAll() throws Exception {
        CBORMapper objectMapper = new CBORMapper();
        int roomsTouched = 0, batsRebaked = 0, degenerate = 0;
        StringBuilder report = new StringBuilder();
        for (DungeonRoomInfo dri : new java.util.ArrayList<>(DungeonRoomInfoRegistry.getRegistered())) {
            boolean touched = false;
            for (Map.Entry<String, DungeonMechanicData> e : dri.getMechanics().entrySet()) {
                if (!(e.getValue() instanceof DungeonSecretBatState.DungeonSecretBatData)) continue;
                DungeonSecretBatState.DungeonSecretBatData batData = (DungeonSecretBatState.DungeonSecretBatData) e.getValue();

                int oldCount = batData.getMoveNearest() == null ? 0
                        : countPositions(batData.getMoveNearest().getPrecalculatedStonk(Collections.emptyList()));

                PrecalculatedMoveNearest newData = PrecalculatedMoveNearest.createOneBat(batData.getSecretPoint(), dri);
                batData.setMoveNearest(newData);
                int newCount = countPositions(newData.getPrecalculatedStonk(Collections.emptyList()));
                batsRebaked++;
                touched = true;
                boolean deg = newCount < oldCount / 20 || newCount < 20;
                if (deg) degenerate++;
                report.append(String.format("%s %s bat=%s old=%d new=%d%s%n",
                        dri.getUuid(), dri.getName(), e.getKey(), oldCount, newCount, deg ? "  <-- DEGENERATE" : ""));
            }
            if (touched) {
                roomsTouched++;
                objectMapper.writeValue(new File(ROOMDATA_DIR, dri.getUuid().toString() + ".roomdata.cbor"), dri);
            }
        }
        System.out.println("== REBAKE REPORT ==");
        System.out.print(report);
        System.out.println("rooms touched=" + roomsTouched + " bats rebaked=" + batsRebaked + " degenerate=" + degenerate);
    }
}
