/*
 *     Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 *     Copyright (C) 2021  cyoung06
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.dungeon;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

// recent Hypixel secret-bat sounds (entity.bat.hurt / entity.bat.death at volume 0.1f).
// a small ring buffer the bat-secret states consult to corroborate removals: a bat that is
// removed right after one of these sounds played near its last-seen position was killed,
// one that just unloads (chunk boundary) was not. all callers are on the render thread;
// the synchronized block is for unit tests and the odd packet thread.
public class SecretBatTracker {
    private static final int MAX_PINGS = 32;
    private static final long MAX_PING_AGE_MS = 10_000L;

    private static final class SoundPing {
        final Vec3 pos;
        final long ms;
        final boolean death;

        SoundPing(Vec3 pos, long ms, boolean death) {
            this.pos = pos;
            this.ms = ms;
            this.death = death;
        }
    }

    private static final Deque<SoundPing> recentSounds = new ArrayDeque<>();

    public static synchronized void recordSecretBatSound(Vec3 pos, boolean death) {
        long now = System.currentTimeMillis();
        recentSounds.addLast(new SoundPing(pos, now, death));
        while (recentSounds.size() > MAX_PINGS) recentSounds.pollFirst();
        while (!recentSounds.isEmpty() && now - recentSounds.peekFirst().ms > MAX_PING_AGE_MS) {
            recentSounds.pollFirst();
        }
    }

    public static synchronized boolean heardSecretBatSoundNear(Vec3 pos, double radiusSqr, long maxAgeMs) {
        return nearestPingDistSqr(pos, maxAgeMs, false) <= radiusSqr;
    }

    public static synchronized boolean heardSecretBatDeathNear(Vec3 pos, double radiusSqr, long maxAgeMs) {
        return nearestPingDistSqr(pos, maxAgeMs, true) <= radiusSqr;
    }

    // squared distance to the nearest matching ping within maxAgeMs, or +inf
    public static synchronized double nearestPingDistSqr(Vec3 pos, long maxAgeMs, boolean deathOnly) {
        long now = System.currentTimeMillis();
        double best = Double.POSITIVE_INFINITY;
        for (SoundPing ping : recentSounds) {
            if (deathOnly && !ping.death) continue;
            if (now - ping.ms > maxAgeMs) continue;
            best = Math.min(best, ping.pos.distanceToSqr(pos));
        }
        return best;
    }

    public static synchronized void clear() {
        recentSounds.clear();
    }

    // test hook
    public static synchronized int pingCount() {
        return recentSounds.size();
    }
}
