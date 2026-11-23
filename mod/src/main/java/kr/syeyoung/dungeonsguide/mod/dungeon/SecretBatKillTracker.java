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
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// THE single authority on bat-secret completion. signals - a death-evidenced entity
// removal, a 0.1f entity.bat.death ping, or the action-bar N/M counter - are folded
// into KILLS, not processed individually: one physical bat death reports up to three
// signals, and the kill model absorbs every later signal of the same kill instead of
// letting it mark a second secret. each kill attributes at most one bat secret; the
// secret stores FOUND as a plain volatile flag.
//
// dedupe rules: an entity-carrying signal dedupes by entity id - one entity id
// produces at most one kill, ever. an entityless death ping joins the kill near its
// position inside the window (non-consuming, so the kill's later entity signals
// still dedupe), or starts a pending kill that a later entity signal at the same
// spot claims instead of opening a second kill. two DIFFERENT bats dying close
// together still produce two kills: claimed kills never join another entity, and a
// kill already carrying a ping never joins another ping - one death emits at most
// one of each.
//
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
    // ping); spawnPos is the entity's recorded spawn point when known - a
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
    // finds the room can't grow it, and TTL'd so stale evidence (a boss-room bat, a
    // kill outside every room) isn't polled against every room forever and can't
    // misattribute minutes later
    private static final Deque<KillEvidence> pending = new ArrayDeque<>();
    private static final Set<Integer> pendingEntities = new HashSet<>();
    private static final int MAX_PENDING = 64;
    private static final long MAX_PENDING_AGE_MS = 10_000L;

    // an attributed kill. entityId -1 while only entityless signals (ping, counter)
    // reported it - an entity signal near it then CLAIMS it instead of opening a
    // second kill. expectedEntityId is the attributed secret's tracked bat when
    // known: only that entity may claim the kill, so a different bat dying at the
    // same spot still opens its own. soundSeen marks the kill that already
    // absorbed its one death ping: a second ping near it belongs to a different kill
    private static final class Kill {
        int entityId;
        int expectedEntityId;
        final Vec3 pos;
        final Vec3 altPos;   // second anchor: the bat's spawn / last-seen position
        final long ms;
        boolean soundSeen;

        Kill(int entityId, Vec3 pos, Vec3 altPos, long ms) {
            this.entityId = entityId;
            this.expectedEntityId = -1;
            this.pos = pos;
            this.altPos = altPos;
            this.ms = ms;
        }
    }
    private static final Deque<Kill> kills = new ArrayDeque<>();
    private static final int MAX_KILLS = 32;
    private static final long KILL_WINDOW_MS = 4_000L;
    private static final double KILL_RADIUS_SQR = 4 * 4;

    // entity ids that already produced a kill - the strongest dedupe. bounded and
    // TTL'd only as a memory cap: ids don't repeat inside a dungeon run
    private static final Map<Integer, Long> attributedEntities = new LinkedHashMap<>();
    private static final int MAX_ATTRIBUTED = 64;
    private static final long ATTRIBUTED_AGE_MS = 30_000L;

    // one candidate wins a signal: the single closest unfound bat secret inside the
    // attribution radius (same tradeoff Odin's nearest-within-5 makes, widened to 16
    // since DG also tracks the entity's last-seen pos)
    private static final double ATTR_RADIUS_SQR = 16 * 16;
    // shared walls are 1-2 blocks: positions that far outside a room's shaped area
    // still count as that room's; an L-room's missing quadrant never does
    private static final int WALL_TOLERANCE = 2;
    // an entityless ping owns the kill when a tracked bat sits this close to it -
    // a bat mid-death-animation is still loaded while its sound/removal packets fly
    private static final double OWNER_RADIUS_SQR = 4 * 4;
    private static final long TRACKED_FRESH_MS = 10_000L;

    public static synchronized void clear() {
        pending.clear();
        pendingEntities.clear();
        kills.clear();
        attributedEntities.clear();
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

    // a 0.1f secret-bat sound played at pos. a death ping is entityless kill
    // evidence and routes through the kill model so the tracked bat's OWNER wins
    // even across a wall; a hurt ping only feeds the marker's live-position hint
    // and stays routed to the room it played in
    public static void onSecretBatSound(Vec3 pos, boolean isDeath) {
        DungeonRoomScaffoldParser parser = scaffoldParser();
        if (parser == null) return;
        if (isDeath) {
            route(new KillEvidence(-1, pos, null, System.currentTimeMillis()),
                    parser.getDungeonRoomList());
            return;
        }
        Point roomPt = parser.getDungeonMapLayout().worldPointToRoomPoint(pos);
        if (roomPt == null) return;
        DungeonRoom room = parser.getRoomMap().get(roomPt);
        // touchesRoom, not isFullyWithin: its inset drops wall-hugging pings,
        // exactly where cornered bats get killed
        if (room == null || room.getRoomBounds() == null || !touchesRoom(room.getRoomBounds(), pos)) return;
        handleSound(room, pos, false);
    }

    // the room's N/M counter attributed a find to this bat secret. attributes only
    // when no recent kill already covers it - the counter increment then belongs
    // to a kill the tracker already processed, and re-marking would credit the
    // same physical kill twice. returns false when covered so the caller can
    // re-pick the increment's real owner
    public static synchronized boolean markFromCounter(DungeonSecretBatState bat) {
        // grab the live anchors BEFORE marking: the marker reports null once found
        Vec3 anchor = bat.getLiveBatMarkerPos();
        Vec3 lastSeen = bat.getLastSeenPos();
        if (anchor == null) {
            OffsetPoint point = bat.getData().getSecretPoint();
            if (point != null && bat.getRoom() != null) {
                anchor = Vec3.atCenterOf(point.getBlockPos(bat.getRoom()));
            }
        }
        long now = System.currentTimeMillis();
        if (killCovers(anchor, now) || (lastSeen != null && killCovers(lastSeen, now))) return false;
        bat.setDidKillBat(true);
        // claim the tracked id when known so the same kill's late entity signal
        // dedupes; the kill anchors at both the live marker and last-seen spot so
        // a removal record at either place still meets it
        if (anchor == null) anchor = lastSeen;
        if (anchor != null) {
            addKill(bat.getTrackedBatId(), anchor,
                    lastSeen != null && !lastSeen.equals(anchor) ? lastSeen : null, false, now);
        }
        return true;
    }

    // the secret's own SRM-style proximity fallback fired. like the counter, the
    // mark must tombstone the anchor: a late removal record for the same bat
    // would otherwise find an unfound neighbour and mark a second secret
    public static synchronized void markFromProximity(DungeonSecretBatState bat) {
        DungeonRoom room = bat.getRoom();
        if (room != null && bat.isFound(room)) return;
        bat.setDidKillBat(true);
        Vec3 lastSeen = bat.getLastSeenPos();
        OffsetPoint point = bat.getData().getSecretPoint();
        Vec3 spawn = point != null && room != null
                ? Vec3.atCenterOf(point.getBlockPos(room)) : null;
        Vec3 anchor = lastSeen != null ? lastSeen : spawn;
        Vec3 alt = lastSeen != null ? spawn : null;
        if (anchor != null) {
            addKill(bat.getTrackedBatId(), anchor, alt, false, System.currentTimeMillis());
        }
    }

    private static void addKill(int entityId, Vec3 pos, Vec3 altPos, boolean soundSeen, long now) {
        addKill(entityId, -1, pos, altPos, soundSeen, now);
    }

    private static void addKill(int entityId, int expectedEntityId, Vec3 pos, Vec3 altPos, boolean soundSeen, long now) {
        Kill k = new Kill(entityId, pos, altPos, now);
        k.expectedEntityId = expectedEntityId;
        k.soundSeen = soundSeen;
        kills.addLast(k);
        while (kills.size() > MAX_KILLS) kills.pollFirst();
        if (entityId != -1) noteEntityKill(entityId, now);
    }

    // once per render tick: retry evidence whose room wasn't resolvable at signal
    // time (room undiscovered/unmatched/processor still building). an evidence
    // whose room is now resolvable but still can't attribute is dropped for good -
    // one evidence, one attribution - and evidence older than MAX_PENDING_AGE_MS
    // expires without retrying at all
    public static void drain(DungeonContext context) {
        if (context == null || context.getScaffoldParser() == null) return;
        drain(context.getScaffoldParser().getDungeonRoomList());
    }

    public static synchronized void drain(Collection<DungeonRoom> rooms) {
        long now = System.currentTimeMillis();
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            KillEvidence ev = pending.pollFirst();
            if (ev == null) break;
            pendingEntities.remove(ev.entityId);
            if (ev.ms > 0 && now - ev.ms > MAX_PENDING_AGE_MS) continue;
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

    // --- kill bookkeeping ---------------------------------------------------------

    private static boolean killNear(Kill k, Vec3 pos) {
        if (pos == null) return false;
        return k.pos.distanceToSqr(pos) <= KILL_RADIUS_SQR
                || (k.altPos != null && k.altPos.distanceToSqr(pos) <= KILL_RADIUS_SQR);
    }

    private static boolean killCovers(Vec3 pos, long now) {
        if (pos == null) return false;
        for (Kill k : kills) {
            if (now - k.ms <= KILL_WINDOW_MS && killNear(k, pos)) return true;
        }
        return false;
    }

    private static boolean entityAttributed(int entityId, long now) {
        Long ms = attributedEntities.get(entityId);
        if (ms == null) return false;
        if (now - ms > ATTRIBUTED_AGE_MS) {
            attributedEntities.remove(entityId);
            return false;
        }
        return true;
    }

    private static void noteEntityKill(int entityId, long now) {
        attributedEntities.put(entityId, now);
        while (attributedEntities.size() > MAX_ATTRIBUTED) {
            Iterator<Integer> it = attributedEntities.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    // a death ping joins a kill near it inside the window - non-consuming, so the
    // kill's later entity signals still dedupe on their entity id. kills that
    // already absorbed a ping are skipped: one physical kill emits at most one
    // death ping, so a second ping at the same spot belongs to a different kill
    private static boolean joinExistingKill(KillEvidence ev, long now) {
        for (Kill k : kills) {
            if (now - k.ms > KILL_WINDOW_MS) continue;
            if (k.soundSeen) continue;
            if (killNear(k, ev.pos) || (ev.spawnPos != null && killNear(k, ev.spawnPos))) {
                k.soundSeen = true;
                return true;
            }
        }
        return false;
    }

    // an entity signal landing on an UNCLAIMED pending kill claims it: the earlier
    // ping/counter already attributed that kill, so this report is consumed, not
    // re-attributed. claimed kills are skipped, and so are kills waiting on a
    // DIFFERENT tracked entity - a different bat dying at the same spot is a
    // different kill
    private static boolean claimPendingKill(KillEvidence ev, long now) {
        for (Kill k : kills) {
            if (now - k.ms > KILL_WINDOW_MS) continue;
            if (k.entityId != -1) continue;
            if (k.expectedEntityId != -1 && k.expectedEntityId != ev.entityId) continue;
            if (killNear(k, ev.pos) || (ev.spawnPos != null && killNear(k, ev.spawnPos))) {
                k.entityId = ev.entityId;
                return true;
            }
        }
        return false;
    }

    // this entity is the tracked bat of an unfound secret somewhere: its kill
    // belongs to that owner and must reach attribution, not be swallowed by a
    // neighbouring pending kill
    private static boolean trackedElsewhere(int entityId, Collection<DungeonRoom> rooms) {
        for (DungeonRoom room : rooms) {
            if (!roomReady(room)) continue;
            for (DungeonMechanicState mech : room.getMechanics().values()) {
                if (!(mech instanceof DungeonSecretBatState)) continue;
                DungeonSecretBatState bat = (DungeonSecretBatState) mech;
                if (!bat.isFound(room) && bat.getTrackedBatId() == entityId) return true;
            }
        }
        return false;
    }

    // --- attribution core --------------------------------------------------------

    static synchronized void route(KillEvidence ev, Collection<DungeonRoom> rooms) {
        long now = System.currentTimeMillis();
        if (ev.entityId != -1) {
            // one entity id produces at most one kill, ever - the second and third
            // signals of the same physical kill stop here
            if (entityAttributed(ev.entityId, now)) return;
            // a bat tracked by an unfound secret carries its kill to that owner -
            // it must not be swallowed by a neighbouring kill's pending claim
            if (!trackedElsewhere(ev.entityId, rooms) && claimPendingKill(ev, now)) {
                noteEntityKill(ev.entityId, now);
                return;
            }
        } else if (joinExistingKill(ev, now)) {
            return;
        }
        DungeonSecretBatState marked = attributeEvidenceSelect(ev, rooms);
        if (marked != null) {
            addKill(ev.entityId, marked.getTrackedBatId(), ev.pos, ev.spawnPos, ev.entityId == -1, now);
            return;
        }
        // unresolvable (no ready room strictly contains either position) is the
        // only retryable miss; a resolved room with no eligible secret is a
        // definitive miss and the evidence dies here
        if (!evidenceResolvable(ev, rooms)) park(ev);
    }

    static synchronized boolean attributeEvidence(KillEvidence ev, Collection<DungeonRoom> rooms) {
        return attributeEvidenceSelect(ev, rooms) != null;
    }

    // attributes the evidence to at most one unfound bat secret and returns it
    // (null on a miss); callers read the winner's tracked id to seal the kill
    private static DungeonSecretBatState attributeEvidenceSelect(KillEvidence ev, Collection<DungeonRoom> rooms) {
        // 1. ownership beats geography: the secret that tracks this bat owns its
        //    kill wherever the bat died - a tracked bat that flew across a wall
        //    still belongs to its own secret. entity signals match the tracked
        //    id; an entityless ping matches a tracked bat sitting at the ping
        //    position (a bat mid-death-animation counts - its removal packet is
        //    still in flight) or the bat's fresh last-seen spot
        DungeonSecretBatState owner = null;
        double ownerDist = Double.MAX_VALUE;
        for (DungeonRoom room : rooms) {
            if (!roomReady(room)) continue;
            for (DungeonMechanicState mech : room.getMechanics().values()) {
                if (!(mech instanceof DungeonSecretBatState)) continue;
                DungeonSecretBatState bat = (DungeonSecretBatState) mech;
                if (bat.isFound(room)) continue;
                double d;
                if (ev.entityId != -1) {
                    if (bat.getTrackedBatId() != ev.entityId) continue;
                    d = bat.distanceToSqrFromTrackedPoint(ev.pos, room);
                } else {
                    d = trackedAnchorDistSqr(bat, ev.pos, System.currentTimeMillis());
                    if (d > OWNER_RADIUS_SQR) continue;
                }
                if (d < ownerDist) {
                    owner = bat;
                    ownerDist = d;
                }
            }
        }
        if (owner != null) {
            owner.setDidKillBat(true);
            // the owner's tracked id IS this kill's entity: the death's own
            // removal/death signals dedupe on it even when they report far from
            // the ping that attributed the kill
            if (ev.entityId == -1 && owner.getTrackedBatId() != -1) {
                noteEntityKill(owner.getTrackedBatId(), System.currentTimeMillis());
            }
            return owner;
        }
        // 2. room-bounded closest, strict containment first: a kill strictly
        //    inside a room is never stolen by a wall-tolerance neighbour - the
        //    wall only claims kills no room strictly owns
        DungeonSecretBatState best = selectAcrossRooms(ev, rooms, true);
        if (best == null) best = selectAcrossRooms(ev, rooms, false);
        if (best == null) return null;
        best.setDidKillBat(true);
        // an entityless signal that lands on a secret with a tracked bat lets
        // that bat's own entity signals dedupe on its id afterwards
        if (ev.entityId == -1 && best.getTrackedBatId() != -1) {
            noteEntityKill(best.getTrackedBatId(), System.currentTimeMillis());
        }
        return best;
    }

    private static DungeonSecretBatState selectAcrossRooms(KillEvidence ev, Collection<DungeonRoom> rooms, boolean strictOnly) {
        DungeonSecretBatState best = null;
        double bestDist = ATTR_RADIUS_SQR;
        for (DungeonRoom room : rooms) {
            if (!roomReady(room)) continue;
            if (strictOnly ? !strictlyHolds(room, ev) : !touchesRoomEvidence(room, ev)) continue;
            DungeonSecretBatState cand = selectBatForKill(room.getMechanics().values(), ev, room, bestDist);
            if (cand != null) {
                best = cand;
                bestDist = killDistance(cand, ev, room);
            }
        }
        return best;
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

    // the room is only readable once its processor exists: the matcher publishes
    // dungeonRoomInfo before buildDoors() finishes and schedules updateRoomProcessor
    // last, so mechanics read in that gap iterate a doors list mid-mutation and
    // cache a mechanics map permanently missing doors (same gate FeatureSmartRoute
    // uses for routing)
    private static boolean roomReady(DungeonRoom room) {
        return room.getRoomProcessor() != null;
    }

    // the evidence touches the room when either of its positions does: a bat spawned
    // here and killed across the wall is still this room's kill
    private static boolean touchesRoomEvidence(DungeonRoom room, KillEvidence ev) {
        RoomBounds b = room.getRoomBounds();
        if (b == null) return false;
        return touchesRoom(b, ev.pos) || (ev.spawnPos != null && touchesRoom(b, ev.spawnPos));
    }

    // either position strictly inside the room's own shape (no wall tolerance)
    private static boolean strictlyHolds(DungeonRoom room, KillEvidence ev) {
        RoomBounds b = room.getRoomBounds();
        if (b == null) return false;
        return strictInRoom(b, ev.pos) || (ev.spawnPos != null && strictInRoom(b, ev.spawnPos));
    }

    // the evidence is resolvable only when a READY room strictly contains one of
    // its positions - a neighbour's 2-block wall overhang does not count, so a
    // kill in an undiscovered room parks until that room matches instead of being
    // dropped because a matched room's wall merely touched it
    private static boolean evidenceResolvable(KillEvidence ev, Collection<DungeonRoom> rooms) {
        for (DungeonRoom room : rooms) {
            if (roomReady(room) && strictlyHolds(room, ev)) return true;
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

    // --- signal selection (the 0.1f ping path) ------------------------------------

    // hurt-ping attribution (unit-test entry for the death path too: a death ping
    // is entityless kill evidence). hurt pings never complete a secret - they only
    // pin the marker's live-position estimate to the bat being hit
    static synchronized void handleSound(DungeonRoom room, Vec3 pos, boolean isDeath) {
        if (isDeath) {
            route(new KillEvidence(-1, pos, null, System.currentTimeMillis()),
                    Collections.singletonList(room));
            return;
        }
        if (!roomReady(room)) return;
        DungeonSecretBatState best = selectBatForSound(room.getMechanics().values(), pos, room,
                bat -> false);
        if (best != null) best.noteSecretBatHeard(pos);
    }

    // the tracked entity is still healthy and somewhere else, so this secret's bat
    // demonstrably isn't the one that died at pos. only ever applied to death evidence
    static boolean liveTrackedElsewhere(DungeonSecretBatState bat, Vec3 pos) {
        Entity e = bat.getTrackedLiveEntity();
        return e != null && e.position().distanceToSqr(pos) > 4 * 4;
    }

    // distance from pos to the bat's own anchor: the raw tracked entity (a bat
    // mid-death-animation still reports !isRemoved() while its death ping and
    // removal packet are in flight - getTrackedLiveEntity would already exclude
    // it) or a fresh last-seen position. +inf when neither exists
    static double trackedAnchorDistSqr(DungeonSecretBatState bat, Vec3 pos, long nowMs) {
        double best = Double.POSITIVE_INFINITY;
        if (bat.getTrackedBatId() != -1) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                Entity e = mc.level.getEntity(bat.getTrackedBatId());
                if (e != null && !e.isRemoved()) best = e.position().distanceToSqr(pos);
            }
        }
        Vec3 lastSeen = bat.getLastSeenPos();
        if (lastSeen != null && bat.getLastAliveMs() > 0 && nowMs - bat.getLastAliveMs() <= TRACKED_FRESH_MS) {
            best = Math.min(best, lastSeen.distanceToSqr(pos));
        }
        return best;
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

    // strict containment: the block the position lies in is inside one of the
    // room's own shape cells - the room's own minX/minZ wall strip counts, while
    // shared walls beyond it and an L-room's missing quadrant don't
    public static boolean strictInRoom(RoomBounds bounds, Vec3 pos) {
        if (bounds == null) return false;
        return cellShaped(bounds,
                (int) Math.floor(pos.x) - bounds.getMinX(),
                (int) Math.floor(pos.z) - bounds.getMinZ());
    }

    private static boolean cellShaped(RoomBounds bounds, int relX, int relZ) {
        int cx = Math.floorDiv(relX, 32), cz = Math.floorDiv(relZ, 32);
        if (cx < 0 || cx >= 4 || cz < 0 || cz >= 4) return false;
        return ((bounds.getShape() >> (cz * 4 + cx)) & 0x1) != 0;
    }

    // loose containment for evidence routing: strictly inside the shape, or at
    // most a shared wall (1-2 blocks) past the bounding box while a shaped cell
    // sits within reach. positions inside the bounding box but in an unshaped
    // cell - the missing quadrant of an L room - are definitively not this room,
    // no matter how close to a shaped arm they sit
    public static boolean touchesRoom(RoomBounds bounds, Vec3 pos) {
        int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z);
        int relX = x - bounds.getMinX(), relZ = z - bounds.getMinZ();
        if (cellShaped(bounds, relX, relZ)) return true;
        // inside the bounding box but unshaped: never this room
        if (x >= bounds.getMinX() && x <= bounds.getMaxX()
                && z >= bounds.getMinZ() && z <= bounds.getMaxZ()) return false;
        if (x < bounds.getMinX() - WALL_TOLERANCE || x > bounds.getMaxX() + WALL_TOLERANCE
                || z < bounds.getMinZ() - WALL_TOLERANCE || z > bounds.getMaxZ() + WALL_TOLERANCE) {
            return false;
        }
        for (int dx = -WALL_TOLERANCE; dx <= WALL_TOLERANCE; dx++) {
            for (int dz = -WALL_TOLERANCE; dz <= WALL_TOLERANCE; dz++) {
                if (cellShaped(bounds, relX + dx, relZ + dz)) return true;
            }
        }
        return false;
    }

    // --- test hooks ---------------------------------------------------------------

    static synchronized int pendingCount() {
        return pending.size();
    }

    static synchronized int killCount() {
        return kills.size();
    }
}
