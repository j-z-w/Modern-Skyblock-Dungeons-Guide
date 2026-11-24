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

package kr.syeyoung.dungeonsguide.mod.parallelUniverse.tab;

import com.mojang.authlib.GameProfile;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.Team;
import kr.syeyoung.dungeonsguide.mod.parallelUniverse.teams.TeamManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.GameType;
import kr.syeyoung.dungeonsguide.mod.utils.TextUtils;

@RequiredArgsConstructor
public class TabListEntry {
    @Getter
    private final GameProfile gameProfile;
    // properties?
    @Getter @Setter
    private final GameType gameMode;
    @Getter @Setter
    private int ping;
    @Getter
    private Component displayName;

    @Getter
    private String formatted;

    public void setDisplayName(Component displayName) {
        this.displayName = displayName;
        formatted = displayName == null ? null : TextUtils.getFormattedText(displayName);
        TabList.INSTANCE.bump();
    }

    // the no-displayName path formats team components every call; teams bump their version on any
    // change. each getter keeps its own stamp: a shared one lets one getter poison the other's cache
    private int cachedTeamVersionName = -1;
    private int cachedTeamVersionWithout = -1;
    private String cachedEffectiveName;
    private String cachedEffectiveWithoutName;

    public String getEffectiveName() {
        if (formatted != null) return formatted;

        int version = TeamManager.INSTANCE.getVersion();
        if (cachedTeamVersionName == version && cachedEffectiveName != null) return cachedEffectiveName;
        Team team = TeamManager.INSTANCE.getPlayerTeam(gameProfile.name());
        String name;
        if (team != null) {
            name = TextUtils.getFormattedText(team.getPrefix()) + gameProfile.name() + TextUtils.getFormattedText(team.getSuffix());
        } else {
            name = gameProfile.name();
        }
        cachedEffectiveName = name;
        cachedTeamVersionName = version;
        return name;
    }
    public String getEffectiveWithoutName() {
        if (formatted != null) return formatted;

        int version = TeamManager.INSTANCE.getVersion();
        if (cachedTeamVersionWithout == version && cachedEffectiveWithoutName != null) return cachedEffectiveWithoutName;
        Team team = TeamManager.INSTANCE.getPlayerTeam(gameProfile.name());
        String name;
        if (team != null) {
            name = TextUtils.getFormattedText(team.getPrefix()) + TextUtils.getFormattedText(team.getSuffix());
        } else {
            name = gameProfile.name();
        }
        cachedEffectiveWithoutName = name;
        cachedTeamVersionWithout = version;
        return name;
    }


    private boolean playerTexturesLoaded = false;
    // 1.8.9 kept the skin / cape locations and the model from SkinManager's callback, 26.1 gives one PlayerSkin
    private PlayerSkin skin;

    public boolean hasLocationSkin() {
        return this.skin != null;
    }

    private static String skinType(PlayerSkin skin) {
        return skin.model() == PlayerModelType.SLIM ? "slim" : "default";
    }

    public String getSkinType() {
        return skinType(this.skin == null ? DefaultPlayerSkin.get(this.gameProfile) : this.skin);
    }

    public Identifier getLocationSkin() {
        if (this.skin == null) {
            this.loadPlayerTextures();
        }
        return (this.skin == null ? DefaultPlayerSkin.get(this.gameProfile) : this.skin).body().texturePath();
    }

    public Identifier getLocationCape() {
        if (this.skin == null) {
            this.loadPlayerTextures();
        }
        return this.skin == null || this.skin.cape() == null ? null : this.skin.cape().texturePath();
    }

    protected void loadPlayerTextures() {
        synchronized(this) {
            if (!this.playerTexturesLoaded) {
                this.playerTexturesLoaded = true;
                Minecraft.getInstance().getSkinManager().get(this.gameProfile).thenAccept(loaded -> loaded.ifPresent(skin -> TabListEntry.this.skin = skin));
            }
        }
    }
}
