package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.PearlCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EtherwarpForensicDumpTest {
    private static final String PFRES = System.getProperty("dg.etherwarpForensicPfres");
    private static final int ROTATION = 1;
    private static final int ROOM_MIN_X = -137;
    private static final int ROOM_MIN_Z = -104;

    private static IPathfindWorld world() {
        return new IPathfindWorld() {
            @Override public BlockState getActualBlock(int x, int y, int z) { return null; }
            @Override public CollisionStateCalculatingCoordinateMap.CollisionState getBlock(int x, int y, int z) { return null; }
            @Override public PearlCalculatingCoordinateMap.PearlLandType getPearl(int x, int y, int z) { return null; }
            @Override public boolean isInstabreak(int x, int y, int z) { return false; }
            @Override public int getXwidth() { return 126; }
            @Override public int getYwidth() { return 512; }
            @Override public int getZwidth() { return 126; }
            @Override public int getMinX() { return ROOM_MIN_X * 2 + 2; }
            @Override public int getMinY() { return 0; }
            @Override public int getMinZ() { return ROOM_MIN_Z * 2 + 2; }
        };
    }

    @Test
    void dumpImplicatedMarketRoute() throws Exception {
        Assumptions.assumeTrue(PFRES != null && new File(PFRES).isFile(),
                "opt-in forensic test; pass -Pdg.etherwarpForensicPfres=<pfres path>");
        PathfindPrecalculation precalc = new PathfindPrecalculation(new File(PFRES));
        System.out.println("PFRES id=" + precalc.getId()
                + " room=" + precalc.getRoomUID()
                + " dims start=(" + precalc.getXStart() + "," + precalc.getYStart() + "," + precalc.getZStart()
                + ") len=(" + precalc.getXLen() + "," + precalc.getYLen() + "," + precalc.getZLen() + ")"
                + " compressed=" + precalc.isCompressed()
                + " targets=" + precalc.getTargetLocations().size());
        System.out.println("ALGO etherwarp=" + precalc.getAlgorithmSetting().isRouteEtherwarp()
                + " radius=" + precalc.getAlgorithmSetting().getEtherwarpRadius()
                + " leeway=" + precalc.getAlgorithmSetting().getEtherwarpLeeway()
                + " offset=" + precalc.getAlgorithmSetting().getEtherwarpOffset());

        PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinder(ROTATION);
        pf.init(world(), null);
        try {
            Vec3 player = new Vec3(-102.4, 60.0, -62.9);
            PathfindResult route = pf.getRoute(player);
            assertNotNull(route, "route did not resolve for the implicated player cell");
            System.out.println("ROTATION=" + ROTATION + " ROOM_MIN=(" + ROOM_MIN_X + ",70," + ROOM_MIN_Z + ") PLAYER=" + player);
            System.out.println("ROUTE cost=" + route.getCost() + " nodes=" + route.getNodeList().size());
            for (int i = 0; i < route.getNodeList().size(); i++) {
                PathfindResult.PathfindNode n = route.getNodeList().get(i);
                System.out.println(String.format("ROUTE[%02d] type=%-11s world=(%.2f,%.2f,%.2f)", i, n.getType(), n.getX(), n.getY(), n.getZ()));
            }

            // Rotation-1 inverse lookup for this square room, matching PrecalculatedPathfinder.buildRoute.
            double dx = player.x - ROOM_MIN_X;
            double dz = player.z - ROOM_MIN_Z;
            double canonicalX = -dz + (63 + 2);
            double canonicalZ = dx;
            int cx = (int) Math.round(canonicalX * 2);
            int cy = (int) Math.round(player.y * 2);
            int cz = (int) Math.round(canonicalZ * 2);
            System.out.println("CANONICAL_START cell=(" + cx + "," + cy + "," + cz + ") pos=(" + canonicalX + "," + player.y + "," + canonicalZ + ")");
            PrecalculatedPathfinder.CachedPathfindNode cur = pf.getNode(cx, cy, cz);
            int x = cx, y = cy, z = cz;
            for (int i = 0; cur.getNodeType() != null && i < 100; i++) {
                System.out.println(String.format("CHAIN[%02d] cell=(%d,%d,%d) center=(%.1f,%.1f,%.1f) type=%-11s g=%.1f parent=(%d,%d,%d)",
                        i, x, y, z, x / 2.0, y / 2.0, z / 2.0, cur.getNodeType(), cur.getGScore(), cur.getX(), cur.getY(), cur.getZ()));
                if (cur.getNodeType() == PathfindResult.PathfindNode.NodeType.DESTINATION) break;
                x = cur.getX(); y = cur.getY(); z = cur.getZ();
                cur = pf.getNode(x, y, z);
            }
        } finally {
            pf.close();
        }
    }
}
