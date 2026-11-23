package kr.syeyoung.dungeonsguide.mod.dungeon;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.ActionKill;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.dataprovider.EDungeonDoorType;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomBounds;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomMatcher;
import kr.syeyoung.dungeonsguide.mod.events.impl.DungeonLeftEvent;
import kr.syeyoung.dungeonsguide.mod.events.listener.DungeonListener;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.utils.Tuple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

// the bat-completion authority: signals are folded into KILLS, not processed one
// by one - a physical kill reports up to three signals (0.1f death ping,
// LivingDeathEvent, EntityExitWorldEvent) plus possibly the N/M counter, and
// every ordering of those signals must mark exactly one bat secret. entity
// signals dedupe by entity id; entityless pings join or open pending kills that
// entity signals claim. covers the ordering matrix, two-bat dedupe, room-shape
// bounding incl. L rooms and wall strips, parked-evidence TTL, the
// room-readiness gate, tracked-owner attribution across rooms, and the
// pure-read status contract solver workers rely on
public class SecretBatKillTrackerTest {

    private static DungeonRoom room;
    private static DungeonRoomInfo info;
    private static BlockPos min;

    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
        info = TestRooms.anyRoom();
        room = TestRooms.buildRoom(TestRooms.presetFor(info), info);
        min = room.getRoomBounds().getMin();
    }

    private static DungeonSecretBatState batAt(Vec3 worldPos) {
        return batAt(room, worldPos);
    }

    private static DungeonSecretBatState batAt(DungeonRoom r, Vec3 worldPos) {
        DungeonSecretBatState.DungeonSecretBatData data = new DungeonSecretBatState.DungeonSecretBatData();
        data.getSecretPoint().setPosInWorld(r, BlockPos.containing(worldPos));
        return new DungeonSecretBatState(data, r);
    }

    private static SecretBatKillTracker.KillEvidence evidence(int entityId, Vec3 pos, Vec3 spawnPos) {
        return new SecretBatKillTracker.KillEvidence(entityId, pos, spawnPos, System.currentTimeMillis());
    }

    private static SecretBatKillTracker.KillEvidence evidence(int entityId, Vec3 pos, Vec3 spawnPos, long ms) {
        return new SecretBatKillTracker.KillEvidence(entityId, pos, spawnPos, ms);
    }

    @BeforeEach
    @AfterEach
    void clear() {
        SecretBatKillTracker.clear();
        DungeonActionContext.clearKillRecords();
    }

    private void putBats(DungeonSecretBatState... bats) {
        putBats(room, bats);
    }

    private void putBats(DungeonRoom r, DungeonSecretBatState... bats) {
        r.getMechanics().clear();
        for (int i = 0; i < bats.length; i++) {
            r.getMechanics().put("t-bat-" + i, bats[i]);
        }
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    // a second DungeonRoom with real mechanics at custom bounds: the 7-arg
    // constructor builds bounds but its async rematch can't run on a DRIWorld
    // context, so the match fields are filled in directly (roomProcessor just
    // needs to be non-null to pass the readiness gate)
    private static DungeonRoom fakeRoom(BlockPos rmin, BlockPos rmax, short shape) throws Exception {
        DungeonRoom r = new DungeonRoom(
                new HashSet<>(Collections.singleton(new Point(0, 0))),
                shape, (byte) 6, rmin, rmax, room.getContext(),
                new HashSet<Tuple<Vector2d, EDungeonDoorType>>());
        setField(r, "dungeonRoomInfo", info);
        RoomMatcher matcher = new RoomMatcher(r);
        matcher.setMatch(info);
        matcher.setRotation(0);
        setField(r, "roomMatcher", matcher);
        setField(r, "roomProcessor", room.getRoomProcessor());
        return r;
    }

    // --- the ordering matrix: one physical kill marks exactly one bat -------------

    @Test
    void soundThenRemovalMarksExactlyOne() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.handleSound(room, pos, true);
            assertTrue(near.isDidKillBat(), "death ping completes the closest bat");
            assertFalse(far.isDidKillBat(), "one kill must not complete every bat");

            // the same kill's late removal record claims the pending kill instead
            // of marking a second secret
            SecretBatKillTracker.route(evidence(4242, pos, pos), Collections.singletonList(room));
            assertFalse(far.isDidKillBat(), "the second signal for the same kill is consumed");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void removalThenSoundMarksExactlyOne() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.route(evidence(4343, pos, pos), Collections.singletonList(room));
            assertTrue(near.isDidKillBat(), "the removal evidence completes the closest bat");

            SecretBatKillTracker.handleSound(room, pos, true);
            assertFalse(far.isDidKillBat(), "the death ping for the same kill is consumed");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void removalOnlyMarksExactlyOne() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.route(evidence(1, pos, pos), Collections.singletonList(room));
            assertTrue(near.isDidKillBat());
            assertFalse(far.isDidKillBat(), "one kill must mark at most one bat secret");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void soundOnlyMarksExactlyOne() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        putBats(near, far);
        try {
            SecretBatKillTracker.handleSound(room, new Vec3(min.getX() + 9, 70, min.getZ() + 9), true);
            assertTrue(near.isDidKillBat());
            assertFalse(far.isDidKillBat());
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void counterOnlyMarksExactlyOne() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        putBats(near, far);
        try {
            assertTrue(SecretBatKillTracker.markFromCounter(near));
            assertTrue(near.isDidKillBat());
            assertFalse(far.isDidKillBat());

            // the counter tombstones the secret's anchor so the same kill's late
            // removal record can't complete a second bat
            SecretBatKillTracker.route(evidence(7, new Vec3(min.getX() + 9, 70, min.getZ() + 9),
                    new Vec3(min.getX() + 9, 70, min.getZ() + 9)), Collections.singletonList(room));
            assertFalse(far.isDidKillBat(), "the late removal for the counter-marked kill is consumed");
        } finally {
            room.getMechanics().clear();
        }
    }

    // full orderings of the three real signals - all must collapse to one kill
    @Test
    void threeSignalKillSoundDeathExit() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 14, 70, min.getZ() + 14));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.handleSound(room, pos, true);                 // BAT_DEATH ping
            SecretBatKillTracker.route(evidence(100, pos, null), Collections.singletonList(room)); // LivingDeath
            SecretBatKillTracker.route(evidence(100, pos, null), Collections.singletonList(room)); // ExitWorld
            assertTrue(near.isDidKillBat());
            assertFalse(far.isDidKillBat(), "3 signals of one kill must mark exactly one bat");
            assertEquals(1, SecretBatKillTracker.killCount(), "3 signals collapse into one kill");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void threeSignalKillDeathExitSound() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 14, 70, min.getZ() + 14));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.route(evidence(100, pos, null), Collections.singletonList(room)); // LivingDeath
            SecretBatKillTracker.route(evidence(100, pos, null), Collections.singletonList(room)); // ExitWorld
            SecretBatKillTracker.handleSound(room, pos, true);                 // BAT_DEATH ping
            assertTrue(near.isDidKillBat());
            assertFalse(far.isDidKillBat(), "3 signals of one kill must mark exactly one bat");
            assertEquals(1, SecretBatKillTracker.killCount());
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void sameEntitySecondSignalAtMovedPositionStillDedupes() {
        // the dying entity can drift during its death animation: the exit record
        // lands >4 blocks from the death record and still must not mark a second bat
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 14));
        putBats(near, far);
        try {
            SecretBatKillTracker.route(evidence(100, new Vec3(min.getX() + 8, 75, min.getZ() + 8), null),
                    Collections.singletonList(room));
            assertTrue(near.isDidKillBat());
            SecretBatKillTracker.route(evidence(100, new Vec3(min.getX() + 8, 70, min.getZ() + 13), null),
                    Collections.singletonList(room));
            assertFalse(far.isDidKillBat(), "entity id dedupe holds across positions, not just inside a radius");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void twoBatsKilledCloseTogetherMarkBoth() {
        // two different entity ids dying 0.5s apart 3 blocks apart: two real
        // kills, two secrets - the first kill's record must not swallow the second
        DungeonSecretBatState a = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState b = batAt(new Vec3(min.getX() + 11, 70, min.getZ() + 8));
        putBats(a, b);
        try {
            Vec3 p1 = new Vec3(min.getX() + 9, 70, min.getZ() + 8);
            Vec3 p2 = new Vec3(min.getX() + 12, 70, min.getZ() + 8);   // 3 blocks away
            SecretBatKillTracker.route(evidence(100, p1, null), Collections.singletonList(room));
            SecretBatKillTracker.route(evidence(101, p2, null), Collections.singletonList(room));
            assertTrue(a.isDidKillBat(), "first kill marks its bat");
            assertTrue(b.isDidKillBat(), "a second entity id is a second kill");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void secondPingAtSameSpotIsADifferentKill() {
        // one physical kill emits at most one death ping: a second ping near a
        // kill that already absorbed its ping is a different kill
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 10, 70, min.getZ() + 10));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.handleSound(room, pos, true);
            assertTrue(near.isDidKillBat());
            SecretBatKillTracker.handleSound(room, pos, true);
            assertTrue(far.isDidKillBat(), "a second ping is a second kill, not a duplicate");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void counterIncrementCoveredByKillDoesNotMark() {
        // a kill right on top of an unfound secret's anchor already accounts for
        // the counter bump: re-attributing would credit the same physical kill
        // twice, so the tracker refuses and the caller re-picks
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState covered = batAt(new Vec3(min.getX() + 10, 70, min.getZ() + 10));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 28, 70, min.getZ() + 28));
        putBats(near, covered, far);
        try {
            SecretBatKillTracker.route(evidence(1, new Vec3(min.getX() + 9, 70, min.getZ() + 9), null),
                    Collections.singletonList(room));
            assertTrue(near.isDidKillBat());
            assertFalse(SecretBatKillTracker.markFromCounter(covered),
                    "the counter increment is covered by the kill - this isn't the increment's owner");
            assertFalse(covered.isDidKillBat());
            // a bat nowhere near the kill still attributes normally
            assertTrue(SecretBatKillTracker.markFromCounter(far));
            assertTrue(far.isDidKillBat());
        } finally {
            room.getMechanics().clear();
        }
    }

    // --- room bounding ----------------------------------------------------------

    @Test
    void killInNeighbouringRoomMarksNothingHere() {
        DungeonSecretBatState a = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        putBats(a);
        try {
            // well outside this room's bounds: routed here in a test, but in the live
            // path it belongs to the room the kill actually lies in
            Vec3 outside = new Vec3(min.getX() - 64, 70, min.getZ() + 8);
            assertFalse(SecretBatKillTracker.attributeEvidence(evidence(1, outside, outside),
                    Collections.singletonList(room)));
            assertFalse(a.isDidKillBat(), "a kill in another room marks nothing in this one");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void lRoomOverhangIsNotPartOfTheRoom() {
        // cells (0,0), (1,0), (0,1) present; (1,1) missing: the classic L overhang.
        // evidence inside the bbox but in the missing quadrant is NOT this room's
        BlockPos bmin = new BlockPos(0, 64, 0), bmax = new BlockPos(63, 96, 63);
        short lShape = (short) ((1 << 0) | (1 << 1) | (1 << 4));
        RoomBounds l = new RoomBounds(lShape, bmin, bmax);

        assertTrue(SecretBatKillTracker.touchesRoom(l, new Vec3(16, 70, 16)), "shaped cell counts");
        assertTrue(SecretBatKillTracker.touchesRoom(l, new Vec3(48, 70, 16)), "shaped cell counts");
        assertTrue(SecretBatKillTracker.touchesRoom(l, new Vec3(16, 70, 48)), "shaped cell counts");
        assertFalse(SecretBatKillTracker.touchesRoom(l, new Vec3(48, 70, 48)),
                "a position inside the bbox but in the L's missing quadrant is not this room");
        assertFalse(SecretBatKillTracker.touchesRoom(l, new Vec3(64, 70, 48)),
                "a wall-width step past the bbox still lands in the missing quadrant");
        // a shared wall on a shaped arm does count (kills happen right at the wall)
        assertTrue(SecretBatKillTracker.touchesRoom(l, new Vec3(-1, 70, 16)),
                "one block into a shared wall on a shaped cell still counts");
    }

    @Test
    void roomWallStripCountsAsInside() {
        // the room's own minX/minZ wall strip is part of the room (a bat sitting on
        // its own north/west wall is this room's), while strictly-inside is still
        // not granted to positions beyond the shared walls
        BlockPos bmin = new BlockPos(0, 64, 0), bmax = new BlockPos(31, 96, 31);
        RoomBounds b = new RoomBounds((short) 1, bmin, bmax);

        assertTrue(SecretBatKillTracker.strictInRoom(b, new Vec3(0.5, 70, 16)),
                "the minX wall strip is inside the room");
        assertTrue(SecretBatKillTracker.strictInRoom(b, new Vec3(16, 70, 0.5)),
                "the minZ wall strip is inside the room");
        assertTrue(SecretBatKillTracker.touchesRoom(b, new Vec3(0.5, 70, 16)),
                "loose containment includes the minX strip too");
        assertTrue(SecretBatKillTracker.touchesRoom(b, new Vec3(31.5, 70, 16)),
                "the last block of the room is inside");
        // the first block past the bbox is the shared wall: not strictly inside,
        // but within the wall tolerance
        assertFalse(SecretBatKillTracker.strictInRoom(b, new Vec3(32, 70, 16)));
        assertTrue(SecretBatKillTracker.touchesRoom(b, new Vec3(32, 70, 16)),
                "the shared wall just past the bbox still touches");
        // and the old discontinuity is gone: just-outside positions no longer
        // flip-flop at exact boundaries
        assertTrue(SecretBatKillTracker.touchesRoom(b, new Vec3(32.5, 70, 16)));
        assertTrue(SecretBatKillTracker.touchesRoom(b, new Vec3(-0.5, 70, 16)));
        assertFalse(SecretBatKillTracker.touchesRoom(b, new Vec3(-3, 70, 16)),
                "past the wall tolerance is another room's ground");
    }

    @Test
    void strictInteriorKillIsNotStolenByWallNeighbour() throws Exception {
        // two adjacent 1x1 rooms sharing a wall plane: a kill strictly inside A
        // attributes to A even when B's bat secret is closer across the wall
        DungeonRoom roomA = fakeRoom(new BlockPos(0, 70, 0), new BlockPos(31, 70, 31), (short) 1);
        DungeonRoom roomB = fakeRoom(new BlockPos(32, 70, 0), new BlockPos(63, 70, 31), (short) 1);
        try {
            DungeonSecretBatState a = batAt(roomA, new Vec3(24, 70, 8));
            DungeonSecretBatState b = batAt(roomB, new Vec3(35, 70, 8));
            putBats(roomA, a);
            putBats(roomB, b);

            // strictly inside A near its wall, closer to B's anchor than to A's:
            // strict containment still beats wall-tolerance proximity
            Vec3 pos = new Vec3(30.5, 70, 8);
            SecretBatKillTracker.route(evidence(1, pos, null), Arrays.asList(roomA, roomB));
            assertTrue(a.isDidKillBat(), "a kill strictly inside room A attributes to room A");
            assertFalse(b.isDidKillBat(), "a wall-tolerance neighbour must not steal it");
        } finally {
            roomA.getMechanics().clear();
            roomB.getMechanics().clear();
        }
    }

    @Test
    void wallKillInUndiscoveredRoomParksNotDrops() throws Exception {
        // a kill inside an undiscovered room sits within the matched neighbour's
        // 2-block wall tolerance but is NOT strictly inside it: the evidence must
        // park until the owning room matches, not be dropped as a resolved miss
        DungeonRoom roomA = fakeRoom(new BlockPos(0, 70, 0), new BlockPos(31, 70, 31), (short) 1);
        try {
            roomA.getMechanics().clear(); // no secrets in A anyway
            Vec3 pos = new Vec3(34, 70, 8);  // inside the undiscovered room B's area
            SecretBatKillTracker.route(evidence(1, pos, null), Collections.singletonList(roomA));
            assertEquals(1, SecretBatKillTracker.pendingCount(),
                    "a wall-touched kill in an undiscovered room parks for later");
        } finally {
            roomA.getMechanics().clear();
        }
    }

    @Test
    void batSpawnedHereKilledAcrossWallStillAttributes() {
        // the evidence's recorded spawn position anchors attribution too: a bat that
        // flew across the wall and died there is still this room's secret
        DungeonSecretBatState a = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        putBats(a);
        try {
            Vec3 killPos = new Vec3(min.getX() - 2, 70, min.getZ() + 8);
            Vec3 spawnPos = new Vec3(min.getX() + 8, 70, min.getZ() + 8);
            // kill pos is outside the room but within tolerance of a shared wall; the
            // spawn anchor inside the room seals it either way
            SecretBatKillTracker.route(evidence(1, killPos, spawnPos), Collections.singletonList(room));
            assertTrue(a.isDidKillBat(), "a bat spawned in the room keeps its kill attribution");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void batLastSeenInAnotherRoomIsNotAttributed() {
        DungeonSecretBatState away = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        away.setLastSeenPos(new Vec3(min.getX() - 64, 70, min.getZ() + 8));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(away);
        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);
        assertNull(SecretBatKillTracker.selectBatForSound(mechs, ping, room, b -> false),
                "a secret whose bat was last seen in another room can't be claimed here");

        away.setLastSeenPos(new Vec3(min.getX() + 9, 70, min.getZ() + 9));
        assertSame(away, SecretBatKillTracker.selectBatForSound(mechs, ping, room, b -> false),
                "a bat last seen inside the room stays attributable");
    }

    // --- tracked ownership across rooms -------------------------------------------

    @Test
    void trackedBatDyingInNeighbourKeepsOwnership() throws Exception {
        // room A's secret tracks entity 100; the bat flies into room B and dies
        // there. the death ping attributes to the OWNER (room A), room B's secret
        // stays untouched, and the kill's later entity signals dedupe instead of
        // being swallowed by the ping's pending kill
        DungeonRoom roomA = fakeRoom(new BlockPos(0, 70, 0), new BlockPos(31, 70, 31), (short) 1);
        DungeonRoom roomB = fakeRoom(new BlockPos(32, 70, 0), new BlockPos(63, 70, 31), (short) 1);
        try {
            DungeonSecretBatState ownerBat = batAt(roomA, new Vec3(8, 70, 8));
            ownerBat.setTrackedBatId(100);
            DungeonSecretBatState roomBBat = batAt(roomB, new Vec3(40, 70, 8));
            putBats(roomA, ownerBat);
            putBats(roomB, roomBBat);

            Vec3 deathPos = new Vec3(40, 70, 8);   // strictly inside room B
            ownerBat.setLastSeenPos(deathPos);
            ownerBat.setLastAliveMs(System.currentTimeMillis());

            List<DungeonRoom> rooms = Arrays.asList(roomA, roomB);
            SecretBatKillTracker.route(evidence(-1, deathPos, null), rooms);  // the 0.1f death ping
            assertTrue(ownerBat.isDidKillBat(), "the ping attributes to the tracked bat's owner");
            assertFalse(roomBBat.isDidKillBat(), "room B's secret is untouched");

            // the same kill's LivingDeath/ExitWorld records dedupe on entity id
            SecretBatKillTracker.route(evidence(100, deathPos, null), rooms);
            SecretBatKillTracker.route(evidence(100, deathPos, null), rooms);
            assertFalse(roomBBat.isDidKillBat(), "room B's kill isn't marked by the deduped signals");
            assertEquals(0, SecretBatKillTracker.pendingCount(), "nothing is parked");
        } finally {
            roomA.getMechanics().clear();
            roomB.getMechanics().clear();
        }
    }

    @Test
    void entitySignalForTrackedBatIsNotSwallowedByPendingKill() throws Exception {
        // a pending ping-kill near a DIFFERENT tracked bat's death must not claim
        // the entity signal: the tracked bat's own secret owns the kill
        DungeonRoom roomA = fakeRoom(new BlockPos(0, 70, 0), new BlockPos(31, 70, 31), (short) 1);
        try {
            DungeonSecretBatState a = batAt(roomA, new Vec3(8, 70, 8));
            DungeonSecretBatState b = batAt(roomA, new Vec3(11, 70, 8));
            b.setTrackedBatId(101);
            putBats(roomA, a, b);

            Vec3 p1 = new Vec3(8.5, 70, 8), p2 = new Vec3(11.5, 70, 8);
            SecretBatKillTracker.route(evidence(-1, p1, null), Collections.singletonList(roomA)); // A's ping
            assertTrue(a.isDidKillBat());
            SecretBatKillTracker.route(evidence(101, p2, null), Collections.singletonList(roomA)); // B's death
            assertTrue(b.isDidKillBat(), "a tracked bat's entity signal attributes to its owner");
        } finally {
            roomA.getMechanics().clear();
        }
    }

    // --- parked evidence ------------------------------------------------------------

    @Test
    void unresolvableEvidenceIsParkedAndDrained() {
        // a kill evidence whose position touches no known room is parked, and a later
        // drain retries it once the room list can resolve it
        room.getMechanics().clear();
        DungeonSecretBatState bat = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            // no room in the list: unresolvable -> parked, not dropped
            SecretBatKillTracker.route(evidence(1, pos, null), Collections.emptyList());
            assertEquals(1, SecretBatKillTracker.pendingCount());
            // drain with the room now resolvable attributes it
            room.getMechanics().put("t-bat", bat);
            SecretBatKillTracker.drain(Collections.singletonList(room));
            assertEquals(0, SecretBatKillTracker.pendingCount());
            assertTrue(bat.isDidKillBat(), "drained evidence still attributes exactly once");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void parkedEvidenceExpiresAfterTtl() {
        // a kill that still can't resolve after the TTL (boss-room bat, outside
        // every room) is dropped instead of retried forever
        room.getMechanics().clear();
        Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
        SecretBatKillTracker.route(
                evidence(1, pos, null, System.currentTimeMillis() - 11_000), Collections.emptyList());
        assertEquals(1, SecretBatKillTracker.pendingCount());
        SecretBatKillTracker.drain(Collections.emptyList());
        assertEquals(0, SecretBatKillTracker.pendingCount(), "stale parked evidence expires");
    }

    @Test
    void evidenceParksUntilRoomProcessorReady() throws Exception {
        // a room mid-match (info published, processor still building) isn't
        // resolvable: evidence touching it parks and attributes once the
        // processor lands
        DungeonSecretBatState a = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        putBats(a);
        Field f = DungeonRoom.class.getDeclaredField("roomProcessor");
        f.setAccessible(true);
        Object processor = f.get(room);
        f.set(room, null);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.route(evidence(1, pos, null), Collections.singletonList(room));
            assertFalse(a.isDidKillBat(), "a mid-match room doesn't take attribution");
            assertEquals(1, SecretBatKillTracker.pendingCount(), "evidence parks until the room is ready");

            f.set(room, processor);
            SecretBatKillTracker.drain(Collections.singletonList(room));
            assertEquals(0, SecretBatKillTracker.pendingCount());
            assertTrue(a.isDidKillBat(), "the parked kill attributes once the processor exists");
        } finally {
            f.set(room, processor);
            room.getMechanics().clear();
        }
    }

    // --- selection semantics ------------------------------------------------------

    @Test
    void pingAttributesToSingleClosestBat() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(near);
        mechs.add(far);

        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);
        assertSame(near, SecretBatKillTracker.selectBatForSound(mechs, ping, room, b -> false),
                "a ping must attribute to exactly one bat: the closest");

        Vec3 distant = new Vec3(min.getX() + 48, 70, min.getZ() + 48);
        assertNull(SecretBatKillTracker.selectBatForSound(mechs, distant, room, b -> false),
                "a ping outside the attribution radius must not pick a bat");
    }

    @Test
    void foundAndLiveTrackedBatsAreSkipped() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(near);
        mechs.add(far);
        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);

        near.setDidKillBat(true);
        assertSame(far, SecretBatKillTracker.selectBatForSound(mechs, ping, room, b -> false));
        near.setDidKillBat(false);

        // the live veto holds even against the closest candidate: a death sound can't
        // belong to a bat the tracker still sees alive and elsewhere
        assertSame(far, SecretBatKillTracker.selectBatForSound(mechs, ping, room, b -> b == near),
                "a healthy tracked bat elsewhere must be vetoed even when closest");
    }

    @Test
    void hurtPingPinsClosestBatWithoutCompleting() {
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        putBats(near, far);
        try {
            SecretBatKillTracker.handleSound(room, new Vec3(min.getX() + 10, 70, min.getZ() + 10), false);
            assertNotNull(near.getHeardPos(), "hurt ping attributes to the closest bat");
            assertNull(far.getHeardPos(), "hurt ping attributes to exactly one bat");
            assertFalse(near.isDidKillBat(), "a hurt ping must not complete a secret");
            assertFalse(far.isDidKillBat());
        } finally {
            room.getMechanics().clear();
        }
    }

    // --- context teardown -----------------------------------------------------------

    @Test
    void dungeonLeftClearsAllBatState() {
        // parked evidence, spawn records, and sound pings from the outgoing
        // context must not leak into the next one when DungeonLeftEvent fires
        // without a WorldUnloadEvent
        Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
        SecretBatKillTracker.route(evidence(1, pos, null), Collections.emptyList());
        DungeonActionContext.getSpawnLocation().put(1, pos);
        SecretBatTracker.recordSecretBatSound(pos, true);
        assertEquals(1, SecretBatKillTracker.pendingCount());
        assertEquals(1, SecretBatTracker.pingCount());

        new DungeonListener().onDungeonLeave(new DungeonLeftEvent());

        assertEquals(0, SecretBatKillTracker.pendingCount(), "parked evidence is cleared");
        assertEquals(0, SecretBatKillTracker.killCount(), "kill records are cleared");
        assertTrue(DungeonActionContext.getSpawnLocation().isEmpty(), "spawn records are cleared");
        assertEquals(0, SecretBatTracker.pingCount(), "sound pings are cleared");
    }

    // --- pure-read status ----------------------------------------------------------

    @Test
    void statusAndIsCompleteArePureReads() throws Exception {
        DungeonSecretBatState bat = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        ActionKill kill = new ActionKill(bat.getData().getSecretPoint());
        kill.setCompletedBy(() -> bat.getSecretStatus(room) == DungeonSecretBatState.SecretStatus.FOUND);

        int killsBefore = SecretBatKillTracker.killCount();
        int pendingBefore = SecretBatKillTracker.pendingCount();

        // hammer it from another thread the way pathCalculator workers do
        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 1000 && !failed.get(); i++) {
                if (kill.isComplete(room)) failed.set(true);
                if (bat.getSecretStatus(room) == DungeonSecretBatState.SecretStatus.FOUND) failed.set(true);
            }
            done.countDown();
        });
        worker.start();
        for (int i = 0; i < 1000; i++) {
            assertFalse(kill.isComplete(room));
            assertNotEquals(DungeonSecretBatState.SecretStatus.FOUND, bat.getSecretStatus(room));
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse(failed.get(), "pure reads must never flip a secret to found");

        // and must not mutate ANY shared bookkeeping on the way
        assertFalse(bat.isDidKillBat());
        assertNull(bat.getHeardPos());
        assertNull(bat.getLastSeenPos());
        assertEquals(-1, bat.getTrackedBatId());
        assertEquals(killsBefore, SecretBatKillTracker.killCount());
        assertEquals(pendingBefore, SecretBatKillTracker.pendingCount());
    }
}
