package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// crypt tombs are multi-block walls; "open" must mean every tomb block is gone.
// getCurrentState used to read only the first offset point, so a wall whose first
// block happened to break (or shipped with air at index 0) reported "open" while
// isBlocking - which checks all points - still held the route back: ChangeState
// (tomb->open) completed early and the next step led into solid wall.
//
// needs -Pdg.configDir=<config dir> (read only), like RedstoneKeyVariantTest.
public class TombStateTest {

    @BeforeAll
    static void setup() throws Exception {
        TestRooms.boot();
    }

    private static class ForcedWorld implements DGWorld {
        private final DGWorld delegate;
        private final Set<BlockPos> airOverride = new HashSet<>();

        ForcedWorld(DGWorld delegate) {
            this.delegate = delegate;
        }

        public BlockState getBlock(int x, int y, int z) {
            if (airOverride.contains(new BlockPos(x, y, z))) return Blocks.AIR.defaultBlockState();
            return delegate.getBlock(x, y, z);
        }

        public boolean isInScope(int x, int y, int z) {
            return delegate.isInScope(x, y, z);
        }

        public int getMinX() { return delegate.getMinX(); }
        public int getMinY() { return delegate.getMinY(); }
        public int getMinZ() { return delegate.getMinZ(); }
        public int getMaxX() { return delegate.getMaxX(); }
        public int getMaxY() { return delegate.getMaxY(); }
        public int getMaxZ() { return delegate.getMaxZ(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return delegate.getBlockEntity(pos); }
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Test
    void tombOpenRequiresEveryBlockGone() throws Exception {
        int tombsChecked = 0, multiPointTombs = 0;
        for (RoomPreset roomPreset : TestRooms.defaultPreset().getPresets().values()) {
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null) continue;
            DungeonRoom room = TestRooms.buildRoom(roomPreset, info);
            for (Map.Entry<String, DungeonMechanicState> mech : room.getMechanics().entrySet()) {
                if (!(mech.getValue() instanceof DungeonTombState)) continue;
                DungeonTombState tomb = (DungeonTombState) mech.getValue();
                List<OffsetPoint> points = tomb.getData().getSecretPoint().getOffsetPointList();
                if (points.isEmpty()) continue;
                tombsChecked++;
                if (points.size() > 1) multiPointTombs++;

                // the reported state must always agree with isBlocking: any standing
                // tomb block means "closed"
                assertEquals(tomb.isBlocking(room) ? "closed" : "open", tomb.getCurrentState(),
                        info.getName() + " " + mech.getKey() + " state disagrees with isBlocking");

                // only the first point cleared: still a wall (single-block tombs are
                // unaffected - clearing their only point legitimately opens them)
                if (points.size() > 1) {
                    ForcedWorld partial = new ForcedWorld(room.getCachedWorld());
                    partial.airOverride.add(points.get(0).getBlockPos(room));
                    DungeonRoom partialRoom = TestRooms.buildRoom(roomPreset, info);
                    setField(partialRoom, "cachedWorld", partial);
                    DungeonTombState partialTomb =
                            (DungeonTombState) partialRoom.getMechanics().get(mech.getKey());
                    assertTrue(partialTomb.isBlocking(partialRoom),
                            info.getName() + " " + mech.getKey() + " still blocking with only first point cleared");
                    assertEquals("closed", partialTomb.getCurrentState(),
                            info.getName() + " " + mech.getKey()
                                    + " reported open with " + (points.size() - 1) + " tomb blocks standing");
                }

                // everything cleared: open
                ForcedWorld cleared = new ForcedWorld(room.getCachedWorld());
                for (OffsetPoint p : points) cleared.airOverride.add(p.getBlockPos(room));
                DungeonRoom clearedRoom = TestRooms.buildRoom(roomPreset, info);
                setField(clearedRoom, "cachedWorld", cleared);
                DungeonTombState clearedTomb =
                        (DungeonTombState) clearedRoom.getMechanics().get(mech.getKey());
                assertFalse(clearedTomb.isBlocking(clearedRoom));
                assertEquals("open", clearedTomb.getCurrentState(),
                        info.getName() + " " + mech.getKey() + " should be open once every block is gone");
            }
        }
        System.out.println("tomb check: " + tombsChecked + " tombs, " + multiPointTombs + " multi-block");
        assertTrue(tombsChecked > 0, "no tomb mechanics found in bundled rooms");
    }
}
