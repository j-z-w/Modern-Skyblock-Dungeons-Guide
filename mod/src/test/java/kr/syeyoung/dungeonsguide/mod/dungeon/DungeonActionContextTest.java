package kr.syeyoung.dungeonsguide.mod.dungeon;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DungeonActionContextTest {
    @BeforeEach
    @AfterEach
    void clearContext() {
        DungeonActionContext.getSpawnLocation().clear();
        DungeonActionContext.getKilledLocation().clear();
        DungeonActionContext.getKilleds().clear();
    }

    @Test
    void killNearRecordedSpawnCounts() {
        DungeonActionContext.getSpawnLocation().put(1, new Vec3(0, 0, 0));
        DungeonActionContext.getKilleds().add(1);
        assertTrue(DungeonActionContext.wasKilledNear(new Vec3(5, 0, 0), 100));
    }

    @Test
    void batThatFlewOffStillCountsAtKillPosition() {
        // the bat spawned far from the secret but was removed/died near it
        DungeonActionContext.getSpawnLocation().put(1, new Vec3(50, 0, 0));
        DungeonActionContext.getKilledLocation().put(1, new Vec3(2, 0, 0));
        DungeonActionContext.getKilleds().add(1);
        assertTrue(DungeonActionContext.wasKilledNear(new Vec3(0, 0, 0), 100));
    }

    @Test
    void killFarFromBothPositionsDoesNotCount() {
        DungeonActionContext.getSpawnLocation().put(1, new Vec3(50, 0, 0));
        DungeonActionContext.getKilledLocation().put(1, new Vec3(50, 0, 0));
        DungeonActionContext.getKilleds().add(1);
        assertFalse(DungeonActionContext.wasKilledNear(new Vec3(0, 0, 0), 100));
    }

    @Test
    void killWithoutAnyLocationDoesNotCount() {
        DungeonActionContext.getKilleds().add(1);
        assertFalse(DungeonActionContext.wasKilledNear(new Vec3(0, 0, 0), 100));
    }
}
