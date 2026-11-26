package kr.syeyoung.dungeonsguide.mod.features.impl.dungeon;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// B2: the bat entity scan box must be a cube centred on the player. the AABB
// two-vector constructor spans BETWEEN the corners, so (playerPos, ZERO) scanned
// the slab from the world origin to the player every tick
public class FeatureBoxBatsTest {

    @Test
    void scanBoxCentersOnPlayer() {
        Vec3 pos = new Vec3(500.5, 70, -300.5);
        AABB box = FeatureBoxBats.scanBox(pos, 20);
        assertEquals(pos.x - 20, box.minX, 1e-9);
        assertEquals(pos.y - 20, box.minY, 1e-9);
        assertEquals(pos.z - 20, box.minZ, 1e-9);
        assertEquals(pos.x + 20, box.maxX, 1e-9);
        assertEquals(pos.y + 20, box.maxY, 1e-9);
        assertEquals(pos.z + 20, box.maxZ, 1e-9);
    }

    @Test
    void scanBoxDoesNotStretchToOrigin() {
        // the regression: a player far from (0,0,0) produced a box reaching back to it
        AABB box = FeatureBoxBats.scanBox(new Vec3(500, 70, 500), 20);
        assertTrue(box.minX > 0 && box.minZ > 0,
                "scan box must be local to the player, not a slab from the world origin");
        assertTrue(box.contains(500, 70, 500), "player position must be inside the scan box");
        assertEquals(40.0, box.getXsize(), 1e-9);
        assertFalse(box.contains(0, 0, 0), "the origin must not be inside the scan box");
    }
}
