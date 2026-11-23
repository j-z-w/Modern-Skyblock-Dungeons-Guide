package kr.syeyoung.dungeonsguide.mod.dungeon;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// the ring buffer of Hypixel secret-bat sound pings that corroborates removals and
// attributes death sounds to a room's closest unfound bat secret
public class SecretBatTrackerTest {

    @BeforeEach
    void clearTracker() {
        SecretBatTracker.clear();
    }

    @Test
    void heardNearRecordedPing() {
        SecretBatTracker.recordSecretBatSound(new Vec3(10, 70, 10), false);
        assertTrue(SecretBatTracker.heardSecretBatSoundNear(new Vec3(11, 70, 12), 8 * 8, 3000));
        assertEquals(1, SecretBatTracker.pingCount());
    }

    @Test
    void distantPingDoesNotAttribute() {
        // a teammate's bat kill in another room pings far from this room's secret point
        SecretBatTracker.recordSecretBatSound(new Vec3(100, 70, 100), true);
        assertFalse(SecretBatTracker.heardSecretBatSoundNear(new Vec3(0, 70, 0), 8 * 8, 3000));
        assertFalse(SecretBatTracker.heardSecretBatDeathNear(new Vec3(0, 70, 0), 16 * 16, 3000));
    }

    @Test
    void deathOnlyFilterSeparatesHurtFromDeath() {
        SecretBatTracker.recordSecretBatSound(new Vec3(5, 70, 5), false);
        // a hurt ping must not count as a kill confirmation
        assertFalse(SecretBatTracker.heardSecretBatDeathNear(new Vec3(5, 70, 5), 100, 3000));
        assertTrue(SecretBatTracker.heardSecretBatSoundNear(new Vec3(5, 70, 5), 100, 3000));

        SecretBatTracker.recordSecretBatSound(new Vec3(6, 70, 6), true);
        assertTrue(SecretBatTracker.heardSecretBatDeathNear(new Vec3(5, 70, 5), 100, 3000));
    }

    @Test
    void stalePingsExpire() throws InterruptedException {
        // a ping older than the attribution window can't corroborate a later removal
        SecretBatTracker.recordSecretBatSound(new Vec3(10, 70, 10), true);
        Thread.sleep(20);
        assertFalse(SecretBatTracker.heardSecretBatDeathNear(new Vec3(10, 70, 10), 100, 1));
    }

    @Test
    void clearDropsAllPings() {
        SecretBatTracker.recordSecretBatSound(new Vec3(10, 70, 10), true);
        SecretBatTracker.clear();
        assertEquals(0, SecretBatTracker.pingCount());
        assertFalse(SecretBatTracker.heardSecretBatSoundNear(new Vec3(10, 70, 10), 100, 3000));
    }

    @Test
    void nearestPingDistanceTracksClosest() {
        SecretBatTracker.recordSecretBatSound(new Vec3(10, 70, 10), false);
        SecretBatTracker.recordSecretBatSound(new Vec3(14, 70, 10), false);
        double d = SecretBatTracker.nearestPingDistSqr(new Vec3(10, 70, 10), 3000, false);
        assertEquals(0.0, d, 1e-6);
    }
}
