package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// The bundled `redstone-key` room models the single Hypixel key as two alternative
// spawn points (redstone-key / redstone-key-2) feeding one key-slot whose
// preRequisite hardcodes `redstone-key:obtained-self`. Before the fix, a variant
// whose head was absent reported `obtained-other`, so Smart Route demanded the
// impossible variant and stalled on a bare ActionChangeState that no display
// engine rendered. These tests pin the corrected semantics: absent head with a
// standing sibling head is `not-spawned` (never had a key), absent head with no
// sibling heads is `obtained-other` (someone took the one key that spawned), and
// the slot ORs over only the variants we can still pick up ourselves.
//
// needs -Pdg.configDir=<config dir> (read only), like SmartRouteRegressionTest.
public class RedstoneKeyVariantTest {
    private static DungeonRoomInfo info;
    private static RoomPreset roomPreset;
    private static AlgorithmSetting setting;

    @BeforeAll
    static void setup() throws Exception {
        TestRooms.boot();
        for (DungeonRoomInfo candidate : DungeonRoomInfoRegistry.getRegistered()) {
            if ("redstone-key".equals(candidate.getName())) {
                info = candidate;
                break;
            }
        }
        assertNotNull(info, "bundled redstone-key room not found");
        roomPreset = TestRooms.defaultPreset().getRoomPreset(info.getUuid());
        assertNotNull(roomPreset, "default preset has no room preset for redstone-key");
        setting = roomPreset.getEffectiveAlgorithmSetting(info);
    }

    // same pattern as SmartRouteRegressionTest.ForcedWorld: per-position block
    // overrides on top of the room's DRIWorld
    private static class ForcedWorld implements DGWorld {
        private final DGWorld delegate;
        private final Map<BlockPos, BlockState> blockOverride = new HashMap<>();

        ForcedWorld(DGWorld delegate) {
            this.delegate = delegate;
        }

