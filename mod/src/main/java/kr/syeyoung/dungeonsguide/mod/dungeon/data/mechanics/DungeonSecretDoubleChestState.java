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
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.BlockPos;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
public class DungeonSecretDoubleChestState implements DungeonMechanicState, ISecret {
    private final DungeonSecretDoubleChestData data;
    private final DungeonRoom room;

    public DungeonSecretDoubleChestState(DungeonSecretDoubleChestData data, DungeonRoom room) {
        this.data = data;
        this.room = room;
    }

    private DungeonSecretChestState.LastMeasuredChestStatus firstHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.WASNT_THERE;
    private DungeonSecretChestState.LastMeasuredChestStatus secondHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.WASNT_THERE;
    private long firstHalfInteractionMs = -1L;
    private long secondHalfInteractionMs = -1L;

    public void noteChestInteraction(BlockPos pos) {
        long now = System.currentTimeMillis();
        if (data.secretPoint.getBlockPos(room).equals(pos)) {
            firstHalfInteractionMs = now;
        } else if (data.secretPoint2.getBlockPos(room).equals(pos)) {
            secondHalfInteractionMs = now;
        }
    }

    private DungeonSecretChestState.LastMeasuredChestStatus halfStatus(boolean first) {
        return first ? firstHalfStatus : secondHalfStatus;
    }

    private void halfStatus(boolean first, DungeonSecretChestState.LastMeasuredChestStatus status) {
        if (first) firstHalfStatus = status;
        else secondHalfStatus = status;
    }

    private long halfInteractionMs(boolean first) {
        return first ? firstHalfInteractionMs : secondHalfInteractionMs;
    }

    private void clearHalfInteraction(boolean first) {
        if (first) firstHalfInteractionMs = -1L;
        else secondHalfInteractionMs = -1L;
    }

    private void tickHalf(DungeonRoom dungeonRoom, OffsetPoint point, boolean first) {
        BlockPos pos = point.getBlockPos(dungeonRoom);
        BlockState blockState = dungeonRoom.getContext().getWorld().getBlockState(pos);
        if (LegacyBlock.CHEST.is(blockState) || LegacyBlock.TRAPPED_CHEST.is(blockState)) {
            ChestBlockEntity chest = (ChestBlockEntity) dungeonRoom.getContext().getWorld().getBlockEntity(pos);
            if (chest != null) {
                // 1.8.9 read the open count, 26.1 only exposes the lid animation (D118)
                if (chest.getOpenNess(1.0f) > 0
                        && DungeonSecretChestState.interactionConfirmed(halfInteractionMs(first), System.currentTimeMillis())) {
                    halfStatus(first, DungeonSecretChestState.LastMeasuredChestStatus.OPENED);
                    clearHalfInteraction(first);
                } else {
                    if (halfStatus(first) == DungeonSecretChestState.LastMeasuredChestStatus.WASNT_THERE)
                        halfStatus(first, DungeonSecretChestState.LastMeasuredChestStatus.UNOPEN);
                }
            } else {
                System.out.println("Expected ChestBlockEntity at " + pos + " to not be null");
            }
        }
    }

    public void tick(DungeonRoom dungeonRoom) {
        tickHalf(dungeonRoom, data.secretPoint, true);
        tickHalf(dungeonRoom, data.secretPoint2, false);
    }

    @Override
    public boolean isFound(DungeonRoom dungeonRoom) {
        return getSecretStatus(dungeonRoom) == SecretStatus.FOUND;
    }

