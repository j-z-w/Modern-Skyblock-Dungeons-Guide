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

import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomBounds;
import lombok.Getter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonActionContext {
    @Getter
    private static final Map<Integer, Vec3> spawnLocation = new HashMap<>();

    // where a killed entity was when its death/removal was recorded; a bat can fly far
    // away from its spawn point before it dies, so the spawn alone is not reliable
    @Getter
    private static final Map<Integer, Vec3> killedLocation = new HashMap<>();

    // LinkedHashSet, not a list: the same dying bat can be recorded by both the death
    // event and the exit-world event, and a duplicate record would let one kill be
    // claimed by a second secret
    @Getter
    private static final Set<Integer> killeds = new java.util.LinkedHashSet<>();
    @Getter
    private static final List<Integer> pickedups = new ArrayList<>();

    // entity ids a bat secret is currently following: a kill record for a tracked bat
    // is owned by the tracking secret and can't be claimed by proximity alone
    @Getter
    private static final Set<Integer> trackedBats = ConcurrentHashMap.newKeySet();

    // positions at which a kill already completed a secret: the second signal for the
    // same kill (the death sound arriving after the removal, or the removal after the
    // sound) must not complete a second secret - one kill marks exactly one bat
    private static final class AttributedKill {
        final Vec3 pos;
        final long ms;
        AttributedKill(Vec3 pos, long ms) { this.pos = pos; this.ms = ms; }
    }
    private static final Deque<AttributedKill> attributedKills = new ArrayDeque<>();
    private static final int MAX_ATTRIBUTED_KILLS = 64;
    private static final long ATTRIBUTED_KILL_AGE_MS = 30_000L;

    public static synchronized void noteKillAttributed(Vec3 pos) {
        if (pos == null) return;
        attributedKills.addLast(new AttributedKill(pos, System.currentTimeMillis()));
        while (attributedKills.size() > MAX_ATTRIBUTED_KILLS) attributedKills.pollFirst();
    }

    public static synchronized boolean isKillAttributedNear(Vec3 pos, double radiusSqr) {
        if (pos == null) return false;
        long now = System.currentTimeMillis();
        for (AttributedKill kill : attributedKills) {
            if (now - kill.ms > ATTRIBUTED_KILL_AGE_MS) continue;
            if (kill.pos.distanceToSqr(pos) <= radiusSqr) return true;
        }
        return false;
    }

    public static synchronized void clearKillAttribution() {
        attributedKills.clear();
        trackedBats.clear();
    }

    // the killeds/killedLocation pair is iterated under the class monitor by the claim
    // methods, so raw writes from event handlers have to funnel through these - a
    // concurrent add mid-iteration is a CME
    public static synchronized void recordKill(int entityId, Vec3 pos) {
        killeds.add(entityId);
        if (pos != null) killedLocation.put(entityId, pos);
    }

    public static synchronized boolean hasKillRecord(int entityId) {
        return killeds.contains(entityId);
    }

    public static synchronized Vec3 killRecordPos(int entityId) {
        return killedLocation.get(entityId);
    }

    public static synchronized void clearKillRecords() {
        killeds.clear();
        killedLocation.clear();
        spawnLocation.clear();
    }

    /**
     * Whether an entity counted as killed has a recorded position within radiusSqr of pos.
     * Checks both where the entity spawned and where it was when the kill was recorded.
     */
    public static boolean wasKilledNear(Vec3 pos, double radiusSqr) {
        return wasKilledNear(pos, radiusSqr, null);
    }

    /**
     * Same as {@link #wasKilledNear(Vec3, double)}, additionally requiring the record to
     * touch the claimant's room (where the entity spawned OR where it died) - a kill
     * recorded entirely in another room is claimed by that room's signals, not this one's.
     */
    public static synchronized boolean wasKilledNear(Vec3 pos, double radiusSqr, RoomBounds bounds) {
        for (Integer killed : killeds) {
            Vec3 spawn = spawnLocation.get(killed);
            Vec3 kill = killedLocation.get(killed);
            if (bounds != null && !touchesRoom(bounds, spawn, kill)) continue;
            if (spawn != null && spawn.distanceToSqr(pos) < radiusSqr) return true;
            if (kill != null && kill.distanceToSqr(pos) < radiusSqr) return true;
        }
        return false;
    }

    /**
     * Claim a kill record for the secret at pos: the record must lie within radiusSqr,
     * inside bounds when given, must not belong to a bat another secret is tracking, and
     * must not be the same kill already attributed through a different signal. A claimed
     * record is consumed so one kill record completes at most one secret.
     */
    public static synchronized boolean claimKilledNear(Vec3 pos, double radiusSqr, RoomBounds bounds) {
        for (Iterator<Integer> it = killeds.iterator(); it.hasNext(); ) {
            int killed = it.next();
            Vec3 spawn = spawnLocation.get(killed);
            Vec3 kill = killedLocation.get(killed);
            boolean near = (kill != null && kill.distanceToSqr(pos) < radiusSqr)
                    || (spawn != null && spawn.distanceToSqr(pos) < radiusSqr);
            if (!near) continue;
            if (bounds != null && !touchesRoom(bounds, spawn, kill)) continue;
            Vec3 anchor = kill != null ? kill : spawn;
            if (trackedBats.contains(killed)) continue; // owned by the tracking secret
            if (isKillAttributedNear(anchor, 4 * 4)) {
                // the same kill already completed a secret through the other signal;
                // drop the stale record so it can't claim a second secret later
                it.remove();
                killedLocation.remove(killed);
                continue;
            }
            it.remove();
            killedLocation.remove(killed);
            noteKillAttributed(anchor);
            return true;
        }
        return false;
    }

    // the record belongs to this room when either of its positions does: a bat spawned
    // here and killed across the wall is still this room's kill
    private static boolean touchesRoom(RoomBounds bounds, Vec3 spawn, Vec3 kill) {
        return (spawn != null && withinRoom(bounds, spawn))
                || (kill != null && withinRoom(bounds, kill))
                || (spawn == null && kill == null);
    }

    // loose containment: the room check exists to reject kills in a different room
    // (32+ blocks away), not to police wall-hugging, so it is far more permissive
    // than RoomBounds.isFullyWithin's 1.5-block inset
    public static boolean withinRoom(RoomBounds bounds, Vec3 pos) {
        return pos.x > bounds.getMinX() - 8 && pos.x < bounds.getMaxX() + 8
                && pos.z > bounds.getMinZ() - 8 && pos.z < bounds.getMaxZ() + 8;
    }
}
