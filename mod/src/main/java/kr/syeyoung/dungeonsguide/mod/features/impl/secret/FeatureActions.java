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

package kr.syeyoung.dungeonsguide.mod.features.impl.secret;


import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AtomicAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.ActionRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.IPathDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay.RoomRouteHandler;
import kr.syeyoung.dungeonsguide.mod.features.richtext.DefaultTextHUDFeatureStyleFeature;
import kr.syeyoung.dungeonsguide.mod.features.richtext.DefaultingDelegatingTextStyle;
import kr.syeyoung.dungeonsguide.mod.features.richtext.NullTextStyle;
import kr.syeyoung.dungeonsguide.mod.features.richtext.TextHUDFeature;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.TextSpan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.awt.*;

public class FeatureActions extends TextHUDFeature {
    public FeatureActions() {
        super("Pathfinding & Secrets", "Action Viewer", "View List of actions that needs to be taken", "secret.actionview");

        registerDefaultStyle("pathfinding", DefaultingDelegatingTextStyle.derive("Feature Default - Pathfinding", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.NAME)));
        registerDefaultStyle("mechanic", DefaultingDelegatingTextStyle.derive("Feature Default - Mechanic", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.DEFAULT))
                .setTextShader(new AColor(0x55, 0xFF,0x55,255)));
        registerDefaultStyle("separator", DefaultingDelegatingTextStyle.derive("Feature Default - Separator", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.SEPARATOR)));
        registerDefaultStyle("state", DefaultingDelegatingTextStyle.derive("Feature Default - State", () -> getStyle("mechanic"))
                .setTextShader(new AColor(0x55, 0xFF,0x55,255)));
        registerDefaultStyle("current", DefaultingDelegatingTextStyle.derive("Feature Default - Current", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.VALUE)));
        registerDefaultStyle("number", DefaultingDelegatingTextStyle.derive("Feature Default - Number", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.NAME)));
        registerDefaultStyle("dot", DefaultingDelegatingTextStyle.derive("Feature Default - Dot", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.SEPARATOR)));
        registerDefaultStyle("action", DefaultingDelegatingTextStyle.derive("Feature Default - Action", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.VALUE)));
        registerDefaultStyle("afterline", DefaultingDelegatingTextStyle.derive("Feature Default - Afterline", () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.EXTRA_INFO)));
    }


    @Override
    public boolean isHUDViewable() {
        if (!SkyblockStatus.isOnDungeon()) return false;
        if (DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext() == null || DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext().getScaffoldParser() == null) return false;
        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();

        LocalPlayer thePlayer = Minecraft.getInstance().player;
        Point roomPt = context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(thePlayer.position());
        DungeonRoom dungeonRoom = context.getScaffoldParser().getRoomMap().get(roomPt);
        if (dungeonRoom == null) return false;
        RoomRouteHandler roomRouteHandler = FeatureRegistry.SECRET_ROUTE_REGISTRY.getRoomHandler(dungeonRoom);
        if (roomRouteHandler == null) return false;
        return  true;
    }


    @Override
    public TextSpan getDummyText() {
        TextSpan dummyText = new TextSpan(new NullTextStyle(), "");
        dummyText.addChild(new TextSpan(getStyle("pathfinding"), "Pathfinding "));
        dummyText.addChild(new TextSpan(getStyle("mechanic"), "Secret "));
        dummyText.addChild(new TextSpan(getStyle("separator"), "-> "));
        dummyText.addChild(new TextSpan(getStyle("state"), "Found\n"));
        dummyText.addChild(new TextSpan(getStyle("current"), "> "));
        dummyText.addChild(new TextSpan(getStyle("number"), "1"));
        dummyText.addChild(new TextSpan(getStyle("dot"), ". "));
        dummyText.addChild(new TextSpan(getStyle("action"), "Move "));
        dummyText.addChild(new TextSpan(getStyle("afterline"), "OffsetPoint{x=1,y=42,z=1} \n"));
        dummyText.addChild(new TextSpan(getStyle("current"), "  "));
        dummyText.addChild(new TextSpan(getStyle("number"), "2"));
        dummyText.addChild(new TextSpan(getStyle("dot"), ". "));
        dummyText.addChild(new TextSpan(getStyle("action"), "Click "));
        dummyText.addChild(new TextSpan(getStyle("afterline"), "OffsetPoint{x=1,y=42,z=1} \n"));
        dummyText.addChild(new TextSpan(getStyle("current"), "  "));
        dummyText.addChild(new TextSpan(getStyle("number"), "3"));
        dummyText.addChild(new TextSpan(getStyle("dot"), ". "));
        dummyText.addChild(new TextSpan(getStyle("action"), "Profit "));
        return dummyText;
    }


    // the HUD only changes when the route's action list or current index does; rebuilding the
    // span tree otherwise just churns TextSpan/String garbage every frame
    private java.util.List<Object> hudSignature;
    private TextSpan hudText;

    @Override
    public void updateStyle(java.util.Map<String, DefaultingDelegatingTextStyle> map) {
        super.updateStyle(map);
        // buildText captured the old DefaultingDelegatingTextStyle instances; drop the memo
        hudText = null;
    }

    @kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler(triggerOutOfSkyblock = true, ignoreDisabled = true)
    public void onDungeonLeft(kr.syeyoung.dungeonsguide.mod.events.impl.DungeonLeftEvent event) {
        // don't retain the last room's ActionRoute (and through it the DungeonRoom)
        hudSignature = null;
        hudText = null;
    }

    @Override
    public TextSpan getText() {
        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();

        LocalPlayer thePlayer = Minecraft.getInstance().player;
        Point roomPt = context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(thePlayer.position());
        DungeonRoom dungeonRoom = context.getScaffoldParser().getRoomMap().get(roomPt);
        RoomRouteHandler roomRouteHandler = FeatureRegistry.SECRET_ROUTE_REGISTRY.getRoomHandler(dungeonRoom);

        java.util.List<Object> signature = new java.util.ArrayList<>();
        signature.add(getStyleGeneration());
        // GUI edits mutate the captured style objects / global defaults in place
        signature.add(TextHUDFeature.globalStyleRevision());
        for (IPathDisplayEngine engine : roomRouteHandler.getPath().values()) {
            ActionRoute path = engine.getActionRoute();
            signature.add(path);
            signature.add(path.getActions()); // identity: the list object is replaced when the route is recalculated
            signature.add(path.getCurrent());
            for (AbstractAction action : path.getActions()) {
                if (action instanceof AtomicAction) {
                    signature.add(((AtomicAction) action).getActions());
                    signature.add(((AtomicAction) action).getCurrent());
                }
            }
        }
        if (hudText != null && signatureEquals(hudSignature, signature)) return hudText;
        hudSignature = signature;
        return hudText = buildText(roomRouteHandler);
    }

    // entries are compared by identity: route objects and action lists are replaced wholesale
    // when a route changes, and a per-element AbstractAction.equals was exactly the deep
    // compare this memo exists to skip. boxed Integers get value equality
    private static boolean signatureEquals(java.util.List<Object> old, java.util.List<Object> next) {
        if (old == null || old.size() != next.size()) return false;
        for (int i = 0; i < next.size(); i++) {
            Object a = old.get(i), b = next.get(i);
            if (a == b) continue;
            if (a instanceof Integer && b instanceof Integer && a.equals(b)) continue;
            return false;
        }
        return true;
    }

    private TextSpan buildText(RoomRouteHandler roomRouteHandler) {
        TextSpan actualBit = new TextSpan(new NullTextStyle(), "");

        for (IPathDisplayEngine path2 : roomRouteHandler.getPath().values()) {
            ActionRoute path = path2.getActionRoute();
            actualBit.addChild(new TextSpan(getStyle("pathfinding"), "Pathfinding "));
            actualBit.addChild(new TextSpan(getStyle("mechanic"), path.toString()+"\n"));

            for (int i = Math.max(0,path.getCurrent()-2); i < path.getActions().size(); i++) {
                actualBit.addChild(new TextSpan(getStyle("current"), (i == path.getCurrent() ? ">" : " ") +" "));
                actualBit.addChild(new TextSpan(getStyle("number"), i+""));
                actualBit.addChild(new TextSpan(getStyle("dot"), ". "));
                AbstractAction action = path.getActions().get(i);
                if (action instanceof AtomicAction)  {
                    AtomicAction atomicAction = (AtomicAction) action;
                    actualBit.addChild(new TextSpan(getStyle("action"), atomicAction.toString().split("\n")[0]));
                    actualBit.addChild(new TextSpan(getStyle("afterline"), " ("+atomicAction.getActions().size()+")\n"));
                    for (int j = 0; j < atomicAction.getActions().size(); j++) {
                        actualBit.addChild(new TextSpan(getStyle("current"), "    "+(j == atomicAction.getCurrent() ? ">" : " ") +" "));
                        actualBit.addChild(new TextSpan(getStyle("number"), j+""));
                        actualBit.addChild(new TextSpan(getStyle("dot"), ". "));
                        AbstractAction action2 = atomicAction.getActions().get(j);
                        String[] str = action2.toString().split("\n");
                        actualBit.addChild(new TextSpan(getStyle("action"), str[0] + " "));
                        actualBit.addChild(new TextSpan(getStyle("afterline"), "("));
                        for (int i1 = 1; i1 < str.length; i1++) {
                            String base = str[i1].trim();
                            if (base.startsWith("-"))
                                base = base.substring(1);
                            base = base.trim();
                            actualBit.addChild(new TextSpan(getStyle("afterline"), base+" "));
                        }
                        actualBit.addChild(new TextSpan(getStyle("afterline"), ")\n"));
                    }
                } else {
                    String[] str = action.toString().split("\n");
                    actualBit.addChild(new TextSpan(getStyle("action"), str[0] + " "));
                    actualBit.addChild(new TextSpan(getStyle("afterline"), "("));
                    for (int i1 = 1; i1 < str.length; i1++) {
                        String base = str[i1].trim();
                        if (base.startsWith("-"))
                            base = base.substring(1);
                        base = base.trim();
                        actualBit.addChild(new TextSpan(getStyle("afterline"), base+" "));
                    }
                    actualBit.addChild(new TextSpan(getStyle("afterline"), ")\n"));
                }
            }
        }
        return actualBit;
    }
}
