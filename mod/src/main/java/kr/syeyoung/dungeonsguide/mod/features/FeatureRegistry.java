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

package kr.syeyoung.dungeonsguide.mod.features;

import kr.syeyoung.dungeonsguide.mod.config.guiconfig.configv3.ParameterItem;
import kr.syeyoung.dungeonsguide.mod.config.types.TCBoolean;
import kr.syeyoung.dungeonsguide.mod.config.types.TCDouble;
import kr.syeyoung.dungeonsguide.mod.events.annotations.EventHandlerRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.advanced.*;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureBoxBats;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureHighlightDoors;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonCurrentRoomSecrets;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonRealTime;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonRoomName;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonSBTime;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonSecrets;
import kr.syeyoung.dungeonsguide.mod.features.impl.dungeon.FeatureDungeonTombs;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.*;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.tooltip.FeatureNotifications;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.*;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers.*;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers.mechanicbrowser.FeatureMechanicBrowse;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.pfrequest.FeatureRequestCalculation;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.precalclist.FeaturePrecalcList;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay.LiveRouteRegistry;
import kr.syeyoung.dungeonsguide.mod.features.richtext.DefaultTextHUDFeatureStyleFeature;
import kr.syeyoung.dungeonsguide.mod.overlay.OverlayManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeatureRegistry {
    @Getter
    private static final List<AbstractFeature> featureList = new ArrayList<AbstractFeature>();
    private static final Map<String, AbstractFeature> featureByKey = new HashMap<String, AbstractFeature>();
    @Getter
    private static final Map<String, List<AbstractFeature>> featuresByCategory = new HashMap<String, List<AbstractFeature>>();
    @Getter
    private static final Map<String, String> categoryDescription = new HashMap<>();

    public static AbstractFeature getFeatureByKey(String key) {
        return featureByKey.get(key);
    }


    public static <T extends AbstractFeature> T register(T abstractFeature) {
        if (featureByKey.containsKey(abstractFeature.getKey())) throw new IllegalArgumentException("DUPLICATE FEATURE DEFINITION");
        featureList.add(abstractFeature);
        featureByKey.put(abstractFeature.getKey(), abstractFeature);
        List<AbstractFeature> features = featuresByCategory.get(abstractFeature.getCategory());
        if (features == null)
            features = new ArrayList<AbstractFeature>();
        features.add(abstractFeature);
        featuresByCategory.put(abstractFeature.getCategory(), features);

        EventHandlerRegistry.registerEvents(abstractFeature);

        return abstractFeature;
    }

    // Global HUD
    public static final DefaultTextHUDFeatureStyleFeature DEFAULT_STYLE = register(new DefaultTextHUDFeatureStyleFeature());

    public static final SimpleFeature GLOBAL_HUD_SCALE = register(new SimpleFeature("Global HUD", "Global HUD Scale", "Configure to use gui scale from Minecraft or specify custom one", "hud.globalscale", false) {
        private boolean init = false;
        {
            addParameter("mc", new FeatureParameter<Boolean>("mc", "Minecraft", "Enable to use minecraft default hud scale", true, TCBoolean.INSTANCE, a -> {
                if (init)
                    OverlayManager.getEventHandler().guiResize(null);
            }));
            addParameter("scale", new FeatureParameter<Double>("scale", "Scale", "Custom HUD Scale",1.0, TCDouble.INSTANCE, a -> {
                if (init)
                    OverlayManager.getEventHandler().guiResize(null);

            }).setWidgetGenerator((param) -> new ParameterItem(param, new TCDouble.DoubleEditWidget(param, 0.1, Double.POSITIVE_INFINITY))));
            init = true;
        }

        @Override
        public boolean isDisableable() {
            return false;
        }
    });


    // Pathfinding & Secrets

    // Display All
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_PATHFINDALL_PARENT = register(new PathfindLineProperties("Pathfinding & Secrets.Display All", "Parent Line Settings", "Line Settings to be used by default", "secret.lineproperties.apf.parent", false, null));
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_PATHFINDALL_ITEM_DROP = register(new PathfindLineProperties("Pathfinding & Secrets.Display All", "Item Drop Line Settings", "Line Settings when pathfind to Item Drop, when using above feature", "secret.lineproperties.apf.itemdrop", true, SECRET_LINE_PROPERTIES_PATHFINDALL_PARENT));
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_PATHFINDALL_ESSENCE = register(new PathfindLineProperties("Pathfinding & Secrets.Display All", "Essence Line Settings", "Line Settings when pathfind to Essence, when using above feature", "secret.lineproperties.apf.essence", true, SECRET_LINE_PROPERTIES_PATHFINDALL_PARENT));
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_PATHFINDALL_CHEST = register(new PathfindLineProperties("Pathfinding & Secrets.Display All", "Chest Line Settings", "Line Settings when pathfind to Chest, when using above feature", "secret.lineproperties.apf.chest", true, SECRET_LINE_PROPERTIES_PATHFINDALL_PARENT));
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_PATHFINDALL_BAT = register(new PathfindLineProperties("Pathfinding & Secrets.Display All", "Bat Line Settings", "Line Settings when pathfind to Bat, when using above feature", "secret.lineproperties.apf.bat", true, SECRET_LINE_PROPERTIES_PATHFINDALL_PARENT));
    public static final FeaturePathfindToAll SECRET_PATHFIND_ALL = register(new FeaturePathfindToAll());
    public static final LiveRouteRegistry SECRET_ROUTE_REGISTRY = register(new LiveRouteRegistry());

    // Display One
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_AUTOPATHFIND = register(new PathfindLineProperties("Pathfinding & Secrets.Display One", "Line Settings", "Line Settings when pathfinding using above features", "secret.lineproperties.autopathfind", true, null));

    public static final SimpleFeature SECRET_AUTO_START = register(new FeatureSingleTargetPathfind());

    // Smart Route
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_SMART_ROUTE = register(new PathfindLineProperties("Pathfinding & Secrets.Smart Route", "Line Settings", "Line Settings when pathfinding using above features", "secret.lineproperties.smartroute", true, null));
    public static final SimpleFeature SECRET_SMART_AUTO_START = register(new FeatureSmartRoute());

    // Secret Browser
    public static final PathfindLineProperties SECRET_LINE_PROPERTIES_SECRET_BROWSER = register(new PathfindLineProperties("Pathfinding & Secrets.Secret Browser", "Line Settings", "Line Settings when pathfinding using Secret Browser", "secret.lineproperties.secretbrowser", true, null));
    public static final FeatureMechanicBrowse SECRET_BROWSE = register(new FeatureMechanicBrowse());

    // Blood Rush
    public static final PathfindLineProperties SECRET_BLOOD_RUSH_LINE_PROPERTIES = register(new PathfindLineProperties("Pathfinding & Secrets.Blood Rush", "Blood Rush Line Settings", "Line Settings to be used", "secret.lineproperties.bloodrush", false, null));
    public static final FeatureBloodRush SECRET_BLOOD_RUSH = register(new FeatureBloodRush());


    public static final FeatureCreateRefreshLine SECRET_CREATE_REFRESH_LINE = register(new FeatureCreateRefreshLine());
    public static final FeatureSkipRouteStep SECRET_SKIP_ROUTE_STEP = register(new FeatureSkipRouteStep());
    public static final FeatureFreezePathfind SECRET_FREEZE_LINES = register(new FeatureFreezePathfind());
    public static final FeatureTogglePathfind SECRET_TOGGLE_KEY = register(new FeatureTogglePathfind());
    public static final FeatureRequestCalculation SECRET_PATHFIND_REQUEST = register(new FeatureRequestCalculation());
    public static final FeaturePrecalcList SECRET_PRECALC_LIST = register(new FeaturePrecalcList());
    public static final FeatureDefaultPresetLoader LOAD_DEFAULT_PRESET = register(new FeatureDefaultPresetLoader());
    public static final FeatureCommunityRouteSteps SECRET_COMMUNITY_ROUTE_STEPS = register(new FeatureCommunityRouteSteps());

    public static final FeatureActions SECRET_ACTIONS = register(new FeatureActions());
    public static final FeatureDAGs SECRET_DAGS = register(new FeatureDAGs());

    public static final SimpleFeature SECRET_MARK_ALL = register(new SimpleFeature("Pathfinding & Secrets", "Mark All Remaining Secrets", "Small markers on every unfound secret in the current room (the routed target keeps its full highlight)", "secret.markall", true));

    public static final FeatureHighlightDoors DUNGEON_HIGHLIGHT_DOORS = register(new FeatureHighlightDoors());
    public static final FeatureBoxBats DUNGEON_BOX_BAT = register(new FeatureBoxBats());



    static {

        categoryDescription.put("Misc", "Misc features");
        categoryDescription.put("Misc.HUD", "HUDs that didn't fit other categories w/ Ability cooldown & DG Cooldown counter");
        categoryDescription.put("Misc.Chat Utils", "Cool Chat Utils that you should use other mods for");
        categoryDescription.put("Player & Mob", "Boxing specific mobs and player render related features");
        categoryDescription.put("Dungeon HUD", "Use information and warnings to display on screen in dungeons");
        categoryDescription.put("Dungeon HUD.In Dungeon HUD", "Useful information to view in dungeons");
        categoryDescription.put("Dungeon HUD.Alerts", "Alert HUDs w/ Fairy soul, watcher spawn alert");
        categoryDescription.put("Communication", "Communication between DG users and DG server");
        categoryDescription.put("Dungeon Party", "Party and Party finder related settings w/ Custom Party Finder & In Chat Profile Viewer & Reparty");
        categoryDescription.put("Dungeon Party.Reparty", "Reparty settings");
        categoryDescription.put("Debug", "Developer Settings");
        categoryDescription.put("Cosmetics", "Apply purchased Dungeons Guide cosmetics here. Cosmetics can be purchased at Dungeons Guide store, by clicking dg icon below");
        categoryDescription.put("Discord", "Discord RPC! (Supports ask-to-invite)");
        categoryDescription.put("Bossfight", "Anything that might help in bossfight");
        categoryDescription.put("Bossfight.Floor 7", "F7 Specific bossfight settings w/ Terminal Solvers");
        categoryDescription.put("Bossfight.Floor 7.Terminal", "Enable and configure Terminal Solvers.");
        categoryDescription.put("Bossfight.Floor 6", "F6 Specific bossfight HUDs and settings");
        categoryDescription.put("Bossfight.Floor 5", "F5 Specific bossfight settings w/ Real Livid detector");
        categoryDescription.put("Bossfight.Floor 4", "F4 Specific bossfight settings");
        categoryDescription.put("Puzzle Solvers", "Access puzzle room solvers here");
        categoryDescription.put("Pathfinding & Secrets", "Access pathfind&secret related settings here. Blood Rush & Secrets Waypoints & Secret Lines");
        categoryDescription.put("Pathfinding & Secrets.Blood Rush", "Start pathfind to nearest wither/blood doors");
        categoryDescription.put("Pathfinding & Secrets.Display One", "Start pathfind to nearest secret");
        categoryDescription.put("Pathfinding & Secrets.Secret Browser", "Browse mechanics & secrets in a room and trigger pathfind using ui");
        categoryDescription.put("Pathfinding & Secrets.Smart Route", "Generate a route that goes through all secrets in a room efficiently. Prefer this over Display One");
        categoryDescription.put("Pathfinding & Secrets.Display All", "Start pathfind to All secrets. Disable pathfind option inside to use this only for beacons");
        categoryDescription.put("Global HUD", "Configure settings that affect all the HUDs. Scale & Quick Text colors");
    }



    // Puzzle solvers, bossfight features, discord RPC and cosmetics are excluded
    // from this build (see mod/build.gradle sourceSet excludes).

    // Debug
    public static final FeatureDebug DEBUG = register(new FeatureDebug());
    public static final FeaturePathfinderDebug DEBUG_PFRES = register(new FeaturePathfinderDebug());
    public static final FeatureCompareRoom COMPARE_ROOM = register(new FeatureCompareRoom());
    // TODO: room editor is not in the first 26.1 build
//    public static final FeatureRoomEdit ADVANCED_ROOMEDIT = register(new FeatureRoomEdit());
    public static final FeatureRoomDebugInfo ADVANCED_DEBUG_ROOM = register(new FeatureRoomDebugInfo());
    public static final FeatureDebuggableMap ADVANCED_DEBUGGABLE_MAP = register(new FeatureDebuggableMap());
    public static final FeatureRoomCoordDisplay ADVANCED_COORDS = register(new FeatureRoomCoordDisplay());
    public static final FeatureDebugTrap ADVANCED_BAT = register(new FeatureDebugTrap());
    public static final FeatureChatCapture DEBUG_CHAT_CAPTURE = register(new FeatureChatCapture());

    public static final SimpleFeature DEBUG_BLOCK_CACHING = register(new SimpleFeature("Debug","Enable block getBlockCaching", "Cache all world.getBlockState callls", "debug.blockcache"));

    // Dungeon Party features are excluded from this build (party finder/kicker,
    // reparty, profile viewer - see mod/build.gradle).

    public static final FeatureNotifications NOTIFICATIONS = register(new FeatureNotifications());

    // Data Communication
    public static final SimpleFeature DUNGEON_INTERMODCOMM = register(new SimpleFeature("Communication", "Communicate With Other's Dungeons Guide", "Sends total secret in the room to others\nSo that they can use the data to calculate total secret in dungeon run\n\nThis automates player chatting action, (chatting data) Thus it might be against hypixel's rules.\nBut mods like auto-gg which also automate player action and is kinda allowed mod exist so I'm leaving this feature.\nThis option is use-at-your-risk and you'll be responsible for ban if you somehow get banned because of this feature\n(Although it is not likely to happen)\nDefaults to off", "dungeon.intermodcomm", false));


    // Dungeon HUD
    // Alerts
    public static final FeatureSoulRoomWarning DUNGEON_FAIRYSOUL = register(new FeatureSoulRoomWarning());

    // In Dungeon HUD
    public static final FeatureDungeonTombs DUNGEON_TOMBS = register(new FeatureDungeonTombs());
    public static final FeatureDungeonCurrentRoomSecrets DUNGEON_SECRETS_ROOM = register(new FeatureDungeonCurrentRoomSecrets());
    public static final FeatureDungeonSecrets DUNGEON_SECRETS = register(new FeatureDungeonSecrets());
    public static final FeatureDungeonSBTime DUNGEON_SBTIME = register(new FeatureDungeonSBTime());
    public static final FeatureDungeonRealTime DUNGEON_REALTIME = register(new FeatureDungeonRealTime());
    public static final FeatureDungeonRoomName DUNGEON_ROOMNAME = register(new FeatureDungeonRoomName());


//    public static final FeatureChestPrice BOSSFIGHT_CHESTPRICE = register(new FeatureChestPrice());

    // Misc

    public static final FeatureCollectDiagnostics COLLECT_ERRORS = register(new FeatureCollectDiagnostics());
//    public static final FeatureCollectDungeonRooms COLLECT_DUNGEON_ROOMS = register(new FeatureCollectDungeonRooms());

    // API


    public static final FeatureDetectFreeze FREEZE_DETECTOR = register(new FeatureDetectFreeze());

}
