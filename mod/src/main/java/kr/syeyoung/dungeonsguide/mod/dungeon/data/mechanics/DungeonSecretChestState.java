/*
 *     Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 *     Copyright (C) 2021  cyoung06
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import com.google.common.collect.Sets;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PrecalculatedStonk;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
public class DungeonSecretChestState implements DungeonMechanicState, ISecret {
    private final DungeonSecretChestData data;
    private final DungeonRoom room;

    public DungeonSecretChestState(DungeonSecretChestData data, DungeonRoom room) {
        this.data = data;
        this.room = room;
    }

    public void markFound() {
        lastMeasuredChestStatus = LastMeasuredChestStatus.OPENED;
        lastInteractionMs = -1L;
    }

    public void noteChestInteraction() {
        lastInteractionMs = System.currentTimeMillis();
    }

    public enum LastMeasuredChestStatus {
        WASNT_THERE, UNOPEN, OPENED
    }

    static final long INTERACTION_CONFIRM_MS = 1500L;

    private LastMeasuredChestStatus lastMeasuredChestStatus = LastMeasuredChestStatus.WASNT_THERE;
    private long lastInteractionMs = -1L;

    static boolean isDoubleChest(DGWorld world, BlockPos pos, BlockState blockState) {
        if (blockState.hasProperty(ChestBlock.TYPE) && blockState.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return true;
        int chestId = LegacyBlock.legacyId(blockState);
        if (chestId != LegacyBlock.CHEST.getLegacyId() && chestId != LegacyBlock.TRAPPED_CHEST.getLegacyId()) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState neighbor = world.getBlockState(pos.relative(direction));
            if (neighbor == null || LegacyBlock.legacyId(neighbor) != chestId) continue;
            if (!blockState.hasProperty(ChestBlock.FACING) || !neighbor.hasProperty(ChestBlock.FACING)) return true;
            Direction facing = blockState.getValue(ChestBlock.FACING);
            if (facing == neighbor.getValue(ChestBlock.FACING) && direction.getAxis() != facing.getAxis()) return true;
        }
        return false;
    }

    static boolean interactionConfirmed(long interactionMs, long now) {
        return interactionMs >= 0 && now >= interactionMs && now - interactionMs <= INTERACTION_CONFIRM_MS;
    }

    private boolean lidCountsAsOpen(DGWorld world, BlockPos pos, BlockState blockState, ChestBlockEntity chest) {
        return chest.getOpenNess(1.0f) > 0
                && (!isDoubleChest(world, pos, blockState) || interactionConfirmed(lastInteractionMs, System.currentTimeMillis()));
    }

    public void tick(DungeonRoom dungeonRoom) {
        BlockPos pos = data.secretPoint.getBlockPos(dungeonRoom);
        BlockState blockState = dungeonRoom.getContext().getWorld().getBlockState(pos);
        if (LegacyBlock.CHEST.is(blockState) || LegacyBlock.TRAPPED_CHEST.is(blockState)) {
            ChestBlockEntity chest = (ChestBlockEntity) dungeonRoom.getContext().getWorld().getBlockEntity(pos);
            if (chest != null) {
                // 1.8.9 read the open count, 26.1 only exposes the lid animation (D118)
                if (lidCountsAsOpen(dungeonRoom.getContext().getWorld(), pos, blockState, chest)) {
                    lastMeasuredChestStatus = LastMeasuredChestStatus.OPENED;
                    lastInteractionMs = -1L;
                } else {
                    if (lastMeasuredChestStatus == LastMeasuredChestStatus.WASNT_THERE)
                        lastMeasuredChestStatus = LastMeasuredChestStatus.UNOPEN;
                }
            } else {
                System.out.println("Expected ChestBlockEntity at " + pos + " to not be null");
            }
        }
    }

    @Override
    public boolean isFound(DungeonRoom dungeonRoom) {
        return getSecretStatus(dungeonRoom) == SecretStatus.FOUND;
    }

    public SecretStatus getSecretStatus(DungeonRoom dungeonRoom) {
        BlockPos pos = data.secretPoint.getBlockPos(dungeonRoom);
        BlockState blockState = dungeonRoom.getCachedWorld().getBlockState(pos);
        if (lastMeasuredChestStatus != LastMeasuredChestStatus.WASNT_THERE)
            return (lastMeasuredChestStatus == LastMeasuredChestStatus.OPENED || LegacyBlock.AIR.is(blockState)) ? SecretStatus.FOUND : SecretStatus.CREATED;

        if (LegacyBlock.AIR.is(blockState)) {
            return SecretStatus.DEFINITELY_NOT;
        } else if (!LegacyBlock.CHEST.is(blockState) && !LegacyBlock.TRAPPED_CHEST.is(blockState)) {
            return SecretStatus.ERROR;
        } else {
            ChestBlockEntity chest = (ChestBlockEntity) dungeonRoom.getContext().getWorld().getBlockEntity(pos);
            // 1.8.9 read the open count, 26.1 only exposes the lid animation (D118)
            if (chest != null && lidCountsAsOpen(dungeonRoom.getContext().getWorld(), pos, blockState, chest)) {
                return SecretStatus.FOUND;
            } else {
                return SecretStatus.CREATED;
            }
        }
    }

    @Override
    public void buildAction(String action, ActionDAGBuilder builder, AlgorithmSetting algorithmSetting) throws PathfindImpossibleException {
        if (action.equalsIgnoreCase("navigate")) {
            builder = builder
                    .requires(new ActionMoveNearestAir(getRepresentingPoint()), algorithmSetting);
            for (String str : data.preRequisite) {
                if (str.isEmpty()) continue;
                builder.optional(new ActionChangeState(str.split(":")[0], str.split(":")[1]), algorithmSetting);
            }
            return;
        }
        if (!"found".equalsIgnoreCase(action))
            throw new PathfindImpossibleException(action + " is not valid state for secret");
        if (action.equals("found") && getSecretStatus(room) == SecretStatus.FOUND) return;

        List<String> requiredRequisite = data.preRequisite.stream().filter(a -> {
            return room.getMechanics().get(a.split(":")[0]) instanceof DungeonOnewayDoorState;
        }).collect(Collectors.toList());
        List<String> optionalRequisite = data.preRequisite.stream().filter(a -> {
            return !requiredRequisite.contains(a);
        }).collect(Collectors.toList());

        if (data.secretCache != null)
            ActionUtils.buildActionMoveAndClick(builder, room, data.secretCache, optionalRequisite, requiredRequisite, algorithmSetting);
        else
            ActionUtils.buildActionMoveAndClick(builder, room, data.secretPoint, builder1 -> {
                boolean doneDoor = false;
                for (String str : data.preRequisite) {
                    if (room.getMechanics().get(str.split(":")[0]) instanceof DungeonOnewayDoorState) {
                        builder1.requires(new ActionChangeState(str.split(":")[0], str.split(":")[1]), algorithmSetting);
                        doneDoor = true;
                    }
                }
                if (doneDoor)
                    builder1 = builder1.requires(new ActionRoot(), algorithmSetting);
                for (String str : data.preRequisite) {
                    if (str.isEmpty()) continue;
                    if (room.getMechanics().get(str) instanceof DungeonOnewayDoorState) continue;
                    builder1.optional(new ActionChangeState(str.split(":")[0], str.split(":")[1]), algorithmSetting);
                }
                return null;
            }, algorithmSetting);


    }

    @Override
    public void highlight(Color color, String name, float partialTicks) {
        BlockPos pos = getSecretPoint().getBlockPos(room);
        RenderUtils.highlightBlockStencil(pos, partialTicks, color, false);
        RenderUtils.drawTextAtWorld(name, pos.getX() + 0.5f, pos.getY() + 0.375f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);
        RenderUtils.drawTextAtWorld(getCurrentState(), pos.getX() + 0.5f, pos.getY() + 0f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);


    }

    public enum SecretType {
        BAT, CHEST, ITEM_DROP, ESSENCE
    }

    @AllArgsConstructor
    @Getter
    public enum SecretStatus {
        DEFINITELY_NOT("definitely_not"), NOT_SURE("not_sure"), CREATED("created"), FOUND("found"), ERROR("error");

        private final String stateName;
    }


    @Override
    public String getCurrentState() {
        return getSecretStatus(room).getStateName();
    }

    @Override
    public Set<String> getAvailableActions() {
        SecretStatus status = getSecretStatus(room);
        if (status == SecretStatus.FOUND) return Sets.newHashSet("navigate");
        else return Sets.newHashSet("found", "navigate");
    }

    @Override
    public Set<String> getTotalPossibleStates() {
        return Sets.newHashSet("found"/*, "definitely_not", "not_sure", "created", "error"*/);
    }

    @Override
    public OffsetPoint getRepresentingPoint() {
        return data.secretPoint;
    }

    @Override
    public OffsetPoint getSecretPoint() {
        return data.secretPoint;
    }

    @Override
    public List<String> getPreRequisite() {
        return data.preRequisite;
    }

    @Data
    public static class DungeonSecretChestData implements DungeonMechanicData {
        private OffsetPoint secretPoint = new OffsetPoint(0, 0, 0);
        private PrecalculatedStonk secretCache;
        private List<String> preRequisite = new ArrayList<String>();


        public DungeonSecretChestData() {
        }

        @Override
        public DungeonSecretChestState createState(DungeonRoom room) {
            return new DungeonSecretChestState(this, room);
        }

        public DungeonSecretChestData clone() throws CloneNotSupportedException {
            DungeonSecretChestData data = new DungeonSecretChestData();
            data.secretPoint = (OffsetPoint) secretPoint.clone();
            data.secretCache = secretCache;
            data.preRequisite = new ArrayList<String>(preRequisite);
            ;
            return data;
        }
    }
}
