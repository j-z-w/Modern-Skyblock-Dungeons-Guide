package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.PearlCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Rotated rooms must query the same canonical pfres cell that the inverse of the
// route-position transform describes. The failing case is invisible in square or
// unrotated rooms: a one-cell lookup error still returns a plausible, usually walk-only,
// neighboring chain instead of the chain carrying the breaker dig nodes.
public class PrecalculatedPathfinderRotationTest {
    private static final int X_LEN = 20;
    private static final int Y_LEN = 10;
    private static final int Z_LEN = 40;
    private static final int TARGET_X = 2;
    private static final int TARGET_Y = 4;
    private static final int TARGET_Z = 2;

    private static IPathfindWorld world(int roomXLen, int roomZLen) {
        return new IPathfindWorld() {
            @Override public BlockState getActualBlock(int x, int y, int z) { return null; }
            @Override public CollisionStateCalculatingCoordinateMap.CollisionState getBlock(int x, int y, int z) { return null; }
            @Override public PearlCalculatingCoordinateMap.PearlLandType getPearl(int x, int y, int z) { return null; }
            @Override public boolean isInstabreak(int x, int y, int z) { return false; }
            @Override public int getXwidth() { return roomXLen * 2; }
            @Override public int getYwidth() { return Y_LEN; }
            @Override public int getZwidth() { return roomZLen * 2; }
            @Override public int getMinX() { return 2; }
            @Override public int getMinY() { return 0; }
            @Override public int getMinZ() { return 2; }
        };
    }

    private static float cost(int x, int y, int z) {
        return x * 10000.0f + y * 100.0f + z;
    }

    private static void putNode(ByteBuffer buffer, int x, int y, int z,
                                int parentX, int parentY, int parentZ, int type, float gScore) {
        int idx = (y * X_LEN * Z_LEN + z * X_LEN + x) * 8;
        buffer.put(idx, (byte) parentX);
        buffer.put(idx + 1, (byte) parentY);
        buffer.put(idx + 2, (byte) parentZ);
        buffer.put(idx + 3, (byte) type);
        int bits = Float.floatToIntBits(gScore); // pfres stores the score little-endian
        buffer.put(idx + 4, (byte) bits);
        buffer.put(idx + 5, (byte) (bits >> 8));
        buffer.put(idx + 6, (byte) (bits >> 16));
        buffer.put(idx + 7, (byte) (bits >> 24));
    }

    private static Vec3 canonicalToWorld(int x, int y, int z, int rotation, int roomXLen, int roomZLen) {
        double rx = x / 2.0, rz = z / 2.0;
        for (int i = 0; i < rotation; i++) {
            double temp = rx;
            rx = rz;
            rz = -temp + (i % 2 == 0 ? roomZLen + 2 : roomXLen + 2);
        }
        return new Vec3(rx, y / 2.0, rz);
    }

    private static PathfindPrecalculation.ReferenceCountedByteBufferWrapper fixtureBuffer() {
        Arena arena = Arena.ofShared();
        ByteBuffer buffer = arena.allocate((long) X_LEN * Y_LEN * Z_LEN * 8).asByteBuffer();
        for (int y = 0; y < Y_LEN; y++)
            for (int z = 0; z < Z_LEN; z++)
                for (int x = 0; x < X_LEN; x++)
                    putNode(buffer, x, y, z, TARGET_X, TARGET_Y, TARGET_Z,
                            PathfindResult.PathfindNode.NodeType.DIG_DOWN.ordinal(), cost(x, y, z));
        putNode(buffer, TARGET_X, TARGET_Y, TARGET_Z, 0, 0, 0,
                PathfindResult.PathfindNode.NodeType.DESTINATION.ordinal(), 0.0f);
        return new PathfindPrecalculation.ReferenceCountedByteBufferWrapper(arena, buffer);
    }

