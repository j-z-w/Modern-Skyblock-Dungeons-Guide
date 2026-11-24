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

package kr.syeyoung.dungeonsguide.mod.dungeon;

import lombok.Getter;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DungeonActionContext {
    // written by entity events on the render thread, also read by solver workers
    // through ActionInteract/ActionKill - concurrent so a worker can't read a
    // mid-resize HashMap
    @Getter
    private static final Map<Integer, Vec3> spawnLocation = new ConcurrentHashMap<>();

    @Getter
    private static final Queue<Integer> pickedups = new ConcurrentLinkedQueue<>();

    public static void clearKillRecords() {
        pickedups.clear();
        spawnLocation.clear();
    }
}
