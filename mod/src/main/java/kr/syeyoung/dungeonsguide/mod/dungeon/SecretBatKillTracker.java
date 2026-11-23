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

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomBounds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// THE single authority on bat-secret completion. every kill/death signal - a
// death-evidenced entity removal, a 0.1f entity.bat.death ping, or the action-bar
// N/M counter - becomes exactly one KillEvidence that is attributed to AT MOST ONE
// bat secret and then consumed; the secret stores FOUND as a plain volatile flag.
// secret states never scan shared collections: getSecretStatus()/isComplete() are
// pure reads, so pathCalculator solver workers can call them freely.
//
// thread ownership: every public entry point runs on the render thread (packet
// handlers, entity events, room-processor ticks). the synchronized monitor is only
// there so unit tests and a stray packet thread can't interleave - it is never held
// by, nor contended with, solver workers
public final class SecretBatKillTracker {
    private SecretBatKillTracker() {}

    // one kill/death signal. entityId is -1 for signals that carry no entity (death
    // ping, counter); spawnPos is the entity's recorded spawn point when known - a
    // bat can fly far from it before dying, but a kill recorded in a wall is still
    // this room's when the bat spawned inside it
    public static final class KillEvidence {
        public final int entityId;
        public final Vec3 pos;
        public final Vec3 spawnPos;
        public final long ms;

        public KillEvidence(int entityId, Vec3 pos, Vec3 spawnPos, long ms) {
            this.entityId = entityId;
            this.pos = pos;
            this.spawnPos = spawnPos;
            this.ms = ms;
        }
    }

    // evidence that couldn't be attributed yet: the room it lies in is still
    // undiscovered or unmatched, so no secret state exists to mark. retried once per
    // render tick from DungeonListener via drain(); bounded so a run that never
    // finds the room can't grow it
    private static final Deque<KillEvidence> pending = new ArrayDeque<>();
    private static final Set<Integer> pendingEntities = new HashSet<>();
    private static final int MAX_PENDING = 64;

    // tombstones of already-attributed kills: the same physical kill usually reports
    // twice (a death ping AND a removal, or a counter mark AND a late record), and
    // the second signal must not complete a second secret. kept short and consumed
    // on match so a genuinely different kill at the same spot isn't swallowed for
    // half a minute
    private static final class Tombstone {
        final Vec3 pos;
        final long ms;
        Tombstone(Vec3 pos, long ms) { this.pos = pos; this.ms = ms; }
    }
    private static final Deque<Tombstone> consumed = new ArrayDeque<>();
    private static final int MAX_CONSUMED = 32;
    private static final long CONSUMED_AGE_MS = 4_000L;
    private static final double CONSUMED_RADIUS_SQR = 4 * 4;

    // one candidate wins a signal: the single closest unfound bat secret inside the
    // attribution radius (same tradeoff Odin's nearest-within-5 makes, widened to 16
    // since DG also tracks the entity's last-seen pos)
    private static final double ATTR_RADIUS_SQR = 16 * 16;
    // shared walls are 1-2 blocks: positions that far outside a room's shaped area
    // still count as that room's; an L-room's missing quadrant never does
    private static final int WALL_TOLERANCE = 2;

    public static synchronized void clear() {
        pending.clear();
        pendingEntities.clear();
        consumed.clear();
    }

    // --- entry points (render thread) ------------------------------------------

    // a bat entity died, or was removed with death evidence (health<=0, dead/dying,
    // or a fresh secret-bat ping where it vanished)
    public static void onBatKill(int entityId, Vec3 pos) {
        DungeonRoomScaffoldParser parser = scaffoldParser();
        if (parser == null) return;
        route(new KillEvidence(entityId, pos,
                        DungeonActionContext.getSpawnLocation().get(entityId), System.currentTimeMillis()),
                parser.getDungeonRoomList());
    }

    // a 0.1f secret-bat sound played at pos. routed to the room the sound played in,
    // not the player's room: a teammate fighting a secret bat across a wall used to
    // mark this room's bat secrets
    public static void onSecretBatSound(Vec3 pos, boolean isDeath) {
        DungeonRoomScaffoldParser parser = scaffoldParser();
        if (parser == null) return;
        Point roomPt = parser.getDungeonMapLayout().worldPointToRoomPoint(pos);
        if (roomPt == null) return;
        DungeonRoom room = parser.getRoomMap().get(roomPt);
        if (room == null || room.getRoomBounds() == null || !room.getRoomBounds().isFullyWithin(pos)) return;
        handleSound(room, pos, isDeath);
    }

