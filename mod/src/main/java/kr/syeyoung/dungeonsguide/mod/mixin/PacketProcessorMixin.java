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
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 26.x queued-packet dispatch: every ClientPacketListener handler schedules onto
// PacketProcessor.packetsToBeHandled via PacketUtils.ensureRunningOnSameThread, and
// Minecraft.runTick drains that queue through ListenerAndPacket.handle() BEFORE
// runAllTasks() - which is why packet events can't be scheduled with Minecraft.execute
// (they would run after vanilla already applied the packet). wrapping the
// packet.handle(listener) invoke here gives an exact Pre -> vanilla -> Post per packet.
// (verified identical in the 26.1.2 and 26.3 deobf jars)
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class PacketProcessorMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void dungeonsguide$bracketVanillaHandle(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        PacketInjector.handleVanillaPacket(packet, () -> original.call(packet, listener));
    }
}
