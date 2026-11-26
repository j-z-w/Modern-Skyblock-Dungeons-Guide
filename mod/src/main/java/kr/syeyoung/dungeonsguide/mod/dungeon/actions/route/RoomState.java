/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2024  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.phys.Vec3;

import java.util.*;

@Getter @Setter
public class RoomState {
    private DungeonRoom dungeonRoom;
    private Vec3 playerPos;
    private double px, py, pz;
    private OffsetVec3 playerPosOff = new OffsetVec3();

    public Vec3 getPlayerPos() {
        if (playerPos == null) playerPos = new Vec3(px, py, pz);
        return playerPos;
    }

    public void setPlayerPos(Vec3 playerPos) {
        this.playerPos = playerPos;
        this.px = playerPos.x; this.py = playerPos.y; this.pz = playerPos.z;
        if (dungeonRoom != null) {
            this.playerPosOff.setPosInWorld(dungeonRoom, playerPos);
        }
    }

    // native-callback hot path: the TSP matrix only reads playerPosOff, so the Vec3 is
    // materialized lazily instead of allocating ~600k Vec3s per solve
    public void setPlayerPos(double x, double y, double z) {
        this.playerPos = null;
        this.px = x; this.py = y; this.pz = z;
        if (dungeonRoom != null) {
            this.playerPosOff.setPosInWorld(dungeonRoom, x, y, z);
        }
    }

    public int openMechanicsBitset;
    private final List<String> openMechanicsIndex;
    public RoomState(List<String> openMechanicsIndex) {
        this.openMechanicsIndex = openMechanicsIndex;
    }
}
