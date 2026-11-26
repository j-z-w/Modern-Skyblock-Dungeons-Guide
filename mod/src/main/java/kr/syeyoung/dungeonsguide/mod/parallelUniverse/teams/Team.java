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

package kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams;

import lombok.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.*;

@RequiredArgsConstructor
public class Team {
    @Getter
    private final String teamName;
    // the server's components, deep copies (D106)
    @Getter
    private Component prefix;
    @Getter
    private Component suffix;
    @Getter
    private Component displayName;

    @Getter @Setter
    private ChatFormatting color;
    @Getter @Setter
    private NameTagVisibility nameTagVisibility;

    private Set<String> players = new HashSet<>();

    // UPDATE packets set these on the live team object; bump so cached formatted names invalidate
    public void setPrefix(Component prefix) {
        this.prefix = prefix;
        TeamManager.INSTANCE.bump();
    }

    public void setSuffix(Component suffix) {
        this.suffix = suffix;
        TeamManager.INSTANCE.bump();
    }

    public void setDisplayName(Component displayName) {
        this.displayName = displayName;
        TeamManager.INSTANCE.bump();
    }

    public Set<String> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public void addTeamMember(String player) {
        players.add(player);
        TeamManager.INSTANCE.registerTeamMember(this, player);
    }
    public void removeTeamMember(String player) {
        players.remove(player);
        TeamManager.INSTANCE.unregisterTeamMember( player);
    }
}
