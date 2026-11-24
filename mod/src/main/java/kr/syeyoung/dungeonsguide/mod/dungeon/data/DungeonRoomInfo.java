/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.serialization.DungeonRoomInfoBlocksDeserializer;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.serialization.DungeonRoomInfoBlocksSerializer;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.serialization.DungeonRoomInfoWorldDeserializer;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.serialization.DungeonRoomInfoWorldSerializer;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
public class DungeonRoomInfo {
    public DungeonRoomInfo(@JsonProperty("shape") short shape, @JsonProperty("color") byte color) {
        this.uuid = UUID.randomUUID();
        this.name = this.uuid.toString();
        this.shape = shape;
        this.color = color;
    }

    private transient boolean registered;

    private boolean isUserMade = false;

    private short shape;
    private byte color;

    // D111: version 1 is 1.8.9's legacy ids, version 2 indexes `palette` (converted by dgdebug convertroomdata)
    private int formatVersion = 1;
    // version 2: block state strings
    private List<String> palette;

    // the matching layer. version 1: legacy block ids, version 2: palette indices. -1 is ignored
    @JsonDeserialize(using = DungeonRoomInfoBlocksDeserializer.class)
    @JsonSerialize(using = DungeonRoomInfoBlocksSerializer.class)
    private int[][] blocks;

    private UUID uuid;
    private String name;

    private String processorId = "default";

