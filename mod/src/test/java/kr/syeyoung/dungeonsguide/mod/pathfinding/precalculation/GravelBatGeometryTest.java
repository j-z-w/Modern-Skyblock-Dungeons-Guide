package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.UUID;

// dumps the block geometry around the gravel bat (room-local 35,22,14) so the
// pocket structure is visible: which positions are enclosed airspace vs roof vs wall
public class GravelBatGeometryTest {

    @BeforeAll
    static void boot() throws Exception {
        McBootstrap.boot();
        if (kr.syeyoung.dungeonsguide.mod.DungeonsGuide.getDungeonsGuide() == null)
            new kr.syeyoung.dungeonsguide.mod.DungeonsGuide();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-rd").toFile());
    }

    @Test
    void dumpGeometry() {
        DungeonRoomInfo dri = DungeonRoomInfoRegistry.getByUUID(UUID.fromString("42029218-9142-4286-b184-e17b3734886f"));
        System.out.println("room=" + dri.getName() + " shape=" + dri.getShape());
        DRIWorld world = new DRIWorld(dri, java.util.Collections.singletonList("door-1"), new kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset());

        for (int y = 16; y <= 26; y++) {
            System.out.println("=== room-local y=" + y + " (world y=" + (y + 70) + ") x-across z-down");
            StringBuilder header = new StringBuilder("    z-x");
            for (int x = 26; x <= 46; x++) header.append(String.format("%3d", x));
            System.out.println(header);
            for (int z = 6; z <= 26; z++) {
                StringBuilder row = new StringBuilder(String.format("z=%3d ", z));
                for (int x = 26; x <= 46; x++) {
                    BlockState bs = world.getBlockState(new BlockPos(x, y + 70, z));
                    String mark;
                    if (x == 35 && z == 14 && y == 22) mark = "  B";
                    else if (bs == null) mark = "  ?";
                    else if (bs.isAir()) mark = "  .";
                    else {
                        String name = bs.getBlock().toString().toLowerCase();
                        if (name.contains("slab")) mark = "  s";
                        else if (name.contains("stair")) mark = "  t";
                        else mark = "  #";
                    }
                    row.append(mark);
                }
                System.out.println(row);
            }
        }
    }
}
