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

package kr.syeyoung.dungeonsguide.mod.features.impl.dungeon;

import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.config.types.TCAColor;
import kr.syeyoung.dungeonsguide.mod.config.types.TCInteger;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;

import java.util.Collections;
import java.util.List;


public class FeatureBoxBats extends SimpleFeature  {
    public FeatureBoxBats() {
        super("Player & Mob", "Box Bats", "Box bats in dungeons\nDoes not appear through walls", "dungeon.batbox", true);
        addParameter("radius", new FeatureParameter<Integer>("radius", "Highlight Radius", "The maximum distance between player and bats to be boxed", 20, TCInteger.INSTANCE));
        addParameter("color", new FeatureParameter<AColor>("color", "Highlight Color", "Highlight Color of Bats", new AColor(255,0,0,50), TCAColor.INSTANCE));
    }


    @DGEventHandler
    public void drawWorld(RenderWorldEvent event) {
        float partialTicks = event.partialTicks;

        if (!SkyblockStatus.isOnDungeon()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // TODO: 1.8.9's getPosition() floored (x, y + 0.5, z), (x + 0.5, y + 0.5, z + 0.5) for the local player; blockPosition() floors (x, y, z)
        final BlockPos player = mc.player.blockPosition();
        int val = this.<Integer>getParameter("radius").getValue();
        final int sq = val * val;

        // entity state only changes on tick boundaries; rescan once per game tick and reuse within a frame batch
        long gameTime = mc.level.getGameTime();
        if (gameTime != lastScanGameTime) {
            lastScanGameTime = gameTime;
            // only the highlight radius matters: a player-centred box. the two-vec
            // AABB ctor spans BETWEEN the points, so (position, ZERO) used to scan a
            // slab from the world origin to the player every tick
            cachedBats = mc.level.getEntitiesOfClass(Bat.class, scanBox(mc.player.position(), val));
        }
        AColor c = this.<AColor>getParameter("color").getValue();
        for (Bat entitySkeleton : cachedBats) {
            if (entitySkeleton.isRemoved() || entitySkeleton.isInvisible()) continue;
            if (entitySkeleton.distanceToSqr(player.getX(), player.getY(), player.getZ()) >= sq) continue;
            RenderUtils.highlightBox(entitySkeleton, c, partialTicks, true);
        }
    }

    // the entity scan box around the player: a cube of side 2*radius centred on the
    // player's position (static for unit tests - AABB's two-vec ctor is a span, not a size)
    static AABB scanBox(Vec3 playerPos, double radius) {
        double r = Math.max(0, radius);
        return new AABB(playerPos.x - r, playerPos.y - r, playerPos.z - r,
                playerPos.x + r, playerPos.y + r, playerPos.z + r);
    }

    // leaving skyblock or toggling the feature off must still drop the cached entity
    // refs, so this fires even when disabled or out of skyblock
    @DGEventHandler(triggerOutOfSkyblock = true, ignoreDisabled = true)
    public void onWorldUnload(kr.syeyoung.dungeonsguide.mod.events.impl.WorldUnloadEvent event) {
        // stale entity references must not survive a dimension/world swap
        cachedBats = Collections.emptyList();
        lastScanGameTime = -1;
    }

    private long lastScanGameTime = -1;
    private List<Bat> cachedBats = Collections.emptyList();
}