    @JsonTypeInfo(use= JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = OffsetPoint.class, names = {"OffsetPoint", "kr.syeyoung.dungeonsguide.dungeon.data.OffsetPoint"}),
            @JsonSubTypes.Type(value = OffsetPointSet.class, names = {"OffsetPointSet", "kr.syeyoung.dungeonsguide.dungeon.data.OffsetPointSet"})
    })
    private Map<String, Object> properties = new HashMap<>();

    @JsonTypeInfo(use= JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = DungeonArrowTrapState.DungeonArrowTrapData.class, name = "trap_arrow"),
            @JsonSubTypes.Type(value = DungeonCrusherTrapState.DungeonCrusherTrapData.class, name = "trap_crusher"),
            @JsonSubTypes.Type(value = DungeonFakeChestTrapState.DungeonFakeChestTrapData.class, name = "trap_fakechest"),
            @JsonSubTypes.Type(value = DungeonFireTrapState.DungeonFireTrapData.class, name = "trap_fire"),
            @JsonSubTypes.Type(value = DungeonFloorTrapState.DungeonFloorTrapData.class, name = "trap_floor"),
            @JsonSubTypes.Type(value = DungeonTripwireTrapState.DungeonTripwireTrapData.class, name = "trap_tripwire"),

            @JsonSubTypes.Type(value = DungeonSecretBatState.DungeonSecretBatData.class, name = "secret_bat"),
            @JsonSubTypes.Type(value = DungeonSecretChestState.DungeonSecretChestData.class, name = "secret_chest"),
            @JsonSubTypes.Type(value = DungeonSecretDoubleChestState.DungeonSecretDoubleChestData.class, name = "secret_doublechest"),
            @JsonSubTypes.Type(value = DungeonSecretEssenceState.DungeonSecretEssenceData.class, name = "secret_essence"),
            @JsonSubTypes.Type(value = DungeonSecretItemDropState.DungeonSecretItemDropData.class, name = "secret_itemdrop"),

            @JsonSubTypes.Type(value = DungeonFairySoulState.DungeonFairySoulData.class, name = "fairy_soul"),


            @JsonSubTypes.Type(value = DungeonDoorState.DungeonDoorData.class, name = "door_reversible"),
            @JsonSubTypes.Type(value = DungeonOnewayDoorState.DungeonOnewayDoorData.class, name = "door_irreversible"),
            @JsonSubTypes.Type(value = DungeonLeverState.DungeonLeverData.class, name = "lever_reversible"),
            @JsonSubTypes.Type(value = DungeonOnewayLeverState.DungeonOnewayLeverData.class, name = "lever_irreversible"),
            @JsonSubTypes.Type(value = DungeonRedstoneKeyState.DungeonRedstoneKeyData.class, name = "redstone_key"),
            @JsonSubTypes.Type(value = DungeonRedstoneKeySlotState.DungeonRedstoneKeySlotData.class, name = "redstone_key_slot"),
            @JsonSubTypes.Type(value = DungeonPressurePlateState.DungeonPressurePlateData.class, name = "pressure_plate"),

            @JsonSubTypes.Type(value = DungeonBreakableWallState.DungeonBreakableWallData.class, name = "superboom_wall"),
            @JsonSubTypes.Type(value = DungeonTombState.DungeonTombData.class, name = "crypt"),

            @JsonSubTypes.Type(value = DungeonDummyState.DungeonDummyData.class, name = "dummy"),
            @JsonSubTypes.Type(value = DungeonJournalState.DungeonJournalData.class, name = "journal"),
            @JsonSubTypes.Type(value = DungeonNPCState.DungeonNPCData.class, name = "npc"),

            @JsonSubTypes.Type(value = DungeonRoomDoor2State.DungeonRoomDoor2Data.class, name = "entrance_exit"),

            @JsonSubTypes.Type(value = DungeonMushroomState.DungeonMushroomData.class, name = "special_mushroom"),
            @JsonSubTypes.Type(value = DungeonWizardState.DungeonWizardData.class, name = "npc_wizard"),
            @JsonSubTypes.Type(value = DungeonWizardCrystalState.DungeonWizardCrystalData.class, name = "special_wizard_crystal"),
    })
    private Map<String, DungeonMechanicData> mechanics = new HashMap<>();
    private int totalSecrets = -1;

    @JsonDeserialize(using = DungeonRoomInfoWorldDeserializer.class)
    @JsonSerialize(using = DungeonRoomInfoWorldSerializer.class)
    private char[] world;
    // retained post-conversion when the palette fits in a byte; `world` is freed then. both are
    // palette indices / pre-conversion legacy ids at the same index, just narrower
    @JsonIgnore
    private transient byte[] packedWorld;
    private int width, length;

    // materializes a copy when only packedWorld is retained; callers of the char[] api are null
    // checks and excluded room-edit/fakeserver sources, reads through it are valid, writes are not
    public char[] getWorld() {
        if (world == null && packedWorld != null) {
            char[] unpacked = new char[packedWorld.length];
            for (int i = 0; i < unpacked.length; i++) unpacked[i] = (char) (packedWorld[i] & 0xFF);
            return unpacked;
        }
        return world;
    }

    public void setWorld(char[] world) {
        this.world = world;
        this.packedWorld = null;
    }

    // swap the char[] for a byte[] once the room is format 2 and the palette fits
    public void packWorld() {
        if (world == null || packedWorld != null || formatVersion != 2 || palette == null || palette.size() > 256) return;
        byte[] packed = new byte[world.length];
        for (int i = 0; i < world.length; i++) packed[i] = (byte) world[i];
        packedWorld = packed;
        world = null;
    }

    // restore the char[] form for paths that need a full-width array (palette outgrowing a byte)
    private void unpackWorld() {
        world = getWorld();
        packedWorld = null;
    }

    public void setSize(int width, int length, int height) {
        this.width = width;
        this.length = length;
        this.world = new char[width * length * 256];
        this.packedWorld = null;
    }

    @JsonIgnore
    private transient BlockState[] paletteStates;

    private BlockState[] getPaletteStates() {
        if (formatVersion != 2) throw new IllegalStateException("Room data " + uuid + " is still 1.8.9 data, run dgdebug convertroomdata");
        if (paletteStates == null || paletteStates.length != palette.size()) {
            BlockState[] states = new BlockState[palette.size()];
            for (int i = 0; i < states.length; i++) states[i] = LegacyBlock.parse(palette.get(i));
            paletteStates = states;
        }
        return paletteStates;
    }

    public int getPaletteIndex(BlockState blockState) {
        if (palette == null) palette = new ArrayList<>();
        String serialized = BlockStateParser.serialize(blockState);
        int index = palette.indexOf(serialized);
        if (index == -1) {
            palette.add(serialized);
            index = palette.size() - 1;
        }
        return index;
    }

    // state at a palette index, null for -1 (ignored in the matching layer)
    public BlockState getPaletteState(int index) {
        return index == -1 ? null : getPaletteStates()[index];
    }

    public void setBlock(OffsetPoint offsetPoint, BlockState iBlockState) {
        int index = offsetPoint.getX() + ((offsetPoint.getY()+70) * length + offsetPoint.getZ()) * width;
        int paletteIndex = getPaletteIndex(iBlockState);
        if (packedWorld != null) {
            if (paletteIndex < 256) {
                packedWorld[index] = (byte) paletteIndex;
                return;
            }
            unpackWorld(); // the palette outgrew a byte
        }
        world[index] = (char) paletteIndex;
    }

    public BlockState getBlock(OffsetPoint offsetPoint, int rot) {
        return getBlock(offsetPoint.getX(), offsetPoint.getY(), offsetPoint.getZ(), rot);
    }

    // 1.8.9 rotated a block's first direction property, unless it pointed up or down
    private static EnumProperty<Direction> directionProperty(BlockState blockState) {
        for (Property<?> property : blockState.getProperties()) {
            if (property instanceof EnumProperty<?> enumProperty && enumProperty.getValueClass() == Direction.class)
                return (EnumProperty<Direction>) enumProperty;
        }
        return null;
    }

    public BlockState getBlock(int x, int y, int z, int rot) {
        if (y < -70 || y >= 186) return Blocks.BEDROCK.defaultBlockState();
        if (x <= 0 || x >= width) return Blocks.BEDROCK.defaultBlockState();
        if (z <= 0 || z >= length) return Blocks.BEDROCK.defaultBlockState();

        int index = x + ((y + 70) * length + z) * width;
        BlockState blockState = getPaletteStates()[packedWorld != null ? packedWorld[index] & 0xFF : world[index]];
        if (rot != 0) {
            EnumProperty<Direction> directions = directionProperty(blockState);

            if (directions != null) {
                Direction enumFacing = blockState.getValue(directions);
                if (!(enumFacing == Direction.UP || enumFacing == Direction.DOWN)) {
                    for (int i = 0; i < 4 - rot; i++)
                        enumFacing = enumFacing.getClockWise();
                    blockState = blockState.setValue(directions, enumFacing);
                }
            }
        }




        return blockState == null ? Blocks.AIR.defaultBlockState() : blockState;
    }
    public boolean hasSchematic() {
        return world != null || packedWorld != null;
    }
}
