package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// bat secret completion gating: the proximity fallback is the only headless-testable part
// of tick() (it needs a live client level), plus the marker position fallback chain
public class DungeonSecretBatStateTest {

    private static DungeonSecretBatState state() {
        return new DungeonSecretBatState(new DungeonSecretBatState.DungeonSecretBatData(), null);
    }

    @Test
    void proximityBlockedWhileBatAlive() {
        // a bat still visible near the spawn means the secret is still there - the route
        // used to mark it found anyway when the player walked up to the point
        long now = 100_000L;
        assertFalse(DungeonSecretBatState.proximityFallbackAllowed(true, 0, now, 10_000));
        assertFalse(DungeonSecretBatState.proximityFallbackAllowed(true, now - 60_000, now, 10_000));
    }

    @Test
    void proximityBlockedWhenBatSeenAliveRecently() {
        // tracked bat vanished seconds ago (chunk unload) - it is probably still alive on
        // the server, so standing at the spawn must not mark the secret yet
        long now = 100_000L;
        assertFalse(DungeonSecretBatState.proximityFallbackAllowed(false, now - 5_000, now, 10_000));
    }

    @Test
    void proximityAllowedWhenSignalsAbsent() {
        long now = 100_000L;
        // never saw a bat: it either died out of view or never rendered - SRM parity
        assertTrue(DungeonSecretBatState.proximityFallbackAllowed(false, 0, now, 10_000));
        // last seen alive long enough ago that a despawn reads as a kill
        assertTrue(DungeonSecretBatState.proximityFallbackAllowed(false, now - 60_000, now, 10_000));
    }

    @Test
    void markerFollowsHeardPositionWhenNoEntityTracked() {
        DungeonSecretBatState s = state();
        assertNull(s.getLiveBatMarkerPos());

        Vec3 ping = new Vec3(12, 70, 8);
        s.noteSecretBatHeard(ping);
        // a fresh 0.1f ping is the best live-position estimate while no entity is tracked
        assertSame(ping, s.getLiveBatMarkerPos());
    }

    @Test
    void markerGoneOnceBatMarkedDead() {
        DungeonSecretBatState s = state();
        s.noteSecretBatHeard(new Vec3(12, 70, 8));
        s.setDidKillBat(true);
        assertNull(s.getLiveBatMarkerPos());
    }
}