    private SecretStatus getHalfSecretStatus(DungeonRoom dungeonRoom, OffsetPoint point, boolean first) {
        BlockPos pos = point.getBlockPos(dungeonRoom);
        BlockState blockState = dungeonRoom.getCachedWorld().getBlockState(pos);
        DungeonSecretChestState.LastMeasuredChestStatus status = halfStatus(first);
        if (status != DungeonSecretChestState.LastMeasuredChestStatus.WASNT_THERE)
            return (status == DungeonSecretChestState.LastMeasuredChestStatus.OPENED || LegacyBlock.AIR.is(blockState)) ? SecretStatus.FOUND : SecretStatus.CREATED;

        if (LegacyBlock.AIR.is(blockState)) {
            return SecretStatus.DEFINITELY_NOT;
        } else if (!LegacyBlock.CHEST.is(blockState) && !LegacyBlock.TRAPPED_CHEST.is(blockState)) {
            return SecretStatus.ERROR;
        } else {
            ChestBlockEntity chest = (ChestBlockEntity) dungeonRoom.getContext().getWorld().getBlockEntity(pos);
            // 1.8.9 read the open count, 26.1 only exposes the lid animation (D118)
            if (chest != null && chest.getOpenNess(1.0f) > 0
                    && DungeonSecretChestState.interactionConfirmed(halfInteractionMs(first), System.currentTimeMillis())) {
                return SecretStatus.FOUND;
            } else {
                return SecretStatus.CREATED;
            }
        }
    }

    public int getFoundHalfCount(DungeonRoom dungeonRoom) {
        int count = 0;
        if (getHalfSecretStatus(dungeonRoom, data.secretPoint, true) == SecretStatus.FOUND) count++;
        if (getHalfSecretStatus(dungeonRoom, data.secretPoint2, false) == SecretStatus.FOUND) count++;
        return count;
    }

    @Override
    public int getFoundSecretCount(DungeonRoom dungeonRoom) {
        return getFoundHalfCount(dungeonRoom);
    }

    public SecretStatus getSecretStatus(DungeonRoom dungeonRoom) {
        SecretStatus first = getHalfSecretStatus(dungeonRoom, data.secretPoint, true);
        SecretStatus second = getHalfSecretStatus(dungeonRoom, data.secretPoint2, false);
        if (first == SecretStatus.FOUND && second == SecretStatus.FOUND) return SecretStatus.FOUND;
        if (first == SecretStatus.ERROR || second == SecretStatus.ERROR) return SecretStatus.ERROR;
        if (first == SecretStatus.DEFINITELY_NOT && second == SecretStatus.DEFINITELY_NOT) return SecretStatus.DEFINITELY_NOT;
        return SecretStatus.CREATED;
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
            ActionUtils.buildActionMoveAndClick(builder, room, new OffsetPoint[]{data.secretPoint, data.secretPoint2}, builder1 -> {
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
        RenderUtils.highlightBlocksStencil(Arrays.asList(pos, data.secretPoint2.getBlockPos(room)), partialTicks, color, false);
        RenderUtils.drawTextAtWorld(name, pos.getX() + 0.5f, pos.getY() + 0.375f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);
        RenderUtils.drawTextAtWorld(getCurrentState(), pos.getX() + 0.5f, pos.getY() + 0f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);
    }

    public void markFound() {
        firstHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.OPENED;
        secondHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.OPENED;
        firstHalfInteractionMs = -1L;
        secondHalfInteractionMs = -1L;
    }

    public void markFound(BlockPos pos) {
        if (data.secretPoint.getBlockPos(room).equals(pos)) {
            firstHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.OPENED;
            firstHalfInteractionMs = -1L;
        } else if (data.secretPoint2.getBlockPos(room).equals(pos)) {
            secondHalfStatus = DungeonSecretChestState.LastMeasuredChestStatus.OPENED;
            secondHalfInteractionMs = -1L;
        }
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
    public static class DungeonSecretDoubleChestData implements DungeonMechanicData {
        private OffsetPoint secretPoint = new OffsetPoint(0, 0, 0);
        private OffsetPoint secretPoint2 = new OffsetPoint(0, 0, 0);
        private List<String> preRequisite = new ArrayList<String>();
        private PrecalculatedStonk secretCache;


        public DungeonSecretDoubleChestData() {
        }

        @Override
        public DungeonSecretDoubleChestState createState(DungeonRoom room) {
            return new DungeonSecretDoubleChestState(this, room);
        }

        public DungeonSecretDoubleChestData clone() throws CloneNotSupportedException {
            DungeonSecretDoubleChestData data = new DungeonSecretDoubleChestData();
            data.secretPoint = (OffsetPoint) secretPoint.clone();
            data.secretPoint2 = (OffsetPoint) secretPoint2.clone();
            data.preRequisite = new ArrayList<String>(preRequisite);
            data.secretCache = secretCache;
            ;
            return data;
        }
    }
}
