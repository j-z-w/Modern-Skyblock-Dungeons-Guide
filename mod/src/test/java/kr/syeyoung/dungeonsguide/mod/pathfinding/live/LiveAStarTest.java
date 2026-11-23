package kr.syeyoung.dungeonsguide.mod.pathfinding.live;

import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap.CollisionState;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.PearlCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LiveAStarTest {

    /**
     * Whole-block solid cells mapped onto the doubled node grid, mimicking the
     * collision map's semantics: a node is blocked when the player's feet or head
     * cell is solid, grounded when the cell right under the feet is solid.
     */
    private static class FakePathfindWorld implements IPathfindWorld {
        private final boolean[][][] solid; // [x][y][z], block coords
        private final int lenX, lenY, lenZ;

        FakePathfindWorld(int lenX, int lenY, int lenZ) {
            this.lenX = lenX;
            this.lenY = lenY;
            this.lenZ = lenZ;
            this.solid = new boolean[lenX][lenY][lenZ];
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        solid[x][y][z] = true;
        }

        @Override
        public CollisionState getBlock(int x, int y, int z) {
            int cx = x / 2, cy = y / 2, cz = z / 2;
            if (x < 0 || z < 0 || y < 0 || cx >= lenX || cy + 1 >= lenY || cz >= lenZ) return CollisionState.BLOCKED;
            boolean blocked = solid[cx][cy][cz] || solid[cx][cy + 1][cz];
            if (blocked) return CollisionState.BLOCKED;
            if (cy - 1 >= 0 && solid[cx][cy - 1][cz]) return CollisionState.ONGROUND;
            return CollisionState.ONAIR;
        }

        @Override public BlockState getActualBlock(int x, int y, int z) { return null; }
        @Override public PearlCalculatingCoordinateMap.PearlLandType getPearl(int x, int y, int z) {
            return PearlCalculatingCoordinateMap.PearlLandType.BLOCKED;
        }
        @Override public boolean isInstabreak(int x, int y, int z) { return false; }
        @Override public int getXwidth() { return lenX * 2; }
        @Override public int getYwidth() { return lenY * 2; }
        @Override public int getZwidth() { return lenZ * 2; }
        @Override public int getMinX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMinZ() { return 0; }
    }

    private static List<AABB> doubledGoalBox(double x, double y, double z) {
        return List.of(new AABB(2 * x - 0.2, 2 * y - 0.2, 2 * z - 0.2, 2 * x + 0.2, 2 * y + 0.2, 2 * z + 0.2));
    }

    private static long cell(int x, int y, int z) {
        return LiveAStar.pack(x, y, z);
    }

    private static void assertLegalPath(FakePathfindWorld world, LiveAStar.Result res, long start) {
        assertTrue(res.cells.length >= 2);
        assertEquals(start, res.cells[0]);
        long prev = -1;
        for (long cell : res.cells) {
            assertTrue(LiveAStar.walkable(world, cell), "path goes through unwalkable cell " + cell);
            if (prev != -1) {
                int dx = Math.abs(LiveAStar.unpackX(cell) - LiveAStar.unpackX(prev));
                int dy = Math.abs(LiveAStar.unpackY(cell) - LiveAStar.unpackY(prev));
                int dz = Math.abs(LiveAStar.unpackZ(cell) - LiveAStar.unpackZ(prev));
                assertEquals(1, dx + dy + dz, "consecutive cells must be axis-adjacent");
            }
            prev = cell;
        }
    }

    @Test
    void straightLineAcrossFloor() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor

        long start = cell(4, 2, 4);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(10, 1, 10), 8000);

        assertEquals(LiveAStar.Status.FOUND, res.status);
        assertLegalPath(world, res, start);
        // 16 + 16 ground cells at cost 1 each
        assertEquals(32, res.cost, 1e-6);
    }

    @Test
    void pathAroundWall() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor
        world.fill(10, 1, 4, 10, 3, 19); // 3-high wall, gap at z<4 and z>19

        long start = cell(4, 2, 10);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(10, 1, 10), 8000);

        assertEquals(LiveAStar.Status.FOUND, res.status);
        assertLegalPath(world, res, start);
        // straight would be 12; the wall forces a detour
        assertTrue(res.cost > 12, "expected detour, got cost " + res.cost);
        for (long c : res.cells) {
            int bx = LiveAStar.unpackX(c) / 2, by = LiveAStar.unpackY(c) / 2, bz = LiveAStar.unpackZ(c) / 2;
            assertFalse(bx == 10 && by >= 1 && by <= 3 && bz >= 4 && bz <= 19, "path clips the wall");
        }
    }

    @Test
    void pathUpOneBlockStep() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor
        world.fill(10, 1, 10, 10, 1, 10); // one-block ledge

        long start = cell(4, 2, 10);
        // standing on the ledge: feet at y=2 -> doubled 4
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(10, 2, 10), 8000);

        assertEquals(LiveAStar.Status.FOUND, res.status);
        assertLegalPath(world, res, start);
        assertEquals(4, LiveAStar.unpackY(res.cells[res.cells.length - 1]));
        // +2 dy on the doubled grid somewhere along the way
        boolean climbed = false;
        for (int i = 1; i < res.cells.length; i++) {
            if (LiveAStar.unpackY(res.cells[i]) > LiveAStar.unpackY(res.cells[i - 1])) climbed = true;
        }
        assertTrue(climbed, "path never goes up");
    }

    @Test
    void unreachableGoal() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor
        // sealed 3-high box around (13,1..2,13)
        world.fill(12, 1, 12, 14, 3, 12);
        world.fill(12, 1, 14, 14, 3, 14);
        world.fill(12, 1, 13, 12, 3, 13);
        world.fill(14, 1, 13, 14, 3, 13);
        world.fill(12, 3, 12, 14, 3, 14); // lid

        long start = cell(4, 2, 4);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(13, 1, 13), 8000);

        assertNotEquals(LiveAStar.Status.FOUND, res.status);
        assertTrue(res.cells.length == 0);
    }

    @Test
    void expansionCapRespected() {
        FakePathfindWorld world = new FakePathfindWorld(64, 16, 64);
        world.fill(0, 0, 0, 63, 0, 63); // big floor

        long start = cell(4, 2, 4);
        // reachable but far corner: with cap 200 the wavefront dies before arrival
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(62, 1, 62), 200);

        assertEquals(LiveAStar.Status.EXPANSION_LIMIT, res.status);
        assertTrue(res.expansions <= 200, "expansions " + res.expansions + " exceeded cap");
    }

    @Test
    void goalBuriedInSolidFails() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor
        // goal inside a 3x3x4 solid pillar: nothing standable within the widen radius
        world.fill(9, 1, 9, 11, 4, 11);

        long start = cell(4, 2, 4);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(10, 3, 10), 8000);

        assertEquals(LiveAStar.Status.NO_PASSABLE_GOAL, res.status);
    }

    @Test
    void goalAgainstWallWidens() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23); // floor
        // goal sits inside a thin wall face; a standable cell exists 1 block away
        world.fill(12, 1, 10, 12, 3, 10);

        long start = cell(4, 2, 10);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(12, 1, 10), 8000);

        // the widen fallback should settle on a standable neighbour of the blocked target
        assertEquals(LiveAStar.Status.FOUND, res.status);
        assertLegalPath(world, res, start);
    }

    @Test
    void pathStalenessCheck() {
        FakePathfindWorld world = new FakePathfindWorld(24, 16, 24);
        world.fill(0, 0, 0, 23, 0, 23);

        long start = cell(4, 2, 4);
        LiveAStar.Result res = LiveAStar.search(world, start, doubledGoalBox(10, 1, 10), 8000);
        assertEquals(LiveAStar.Status.FOUND, res.status);
        assertTrue(LiveAStar.pathStillValid(world, res.cells));

        // wall off the path: make a path cell unwalkable
        long mid = res.cells[res.cells.length / 2];
        int bx = LiveAStar.unpackX(mid) / 2, by = LiveAStar.unpackY(mid) / 2, bz = LiveAStar.unpackZ(mid) / 2;
        world.solid[bx][by][bz] = true;
        assertFalse(LiveAStar.pathStillValid(world, res.cells));
    }
}
