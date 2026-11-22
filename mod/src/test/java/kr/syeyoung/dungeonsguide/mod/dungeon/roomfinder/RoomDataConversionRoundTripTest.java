package kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class RoomDataConversionRoundTripTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
    }

    private static int[][] copy(int[][] arr) {
        int[][] copy = new int[arr.length][];
        for (int i = 0; i < arr.length; i++) copy[i] = arr[i].clone();
        return copy;
    }

    @Test
    void conversionRoundTrips() throws Exception {
        CBORMapper objectMapper = new CBORMapper();
        BlockState[] table = LegacyBlock.stateTable();

        // states produced by more than one legacy id can't round-trip unambiguously (still and flowing
        // water/lava share states now); those mismatches are collisions to report, not failures
        Map<BlockState, Integer> producer = new HashMap<>();
        Set<BlockState> ambiguous = new HashSet<>();
        for (int legacy = 0; legacy < table.length; legacy++) {
            BlockState state = table[legacy];
            if (state == null || state.isAir()) continue;
            Integer first = producer.putIfAbsent(state, legacy >> 4);
            if (first != null && first != legacy >> 4) ambiguous.add(state);
        }

        Map<Integer, int[]> worldStats = new TreeMap<>(); // legacy id -> [total, meta mismatch, id mismatch]
        Map<Integer, int[]> collisions = new TreeMap<>(); // legacy id -> [blocks layer, world layer] ambiguous hits
        long blocksTotal = 0, worldTotal = 0;
        int roomsChecked = 0;

        List<String> lines = IOUtils.readLines(DungeonsGuide.class.getResourceAsStream("/roomdata/datas.txt"));
        for (String name : lines) {
            if (!name.endsWith(".roomdata.cbor")) continue;
            DungeonRoomInfo dri;
            try (InputStream is = DungeonsGuide.class.getResourceAsStream("/" + name)) {
                dri = objectMapper.readValue(is, DungeonRoomInfo.class);
            }
            assertEquals(1, dri.getFormatVersion(), name + " should still be 1.8.9 data on disk");

            int[][] origBlocks = copy(dri.getBlocks());
            char[] origWorld = dri.getWorld().clone();

            RoomDataConverter.convert(dri, table);
            assertEquals(2, dri.getFormatVersion(), name);

            // the matching layer: legacy ids without meta must map back to the same id
            for (int i = 0; i < origBlocks.length; i++) {
                for (int j = 0; j < origBlocks[i].length; j++) {
                    int origId = origBlocks[i][j];
                    if (origId == -1) continue;
                    blocksTotal++;
                    BlockState state = dri.getPaletteState(dri.getBlocks()[i][j]);
                    assertNotNull(state, name + " blocks[" + i + "][" + j + "]");
                    int roundTripped = LegacyBlock.legacyId(state);
                    if (roundTripped != origId && ambiguous.contains(state)) {
                        collisions.computeIfAbsent(origId, k -> new int[2])[0]++;
                        continue;
                    }
                    assertEquals(origId, roundTripped,
                            name + " blocks[" + i + "][" + j + "] legacy id " + origId + " -> " + state + " -> " + roundTripped);
                }
            }

            // the world layer: full legacy value, meta mismatches get reported not failed
            for (int i = 0; i < origWorld.length; i++) {
                int orig = origWorld[i] & 4095;
                worldTotal++;
                int index = dri.getWorld()[i];
                BlockState state = index >= 0 && index < dri.getPalette().size() ? dri.getPaletteState(index) : null;
                assertNotNull(state, name + " world[" + i + "] palette index " + index);
                int roundTripped = LegacyBlock.legacy(state);
                int[] stats = worldStats.computeIfAbsent(orig >> 4, k -> new int[3]);
                stats[0]++;
                if (roundTripped >> 4 != orig >> 4) {
                    if (ambiguous.contains(state)) collisions.computeIfAbsent(orig >> 4, k -> new int[2])[1]++;
                    else stats[2]++;
                } else if ((roundTripped & 15) != (orig & 15)) stats[1]++;
            }
            roomsChecked++;
        }

        System.out.println("checked " + roomsChecked + " rooms, " + blocksTotal + " blocks entries, " + worldTotal + " world entries");
        System.out.println("world layer per legacy id: id -> [total, meta mismatches, id mismatches]");
        for (Map.Entry<Integer, int[]> entry : worldStats.entrySet()) {
            if (entry.getValue()[1] == 0 && entry.getValue()[2] == 0) continue;
            System.out.println("  " + entry.getKey() + " -> " + java.util.Arrays.toString(entry.getValue()));
        }
        long idMismatches = worldStats.values().stream().mapToLong(s -> s[2]).sum();
        long metaMismatches = worldStats.values().stream().mapToLong(s -> s[1]).sum();
        System.out.println("world layer: " + metaMismatches + " meta mismatches, " + idMismatches + " id mismatches");
        System.out.println("ambiguous-state collisions per legacy id: id -> [blocks layer, world layer]");
        for (Map.Entry<Integer, int[]> entry : collisions.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + java.util.Arrays.toString(entry.getValue()));
        }
        assertEquals(0, idMismatches, "world layer unambiguous id mismatches");
    }
}
