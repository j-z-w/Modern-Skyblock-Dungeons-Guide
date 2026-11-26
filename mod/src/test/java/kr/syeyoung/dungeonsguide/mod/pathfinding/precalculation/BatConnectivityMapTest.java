package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.RaytraceHelper;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

// visualizes the connectivity fill over the geometry slices: C = connected to bat pocket,
// . = unconnected air, # solid, s/t slab/stair. reveals where the fill escapes the cavity
public class BatConnectivityMapTest {

    @BeforeAll
    static void boot() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dg.consoleDump"),
                "console geometry dump is opt-in; pass -Pdg.consoleDump=true");
        McBootstrap.boot();
        if (kr.syeyoung.dungeonsguide.mod.DungeonsGuide.getDungeonsGuide() == null)
            new kr.syeyoung.dungeonsguide.mod.DungeonsGuide();
        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-rd").toFile());
    }

    @Test
    void mapConnectivity() {
        DungeonRoomInfo dri = DungeonRoomInfoRegistry.getByUUID(UUID.fromString("42029218-9142-4286-b184-e17b3734886f"));
        DRIWorld world = new DRIWorld(dri, Collections.emptyList(), new PathfindPreset());
        Vec3 seed = new Vec3(35 + 0.5, 22 + 70.5, 14 + 0.5);
        Set<Long> connected = RaytraceHelper.computeConnectedAirspace(world, seed, 12);
        System.out.println("connected cells=" + (connected == null ? "NULL" : connected.size()));

        for (int y = 18; y <= 24; y++) {
            System.out.println("=== room-local y=" + y + " x-across z-down  (C=connected .=air #=solid)");
            StringBuilder header = new StringBuilder("    z-x");
            for (int x = 26; x <= 46; x++) header.append(String.format("%3d", x));
            System.out.println(header);
            for (int z = 6; z <= 26; z++) {
                StringBuilder row = new StringBuilder(String.format("z=%3d ", z));
                for (int x = 26; x <= 46; x++) {
                    BlockState bs = world.getBlockState(new BlockPos(x, y + 70, z));
                    String mark;
                    if (bs == null) mark = "  ?";
                    else if (bs.isAir()) {
                        // air: connected? test both half-levels of this block column at this y
                        boolean conn = false;
                        for (double frac : new double[]{0.25, 0.75}) {
                            if (connected != null && RaytraceHelper.isInConnectedAirspace(connected, x + 0.5, y + 70 + frac, z + 0.5)) conn = true;
                        }
                        mark = conn ? "  C" : "  .";
                    } else {
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
