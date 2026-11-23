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

package kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers;

import com.mojang.blaze3d.platform.InputConstants;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.config.types.TCKeybind;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.ActionComplete;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.events.impl.KeyBindPressedEvent;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay.RoomRouteHandler;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.IPathDisplayEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.awt.*;

// manual stall recovery for any route step, bat secrets included: pressing the key marks
// the currently displayed step done and advances. the underlying secret is NOT marked
// found - its own detection (sound/removal/counter/proximity) still applies, so a skipped
// step can later be confirmed or re-queued by a recalculation
public class FeatureSkipRouteStep extends SimpleFeature {
    public FeatureSkipRouteStep() {
        super("Pathfinding & Secrets", "Skip Current Route Step",
                "Key that advances the route past the currently displayed step.\nUse it when a step is stuck (e.g. a bat secret whose kill wasn't detected).\nThe skipped secret stays 'not sure' and can still complete on its own.",
                "secret.skiproutestep", true);
        addParameter("key", new FeatureParameter<InputConstants.Key>("key", "Key", "Press to skip the current route step", InputConstants.UNKNOWN, TCKeybind.INSTANCE));
    }

    @DGEventHandler
    public void onKeybindPress(KeyBindPressedEvent keyBindPressedEvent) {
        // default UNKNOWN must never match: every unmapped key event would skip the
        // current route step otherwise
        if (!TCKeybind.matches(this.<InputConstants.Key>getParameter("key").getValue(),
                keyBindPressedEvent.getKey())) return;

        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        if (!SkyblockStatus.isOnDungeon() || context == null) return;
        LocalPlayer thePlayer = Minecraft.getInstance().player;
        if (thePlayer == null || context.getScaffoldParser() == null) return;
        Point roomPt = context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(thePlayer.position());
        DungeonRoom currentRoom = context.getScaffoldParser().getRoomMap().get(roomPt);
        if (currentRoom == null) return;
        RoomRouteHandler handler = FeatureRegistry.SECRET_ROUTE_REGISTRY.getRoomHandler(currentRoom);
        if (handler == null) return;

        AbstractAction skipped = null;
        int skippedRoutes = 0;
        for (IPathDisplayEngine<?> engine : handler.getPath().values()) {
            AbstractAction done = engine.getActionRoute().skipCurrentStep();
            if (done != null && !(done instanceof ActionComplete)) {
                skipped = done;
                skippedRoutes++;
            }
        }
        if (skippedRoutes == 0) {
            ChatTransmitter.addToQueue(Component.literal("Dungeons Guide :: No route step to skip"));
        } else {
            String name = skipped.toString().split("\n")[0];
            ChatTransmitter.addToQueue(Component.literal("Dungeons Guide :: Skipped route step: " + name));
        }
    }
}
