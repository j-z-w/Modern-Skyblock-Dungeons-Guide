package kr.syeyoung.dungeonsguide.mod.dungeon.data;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class LegacyBlockTableTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
    }

    @Test
    void legacyTableCoversAll4096() {
        BlockState[] table = LegacyBlock.stateTable();
        assertEquals(4096, table.length);

        int nulls = 0;
        Map<Integer, Integer> airById = new TreeMap<>();
        for (int legacy = 0; legacy < table.length; legacy++) {
            if (table[legacy] == null) {
                nulls++;
            } else if (table[legacy].isAir() && legacy >> 4 != 0) {
                airById.merge(legacy >> 4, 1, Integer::sum);
            }
        }
        System.out.println("legacy table: null entries=" + nulls);
        System.out.println("legacy table: legacy ids (count of metas) mapping to air: " + airById);
        assertEquals(0, nulls);
    }

    @Test
    void legacyBlockSanity() {
        assertTrue(LegacyBlock.STONE.is(Blocks.STONE.defaultBlockState()), "stone");
        assertTrue(LegacyBlock.STONE.is(Blocks.GRANITE.defaultBlockState()), "granite");
        assertTrue(LegacyBlock.STONE.is(Blocks.DIORITE.defaultBlockState()), "diorite");
        assertTrue(LegacyBlock.STONE.is(Blocks.ANDESITE.defaultBlockState()), "andesite");
        assertTrue(LegacyBlock.STAINED_HARDENED_CLAY.is(Blocks.BLUE_TERRACOTTA.defaultBlockState()), "blue terracotta");
        assertTrue(LegacyBlock.STAINED_HARDENED_CLAY.is(Blocks.RED_TERRACOTTA.defaultBlockState()), "red terracotta");
        assertTrue(LegacyBlock.WOOL.is(Blocks.WHITE_WOOL.defaultBlockState()), "white wool");
        assertTrue(LegacyBlock.WOOL.is(Blocks.LIME_WOOL.defaultBlockState()), "lime wool");
        assertTrue(LegacyBlock.SKULL.is(Blocks.SKELETON_SKULL.defaultBlockState()), "skeleton skull");
        assertTrue(LegacyBlock.SKULL.is(Blocks.WITHER_SKELETON_SKULL.defaultBlockState()), "wither skeleton skull");
        assertTrue(LegacyBlock.SKULL.is(Blocks.PLAYER_HEAD.defaultBlockState()), "player head");
        assertTrue(LegacyBlock.SKULL.is(Blocks.CREEPER_WALL_HEAD.defaultBlockState()), "creeper wall head");
        assertTrue(LegacyBlock.CHEST.is(Blocks.CHEST.defaultBlockState()), "chest");
        assertTrue(LegacyBlock.TRAPPED_CHEST.is(Blocks.TRAPPED_CHEST.defaultBlockState()), "trapped chest");
        assertTrue(LegacyBlock.LEVER.is(Blocks.LEVER.defaultBlockState()), "lever");
        assertTrue(LegacyBlock.STONE_BRICK_STAIRS.is(Blocks.STONE_BRICK_STAIRS.defaultBlockState()), "stone brick stairs");
        assertTrue(LegacyBlock.STONE_SLAB.is(Blocks.STONE_SLAB.defaultBlockState()), "stone slab");
        assertTrue(LegacyBlock.STONE_SLAB.is(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()), "smooth stone slab");
    }
}
