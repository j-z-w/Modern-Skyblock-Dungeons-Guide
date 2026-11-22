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

package kr.syeyoung.dungeonsguide.mod.commands;

import com.mojang.brigadier.context.CommandContext;
import kr.syeyoung.dungeonsguide.mod.events.impl.ClientTickEvent;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.configv3.ConfigGuiScreenAdapter;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.HUDLocationConfig;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.FeatureCollectDiagnostics;
import kr.syeyoung.dungeonsguide.mod.features.impl.party.playerpreview.api.ApiFetcher;
import kr.syeyoung.dungeonsguide.mod.features.impl.party.playerpreview.api.SkinFetcher;
import kr.syeyoung.dungeonsguide.mod.gui.elements.GlobalHUDScale;
import kr.syeyoung.dungeonsguide.mod.gui.elements.image.ImageTexture;
import kr.syeyoung.dungeonsguide.mod.gui.xml.DomElementRegistry;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;
import net.minecraft.network.chat.Component;


public class CommandDungeonsGuide {

    // registered as redirects to /dg
    public static final String[] ALIASES = {
            "dungeonsguide",
            "dungeonguide", //because people keep saying "dungeon guide" the singular
            "deegee", //in case another mod/server somehow uses a command "/dg" and no one wants to type out the entire name "dungeonsguide"
            "던전가이드", //easter egg
            "던전안내" //easter egg
            // for each new alias, add a new line
    };

    @DGCommand("dg")
    public void openConfig() {
        target = new ConfigGuiScreenAdapter(null);
    }

    @DGCommand("dg gui")
    public void openGuiConfig() {
        target = new ConfigGuiScreenAdapter(null, new GlobalHUDScale(new HUDLocationConfig(null)));
    }

    @DGCommand("dg aliases")
    public void showAlias() {
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide aliases§7::"));
        ChatTransmitter.addToQueue(Component.literal(" §7- §e/dungeonsguide"));
        ChatTransmitter.addToQueue(Component.literal(" §7- §e/dungeonguide"));
        ChatTransmitter.addToQueue(Component.literal(" §7- §e/deegee"));
    }

    @DGCommand("dg {text}")
    public void showHelp(@CommandParam(value = "text", stringType = CommandParam.EnumStringType.GREEDY) String text) {
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg §7-§fOpens configuration gui"));
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg gui §7-§fOpens configuration gui"));
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg scale [scale] §7-§fSets the Global HUD scale (Also disables Minecraft default HUD scale for you as well if you haven't already.)"));
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg help §7-§fShows command help"));
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg purge §7-§f Purge api cache."));
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §e/dg unload §7-§f Unload Current Version of Dungeons Guide, to load the new version"));
    }

    @DGCommand("dg scale reset")
    @DGCommand("dg setscale reset")
    @DGCommand("dg uiscale reset")
    @DGCommand("dg setuiscale reset")
    public void resetUIScale() {
        FeatureRegistry.GLOBAL_HUD_SCALE.<Double>getParameter("scale").setValue(1d);
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §eGlobal HUD scale successfully reset to 1."));
    }

    @DGCommand("dg scale {scale}")
    @DGCommand("dg setuiscale {scale}")
    @DGCommand("dg setscale {scale}")
    @DGCommand("dg uiscale {scale}")
    public void setUIScale(@CommandParam(value = "scale", min = 0.5, max = 10) double theScale, CommandContext<FabricClientCommandSource> context) {
        String alias = context.getNodes().get(1).getNode().getName();

        if (theScale < 0.01 || theScale > (Math.PI + Math.E)) {
            ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §cSorry, but while " + theScale + " is a valid number, it is not a suitable GUI scale. Try again, §eor reset your Global HUD scale with §6/dg " + alias + " reset§e."));
            return;
        }
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §aSuccessfully set your Global HUD scale to " + theScale + ". §eTo reset your Global HUD scale, run §6/dg " + alias + " reset§e."));
        FeatureRegistry.GLOBAL_HUD_SCALE.<Boolean>getParameter("mc").setValue(false);
        FeatureRegistry.GLOBAL_HUD_SCALE.<Double>getParameter("scale").setValue(theScale);
    }

    @Setter
    private Screen target;

    @SubscribeEvent
    public void onTick(ClientTickEvent e) {
        try {
            if (target != null && e.phase == ClientTickEvent.Phase.START) {
                Minecraft.getInstance().setScreen(target);
                target = null;
            }
        } catch (Exception t) {
            FeatureCollectDiagnostics.queueSendLogAsync(t);
            t.printStackTrace();
        }
    }

    @DGCommand("dg purge")
    public void purgeCommand() {
        ApiFetcher.purgeCache();
        ImageTexture.imageMap.clear();
        SkinFetcher.purgeCache();
        DomElementRegistry.onResourceManagerReload();

        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: §fSuccessfully purged API Cache!"));
    }

    @DGCommand("dg unload")
    public void unloadCommand() {
        Main.getMain().unloadWithoutStacktraceReference();
    }
}
