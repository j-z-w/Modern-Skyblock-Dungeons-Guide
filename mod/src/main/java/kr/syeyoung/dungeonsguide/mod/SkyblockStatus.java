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

package kr.syeyoung.dungeonsguide.mod;

import kr.syeyoung.dungeonsguide.mod.events.impl.ClientTickEvent;
import com.google.common.collect.Sets;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.events.impl.DungeonLeftEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.HypixelJoinedEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.SkyblockJoinedEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.SkyblockLeftEvent;
// import kr.syeyoung.dungeonsguide.mod.fakeserver.DungeonServerLaunchUtils;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Objective;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.Score;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard.ScoreboardManager;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.TeamManager;
import kr.syeyoung.dungeonsguide.mod.utils.TextUtils;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;

import java.util.Collection;
import java.util.Set;

public class SkyblockStatus {
    boolean wasOnHypixel = false;
    private static String locationName;

    public static String getLocationName() {
        return locationName;
    }

    public static void setLocationName(String locationName) {
        SkyblockStatus.locationName = locationName;
    }


    @SubscribeEvent
    public void onTick(ClientTickEvent ev) {
        if (ev.phase != ClientTickEvent.Phase.START) return;

        SkyblockStatus skyblockStatus = DungeonsGuide.getDungeonsGuide().getSkyblockStatus();
        boolean isOnDungeonPrev = isOnDungeon();
        boolean isOnSkyblockPrev = isOnSkyblock();
        boolean isRunningDungeonPrev = isDungeonRunning;
        skyblockStatus.updateStatus();

        if (!wasOnHypixel && skyblockStatus.isOnHypixel()) {
            DungeonsGuide.getDungeonsGuide().getEventBus().fireEvent(new HypixelJoinedEvent());
        }
        wasOnHypixel = skyblockStatus.isOnHypixel();

        if (isOnSkyblockPrev && !isOnSkyblock()) {
            DungeonsGuide.getDungeonsGuide().getEventBus().fireEvent(new SkyblockLeftEvent());
        } else if (!isOnSkyblockPrev && isOnSkyblock()) {
            DungeonsGuide.getDungeonsGuide().getEventBus().fireEvent(new SkyblockJoinedEvent());
        }

        if (isOnDungeonPrev && !isOnDungeon() || isRunningDungeonPrev && !isDungeonRunning) {
            DungeonsGuide.getDungeonsGuide().getEventBus().fireEvent(new DungeonLeftEvent());
        }



    }



    public static boolean isOnSkyblock(){
        SkyblockStatus skyblockStatus = DungeonsGuide.getDungeonsGuide().getSkyblockStatus();

        return skyblockStatus != null && (skyblockStatus.isOnSkyblock || skyblockStatus.forceIsOnDungeon);
    }

    public static boolean isOnDungeon() {
        SkyblockStatus skyblockStatus = DungeonsGuide.getDungeonsGuide().getSkyblockStatus();

        return skyblockStatus != null && (skyblockStatus.forceIsOnDungeon || skyblockStatus.isOnDungeon);
    }


    private boolean isOnSkyblock;
    private boolean isOnDungeon;

    private boolean isDungeonRunning;

    @Getter
    @Setter
    private boolean forceIsOnDungeon;

    @Getter
    @Setter
    private boolean forceIsOnDungeon2;

    public static boolean isOnHypixel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return false;
        // 1.8.9 kept the server brand on the player, 26.1 on the connection
        String clientBrand = mc.getConnection().serverBrand();
        if (clientBrand == null) return false;
        // 1.8.9 also checked mc.loadingScreen != null, which is set once the game has started
        if (!mc.hasSingleplayerServer()) {
            return clientBrand.startsWith("Hypixel BungeeCord");
        }
        return false;
    }

    private static final Set<String> SKYBLOCK_IN_ALL_LANGUAGES = Sets.newHashSet("SKYBLOCK");

    // the sidebar's location line, " §7⏣ The Catacombs (F3)". Hypixel now draws the marker with its resource pack's
    // icon font, a private use character (seen: U+E067) instead of ⏣
    static boolean isLocationLine(String team) {
        if (team.length() < 4 || !team.startsWith(" §7")) return false;
        char marker = team.charAt(3);
        return marker == '⏣' || Character.getType(marker) == Character.PRIVATE_USE;
    }

    // last sidebar scan result is pure function of (scoreboard, team, objective, context, force flag);
    // recompute only when one of them actually changed instead of formatting every line every tick
    private int lastStatusVersion = -1;
    private Objective lastStatusObjective;
    private DungeonContext lastStatusContext;
    private boolean lastStatusForced;

    public void updateStatus() {
        // TODO: fakeserver is not in the first 26.1 build. was: DungeonServerLaunchUtils.isDungeonIntegratedServerRunning() && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null || forceIsOnDungeon2
        forceIsOnDungeon = forceIsOnDungeon2;
        if (forceIsOnDungeon) {
            locationName = "TEST DG";
        }

        if (!isOnHypixel()) {
            isOnDungeon = false;
            isOnSkyblock = false;
            lastStatusVersion = -1;
            return;
        }

        Objective objective = ScoreboardManager.INSTANCE.getSidebarObjective();
        if (objective == null) return;

        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade() == null
                ? null : DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        int statusVersion = ScoreboardManager.INSTANCE.getVersion() + TeamManager.INSTANCE.getVersion() + objective.getVersion();
        if (statusVersion == lastStatusVersion && lastStatusObjective == objective
                && lastStatusContext == context && lastStatusForced == forceIsOnDungeon) {
            return;
        }
        lastStatusVersion = statusVersion;
        lastStatusObjective = objective;
        lastStatusContext = context;
        lastStatusForced = forceIsOnDungeon;

        String objectiveName = TextUtils.stripColor(TextUtils.getFormattedText(objective.getDisplayName()));
        boolean skyblockFound = false;
        for (String skyblock : SKYBLOCK_IN_ALL_LANGUAGES) {
            if (objectiveName.startsWith(skyblock)) {
                skyblockFound = true;
                isOnSkyblock = true;
                break;
            }
        }

        if (!skyblockFound) {
            isOnSkyblock = false;
            isOnDungeon = false;
            return;
        }

        boolean foundDungeon = false;
        // dungeon presence comes from this sidebar snapshot only, never a location left over from an earlier world
        String snapshotLocation = null;

        Collection<Score> scores = objective.getScores();
        for (Score sc : scores) {
            String strippedLine = TextUtils.keepScoreboardCharacters(
                    TextUtils.stripColor(sc.getJustTeam())).trim();
            if (strippedLine.contains("Cleared: ")) {
                foundDungeon = true;
                if(context != null){
                    // an unexpected sidebar format must not crash the game every tick
                    try {
                        context.setPercentage(Integer.parseInt(
                                strippedLine.substring(9).split(" ")[0]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (isLocationLine(sc.getJustTeam())) {
                snapshotLocation = strippedLine.trim();
                setLocationName(snapshotLocation);
            }
        }

        isDungeonRunning = foundDungeon;
        isOnDungeon = foundDungeon || (snapshotLocation != null && snapshotLocation.startsWith("The Catacombs"));
    }

}