    // the room's N/M counter attributed a find to this bat secret; tombstone the
    // secret's anchor so the same kill's late removal record can't mark a second bat
    public static synchronized void markFromCounter(DungeonSecretBatState bat) {
        // grab the live anchor BEFORE marking: the marker reports null once found
        Vec3 anchor = bat.getLiveBatMarkerPos();
        bat.setDidKillBat(true);
        if (anchor == null) {
            OffsetPoint point = bat.getData().getSecretPoint();
            if (point != null && bat.getRoom() != null) {
                anchor = Vec3.atCenterOf(point.getBlockPos(bat.getRoom()));
            }
        }
        tombstone(anchor);
    }

    // once per render tick: retry evidence whose room wasn't resolvable at signal
    // time (room undiscovered/unmatched). an evidence whose room is now resolvable
    // but still can't attribute is dropped for good - one evidence, one attribution
    public static void drain(DungeonContext context) {
        if (context == null || context.getScaffoldParser() == null) return;
        drain(context.getScaffoldParser().getDungeonRoomList());
    }

    public static synchronized void drain(Collection<DungeonRoom> rooms) {
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            KillEvidence ev = pending.pollFirst();
            if (ev == null) break;
            pendingEntities.remove(ev.entityId);
            route(ev, rooms);
        }
    }

    private static DungeonRoomScaffoldParser scaffoldParser() {
        DungeonsGuide dg = DungeonsGuide.getDungeonsGuide();
        if (dg == null || dg.getDungeonFacade() == null) return null;
        DungeonContext context = dg.getDungeonFacade().getContext();
        if (context == null || context.getScaffoldParser() == null) return null;
        return context.getScaffoldParser();
    }

    // --- attribution core --------------------------------------------------------

    static synchronized void route(KillEvidence ev, Collection<DungeonRoom> rooms) {
        // second signal for an already-attributed kill: drop it, don't attribute twice
        if (consumeDuplicate(ev.pos)) return;
        if (attributeEvidence(ev, rooms)) {
            tombstone(ev.pos);
            return;
        }
        // unresolvable (no known room touches the evidence, or every touching room is
        // still unmatched) is the only retryable miss; a resolved room with no
        // eligible secret is a definitive miss and the evidence dies here
        if (!evidenceResolvable(ev, rooms)) park(ev);
    }

    static synchronized boolean attributeEvidence(KillEvidence ev, Collection<DungeonRoom> rooms) {
        // 1. the secret following THIS entity owns its kill wherever the bat died - a
        //    tracked bat that flew across a wall still belongs to its own secret
        if (ev.entityId != -1) {
            DungeonSecretBatState owner = null;
            double ownerDist = Double.MAX_VALUE;
            for (DungeonRoom room : rooms) {
                for (DungeonMechanicState mech : room.getMechanics().values()) {
                    if (!(mech instanceof DungeonSecretBatState)) continue;
                    DungeonSecretBatState bat = (DungeonSecretBatState) mech;
                    if (bat.isFound(room) || bat.getTrackedBatId() != ev.entityId) continue;
                    double d = bat.distanceToSqrFromTrackedPoint(ev.pos, room);
                    if (d < ownerDist) {
                        owner = bat;
                        ownerDist = d;
                    }
                }
            }
            if (owner != null) {
                owner.setDidKillBat(true);
                return true;
            }
        }
        // 2. room-bounded closest: the nearest unfound, non-vetoed bat secret across
        //    the rooms the evidence actually lies in (own shape, L-cut-outs included)
        DungeonSecretBatState best = null;
        double bestDist = ATTR_RADIUS_SQR;
        for (DungeonRoom room : rooms) {
            if (!touchesRoomEvidence(room, ev)) continue;
            DungeonSecretBatState cand = selectBatForKill(room.getMechanics().values(), ev, room, bestDist);
            if (cand != null) {
                best = cand;
                bestDist = killDistance(cand, ev, room);
            }
        }
        if (best == null) return false;
        best.setDidKillBat(true);
        return true;
    }

    private static double killDistance(DungeonSecretBatState bat, KillEvidence ev, DungeonRoom room) {
        double d = bat.distanceToSqrFromTrackedPoint(ev.pos, room);
        if (ev.spawnPos != null) {
            OffsetPoint point = bat.getData().getSecretPoint();
            if (point != null) {
                d = Math.min(d, ev.spawnPos.distanceToSqr(Vec3.atCenterOf(point.getBlockPos(room))));
            }
        }
        return d;
    }

    // the single closest unfound, non-vetoed bat secret in this room within
    // radiusSqr of the evidence position (measured against spawn, last-seen, live
    // tracked position, and the bat entity's recorded spawn), or null
    static DungeonSecretBatState selectBatForKill(
            Collection<? extends DungeonMechanicState> mechanics,
            KillEvidence ev, DungeonRoom room, double radiusSqr) {
        DungeonSecretBatState best = null;
        double bestDist = radiusSqr;
        for (DungeonMechanicState mechanic : mechanics) {
            if (!(mechanic instanceof DungeonSecretBatState)) continue;
            DungeonSecretBatState bat = (DungeonSecretBatState) mechanic;
            if (bat.isFound(room)) continue;
            // a secret whose tracked entity is still healthy and elsewhere can't be
            // the bat that died here - never the dying one, which isn't "live"
            if (liveTrackedElsewhere(bat, ev.pos)) continue;
            if (!anchorInRoom(bat, room)) continue;
            double d = killDistance(bat, ev, room);
            if (d < bestDist) {
                best = bat;
                bestDist = d;
            }
        }
        return best;
    }

    // the evidence touches the room when either of its positions does: a bat spawned
    // here and killed across the wall is still this room's kill
    private static boolean touchesRoomEvidence(DungeonRoom room, KillEvidence ev) {
        RoomBounds b = room.getRoomBounds();
        if (b == null) return false;
        return touchesRoom(b, ev.pos) || (ev.spawnPos != null && touchesRoom(b, ev.spawnPos));
    }

    // the evidence is resolvable once at least one touching room is matched: it had
    // its chance and missed, retrying can't produce a secret that isn't there
    private static boolean evidenceResolvable(KillEvidence ev, Collection<DungeonRoom> rooms) {
        for (DungeonRoom room : rooms) {
            if (touchesRoomEvidence(room, ev) && room.getDungeonRoomInfo() != null) return true;
        }
        return false;
    }

    private static void park(KillEvidence ev) {
        if (ev.entityId != -1 && !pendingEntities.add(ev.entityId)) return;
        pending.addLast(ev);
        while (pending.size() > MAX_PENDING) {
            KillEvidence dropped = pending.pollFirst();
            if (dropped != null) pendingEntities.remove(dropped.entityId);
        }
    }

    private static void tombstone(Vec3 pos) {
        if (pos == null) return;
        consumed.addLast(new Tombstone(pos, System.currentTimeMillis()));
        while (consumed.size() > MAX_CONSUMED) consumed.pollFirst();
    }

    // a signal landing on a recent attributed kill is that kill's second report:
    // swallow it and CONSUME the tombstone so a third signal - a genuinely different
    // kill at the same spot inside the window - still attributes normally
    private static boolean consumeDuplicate(Vec3 pos) {
        if (pos == null) return false;
        long now = System.currentTimeMillis();
        for (Iterator<Tombstone> it = consumed.iterator(); it.hasNext(); ) {
            Tombstone t = it.next();
            if (now - t.ms > CONSUMED_AGE_MS) {
                it.remove();
                continue;
            }
            if (t.pos.distanceToSqr(pos) <= CONSUMED_RADIUS_SQR) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // --- signal selection (the 0.1f ping path) ------------------------------------

    // process a secret-bat sound inside its room. death pings complete the closest
    // unfound bat secret measured from spawn OR last-seen bat position; hurt pings
    // only feed last-seen so the marker follows the bat as it gets knocked around
    static synchronized void handleSound(DungeonRoom room, Vec3 pos, boolean isDeath) {
        // the kill already completed a secret through the removal/counter path - a
        // second signal for the same kill must not complete a second secret
        if (isDeath && consumeDuplicate(pos)) return;
        // one candidate wins a ping. hurt pings never veto a tracked bat (the bat
        // being hit IS alive - vetoing it pinned its fight position onto the wrong
        // secret); death pings veto only a bat whose tracked entity is still healthy
        // AND away from the sound, never the one whose dying entity sits at pos
        DungeonSecretBatState best = selectBatForSound(room.getMechanics().values(), pos, room,
                bat -> isDeath && liveTrackedElsewhere(bat, pos));
        if (best == null) return;
        if (isDeath) {
            best.setDidKillBat(true);
            tombstone(pos);
        }
        best.noteSecretBatHeard(pos);
    }

    // the tracked entity is still healthy and somewhere else, so this secret's bat
    // demonstrably isn't the one that died at pos. only ever applied to death evidence
    static boolean liveTrackedElsewhere(DungeonSecretBatState bat, Vec3 pos) {
        Entity e = bat.getTrackedLiveEntity();
        return e != null && e.position().distanceToSqr(pos) > 4 * 4;
    }

    // the secret's own anchor must lie in this room: the last-seen position of the bat
    // it follows when there is one, else its recorded spawn. a bat last seen in a
    // neighbouring room can't be claimed by a signal in this one - its death, when it
    // comes, is routed to the room it actually died in
    static boolean anchorInRoom(DungeonSecretBatState bat, DungeonRoom room) {
        if (room == null || room.getRoomBounds() == null) return true;
        Vec3 lastSeen = bat.getLastSeenPos();
        if (lastSeen != null) return touchesRoom(room.getRoomBounds(), lastSeen);
        OffsetPoint point = bat.getData().getSecretPoint();
        if (point == null) return true;
        net.minecraft.core.BlockPos spawn = point.getBlockPos(room);
        return spawn == null || touchesRoom(room.getRoomBounds(), Vec3.atCenterOf(spawn));
    }

    // one candidate wins a ping: the single closest unfound, non-vetoed bat secret
    // inside the attribution radius (null when none qualifies). static so the selection
    // rule itself is unit-testable; the live veto goes through a predicate for the same
    // reason (getTrackedLiveEntity needs a client level)
    static DungeonSecretBatState selectBatForSound(
            java.util.Collection<? extends DungeonMechanicState> mechanics,
            Vec3 pos, DungeonRoom room,
            java.util.function.Predicate<DungeonSecretBatState> liveVeto) {
        DungeonSecretBatState best = null;
        double bestDist = ATTR_RADIUS_SQR;
        for (DungeonMechanicState mechanic : mechanics) {
            if (!(mechanic instanceof DungeonSecretBatState)) continue;
            DungeonSecretBatState bat = (DungeonSecretBatState) mechanic;
            if (bat.isFound(room)) continue;
            if (liveVeto.test(bat)) continue;
            if (!anchorInRoom(bat, room)) continue;
            double d = bat.distanceToSqrFromTrackedPoint(pos, room);
            if (d < bestDist) { best = bat; bestDist = d; }
        }
        return best;
    }

    // --- room membership ------------------------------------------------------------

    // strict containment: the block the position lies in is part of the room's own
    // shape (shared walls and an L-room's missing quadrant don't count)
    public static boolean strictInRoom(RoomBounds bounds, Vec3 pos) {
        return bounds != null
                && bounds.canAccessAbsolute((int) Math.floor(pos.x), 0, (int) Math.floor(pos.z));
    }

    // loose containment for evidence routing: inside the shape, or at most a shared
    // wall (1-2 blocks) past the bounding box while a shaped cell sits within reach.
    // positions inside the bounding box but in an unshaped cell - the missing
    // quadrant of an L room - are definitively not this room, no matter how close to
    // a shaped arm they sit
    public static boolean touchesRoom(RoomBounds bounds, Vec3 pos) {
        int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z);
        if (bounds.canAccessAbsolute(x, 0, z)) return true;
        // inside the bounding box but unshaped: never this room
        if (pos.x >= bounds.getMinX() && pos.x <= bounds.getMaxX() + 1
                && pos.z >= bounds.getMinZ() && pos.z <= bounds.getMaxZ() + 1) return false;
        if (pos.x < bounds.getMinX() - WALL_TOLERANCE || pos.x > bounds.getMaxX() + 1 + WALL_TOLERANCE
                || pos.z < bounds.getMinZ() - WALL_TOLERANCE || pos.z > bounds.getMaxZ() + 1 + WALL_TOLERANCE) {
            return false;
        }
        for (int dx = -WALL_TOLERANCE; dx <= WALL_TOLERANCE; dx++) {
            for (int dz = -WALL_TOLERANCE; dz <= WALL_TOLERANCE; dz++) {
                if (bounds.canAccessAbsolute(x + dx, 0, z + dz)) return true;
            }
        }
        return false;
    }

    // --- test hooks ---------------------------------------------------------------

    static synchronized int pendingCount() {
        return pending.size();
    }

    static synchronized int consumedCount() {
        return consumed.size();
    }
}
