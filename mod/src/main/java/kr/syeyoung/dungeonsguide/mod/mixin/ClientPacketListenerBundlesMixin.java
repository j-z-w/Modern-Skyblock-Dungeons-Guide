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
import kr.syeyoung.dungeonsguide.mod.events.listener.PacketInjector;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// bundles dispatch their sub-packets inline: handleBundlePacket loops
// subPacket.handle(this) on the render thread inside the bundle's own queued task.
// the ListenerAndPacket wrap sees only ClientboundBundlePacket (never a targeted class),
// so each sub-packet gets its own Pre -> vanilla -> Post here
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerBundlesMixin {
    @WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void dungeonsguide$bracketSubPacketHandle(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        PacketInjector.handleVanillaPacket(packet, () -> original.call(packet, listener));
    }
}
