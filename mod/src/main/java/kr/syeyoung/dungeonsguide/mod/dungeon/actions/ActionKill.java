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

package kr.syeyoung.dungeonsguide.mod.dungeon.actions;

import kr.syeyoung.dungeonsguide.mod.events.impl.LivingDeathEvent;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonActionContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;
import java.util.function.Supplier;

@Data
@EqualsAndHashCode(callSuper=false)
public class ActionKill extends AbstractAction {
    private OffsetPoint target;
    private Predicate<Entity> predicate = entity -> false;
    private int radius;
    // mechanic-owned completion backer: set for bat secrets so any of the mechanic's signals
    // (sound ping, tracked entity, counter attribution) completes this route action too
    private Supplier<Boolean> completedBy;

    public ActionKill(OffsetPoint target) {
        this.target = target;
    }

    @Override
    public boolean isComplete(DungeonRoom dungeonRoom) {
        // a completedBy-backed action (bat secrets) is a pure read of the mechanic's
        // stored state - no kill-record scan: the render-thread authority already
        // decided which secret each kill belongs to
        if (completedBy != null) return Boolean.TRUE.equals(completedBy.get());
        return killed;
    }

    // written on the render thread, read by solver workers via isComplete
    private volatile boolean killed = false;
    @Override
    public void onLivingDeath(DungeonRoom dungeonRoom, LivingDeathEvent event) {
        if (killed || completedBy != null) return;

        Vec3 spawnLoc = DungeonActionContext.getSpawnLocation().get(event.entity.getId());
        if (spawnLoc == null) spawnLoc = event.entity.position(); // never tracked: where it died
        if (target.getBlockPos(dungeonRoom).distToLowCornerSqr(spawnLoc.x, spawnLoc.y, spawnLoc.z) > radius * radius) return;
        if (!predicate.test(event.entity)) return;
        killed = true;
    }

    @Override
    public String toString() {
        return "KillEntity\n- target: "+target.toString()+"\n- radius: "+radius+"\n- predicate: "+(predicate.test(null) ? "null" : predicate.getClass().getSimpleName());
    }
}