    @Test
    void rotatedLookupRoundTripsCanonicalDigCells() throws Exception {
        for (int rotation = 0; rotation < 4; rotation++) {
            int roomXLen = (rotation & 1) == 0 ? X_LEN / 2 - 1 : Z_LEN / 2 - 1;
            int roomZLen = (rotation & 1) == 0 ? Z_LEN / 2 - 1 : X_LEN / 2 - 1;
            PrecalculatedPathfinder pf = new PrecalculatedPathfinder(
                    rotation, 0, 0, 0, X_LEN, Y_LEN, Z_LEN, fixtureBuffer());
            pf.init(world(roomXLen, roomZLen), null);
            for (int[] cell : new int[][]{{6, 4, 8}, {14, 4, 30}, {8, 4, 34}}) {
                Vec3 worldPos = canonicalToWorld(cell[0], cell[1], cell[2], rotation, roomXLen, roomZLen);
                PathfindResult route = pf.getRoute(worldPos);
                assertNotNull(route, "rot " + rotation + " cell " + cell[0] + "," + cell[2] + " did not resolve");
                assertEquals(cost(cell[0], cell[1], cell[2]), route.getCost(), 0.001,
                        "rot " + rotation + " queried the wrong canonical cell");
                assertEquals(PathfindResult.PathfindNode.NodeType.DIG_DOWN,
                        route.getNodeList().get(0).getType(), "dig node type lost in pfres round trip");
                assertEquals(PathfindResult.PathfindNode.NodeType.DESTINATION,
                        route.getNodeList().get(route.getNodeList().size() - 1).getType(),
                        "dig chain did not reach its destination");

                List<NeoRouteDisplayEngine.PathSegment> segments =
                        NeoRouteDisplayEngine.transformPathfindResult(route.getNodeList(), 0.2, 0.1);
                assertFalse(segments.isEmpty(), "renderer transform produced no continuous line");
                assertEquals(PathfindResult.PathfindNode.NodeType.DIG_DOWN, segments.get(0).getNodeType(),
                        "renderer transform dropped the dig segment type");
            }
            pf.close();
        }
    }

    private static boolean isDig(PathfindResult.PathfindNode.NodeType type) {
        return type == PathfindResult.PathfindNode.NodeType.STONK_EXIT
                || type == PathfindResult.PathfindNode.NodeType.STONK_WALK
                || type == PathfindResult.PathfindNode.NodeType.DIG_DOWN
                || type == PathfindResult.PathfindNode.NodeType.DIG_UP;
    }

    private static void assertImplicatedFixtureRoute(String id, int rotation, int[] canonicalCell,
                                                     int expectedNodes, int expectedDigNodes) throws Exception {
        String configuredDir = System.getProperty("dg.pfresDir", System.getenv("DG_PFRES_DIR"));
        assumeTrue(configuredDir != null, "set dg.pfresDir or DG_PFRES_DIR to run the implicated fixture check");
        File file = new File(new File(configuredDir), id + ".pfres");
        assumeTrue(file.isFile(), "optional implicated pfres fixture is not present: " + file);

        PathfindPrecalculation precalc = new PathfindPrecalculation(file);
        assertTrue(precalc.getAlgorithmSetting().isDungeonBreaker(), id + " was not generated in breaker mode");
        assertEquals(20, precalc.getAlgorithmSetting().getDigBudget(), id + " has an unexpected dig budget");

        java.lang.reflect.Method readBuffer = PathfindPrecalculation.class.getDeclaredMethod("readBufferFromFile");
        readBuffer.setAccessible(true);
        PathfindPrecalculation.ReferenceCountedByteBufferWrapper buffer =
                (PathfindPrecalculation.ReferenceCountedByteBufferWrapper) readBuffer.invoke(precalc);
        PrecalculatedPathfinder pf = new PrecalculatedPathfinder(rotation,
                precalc.getXStart(), precalc.getYStart(), precalc.getZStart(),
                precalc.getXLen(), precalc.getYLen(), precalc.getZLen(), buffer);
        int roomXLen = (rotation & 1) == 0 ? precalc.getXLen() / 2 - 1 : precalc.getZLen() / 2 - 1;
        int roomZLen = (rotation & 1) == 0 ? precalc.getZLen() / 2 - 1 : precalc.getXLen() / 2 - 1;
        pf.init(world(roomXLen, roomZLen), null);
        try {
            Vec3 worldPos = canonicalToWorld(canonicalCell[0], canonicalCell[1], canonicalCell[2], rotation, roomXLen, roomZLen);
            PathfindResult route = pf.getRoute(worldPos);
            assertNotNull(route, id + " rotated start resolved to an unpopulated neighboring cell");
            assertEquals(expectedNodes, route.getNodeList().size(), id + " did not reconstruct the intended chain");
            assertEquals(expectedDigNodes, route.getNodeList().stream().filter(n -> isDig(n.getType())).count(),
                    id + " lost its breaker dig nodes");
            assertEquals(PathfindResult.PathfindNode.NodeType.DESTINATION,
                    route.getNodeList().get(route.getNodeList().size() - 1).getType(),
                    id + " dig chain did not reach the crypt-interior destination");

            List<NeoRouteDisplayEngine.PathSegment> segments =
                    NeoRouteDisplayEngine.transformPathfindResult(route.getNodeList(), 0.2, 0.1);
            assertFalse(segments.isEmpty(), id + " produced no line segments for NeoRoute");
            assertTrue(segments.stream().anyMatch(s -> isDig(s.getNodeType())),
                    id + " NeoRoute segment transform lost every dig segment");
        } finally {
            pf.close();
        }
    }

