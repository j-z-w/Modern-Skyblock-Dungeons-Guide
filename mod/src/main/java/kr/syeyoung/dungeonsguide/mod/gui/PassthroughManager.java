/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2024  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import kr.syeyoung.dungeonsguide.mod.events.impl.FogColorsEvent;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;


public class PassthroughManager {

    public static PassthroughManager INSTANCE = new PassthroughManager();
    private GpuTexture texture;
    @Getter
    private GpuTextureView textureView;

    // set by Passthrough.doRender: the captured texture is only ever consumed by that one
    // gui element, so without the request the copy is dead work. callers of captureWorld()
    // run before the frame's gui renders, so the copy lands one frame behind (passthrough
    // is cosmetic anyway - one frame behind is already documented behaviour)
    private volatile boolean captureRequested;
    public void requestCapture() {
        captureRequested = true;
    }

    @Getter
    private int fogColor = 0;

    @SubscribeEvent
    public void onFogColour(FogColorsEvent event) {
        fogColor = 0xff000000
                    | ((int) (event.red * 255) & 0xFF) << 16
                    | ((int) (event.green * 255) & 0xFF) << 8
                    | (int) (event.blue * 255) & 0xFF;
    }


    // 1.8.9 copied the world at RenderGameOverlayEvent.Pre. on 26.1 the hud is extracted before the world renders,
    // so the copy happens in the render phase right before DG's gui draws (GameRendererMixin)
    public void captureWorld() {
        if (!captureRequested) return; // no Passthrough element rendered: nothing reads the texture
        captureRequested = false;
        ProfilerFiller profiler = Profiler.get();
        profiler.push("Dungeons Guide - Passthrough");

        RenderTarget mainRenderTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int width = mainRenderTarget.width;
        int height = mainRenderTarget.height;

        if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            if (textureView != null) textureView.close();
            if (texture != null) texture.close();
            texture = RenderSystem.getDevice().createTexture(() -> "Dungeons Guide passthrough", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            textureView = RenderSystem.getDevice().createTextureView(texture);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(mainRenderTarget.getColorTexture(), texture, 0, 0, 0, 0, 0, width, height);

        profiler.pop();
    }
}
