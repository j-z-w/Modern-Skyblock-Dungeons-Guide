package kr.syeyoung.dungeonsguide.mod.events.listener;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Hypixel marks secret bats with positioned entity.bat.hurt / entity.bat.death sounds at
// volume exactly 0.1f (Odin's SecretPickupEvent.Bat signature); ordinary bat sounds use
// normal volumes or entity-sound packets and must not match
public class SecretBatSoundTest {

    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
    }

    private static ClientboundSoundPacket sound(net.minecraft.sounds.SoundEvent event, float volume) {
        return new ClientboundSoundPacket(Holder.direct(event), SoundSource.AMBIENT, 10, 70, 10, volume, 1.0f, 0L);
    }

    @Test
    void deathPingAtSecretVolumeMatches() {
        assertEquals(Boolean.TRUE, PacketListener.secretBatSound(sound(SoundEvents.BAT_DEATH, 0.1f)));
    }

    @Test
    void hurtPingAtSecretVolumeMatches() {
        assertEquals(Boolean.FALSE, PacketListener.secretBatSound(sound(SoundEvents.BAT_HURT, 0.1f)));
    }

    @Test
    void normalVolumeBatSoundsAreIgnored() {
        assertNull(PacketListener.secretBatSound(sound(SoundEvents.BAT_DEATH, 1.0f)));
        assertNull(PacketListener.secretBatSound(sound(SoundEvents.BAT_HURT, 1.0f)));
        assertNull(PacketListener.secretBatSound(sound(SoundEvents.BAT_DEATH, 0.5f)));
    }

    @Test
    void otherSoundsAtSecretVolumeAreIgnored() {
        // another mob's sound at 0.1f is not a secret-bat signal
        assertNull(PacketListener.secretBatSound(sound(SoundEvents.ZOMBIE_DEATH, 0.1f)));
        assertNull(PacketListener.secretBatSound(sound(SoundEvents.BAT_AMBIENT, 0.1f)));
    }

    @Test
    void removalCountsAsKillOnlyWithEvidence() {
        // died: health synced to 0 or the death animation started (Skyblocker's signal)
        assertTrue(DungeonListener.countsAsBatKill(0.0f, false, false));
        assertTrue(DungeonListener.countsAsBatKill(6.0f, true, false));
        // corroborated by a recent secret-bat sound where it vanished
        assertTrue(DungeonListener.countsAsBatKill(6.0f, false, true));
        // a plain chunk-unload removal of a healthy bat is NOT a kill - marking it used
        // to mark a still-alive bat's secret found
        assertFalse(DungeonListener.countsAsBatKill(6.0f, false, false));
    }

    @Test
    void liveBatLeavingRenderDistanceStaysUnfound() {
        // regression for the tightened removal rule: a healthy bat that simply leaves
        // render distance (no death evidence, no 0.1f ping) right next to its unfound
        // secret must NOT count as a kill - proximity alone is not evidence
        float aliveHealth = 6.0f;
        boolean notDying = false;
        boolean noPing = false;
        assertFalse(DungeonListener.countsAsBatKill(aliveHealth, notDying, noPing),
                "live bat despawn must not count as a kill");
    }
}
