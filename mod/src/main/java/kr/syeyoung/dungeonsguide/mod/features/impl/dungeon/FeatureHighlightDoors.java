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

package kr.syeyoung.dungeonsguide.mod.features.impl.dungeon;

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.config.types.TCAColor;
import kr.syeyoung.dungeonsguide.mod.config.types.TCBoolean;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonRoomDoor2State;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonRoomDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonMapLayout;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.awt.Point;
import java.util.Map;

public class FeatureHighlightDoors extends SimpleFeature {
    public FeatureHighlightDoors() {
        super("Pathfinding & Secrets", "Highlight Doors", "Highlight door mechanics in the dungeon room you are currently in", "dungeon.highlightdoors", true);
        addParameter("color", new FeatureParameter<AColor>("color", "Highlight Color", "Highlight color of door mechanics", new AColor(200, 220, 255, 80), TCAColor.INSTANCE));
        addParameter("entranceDoors", new FeatureParameter<Boolean>("entranceDoors", "Highlight Entrance Doors", "Highlight doors connecting this room to other dungeon rooms", true, TCBoolean.INSTANCE));
        addParameter("onewayDoors", new FeatureParameter<Boolean>("onewayDoors", "Highlight One-Way Doors", "Highlight one-way puzzle doors in this room", true, TCBoolean.INSTANCE));
        addParameter("regularDoors", new FeatureParameter<Boolean>("regularDoors", "Highlight Regular Doors", "Highlight regular openable doors in this room", true, TCBoolean.INSTANCE));
    }

    @DGEventHandler
    public void drawWorld(RenderWorldEvent event) {
        if (!SkyblockStatus.isOnDungeon()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        if (context == null) return;
        DungeonRoomScaffoldParser scaffoldParser = context.getScaffoldParser();
        if (scaffoldParser == null) return;
        DungeonMapLayout mapLayout = scaffoldParser.getDungeonMapLayout();
        if (mapLayout == null) return;

        Point roomPt = mapLayout.worldPointToRoomPoint(player.position());
        if (roomPt == null) return;
        DungeonRoom dungeonRoom = scaffoldParser.getRoomMap().get(roomPt);
        if (dungeonRoom == null || !dungeonRoom.isMatched() || dungeonRoom.getDungeonRoomInfo() == null) return;

        boolean entranceDoors = this.<Boolean>getParameter("entranceDoors").getValue();
        boolean onewayDoors = this.<Boolean>getParameter("onewayDoors").getValue();
        boolean regularDoors = this.<Boolean>getParameter("regularDoors").getValue();
        if (!entranceDoors && !onewayDoors && !regularDoors) return;

        AColor color = this.<AColor>getParameter("color").getValue();
        for (Map.Entry<String, DungeonMechanicState> entry : dungeonRoom.getMechanics().entrySet()) {
            DungeonMechanicState mechanic = entry.getValue();
            if (!shouldHighlight(mechanic, entranceDoors, onewayDoors, regularDoors)) continue;
            mechanic.highlight(color, entry.getKey(), event.partialTicks);
        }
    }

    private boolean shouldHighlight(DungeonMechanicState mechanic, boolean entranceDoors, boolean onewayDoors, boolean regularDoors) {
        if (mechanic instanceof DungeonRoomDoor2State || mechanic instanceof DungeonRoomDoorState) {
            return entranceDoors;
        }
        if (mechanic instanceof DungeonOnewayDoorState) {
            return onewayDoors;
        }
        if (mechanic instanceof DungeonDoorState) {
            return regularDoors;
        }
        return false;
    }
}
