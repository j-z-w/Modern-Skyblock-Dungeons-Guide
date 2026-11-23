package kr.syeyoung.dungeonsguide.mod.dungeon;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.ActionKill;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomBounds;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

// the bat-completion authority: every kill signal becomes exactly one consumed
// KillEvidence attributed to at most one bat secret. covers the full signal-ordering
// matrix (sound/removal/counter), exact-once dedupe, room-shape bounding incl. L
// rooms, and the pure-read status contract solver workers rely on
public class SecretBatKillTrackerTest {

    private static DungeonRoom room;
    private static BlockPos min;

    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
        DungeonRoomInfo info = TestRooms.anyRoom();
        room = TestRooms.buildRoom(TestRooms.presetFor(info), info);
        min = room.getRoomBounds().getMin();
    }

    private static DungeonSecretBatState batAt(Vec3 worldPos) {
        DungeonSecretBatState.DungeonSecretBatData data = new DungeonSecretBatState.DungeonSecretBatData();
        data.getSecretPoint().setPosInWorld(room, BlockPos.containing(worldPos));
        return new DungeonSecretBatState(data, room);
    }

    private static SecretBatKillTracker.KillEvidence evidence(int entityId, Vec3 pos, Vec3 spawnPos) {
        return new SecretBatKillTracker.KillEvidence(entityId, pos, spawnPos, System.currentTimeMillis());
    }

    @BeforeEach
    @AfterEach
    void clear() {
        SecretBatKillTracker.clear();
        DungeonActionContext.clearKillRecords();
    }

    private void putBats(DungeonSecretBatState... bats) {
        room.getMechanics().clear();
        for (int i = 0; i < bats.length; i++) {
            room.getMechanics().put("t-bat-" + i, bats[i]);
        }
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

            // the same kill's late removal record must not mark a second secret
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
            SecretBatKillTracker.markFromCounter(near);
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

    @Test
    void twoQuickKillsMarkBothBats() {
        DungeonSecretBatState a = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState b = batAt(new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        putBats(a, b);
        try {
            SecretBatKillTracker.route(evidence(1, new Vec3(min.getX() + 9, 70, min.getZ() + 9), null),
                    Collections.singletonList(room));
            SecretBatKillTracker.route(evidence(2, new Vec3(min.getX() + 19, 70, min.getZ() + 19), null),
                    Collections.singletonList(room));
            assertTrue(a.isDidKillBat(), "first kill marks its bat");
            assertTrue(b.isDidKillBat(), "a second, genuinely different kill still marks");
        } finally {
            room.getMechanics().clear();
        }
    }

    @Test
    void duplicateSignalTombstoneIsConsumedNotPersistent() {
        // the dedupe is per-evidence, not a spatial tombstone: after the duplicate
        // report is consumed, a THIRD signal at the same spot is a new kill and must
        // attribute to the next-closest bat
        DungeonSecretBatState near = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(new Vec3(min.getX() + 10, 70, min.getZ() + 10));
        putBats(near, far);
        try {
            Vec3 pos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            SecretBatKillTracker.route(evidence(1, pos, null), Collections.singletonList(room));
            assertTrue(near.isDidKillBat());
            // the duplicate report consumes the tombstone
            SecretBatKillTracker.route(evidence(2, pos, null), Collections.singletonList(room));
            assertFalse(far.isDidKillBat());
            // a new kill at the same spot inside the window still attributes
            SecretBatKillTracker.route(evidence(3, pos, null), Collections.singletonList(room));
            assertTrue(far.isDidKillBat(), "a dedupe tombstone must not swallow a later kill at the same spot");
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

    // --- pure-read status ----------------------------------------------------------

    @Test
    void statusAndIsCompleteArePureReads() throws Exception {
        DungeonSecretBatState bat = batAt(new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        ActionKill kill = new ActionKill(bat.getData().getSecretPoint());
        kill.setCompletedBy(() -> bat.getSecretStatus(room) == DungeonSecretBatState.SecretStatus.FOUND);

        int consumedBefore = SecretBatKillTracker.consumedCount();
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
        assertEquals(consumedBefore, SecretBatKillTracker.consumedCount());
        assertEquals(pendingBefore, SecretBatKillTracker.pendingCount());
    }

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
}
