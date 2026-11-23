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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DungeonActionContext {
    @Getter
    private static final Map<Integer, Vec3> spawnLocation = new HashMap<>();

    // where a killed entity was when its death/removal was recorded; a bat can fly far
    // away from its spawn point before it dies, so the spawn alone is not reliable
    @Getter
    private static final Map<Integer, Vec3> killedLocation = new HashMap<>();

    @Getter
    private static final List<Integer> killeds = new ArrayList<>();
    @Getter
    private static final List<Integer> pickedups = new ArrayList<>();

    /**
     * Whether an entity counted as killed has a recorded position within radiusSqr of pos.
     * Checks both where the entity spawned and where it was when the kill was recorded.
     */
    public static boolean wasKilledNear(Vec3 pos, double radiusSqr) {
        for (Integer killed : killeds) {
            Vec3 spawn = spawnLocation.get(killed);
            if (spawn != null && spawn.distanceToSqr(pos) < radiusSqr) return true;
            Vec3 last = killedLocation.get(killed);
            if (last != null && last.distanceToSqr(pos) < radiusSqr) return true;
        }
        return false;
    }
}
