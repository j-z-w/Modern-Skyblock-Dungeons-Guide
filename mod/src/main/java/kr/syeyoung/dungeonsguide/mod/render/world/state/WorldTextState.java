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
package kr.syeyoung.dungeonsguide.mod.render.world.state;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.AllArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Text facing the camera, centered on pos. 1.8.9's legacy formatting codes in the string still apply:
 * FormattedText.of runs them through StringDecomposer.
 */
@AllArgsConstructor
public class WorldTextState implements DGWorldRenderState {
    private final String text;
    private final Vec3 pos;
    private final int color;
    private final float scale;
    // scale grows with distance, so the text keeps its size on screen
    private final boolean increase;
    private final boolean blackBox;
    private final boolean depth;

    @Override
    public void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Vec3 renderPos = pos.subtract(camera);

        float lScale = scale;
        if (increase) {
            double distance = renderPos.length();
            double multiplier = distance / 120f; //mobs only render ~120 blocks away
            lScale *= 0.45f * multiplier;
        }

        poseStack.pushPose();
        poseStack.translate(renderPos.x, renderPos.y, renderPos.z);
        // 1.8.9 rotated by the player's view yaw / pitch and scaled by (-s, -s, s), 26.1 name tags use the camera rotation
        poseStack.mulPose(mc.gameRenderer.mainCamera().rotation());
        poseStack.scale(lScale, -lScale, lScale);

        // 1.8.9's black box was black at 0.25 alpha; submitText draws the background when backgroundColor != 0
        collector.submitText(poseStack,
                -(font.width(text) / 2f), 0,
                Language.getInstance().getVisualOrder(FormattedText.of(text)),
                false,
                depth ? Font.DisplayMode.NORMAL : Font.DisplayMode.SEE_THROUGH,
                LightCoordsUtil.FULL_BRIGHT, color,
                blackBox ? 0x40000000 : 0, 0);
        poseStack.popPose();
    }
}
