package kr.syeyoung.dungeonsguide.mod.dungeon;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// the context keeps only spawn positions and item pickups now - both shared with
// solver workers, so they must be concurrent-safe snapshots, not HashMap reads
public class DungeonActionContextTest {
    @BeforeEach
    @AfterEach
    void clearContext() {
        DungeonActionContext.clearKillRecords();
    }

    @Test
    void spawnLocationRoundTrips() {
        DungeonActionContext.getSpawnLocation().put(1, new Vec3(0, 0, 0));
        assertEquals(new Vec3(0, 0, 0), DungeonActionContext.getSpawnLocation().get(1));
    }

    @Test
    void pickedupsIsFifo() {
        DungeonActionContext.getPickedups().add(7);
        DungeonActionContext.getPickedups().add(9);
        assertEquals(7, DungeonActionContext.getPickedups().poll());
        assertEquals(9, DungeonActionContext.getPickedups().poll());
    }

    @Test
    void spawnMapSurvivesConcurrentAccess() throws Exception {
        // solver workers read the map while render-thread entity events write it:
        // a plain HashMap could be read mid-resize
        CountDownLatch done = new CountDownLatch(2);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5000; i++) DungeonActionContext.getSpawnLocation().put(i, new Vec3(i, 0, 0));
            done.countDown();
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 5000; i++) DungeonActionContext.getSpawnLocation().get(i);
            done.countDown();
        });
        writer.start();
        reader.start();
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }
}
