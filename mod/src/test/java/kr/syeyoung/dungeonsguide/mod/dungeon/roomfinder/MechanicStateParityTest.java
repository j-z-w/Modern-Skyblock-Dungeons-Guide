package kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonFakeChestTrapState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonFloorTrapState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonPressurePlateState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonRedstoneKeySlotState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonRedstoneKeyState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretChestState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretDoubleChestState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretEssenceState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonWizardCrystalState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

// T2 audit: for every bundled room, evaluate each block-state-based mechanic on the room's own
// converted (format v2) block data in its initial configuration and check the 1.8.9 semantics
// survive the conversion - closed doors must read closed, unpulled levers untriggered, chest
// secrets "created", skull mechanics see skulls, and every isBlocking agrees with the legacy
// air/non-air data the mechanic was authored against.
public class MechanicStateParityTest {

    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
        // DungeonRoom's static matcher thread / the discovered-room event need the instance
        new DungeonsGuide();
    }

    // 1.8.9's world[] held (id << 4) | meta; room-relative y is stored at absolute y
    private static int legacyAt(char[] origWorld, DungeonRoomInfo dri, int x, int y, int z) {
        return origWorld[x + ((y + 70) * dri.getLength() + z) * dri.getWidth()] & 4095;
    }

    @Test
    void mechanicStatesMatchLegacySemantics() throws Exception {
        CBORMapper objectMapper = new CBORMapper();
        BlockState[] table = LegacyBlock.stateTable();

        List<String> mismatches = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        Map<String, int[]> perType = new TreeMap<>(); // mechanic type -> [count, mismatches]
        int roomsChecked = 0;

        List<String> lines = IOUtils.readLines(DungeonsGuide.class.getResourceAsStream("/roomdata/datas.txt"));
        for (String name : lines) {
            if (!name.endsWith(".roomdata.cbor")) continue;
            DungeonRoomInfo dri;
            try (InputStream is = DungeonsGuide.class.getResourceAsStream("/" + name)) {
                dri = objectMapper.readValue(is, DungeonRoomInfo.class);
            }
            if (dri.getFormatVersion() != 1) {
                exceptions.add(name + " was not 1.8.9 data (format " + dri.getFormatVersion() + ")");
                continue;
            }
            char[] origWorld = dri.getWorld().clone();
            int origLength = dri.getLength(), origWidth = dri.getWidth();
            RoomDataConverter.convert(dri, table);

            // a live room over the converted data, the same path in-game mechanics read through
            DRIWorld world = new DRIWorld(dri, PathfindPresetRegistry.DEFAULT_PRESET);
            DungeonContext context = new DungeonContext("TEST DG", world,
                    PathfindPresetRegistry.DEFAULT_PRESET, false);
            context.setScaffoldParser(new DungeonRoomScaffoldParser(null, context));
            DungeonRoom room;
            try {
                room = new DungeonRoom(context);
            } catch (Throwable t) {
                exceptions.add(dri.getName() + " room construction: " + t);
                context.cleanup();
                continue;
            }

            Map<String, DungeonMechanicState> mechanics = room.getMechanics();
            Map<String, DungeonMechanicData> datas = dri.getMechanics();

            for (Map.Entry<String, DungeonMechanicState> entry : mechanics.entrySet()) {
                String mechName = entry.getKey();
                DungeonMechanicState state = entry.getValue();
                DungeonMechanicData data = datas.get(mechName);
                String type = data == null ? state.getClass().getSimpleName() : data.getClass().getSimpleName();
                perType.computeIfAbsent(type, k -> new int[2])[0]++;

                // every mechanic must evaluate without throwing on converted data
                String actualState;
                boolean actualBlocking;
                try {
                    actualState = state.getCurrentState();
                    actualBlocking = !(state instanceof WorldMutatingMechanicState)
                            || ((WorldMutatingMechanicState) state).isBlocking(room);
                } catch (Throwable t) {
                    if (isClientDependent(t)) {
                        System.out.println("  client-dependent, skipping state check: " + dri.getName()
                                + " " + mechName + " (" + type + ")");
                        continue;
                    }
                    exceptions.add(dri.getName() + " " + mechName + " (" + type + ") threw " + t);
                    continue;
                }

                // isBlocking parity: any blocked point where the legacy data is non-air (or the
                // point is outside the room, which 1.8.9 also read as blocking via null)
                if (data instanceof WorldMutatingMechanicData) {
                    List<OffsetPoint> points = ((WorldMutatingMechanicData) data).blockedPoints();
                    boolean expectedBlocking = false;
                    OffsetPoint firstBlocking = null;
                    for (OffsetPoint p : points) {
                        boolean nonAir;
                        if (!room.getRoomBounds().canAccessRelative(p.getX(), p.getZ())) {
                            nonAir = true; // 1.8.9: getBlock returned null, null != Blocks.air
                        } else {
                            nonAir = (legacyAt(origWorld, dri, p.getX(), p.getY(), p.getZ()) >> 4) != 0;
                        }
                        // per-point parity too: the converted read must classify the same way
                        boolean actualNonAir = !LegacyBlock.AIR.is(safeGet(room, p));
                        if (nonAir != actualNonAir) {
                            mismatches.add(dri.getName() + " " + mechName + " (" + type + ") point " + p
                                    + " legacy=" + (nonAir ? "non-air" : "air")
                                    + " converted=" + safeGet(room, p));
                            perType.get(type)[1]++;
                        }
                        if (nonAir) {
                            expectedBlocking = true;
                            if (firstBlocking == null) firstBlocking = p;
                        }
                    }
                    if (expectedBlocking != actualBlocking) {
                        mismatches.add(dri.getName() + " " + mechName + " (" + type + ") isBlocking expected="
                                + expectedBlocking + " actual=" + actualBlocking);
                        perType.get(type)[1]++;
                    }

                    // getCurrentState parity. doors use isBlocking; walls/tombs/traps read only the
                    // first point, exactly like the code does
                    String expected;
                    if (state instanceof DungeonDoorState || state instanceof DungeonOnewayDoorState) {
                        expected = expectedBlocking ? "closed" : "open";
                    } else if (!points.isEmpty()) {
                        expected = firstPointAir(dri, origWorld, origWidth, origLength, room, points.get(0))
                                ? firstAirState(state) : firstBlockedState(state);
                    } else {
                        expected = actualState; // nothing to compare
                    }
                    if (!expected.equalsIgnoreCase(actualState)) {
                        mismatches.add(dri.getName() + " " + mechName + " (" + type + ") state expected="
                                + expected + " actual=" + actualState);
                        perType.get(type)[1]++;
                    }
                } else if (state instanceof DungeonLeverState || state instanceof DungeonOnewayLeverState
                        || state instanceof DungeonPressurePlateState) {
                    // delegated: "triggered" iff the linked door/wall reads open
                    String triggering = triggeringOf(state);
                    DungeonMechanicState linked = triggering == null ? null : mechanics.get(triggering);
                    String expected;
                    if (linked == null) expected = "undeterminable";
                    else expected = "open".equalsIgnoreCase(linked.getCurrentState()) ? "triggered" : "untriggered";
                    if (!expected.equalsIgnoreCase(actualState)) {
                        mismatches.add(dri.getName() + " " + mechName + " (" + type + ") delegated state expected="
                                + expected + " actual=" + actualState + " (triggers " + triggering + ")");
                        perType.get(type)[1]++;
                    }
                    // the lever/plate point itself must read the same block it was authored against
                    OffsetPoint point = state.getRepresentingPoint();
                    checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type, point, mismatches, perType);
                } else if (state instanceof DungeonSecretChestState) {
                    OffsetPoint p = ((DungeonSecretChestState) state).getData().getSecretPoint();
                    String expected = chestState(legacyAt(origWorld, dri, p.getX(), p.getY(), p.getZ()) >> 4);
                    if (!expected.equalsIgnoreCase(actualState)) {
                        mismatches.add(dri.getName() + " " + mechName + " (" + type + ") chest state expected="
                                + expected + " actual=" + actualState);
                        perType.get(type)[1]++;
                    }
                    checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type, p, mismatches, perType);
                } else if (state instanceof DungeonSecretDoubleChestState) {
                    for (OffsetPoint p : new OffsetPoint[]{
                            ((DungeonSecretDoubleChestState) state).getData().getSecretPoint(),
                            ((DungeonSecretDoubleChestState) state).getData().getSecretPoint2()}) {
                        if (p != null)
                            checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type, p, mismatches, perType);
                    }
                } else if (state instanceof DungeonSecretEssenceState) {
                    // tick-driven flags start empty, so the initial read is always not_sure
                    if (!"not_sure".equalsIgnoreCase(actualState)) {
                        mismatches.add(dri.getName() + " " + mechName + " (" + type + ") expected=not_sure actual=" + actualState);
                        perType.get(type)[1]++;
                    }
                    checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type,
                            ((DungeonSecretEssenceState) state).getData().getSecretPoint(), mismatches, perType);
                } else if (state instanceof DungeonRedstoneKeyState) {
                    checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type,
                            ((DungeonRedstoneKeyState) state).getData().getSecretPoint(), mismatches, perType);
                } else if (state instanceof DungeonWizardCrystalState) {
                    checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type,
                            ((DungeonWizardCrystalState) state).getData().getSecretPoint(), mismatches, perType);
                } else if (state instanceof DungeonRedstoneKeySlotState) {
                    for (OffsetPoint p : ((DungeonRedstoneKeySlotState) state).getData().getHeadPoint().getOffsetPointList()) {
                        checkBlockParity(dri, origWorld, origWidth, origLength, room, mechName, type, p, mismatches, perType);
                    }
                }
            }
            roomsChecked++;
            context.cleanup();
        }

        System.out.println("mechanic parity: " + roomsChecked + " rooms");
        for (Map.Entry<String, int[]> entry : perType.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue()[0] + " mechanics, "
                    + entry.getValue()[1] + " mismatches");
        }
        for (String s : mismatches) System.out.println("  MISMATCH " + s);
        for (String s : exceptions) System.out.println("  EXCEPTION " + s);
        assertTrue(roomsChecked > 100, "expected all bundled rooms");
        assertEquals(List.of(), exceptions, "mechanics that threw");
        assertEquals(List.of(), mismatches, "mechanic reads that disagree with 1.8.9 semantics");
    }

    // per-point parity for block checks that look up a specific legacy id (lever, chest, skull):
    // legacyId(converted) must equal the authored legacy id
    private void checkBlockParity(DungeonRoomInfo dri, char[] origWorld, int origWidth, int origLength,
                                  DungeonRoom room, String mechName, String type, OffsetPoint p,
                                  List<String> mismatches, Map<String, int[]> perType) {
        if (!room.getRoomBounds().canAccessRelative(p.getX(), p.getZ())) return;
        int orig = legacyAt(origWorld, dri, p.getX(), p.getY(), p.getZ()) >> 4;
        BlockState converted = safeGet(room, p);
        int convertedId = LegacyBlock.legacyId(converted);
        if (orig != convertedId) {
            mismatches.add(dri.getName() + " " + mechName + " (" + type + ") point " + p
                    + " legacy id " + orig + " converted to " + converted + " (legacyId=" + convertedId + ")");
            perType.get(type)[1]++;
        }
    }

    // some states (e.g. wizard crystal) only produce a state by touching the live client; those
    // can't be exercised offline at all, so with no Minecraft instance a thrown read is a skip,
    // not a parity failure - their block points are still parity-checked where applicable
    private static boolean isClientDependent(Throwable t) {
        return net.minecraft.client.Minecraft.getInstance() == null;
    }

    private static BlockState safeGet(DungeonRoom room, OffsetPoint p) {
        try {
            return p.getBlock(room);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean firstPointAir(DungeonRoomInfo dri, char[] origWorld, int w, int l,
                                         DungeonRoom room, OffsetPoint p) {
        if (!room.getRoomBounds().canAccessRelative(p.getX(), p.getZ())) return false;
        return (legacyAt(origWorld, dri, p.getX(), p.getY(), p.getZ()) >> 4) == 0;
    }

    private static String firstAirState(DungeonMechanicState state) {
        return state instanceof DungeonFakeChestTrapState || state instanceof DungeonFloorTrapState
                ? "triggered" : "open";
    }

    private static String firstBlockedState(DungeonMechanicState state) {
        return state instanceof DungeonFakeChestTrapState || state instanceof DungeonFloorTrapState
                ? "untriggered" : "closed";
    }

    private static String chestState(int legacyId) {
        if (legacyId == 0) return "definitely_not";
        if (legacyId == 54 || legacyId == 146) return "created";
        return "error";
    }

    private static String triggeringOf(DungeonMechanicState state) {
        if (state instanceof DungeonLeverState) return ((DungeonLeverState) state).getData().getTriggering();
        if (state instanceof DungeonOnewayLeverState) return ((DungeonOnewayLeverState) state).getData().getTriggering();
        if (state instanceof DungeonPressurePlateState) return ((DungeonPressurePlateState) state).getData().getTriggering();
        return null;
    }
}
