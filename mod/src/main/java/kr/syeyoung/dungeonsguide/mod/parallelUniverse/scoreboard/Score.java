/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2023  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.parallelUniverse.scoreboard;

import kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.Team;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.TeamManager;
import kr.syeyoung.dungeonsguide.mod.utils.TextUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;

@Data
public class Score {
    private final String playerName;
    private final int score;
    // 26.1: the per score display name and number format, deep copied (D106)
    private final Component display;
    private final NumberFormat numberFormat;

    // component->string formatting runs every call otherwise; teams bump their version on any change.
    // each getter keeps its own stamp: a shared one lets one getter poison the other's cache.
    // excluded from equals/hashCode: mutable caches must not move a Score inside hash collections
    @EqualsAndHashCode.Exclude private int cachedTeamVersionName = -1;
    @EqualsAndHashCode.Exclude private int cachedTeamVersionJust = -1;
    @EqualsAndHashCode.Exclude private String cachedVisibleName;
    @EqualsAndHashCode.Exclude private String cachedJustTeam;

    public String getVisibleName() {
        int version = TeamManager.INSTANCE.getVersion();
        if (cachedTeamVersionName == version && cachedVisibleName != null) return cachedVisibleName;
        Team team =  TeamManager.INSTANCE.getPlayerTeam(playerName);
        String name;
        if (team != null) {
            name = TextUtils.getFormattedText(team.getPrefix()) + playerName + TextUtils.getFormattedText(team.getSuffix());
        } else {
            name = playerName;
        }
        cachedVisibleName = name;
        cachedTeamVersionName = version;
        return name;
    }
    public String getJustTeam() {
        int version = TeamManager.INSTANCE.getVersion();
        if (cachedTeamVersionJust == version && cachedJustTeam != null) return cachedJustTeam;
        Team team =  TeamManager.INSTANCE.getPlayerTeam(playerName);
        String name;
        if (team != null) {
            name = TextUtils.getFormattedText(team.getPrefix())+ TextUtils.getFormattedText(team.getSuffix());
        } else {
            name = playerName;
        }
        cachedJustTeam = name;
        cachedTeamVersionJust = version;
        return name;
    }
}
