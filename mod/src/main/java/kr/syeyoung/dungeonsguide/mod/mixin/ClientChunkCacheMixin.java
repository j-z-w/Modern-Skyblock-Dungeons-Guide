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

package kr.syeyoung.dungeonsguide.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.syeyoung.dungeonsguide.launcher.FabricEventBridge;
import kr.syeyoung.dungeonsguide.mod.events.impl.BlockUpdateEvent;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import kr.syeyoung.dungeonsguide.mod.utils.Tuple;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

@Mixin(ClientChunkCache.class)
public class ClientChunkCacheMixin {
    // D105: a chunk the client already has, sent again. 1.8.9 dg listened to the chunk packet to see these block changes,
    // here the new sections are decoded from a copy of the buffer and compared with the loaded chunk
    @WrapOperation(method = "replaceWithPacketData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V", ordinal = 1))
    private void dungeonsguide$onChunkResend(LevelChunk chunk, int x, int z, ClientboundLevelChunkPacketData packetData, Operation<Void> original) {
        BlockUpdateEvent.Pre pre = new BlockUpdateEvent.Pre();
        try {
            FriendlyByteBuf copy = new FriendlyByteBuf(packetData.getReadBuffer().duplicate());
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection neu = new LevelChunkSection(chunk.getLevel().palettedContainerFactory());
                neu.read(copy);
                LevelChunkSection old = sections[i];
                if (old.hasOnlyAir() && neu.hasOnlyAir()) continue;

                int baseX = chunk.getPos().getMinBlockX();
                int baseY = SectionPos.sectionToBlockCoord(chunk.getLevel().getSectionYFromSectionIndex(i));
                int baseZ = chunk.getPos().getMinBlockZ();
                for (int y = 0; y < 16; y++) {
                    for (int zz = 0; zz < 16; zz++) {
                        for (int xx = 0; xx < 16; xx++) {
                            BlockState newState = neu.getBlockState(xx, y, zz);
                            if (old.getBlockState(xx, y, zz) != newState)
                                pre.getUpdatedBlocks().add(new Tuple<>(new BlockPos(baseX + xx, baseY + y, baseZ + zz), newState));
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Set<Tuple<BlockPos, BlockState>> updated = pre.getUpdatedBlocks();
        if (!updated.isEmpty()) FabricEventBridge.fire(pre);
        original.call(chunk, x, z, packetData);
        if (!updated.isEmpty()) {
            BlockUpdateEvent.Post post = new BlockUpdateEvent.Post();
            post.getUpdatedBlocks().addAll(updated);
            FabricEventBridge.fire(post);
        }
    }
}