    @Test
    void implicatedBreakerPrecalcsKeepDigRoutesInRotatedRooms() throws Exception {
        // Canonical octree coordinates recovered directly from the two user-observed files.
        // gravel is a 96x32 room observed at rotation 3; archway is 32x64 and exercises rotation 2.
        assertImplicatedFixtureRoute("68d1443e9132dd1b21f6e399229f9f92", 3,
                new int[]{96, 138, 6}, 35, 2);
        assertImplicatedFixtureRoute("967c1aee3e32ded341ec37fee293e8a2", 2,
                new int[]{8, 164, 22}, 13, 4);
    }
    @Test
    void trapHardDigRouteReconstructsInEveryRotation() throws Exception {
        JsonNode roomdata;
        try (InputStream in = PrecalculatedPathfinderRotationTest.class.getResourceAsStream(
                "/roomdata/81cf3e0b-70c5-4803-9169-7e7864b096ce.roomdata.cbor")) {
            assertNotNull(in, "bundled trap-hard roomdata missing");
            roomdata = new CBORMapper().readTree(in);
        }
        assertEquals("trap-hard", roomdata.get("name").asText());
        assertEquals(32, roomdata.get("width").asInt());
        assertEquals(32, roomdata.get("length").asInt());

        String configuredDir = System.getProperty("dg.pfresDir", System.getenv("DG_PFRES_DIR"));
        assumeTrue(configuredDir != null, "set dg.pfresDir or DG_PFRES_DIR to run the trap-hard fixture check");
        File file = new File(new File(configuredDir), "9d3f77695eaef322749397b3309d5b73.pfres");
        assumeTrue(file.isFile(), "trap-hard fixture is not present: " + file);

        PathfindPrecalculation precalc = new PathfindPrecalculation(file);
        assertTrue(precalc.getAlgorithmSetting().isDungeonBreaker());

        java.lang.reflect.Method readBuffer = PathfindPrecalculation.class.getDeclaredMethod("readBufferFromFile");
        readBuffer.setAccessible(true);
        for (int rotation = 0; rotation < 4; rotation++) {
            PathfindPrecalculation.ReferenceCountedByteBufferWrapper buffer =
                    (PathfindPrecalculation.ReferenceCountedByteBufferWrapper) readBuffer.invoke(precalc);
            PrecalculatedPathfinder pf = new PrecalculatedPathfinder(rotation,
                    precalc.getXStart(), precalc.getYStart(), precalc.getZStart(),
                    precalc.getXLen(), precalc.getYLen(), precalc.getZLen(), buffer);
            pf.init(world(31, 31), null);
            try {
                int[] start = {27, 178, 45};
                int[] end = {21, 178, 50};
                Vec3 worldStart = canonicalToWorld(start[0], start[1], start[2], rotation, 31, 31);
                Vec3 worldEnd = canonicalToWorld(end[0], end[1], end[2], rotation, 31, 31);
                PathfindResult route = pf.getRoute(worldStart);
                assertNotNull(route, "rot " + rotation + " trap-hard start did not resolve");
                assertEquals(12, route.getNodeList().size(), "rot " + rotation + " did not reconstruct the observed 12-node route");
                assertEquals(2, route.getNodeList().stream().filter(n -> isDig(n.getType())).count(),
                        "rot " + rotation + " lost the two breaker dig nodes");
                PathfindResult.PathfindNode last = route.getNodeList().get(route.getNodeList().size() - 1);
                assertEquals(PathfindResult.PathfindNode.NodeType.DESTINATION, last.getType());
                assertEquals(worldEnd.x, last.getX(), 0.0001, "rot " + rotation + " endpoint x used the wrong rotation span");
                assertEquals(worldEnd.y + 0.05, last.getY(), 0.0001, "rot " + rotation + " endpoint y");
                assertEquals(worldEnd.z, last.getZ(), 0.0001, "rot " + rotation + " endpoint z used the wrong rotation span");
            } finally {
                pf.close();
            }
        }
    }

}
