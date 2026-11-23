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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One time conversion of 1.8.9 room data to format 2 (D109 / D111), run in a dev client by dgdebug convertroomdata.
 */
public class RoomDataConverter {
    // the data version vanilla's flattening produces
    private static final int FLATTENING_DATA_VERSION = 1451;

    // legacy id << 4 | meta -> 26.1 state, through vanilla's 1.13 flattening table and the DataFixer
    @SuppressWarnings("unchecked")
    public static BlockState[] buildLegacyTable() {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        BlockState[] table = new BlockState[4096];
        for (int legacy = 0; legacy < 4096; legacy++) {
            Dynamic<Tag> flattened = (Dynamic<Tag>) BlockStateData.getTag(legacy);
            Dynamic<Tag> upgraded = DataFixers.getDataFixer().update(References.BLOCK_STATE, flattened, FLATTENING_DATA_VERSION, current);
            CompoundTag tag = (CompoundTag) upgraded.getValue();
            // skull's type lived in the 1.8.9 block entity, so the flattening table can't pick a block and emits
            // %%FILTER_ME%%; skeleton skull stands in for "any skull" (LegacyBlock.SKULL matches every skull type)
            table[legacy] = (legacy >> 4) == 144 && "%%FILTER_ME%%".equals(tag.getStringOr("Name", ""))
                    ? skullState(tag)
                    : NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
        }
        return table;
    }

    private static BlockState skullState(CompoundTag tag) {
        // 1.13 kept the placement in Properties.facing: up/down is the floor skull, the rest is the wall skull
        Direction facing = Direction.byName(tag.getCompoundOrEmpty("Properties").getStringOr("facing", ""));
        if (facing == null || facing.getAxis().isVertical()) return Blocks.SKELETON_SKULL.defaultBlockState();
        return Blocks.SKELETON_WALL_SKULL.defaultBlockState().setValue(WallSkullBlock.FACING, facing);
    }

    // what LegacyBlock reads
    public static void writeTable(BlockState[] table, File file) throws IOException {
        JsonArray entries = new JsonArray();
        for (int legacy = 0; legacy < table.length; legacy++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", legacy >> 4);
            entry.addProperty("meta", legacy & 15);
            entry.addProperty("state", BlockStateParser.serialize(table[legacy]));
            entries.add(entry);
        }
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(entries), StandardCharsets.UTF_8);
    }

    public static void convert(DungeonRoomInfo dungeonRoomInfo, BlockState[] table) {
        if (dungeonRoomInfo.getFormatVersion() != 1) return;

        // mark which legacy ids the room actually uses so the palette stays per-room sized
        boolean[] used = new boolean[4096];
        char[] world = dungeonRoomInfo.getWorld();
        if (world != null) {
            for (int i = 0; i < world.length; i++) used[world[i] & 4095] = true;
        }
        int[][] blocks = dungeonRoomInfo.getBlocks();
        for (int[] row : blocks) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] != -1) used[(row[i] << 4) & 4095] = true;
            }
        }

        // resolve each used legacy id once, so the 66M-cell world loop below only touches arrays.
        // palette order is legacy-id order rather than first-seen order; it is only consumed
        // through this room's own indices, so the reordering is invisible
        int[] lut = new int[4096];
        List<BlockState> states = new ArrayList<>();
        Map<BlockState, Integer> indices = new HashMap<>();
        for (int legacy = 0; legacy < 4096; legacy++) {
            if (used[legacy]) lut[legacy] = paletteIndex(table[legacy], states, indices);
        }

        if (world != null) {
            if (states.size() <= 256) {
                // retain a byte[] of palette indices instead of the char[]
                byte[] packed = new byte[world.length];
                for (int i = 0; i < world.length; i++) {
                    packed[i] = (byte) lut[world[i] & 4095];
                }
                dungeonRoomInfo.setWorld(null);
                dungeonRoomInfo.setPackedWorld(packed);
            } else {
                for (int i = 0; i < world.length; i++) {
                    world[i] = (char) lut[world[i] & 4095];
                }
            }
        }

        // the matching layer has block ids without meta, matching compares legacy ids (D110), so meta 0 stands in
        for (int[] row : blocks) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] == -1) continue;
                row[i] = lut[(row[i] << 4) & 4095];
            }
        }

        List<String> palette = new ArrayList<>(states.size());
        BlockState[] paletteStates = states.toArray(new BlockState[0]);
        for (BlockState state : states) {
            palette.add(BlockStateParser.serialize(state));
        }

        dungeonRoomInfo.setPalette(palette);
        dungeonRoomInfo.setPaletteStates(paletteStates); // the states are already known; skip the lazy re-parse
        dungeonRoomInfo.setFormatVersion(2);
    }

    private static int paletteIndex(BlockState state, List<BlockState> states, Map<BlockState, Integer> indices) {
        Integer index = indices.get(state);
        if (index != null) return index;
        index = states.size();
        states.add(state);
        indices.put(state, index);
        return index;
    }
}
