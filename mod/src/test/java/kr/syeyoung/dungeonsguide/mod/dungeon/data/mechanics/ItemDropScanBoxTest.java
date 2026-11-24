package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// B4: the item-drop detection box must be +/-4 around the drop point, not a slab
// from the world origin (the expandTowards bug scanned half the dungeon and read
// any unrelated item entity inside it as "the drop is still there")
public class ItemDropScanBoxTest {

    @Test
    void scanBoxCentersOnDropPoint() {
        BlockPos pos = new BlockPos(100, 70, -50);
        AABB box = DungeonSecretItemDropState.scanBox(pos);

        // pos..pos+1 inflated by 4 on every axis
        assertEquals(96.0, box.minX, 1e-9);
        assertEquals(66.0, box.minY, 1e-9);
        assertEquals(-54.0, box.minZ, 1e-9);
        assertEquals(105.0, box.maxX, 1e-9);
        assertEquals(75.0, box.maxY, 1e-9);
        assertEquals(-45.0, box.maxZ, 1e-9);
    }

    @Test
    void scanBoxDoesNotStretchToOrigin() {
        // the regression: a far-away drop produced a box reaching back toward (0,0,0)
        AABB box = DungeonSecretItemDropState.scanBox(new BlockPos(500, 80, 500));
        assertTrue(box.minX > 0 && box.minZ > 0, "box must be local to the drop, not origin-anchored");
        assertTrue(box.contains(500.5, 80.5, 500.5), "drop point must be inside the scan box");
        assertEquals(9.0, box.getXsize(), 1e-9);
    }
}
