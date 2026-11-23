package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// S12: RouteRoomOverlay.canonicalize must be the exact inverse of toWorld.
// the old version ran the rotation steps forward and used blocks-array dims, so
// canonicalize(toWorld(p)) drifted by a block whenever rotation >= 2 or the
// bounds span differed from the blocks dimensions
public class RouteInverseTest {

    @Test
    void canonicalizeInvertsToWorldAllRotations() {
        for (int rot = 0; rot < 4; rot++) {
            for (double[] pt : new double[][]{{0.5, 4, 0.5}, {5.5, 4, 20.25}, {63, 10, 31}, {1, 0, 30}}) {
                // non-square bounds + off-origin min: exercises both the loop-order
                // fix (needs xLen != zLen) and the span-vs-blocks fix
                double xLen = 64, zLen = 32;
                int minX = 100, minY = 66, minZ = 200;
                RouteVec3 canon = new RouteVec3(pt[0], pt[1], pt[2]);
                Vec3 world = RouteRoomOverlay.toWorld(canon, rot, xLen, zLen, minX, minY, minZ);
                RouteVec3 back = RouteRoomOverlay.canonicalize(world, rot, xLen, zLen, minX, minY, minZ);
                assertEquals(canon.x, back.x, 1e-6, "x round-trip at rotation " + rot);
                assertEquals(canon.y, back.y, 1e-6, "y round-trip at rotation " + rot);
                assertEquals(canon.z, back.z, 1e-6, "z round-trip at rotation " + rot);

                RouteVec3 canon2 = RouteRoomOverlay.canonicalize(world, rot, xLen, zLen, minX, minY, minZ);
                Vec3 world2 = RouteRoomOverlay.toWorld(canon2, rot, xLen, zLen, minX, minY, minZ);
                assertEquals(world.x, world2.x, 1e-6, "toWorld(canonicalize(w)) x at rotation " + rot);
                assertEquals(world.z, world2.z, 1e-6, "toWorld(canonicalize(w)) z at rotation " + rot);
            }
        }
    }

    @Test
    void canonicalizeMatchesOffsetPointInverseForm() {
        // T_i^{-1}(a,b) = (-b + B_i, a) undone in REVERSE order, B_i the bound span.
        // rotation 2, xLen != zLen: the wrong forward loop produces a different answer
        double xLen = 64, zLen = 32;
        Vec3 world = new Vec3(100 + 10, 70, 200 + 5); // rel (10, 5)
        RouteVec3 canon = RouteRoomOverlay.canonicalize(world, 2, xLen, zLen, 100, 70, 200);
        // manual: i=1: x=-5 + xLen=59, z=10; i=0: x=-10 + zLen=22, z=59
        assertEquals(22, canon.x, 1e-6);
        assertEquals(59, canon.z, 1e-6);
    }
}
