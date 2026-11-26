/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026  cyoung06 (syeyoung)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.dungeon.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
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

import java.util.HashMap;
import java.util.Map;

/**
 * 1.8.9's blocks as groups of 26.1 block states (D110 / D112). In 1.8.9 `block == Blocks.stone` also matched granite,
 * so code checks keep that meaning through these instead of comparing 26.1 blocks.
 * The table is built at runtime by RoomDataConverter.buildLegacyTable from vanilla's flattening data, so it stays
 * valid when block state names change between versions. dgdebug convertroomdata can still dump it as json.
 */
@AllArgsConstructor
public enum LegacyBlock {
    AIR(0), STONE(1), PLANKS(5), BEDROCK(7), SPONGE(19), DISPENSER(23), WOOL(35), DOUBLE_STONE_SLAB(43), STONE_SLAB(44),
    TNT(46), BOOKSHELF(47), CHEST(54), STANDING_SIGN(63), WALL_SIGN(68), LEVER(69), WOODEN_PRESSURE_PLATE(72),
    STONE_BUTTON(77), SNOW(80), CLAY(82), MONSTER_EGG(97), STONEBRICK(98), IRON_BARS(101), STONE_BRICK_STAIRS(109),
    END_PORTAL_FRAME(120), COMMAND_BLOCK(137), SKULL(144), TRAPPED_CHEST(146), LIGHT_WEIGHTED_PRESSURE_PLATE(147),
    QUARTZ_ORE(153), DROPPER(158), STAINED_HARDENED_CLAY(159), STAINED_GLASS_PANE(160), BARRIER(166), PRISMARINE(168),
    SEA_LANTERN(169), CARPET(171), HARDENED_CLAY(172), COAL_BLOCK(173), STONE_SLAB2(182);

    @Getter
    private final int legacyId;

    private static final Map<Integer, LegacyBlock> BY_ID = new HashMap<>();
    static {
        for (LegacyBlock value : values()) BY_ID.put(value.legacyId, value);
    }

    // 1.8.9 ids that only matter for the aliases in load()
    private static final int FLOWER_POT = 140, STANDING_BANNER = 176, WALL_BANNER = 177;

    // legacy id << 4 | meta for each 26.1 state, and the state for each legacy id << 4 | meta
    private static Map<BlockState, Integer> legacyByState;
    private static Map<Block, Integer> legacyByBlock;
    // written last, its volatile write publishes the two maps (every lookup goes through load())
    private static volatile BlockState[] stateByLegacy;

    private static void load() {
        if (stateByLegacy == null) loadTable();
    }

    private static synchronized void loadTable() {
        if (stateByLegacy != null) return;
        long start = System.currentTimeMillis();
        BlockState[] byLegacy = RoomDataConverter.buildLegacyTable();
        Map<BlockState, Integer> byState = new HashMap<>();
        Map<Block, Integer> byBlock = new HashMap<>();
        for (int legacy = 0; legacy < byLegacy.length; legacy++) {
            BlockState state = byLegacy[legacy];
            // ids / metas 1.8.9 didn't have came out as air, they don't map back
            if (state == null || (state.isAir() && legacy >> 4 != 0)) continue;
            byState.putIfAbsent(state, legacy);
            byBlock.putIfAbsent(state.getBlock(), legacy);
        }
        // 1.8.9's meta 0 stone slab flattened to smooth_stone_slab; the plain stone_slab readded later is the same block
        byBlock.putIfAbsent(Blocks.STONE_SLAB, STONE_SLAB.legacyId << 4);
        for (BlockState state : Blocks.STONE_SLAB.getStateDefinition().getPossibleStates()) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            // 1.8.9 meta 8 is the upper half
            byState.putIfAbsent(state, type == SlabType.DOUBLE ? DOUBLE_STONE_SLAB.legacyId << 4
                    : STONE_SLAB.legacyId << 4 | (type == SlabType.TOP ? 8 : 0));
        }
        // the skull type lived in the 1.8.9 block entity, so block == Blocks.skull matched every skull type;
        // the potted plant and the banner colour too, flattening only produces the skeleton skull / empty pot / white banner
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof AbstractSkullBlock) byBlock.putIfAbsent(block, SKULL.legacyId << 4);
            else if (block instanceof FlowerPotBlock) byBlock.putIfAbsent(block, FLOWER_POT << 4);
            else if (block instanceof WallBannerBlock) byBlock.putIfAbsent(block, WALL_BANNER << 4);
            else if (block instanceof BannerBlock) byBlock.putIfAbsent(block, STANDING_BANNER << 4);
        }
        // 1.8.9 had a single air
        byBlock.putIfAbsent(Blocks.CAVE_AIR, 0);
        byBlock.putIfAbsent(Blocks.VOID_AIR, 0);
        legacyByState = byState;
        legacyByBlock = byBlock;
        stateByLegacy = byLegacy;
        System.out.println("Building the legacy block table took " + (System.currentTimeMillis() - start) + "ms");
    }

    // the converted state for each legacy id << 4 | meta, reused by RoomDataConverter so it's built only once
    public static BlockState[] stateTable() {
        load();
        return stateByLegacy;
    }

    public static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad block state " + state, e);
        }
    }

    // legacy id << 4 | meta, -1 for blocks 1.8.9 didn't have
    public static int legacy(BlockState state) {
        load();
        Integer legacy = legacyByState.get(state);
        if (legacy == null) legacy = legacyByBlock.get(state.getBlock());
        return legacy == null ? -1 : legacy;
    }

    public static int legacyId(BlockState state) {
        int legacy = legacy(state);
        return legacy == -1 ? -1 : legacy >> 4;
    }

    public static int meta(BlockState state) {
        int legacy = legacy(state);
        return legacy == -1 ? 0 : legacy & 15;
    }

    public static LegacyBlock of(BlockState state) {
        return BY_ID.get(legacyId(state));
    }

    public static BlockState state(int legacyId, int meta) {
        load();
        BlockState state = stateByLegacy[(legacyId << 4 | meta) & 4095];
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    public BlockState state(int meta) {
        return state(legacyId, meta);
    }

    // null is no block, like 1.8.9's null == Blocks.x
    public boolean is(BlockState state) {
        return state != null && legacyId(state) == legacyId;
    }
}
