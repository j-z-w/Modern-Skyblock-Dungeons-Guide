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

package kr.syeyoung.dungeonsguide.mod.events.impl;

import kr.syeyoung.dungeonsguide.mod.events.DGEvent;
import net.minecraft.world.phys.Vec3;

// Hypixel marks secret bats by playing entity.bat.hurt / entity.bat.death as a positioned
// sound at volume 0.1f (the same signature Odin's SecretPickupEvent.Bat uses). fired on the
// render thread at PacketProcessedEvent.Pre for every matching ClientboundSoundPacket.
public class SecretBatSoundEvent extends DGEvent {
    public final Vec3 pos;
    // true for entity.bat.death, false for entity.bat.hurt
    public final boolean death;

    public SecretBatSoundEvent(Vec3 pos, boolean death) {
        this.pos = pos;
        this.death = death;
    }
}
