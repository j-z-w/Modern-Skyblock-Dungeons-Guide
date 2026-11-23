package kr.syeyoung.dungeonsguide.mod.dungeon.data;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
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
        // only ids 1.8.9 never had may come out as air, anything else means the flattening/DataFixer broke
        assertEquals(Set.of(253, 254), airById.keySet());
    }

    @Test
    void legacyBlockSanity() {
        assertTrue(LegacyBlock.STONE.is(Blocks.STONE.defaultBlockState()), "stone");
        assertTrue(LegacyBlock.STONE.is(Blocks.GRANITE.defaultBlockState()), "granite");
        assertTrue(LegacyBlock.STONE.is(Blocks.DIORITE.defaultBlockState()), "diorite");
        assertTrue(LegacyBlock.STONE.is(Blocks.ANDESITE.defaultBlockState()), "andesite");
        assertTrue(LegacyBlock.STAINED_HARDENED_CLAY.is(Blocks.DYED_TERRACOTTA.blue().defaultBlockState()), "blue terracotta");
        assertTrue(LegacyBlock.STAINED_HARDENED_CLAY.is(Blocks.DYED_TERRACOTTA.red().defaultBlockState()), "red terracotta");
        assertTrue(LegacyBlock.WOOL.is(Blocks.WOOL.white().defaultBlockState()), "white wool");
        assertTrue(LegacyBlock.WOOL.is(Blocks.WOOL.lime().defaultBlockState()), "lime wool");
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
        // meta matters for the .pfreq files the precalc generator reads
        assertEquals(8, LegacyBlock.meta(Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)), "upper stone slab");
        assertEquals(0, LegacyBlock.meta(Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)), "lower stone slab");
    }

    // every state Hypixel (through ViaVersion) can send for these blocks, not just default states. Matched by
    // block id / class so it keeps working when blocks get merged, e.g. colored terracotta -> dyed_terracotta in 26.2
    @Test
    void legacyBlockMatrixCoversAllStates() {
        for (Block block : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            LegacyBlock expected;
            if (path.equals("terracotta")) expected = LegacyBlock.HARDENED_CLAY;
            else if (path.endsWith("terracotta") && !path.contains("glazed")) expected = LegacyBlock.STAINED_HARDENED_CLAY;
            else if (block instanceof AbstractSkullBlock) expected = LegacyBlock.SKULL;
            else if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) expected = LegacyBlock.AIR;
            else if (block == Blocks.IRON_BARS) expected = LegacyBlock.IRON_BARS;
            else if (block == Blocks.CHEST) expected = LegacyBlock.CHEST;
            else if (block == Blocks.TRAPPED_CHEST) expected = LegacyBlock.TRAPPED_CHEST;
            else if (block == Blocks.LEVER) expected = LegacyBlock.LEVER;
            else if (block == Blocks.STONE_BRICK_STAIRS) expected = LegacyBlock.STONE_BRICK_STAIRS;
            else continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                assertTrue(expected.is(state), expected + " should match " + state);
            }
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            int expectedId;
            if (block instanceof FlowerPotBlock) expectedId = 140;
            else if (block instanceof WallBannerBlock) expectedId = 177;
            else if (block instanceof BannerBlock) expectedId = 176;
            else continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                assertEquals(expectedId, LegacyBlock.legacyId(state), state.toString());
            }
        }
    }
}