        public BlockState getBlock(int x, int y, int z) {
            BlockState s = blockOverride.get(new BlockPos(x, y, z));
            return s != null ? s : delegate.getBlock(x, y, z);
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

    private static DungeonRoom roomWithBlocks(Map<BlockPos, BlockState> overrides) throws Exception {
        DungeonRoom room = TestRooms.buildRoom(roomPreset, info);
        ForcedWorld forced = new ForcedWorld(room.getCachedWorld());
        forced.blockOverride.putAll(overrides);
        setField(room, "cachedWorld", forced);
        return room;
    }

    private static DungeonRoom roomWith(boolean key1Head, boolean key2Head, boolean slotHead) throws Exception {
        DungeonRoom room = roomWithBlocks(new HashMap<>());
        Map<BlockPos, BlockState> overrides = new HashMap<>();
        overrides.put(headPos(room, "redstone-key"), head(key1Head));
        overrides.put(headPos(room, "redstone-key-2"), head(key2Head));
        overrides.put(slotHeadPos(room), head(slotHead));
        ForcedWorld forced = (ForcedWorld) room.getCachedWorld();
        forced.blockOverride.putAll(overrides);
        return room;
    }

    private static BlockState head(boolean present) {
        return present ? Blocks.PLAYER_HEAD.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static BlockPos headPos(DungeonRoom room, String name) {
        return ((DungeonRedstoneKeyState) room.getMechanics().get(name))
                .getData().getSecretPoint().getBlockPos(room);
    }

    private static BlockPos slotHeadPos(DungeonRoom room) {
        return ((DungeonRedstoneKeySlotState) room.getMechanics().get("key-slot"))
                .getData().getHeadPoint().getOffsetPointList().get(0).getBlockPos(room);
    }

    private static String state(DungeonRoom room, String mechanic) {
        return room.getMechanics().get(mechanic).getCurrentState();
    }

    private static ActionDAGNode findChangeState(ActionDAG dag, String mechanicName, String state) {
        for (ActionDAGNode node : dag.getAllNodes()) {
            AbstractAction a = node.getAction();
            if (a instanceof ActionChangeState
                    && mechanicName.equals(((ActionChangeState) a).getMechanicName())
                    && state.equalsIgnoreCase(((ActionChangeState) a).getState())) {
                return node;
            }
        }
        return null;
    }

    private static List<AbstractActionMove> movesOf(ActionDAGNode node) {
        List<AbstractActionMove> out = new ArrayList<>();
        AbstractAction a = node.getAction();
        if (a instanceof AtomicAction) {
            for (AbstractAction inner : ((AtomicAction) a).getActions())
                if (inner instanceof AbstractActionMove) out.add((AbstractActionMove) inner);
        } else if (a instanceof AbstractActionMove) {
            out.add((AbstractActionMove) a);
        }
        return out;
    }

    // same requirements as FeatureSmartRoute.createSmartRoute
    private static ActionDAG buildSmartRouteDAG(DungeonRoom room) {
        ActionDAGBuilder builder = new ActionDAGBuilder(room);
        for (Map.Entry<String, DungeonMechanicState> value : room.getMechanics().entrySet()) {
            if (value.getValue() instanceof ISecret && !((ISecret) value.getValue()).isFound(room)) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "found"), setting);
                } catch (PathfindImpossibleException e) {
                    System.out.println("  requires failed: " + value.getKey() + ": " + e.getMessage());
                }
            } else if (value.getValue() instanceof DungeonRedstoneKeyState
                    && value.getValue().getCurrentState().equalsIgnoreCase("unobtained")) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "obtained-self"), setting);
                } catch (PathfindImpossibleException e) {
                    System.out.println("  requires failed: " + value.getKey() + ": " + e.getMessage());
                }
            }
        }
        return builder.build();
    }

    // ---------------- state semantics ----------------

    @Test
    void variantTwoSpawned() throws Exception {
        DungeonRoom room = roomWith(false, true, false);
        assertEquals("not-spawned", state(room, "redstone-key"));
        assertEquals("unobtained", state(room, "redstone-key-2"));
        assertEquals("untriggered", state(room, "key-slot"));
        assertEquals(Set.of("navigate"), room.getMechanics().get("redstone-key").getAvailableActions());
    }

    @Test
    void variantOneSpawned() throws Exception {
        DungeonRoom room = roomWith(true, false, false);
        assertEquals("unobtained", state(room, "redstone-key"));
        assertEquals("not-spawned", state(room, "redstone-key-2"));
    }

    @Test
    void noHeadAnywhereIsObtainedOther() throws Exception {
        // the single key necessarily spawned somewhere; all heads gone + we didn't
        // click it means someone else picked it up
        DungeonRoom room = roomWith(false, false, false);
        assertEquals("obtained-other", state(room, "redstone-key"));
        assertEquals("obtained-other", state(room, "redstone-key-2"));
    }

    @Test
    void selfClickBeatsAbsentHead() throws Exception {
        DungeonRoom room = roomWith(false, false, false);
        ((DungeonRedstoneKeyState) room.getMechanics().get("redstone-key")).setDidClickOnRedstoneKey(true);
        assertEquals("obtained-self", state(room, "redstone-key"));
        assertEquals("obtained-other", state(room, "redstone-key-2"));
    }

    @Test
    void slotTriggeredMeansPlaced() throws Exception {
        DungeonRoom room = roomWith(false, false, true);
        assertEquals("triggered", state(room, "key-slot"));
        assertEquals("placed", state(room, "redstone-key"));
        assertEquals("placed", state(room, "redstone-key-2"));
    }

    // ---------------- slot prerequisite ----------------

    // upstream attaches the key requirement under the slot's MoveAndClick atomic
    // (the `builder` variable is repointed by requires()), so the variant OR lives
    // on that atomic node
    private static ActionDAGNode slotMoveAndClickNode(ActionDAG dag) {
        ActionDAGNode slot = findChangeState(dag, "key-slot", "triggered");
        assertNotNull(slot, "key-slot:triggered node");
        return slot.getRequire().stream()
                .filter(n -> n.getAction() instanceof AtomicAction)
                .findFirst()
                .orElseThrow(() -> new AssertionError("slot lost its MoveAndClick requirement"));
    }

    private static ActionDAGNode requiredChangeState(ActionDAGNode node, String mechanicName, String state) {
        for (ActionDAGNode req : node.getRequire()) {
            AbstractAction a = req.getAction();
            if (a instanceof ActionChangeState
                    && mechanicName.equals(((ActionChangeState) a).getMechanicName())
                    && state.equalsIgnoreCase(((ActionChangeState) a).getState())) {
                return req;
            }
        }
        return null;
    }

    @Test
    void slotRequiresVariantTwoWhenPresent() throws Exception {
        DungeonRoom room = roomWith(false, true, false);
        ActionDAG dag = new ActionDAGBuilder(room)
                .requires(new ActionChangeState("key-slot", "triggered"), setting)
                .getRoot().build();
        ActionDAGNode moveAndClick = slotMoveAndClickNode(dag);
        assertNotNull(requiredChangeState(moveAndClick, "redstone-key-2", "obtained-self"),
                "slot should require the obtainable variant");
        // the spawn-empty variant must never appear as a required state anywhere
        assertNull(findChangeState(dag, "redstone-key", "obtained-self"));
    }

    @Test
    void slotRequiresVariantOneWhenPresent() throws Exception {
        DungeonRoom room = roomWith(true, false, false);
        ActionDAG dag = new ActionDAGBuilder(room)
                .requires(new ActionChangeState("key-slot", "triggered"), setting)
                .getRoot().build();
        ActionDAGNode moveAndClick = slotMoveAndClickNode(dag);
        assertNotNull(requiredChangeState(moveAndClick, "redstone-key", "obtained-self"),
                "slot should require the obtainable variant");
        assertNull(findChangeState(dag, "redstone-key-2", "obtained-self"));
    }

    @Test
    void slotKeepsMoveGuidanceWhenNoKeyObtainable() throws Exception {
        // teammate picked the key: slot can't require any :obtained-self, but must
        // keep its move-and-click child so the route still leads the player there
        DungeonRoom room = roomWith(false, false, false);
        ActionDAG dag = new ActionDAGBuilder(room)
                .requires(new ActionChangeState("key-slot", "triggered"), setting)
                .getRoot().build();
        ActionDAGNode moveAndClick = slotMoveAndClickNode(dag);
        assertNull(requiredChangeState(moveAndClick, "redstone-key", "obtained-self"));
        assertNull(requiredChangeState(moveAndClick, "redstone-key-2", "obtained-self"));
    }

    @Test
    void slotPicksFirstViableWhenSeveralObtainable() throws Exception {
        // degenerate both-heads world (the bundled schematic): require exactly one
        // variant - several obtainable keys as AND-requires stay consistent with
        // the solver's single-alternative rule
        DungeonRoom room = roomWith(true, true, false);
        ActionDAG dag = new ActionDAGBuilder(room)
                .requires(new ActionChangeState("key-slot", "triggered"), setting)
                .getRoot().build();
        ActionDAGNode moveAndClick = slotMoveAndClickNode(dag);
        boolean v1 = requiredChangeState(moveAndClick, "redstone-key", "obtained-self") != null;
        boolean v2 = requiredChangeState(moveAndClick, "redstone-key-2", "obtained-self") != null;
        assertTrue(v1 ^ v2, "exactly one variant should be required, v1=" + v1 + " v2=" + v2);
    }

    // ---------------- smart route regression ----------------

    @Test
    void smartRouteTargetsPresentVariant() throws Exception {
        DungeonRoom room = roomWith(false, true, false);
        ActionDAG dag = buildSmartRouteDAG(room);
        assertNull(findChangeState(dag, "redstone-key", "obtained-self"),
                "route must never demand the variant that did not spawn");
        ActionDAGNode key2 = findChangeState(dag, "redstone-key-2", "obtained-self");
        assertNotNull(key2, "route should demand the obtainable variant");
        // and it must carry movement guidance, not be a bare ChangeState
        assertTrue(key2.getAllChildren().stream().anyMatch(n -> !movesOf(n).isEmpty()),
                "redstone-key-2:obtained-self should have a move action under it");
    }
}
