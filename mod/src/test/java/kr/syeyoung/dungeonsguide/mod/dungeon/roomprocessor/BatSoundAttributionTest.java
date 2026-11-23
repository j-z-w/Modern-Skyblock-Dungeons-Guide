package kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor;

import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonActionContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// B1/S8: secret-bat sound attribution. a ping must complete exactly the single
// closest unfound, non-live-tracked bat secret inside the radius - never every
// bat in a multi-bat room, never a found one, never a still-live-tracked one
public class BatSoundAttributionTest {

    private static DungeonRoom room;

    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
        DungeonRoomInfo info = TestRooms.anyRoom();
        room = TestRooms.buildRoom(TestRooms.presetFor(info), info);
    }

    private static DungeonSecretBatState batAt(DungeonRoom room, Vec3 worldPos) {
        DungeonSecretBatState.DungeonSecretBatData data = new DungeonSecretBatState.DungeonSecretBatData();
        data.getSecretPoint().setPosInWorld(room, BlockPos.containing(worldPos));
        return new DungeonSecretBatState(data, room);
    }

    @BeforeEach
    @AfterEach
    void clearKillContext() {
        DungeonActionContext.clearKillRecords();
        DungeonActionContext.clearKillAttribution();
    }

    @Test
    void pingAttributesToSingleClosestBat() {
        BlockPos min = room.getRoomBounds().getMin();
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(near);
        mechs.add(far);

        // 2 blocks from `near`, ~14 from `far` - both inside the 16-block radius
        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);
        assertSame(near, GeneralRoomProcessor.selectBatForSound(mechs, ping, room, b -> false),
                "a ping must attribute to exactly one bat: the closest");

        // a ping far from every bat secret attributes to nothing
        Vec3 distant = new Vec3(min.getX() + 8 + 40, 70, min.getZ() + 8 + 40);
        assertNull(GeneralRoomProcessor.selectBatForSound(mechs, distant, room, b -> false),
                "a ping outside the attribution radius must not pick a bat");
    }

    @Test
    void foundAndLiveTrackedBatsAreSkipped() {
        BlockPos min = room.getRoomBounds().getMin();
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(near);
        mechs.add(far);
        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);

        // a completed secret can't be completed again by a later ping
        near.setDidKillBat(true);
        assertSame(far, GeneralRoomProcessor.selectBatForSound(mechs, ping, room, b -> false));
        near.setDidKillBat(false);

        // S8: the live veto must hold even against the closest candidate - a death
        // sound can't belong to a bat the tracker still sees alive
        assertSame(far, GeneralRoomProcessor.selectBatForSound(mechs, ping, room, b -> b == near),
                "live-tracked bat must be vetoed even when closest");
    }

    @Test
    void deathCompletesOnlyOneBatThroughProcessor() {
        BlockPos min = room.getRoomBounds().getMin();
        GeneralRoomProcessor grp = new GeneralRoomProcessor(room);
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        room.getMechanics().put("test-secret-bat-a", near);
        room.getMechanics().put("test-secret-bat-b", far);
        try {
            grp.onSecretBatSound(new Vec3(min.getX() + 10, 70, min.getZ() + 10), true);
            assertTrue(near.isDidKillBat(), "closest bat completes on a death ping");
            assertFalse(far.isDidKillBat(), "one death ping must not complete every bat");

            // a hurt ping only pins heardPos on the closest bat, doesn't complete it
            DungeonSecretBatState other = batAt(room, new Vec3(min.getX() + 24, 70, min.getZ() + 24));
            room.getMechanics().put("test-secret-bat-c", other);
            grp.onSecretBatSound(new Vec3(min.getX() + 25, 70, min.getZ() + 25), false);
            assertFalse(other.isDidKillBat(), "a hurt ping must not complete a secret");
        } finally {
            room.getMechanics().remove("test-secret-bat-a");
            room.getMechanics().remove("test-secret-bat-b");
            room.getMechanics().remove("test-secret-bat-c");
        }
    }

    @Test
    void deathSoundThenLateRemovalMarksExactlyOneBat() {
        // ordering 1: the death ping lands before the removal is processed. the kill
        // record it leaves behind must not complete a second bat secret
        BlockPos min = room.getRoomBounds().getMin();
        GeneralRoomProcessor grp = new GeneralRoomProcessor(room);
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        room.getMechanics().put("t-kill-a", near);
        room.getMechanics().put("t-kill-b", far);
        try {
            Vec3 ping = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            grp.onSecretBatSound(ping, true);
            assertTrue(near.isDidKillBat(), "closest bat completes on the death ping");
            assertFalse(far.isDidKillBat(), "one kill must not complete every bat");

            // the removal arrives late: its record is inside `far`'s claim radius but
            // the kill was already attributed, so the record must be dropped, not claimed
            DungeonActionContext.recordKill(4242, ping);
            assertNotEquals(DungeonSecretBatState.SecretStatus.FOUND, far.getSecretStatus(room),
                    "a kill already attributed through the sound can't mark a second secret");
        } finally {
            room.getMechanics().remove("t-kill-a");
            room.getMechanics().remove("t-kill-b");
        }
    }

    @Test
    void removalThenDeathSoundMarksExactlyOneBat() {
        // ordering 2: the removal/kill record lands first and a secret claims it; the
        // death sound arriving second must not complete a second bat secret
        BlockPos min = room.getRoomBounds().getMin();
        GeneralRoomProcessor grp = new GeneralRoomProcessor(room);
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 12, 70, min.getZ() + 12));
        room.getMechanics().put("t-kill2-a", near);
        room.getMechanics().put("t-kill2-b", far);
        try {
            Vec3 killPos = new Vec3(min.getX() + 9, 70, min.getZ() + 9);
            DungeonActionContext.recordKill(4343, killPos);
            assertEquals(DungeonSecretBatState.SecretStatus.FOUND, near.getSecretStatus(room),
                    "a kill record near the spawn must mark its secret");

            grp.onSecretBatSound(killPos, true);
            assertFalse(far.isDidKillBat(),
                    "the second signal for the same kill must not mark a second bat");
        } finally {
            room.getMechanics().remove("t-kill2-a");
            room.getMechanics().remove("t-kill2-b");
        }
    }

    @Test
    void hurtPingPinsClosestBatWithoutVeto() {
        // a BAT_HURT ping must never veto: the bat being hit IS alive. it pins heardPos
        // on the single closest unfound secret and completes nothing
        BlockPos min = room.getRoomBounds().getMin();
        GeneralRoomProcessor grp = new GeneralRoomProcessor(room);
        DungeonSecretBatState near = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        DungeonSecretBatState far = batAt(room, new Vec3(min.getX() + 20, 70, min.getZ() + 20));
        room.getMechanics().put("t-hurt-a", near);
        room.getMechanics().put("t-hurt-b", far);
        try {
            Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);
            grp.onSecretBatSound(ping, false);
            assertNotNull(near.getHeardPos(), "hurt ping attributes to the closest bat");
            assertNull(far.getHeardPos(), "hurt ping attributes to exactly one bat");
            assertFalse(near.isDidKillBat());
            assertFalse(far.isDidKillBat());
        } finally {
            room.getMechanics().remove("t-hurt-a");
            room.getMechanics().remove("t-hurt-b");
        }
    }

    @Test
    void batLastSeenInAnotherRoomIsNotAttributed() {
        // a bat that flew into a neighbouring room dies there: this room's selection
        // must skip a secret whose tracked anchor is outside this room's bounds even
        // though the ping is within radius of its recorded spawn
        BlockPos min = room.getRoomBounds().getMin();
        DungeonSecretBatState away = batAt(room, new Vec3(min.getX() + 8, 70, min.getZ() + 8));
        away.setLastSeenPos(new Vec3(min.getX() - 64, 70, min.getZ() + 8));
        List<DungeonMechanicState> mechs = new ArrayList<>();
        mechs.add(away);
        Vec3 ping = new Vec3(min.getX() + 10, 70, min.getZ() + 10);
        assertNull(GeneralRoomProcessor.selectBatForSound(mechs, ping, room, b -> false),
                "a secret whose bat was last seen in another room can't be claimed here");

        // same spawn, but the bat's last-seen position is inside the room: attributable
        away.setLastSeenPos(new Vec3(min.getX() + 9, 70, min.getZ() + 9));
        assertSame(away, GeneralRoomProcessor.selectBatForSound(mechs, ping, room, b -> false),
                "a bat last seen inside the room stays attributable");
    }
}
