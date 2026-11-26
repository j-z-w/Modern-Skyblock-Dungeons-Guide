package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.PearlCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Forensic scan for the gravel-room bat endpoint complaint (2026-11-25):
// route ends midair / leads into a wall / no dig highlight.
// Decodes the dense node buffer of the implicated precalc directly:
//   - node-type histogram over the whole region
//   - every DESTINATION cell in canonical fine-grid coords
//   - the backward parent chain from each destination (how the endpoint is reached)
public class BatRegenVerifyTest {
    private static final String PFRES = System.getProperty("dg.batRegenPfres");

    private static IPathfindWorld stubWorld() {
        return new IPathfindWorld() {
            @Override public BlockState getActualBlock(int x, int y, int z) { return null; }
            @Override public CollisionStateCalculatingCoordinateMap.CollisionState getBlock(int x, int y, int z) { return null; }
            @Override public PearlCalculatingCoordinateMap.PearlLandType getPearl(int x, int y, int z) { return null; }
            @Override public boolean isInstabreak(int x, int y, int z) { return false; }
            @Override public int getXwidth() { return 126; }
            @Override public int getYwidth() { return 512; }
            @Override public int getZwidth() { return 126; }
            @Override public int getMinX() { return 0; }
            @Override public int getMinY() { return 0; }
            @Override public int getMinZ() { return 0; }
        };
    }

    @BeforeAll
    static void boot() throws Exception {
        Assumptions.assumeTrue(PFRES != null && new File(PFRES).isFile(),
                "opt-in forensic test; pass -Pdg.batRegenPfres=<pfres path>");
        kr.syeyoung.dungeonsguide.mod.McBootstrap.boot();
        if (kr.syeyoung.dungeonsguide.mod.DungeonsGuide.getDungeonsGuide() == null)
            new kr.syeyoung.dungeonsguide.mod.DungeonsGuide();
    }

    @Test
    void scanDestination() throws Exception {
        PathfindPrecalculation precalc = new PathfindPrecalculation(new File(PFRES));
        System.out.println("PFRES id=" + precalc.getId() + " room=" + precalc.getRoomUID()
                + " start=(" + precalc.getXStart() + "," + precalc.getYStart() + "," + precalc.getZStart() + ")"
                + " len=(" + precalc.getXLen() + "," + precalc.getYLen() + "," + precalc.getZLen() + ")");
        System.out.println("targets=" + precalc.getTargetLocations());

        PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinder(0);
        pf.init(stubWorld(), null);
        try {
            int xs = precalc.getXStart(), ys = precalc.getYStart(), zs = precalc.getZStart();
            int xl = precalc.getXLen(), yl = precalc.getYLen(), zl = precalc.getZLen();

            Map<String, Integer> histogram = new HashMap<>();
            List<int[]> destinations = new ArrayList<>();
            int nonEmpty = 0;
            for (int y = ys; y < ys + yl; y++) {
                for (int z = zs; z < zs + zl; z++) {
                    for (int x = xs; x < xs + xl; x++) {
                        PrecalculatedPathfinder.CachedPathfindNode n = pf.getNode(x, y, z);
                        if (n.getNodeType() == null) continue;
                        nonEmpty++;
                        histogram.merge(n.getNodeType().name(), 1, Integer::sum);
                        if (n.getNodeType() == PathfindResult.PathfindNode.NodeType.DESTINATION)
                            destinations.add(new int[]{x, y, z});
                    }
                }
            }
            System.out.println("nonEmpty=" + nonEmpty + " histogram=" + histogram);
            System.out.println("destination count=" + destinations.size());
            for (int[] d : destinations) {
                System.out.println(String.format("DEST cell=(%d,%d,%d) canonicalBlock=(%.1f,%.1f,%.1f)",
                        d[0], d[1], d[2], d[0] / 2.0, d[1] / 2.0, d[2] / 2.0));
            }

            // walk the parent chain backward from the first few destinations
            for (int di = 0; di < Math.min(destinations.size(), 3); di++) {
                int[] d = destinations.get(di);
                System.out.println("=== chain back from DEST " + di + " cell=(" + d[0] + "," + d[1] + "," + d[2] + ")");
                int x = d[0], y = d[1], z = d[2];
                for (int i = 0; i < 40; i++) {
                    PrecalculatedPathfinder.CachedPathfindNode n = pf.getNode(x, y, z);
                    if (n.getNodeType() == null) {
                        System.out.println(String.format("  [%02d] cell=(%d,%d,%d) canon=(%.1f,%.1f,%.1f) EMPTY",
                                i, x, y, z, x / 2.0, y / 2.0, z / 2.0));
                        break;
                    }
                    System.out.println(String.format("  [%02d] cell=(%d,%d,%d) canon=(%.2f,%.2f,%.2f) type=%-11s g=%.2f parent=(%d,%d,%d)",
                            i, x, y, z, x / 2.0, y / 2.0, z / 2.0, n.getNodeType(), n.getGScore(), n.getX(), n.getY(), n.getZ()));
                    if (n.getX() == x && n.getY() == y && n.getZ() == z) break;
                    x = n.getX(); y = n.getY(); z = n.getZ();
                }
            }
        } finally {
            pf.close();
        }
    }

    // player was at world (-135.3,92.0,-113.7) -> estimated canonical fine ~(67,184,47).
    // scan the neighborhood for non-empty cells and decode full forward chains with types.
    @Test
    void replicatePlayerQuery() throws Exception {
        PathfindPrecalculation precalc = new PathfindPrecalculation(new File(PFRES));
        PrecalculatedPathfinder pf = (PrecalculatedPathfinder) precalc.createPathfinder(0);
        pf.init(stubWorld(), null);
        try {
            java.util.Set<String> seenSigs = new java.util.HashSet<>();
            for (int nx = 63; nx <= 72; nx++) for (int ny = 178; ny <= 190; ny++) for (int nz = 42; nz <= 52; nz++) {
                PrecalculatedPathfinder.CachedPathfindNode start0 = pf.getNode(nx, ny, nz);
                if (start0.getNodeType() == null) continue;
                // decode forward chain (toward DESTINATION) with types
                StringBuilder sig = new StringBuilder();
                StringBuilder out = new StringBuilder();
                int x = nx, y = ny, z = nz, hops = 0;
                boolean loops = false, reachesDest = false;
                java.util.Set<String> visited = new java.util.HashSet<>();
                while (hops < 60) {
                    PrecalculatedPathfinder.CachedPathfindNode n = pf.getNode(x, y, z);
                    if (n.getNodeType() == null) { out.append(" -> EMPTY"); break; }
                    out.append(String.format(" [%d](%d,%d,%d)%s", hops, x, y, z, n.getNodeType()));
                    sig.append(n.getNodeType().name()).append(59);
                    if (n.getNodeType() == PathfindResult.PathfindNode.NodeType.DESTINATION) { reachesDest = true; break; }
                    if (!visited.add(x+","+y+","+z)) { loops = true; out.append(" -> LOOP"); break; }
                    if (n.getX() == x && n.getY() == y && n.getZ() == z) { out.append(" -> SELF"); break; }
                    x = n.getX(); y = n.getY(); z = n.getZ();
                    hops++;
                }
                String key = sig.toString();
                if (seenSigs.add(key)) {
                    System.out.println("start=(" + nx + "," + ny + "," + nz + ") type=" + start0.getNodeType()
                            + " hops=" + hops + " reachesDest=" + reachesDest + (loops ? " LOOP" : "") + " chain:" + out);
                }
            }
            System.out.println("scan complete, unique chains=" + seenSigs.size());
        } finally {
            pf.close();
        }
    }
}
