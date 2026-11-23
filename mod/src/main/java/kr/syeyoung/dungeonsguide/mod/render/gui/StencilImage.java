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

package kr.syeyoung.dungeonsguide.mod.render.gui;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kr.syeyoung.dungeonsguide.mod.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lombok.Getter;

/**
 * An offscreen color texture DG's gui draws into (D74/D76); the finished image goes back into
 * vanilla's gui as one textured element (D80). O1: no stencil attachment on 26.3, clipping is
 * scissor-only so the name is historical.
 */
public class StencilImage implements AutoCloseable {
    private final String label;
    @Getter
    private GpuTexture colorTexture;
    @Getter
    private GpuTextureView colorView;
    @Getter
    private int width, height;

    public StencilImage(String label) {
        this.label = label;
    }

    public void ensureSize(int width, int height) {
        if (colorTexture != null && this.width == width && this.height == height) return;
        close();
        this.width = width;
        this.height = height;

        colorTexture = RenderSystem.getDevice().createTexture(() -> label, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        colorView = RenderSystem.getDevice().createTextureView(colorTexture);
    }

    // D80: the finished image goes back into vanilla's gui as one textured element covering the window
    public void extractComposite(GuiGraphicsExtractor graphics) {
        float guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        ((GuiGraphicsExtractorAccessor) graphics).dungeonsguide$getGuiRenderState().addGuiElement(new Composite(colorView, width / guiScale, height / guiScale));
    }

    private record Composite(GpuTextureView view, float width, float height) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            // gl textures start at the bottom row
            consumer.addVertex(0, 0, 0).setUv(0, 1).setColor(-1);
            consumer.addVertex(0, height, 0).setUv(0, 0).setColor(-1);
            consumer.addVertex(width, height, 0).setUv(1, 0).setColor(-1);
            consumer.addVertex(width, 0, 0).setUv(1, 1).setColor(-1);
        }

        // DG draws with translucent blending into a cleared image, so the image is premultiplied
        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return null;
        }

        // vanilla's GuiRenderState drops elements without bounds; the composite covers the whole window
        @Override
        public @Nullable ScreenRectangle bounds() {
            return new ScreenRectangle(0, 0, (int) Math.ceil(width), (int) Math.ceil(height));
        }
    }

    @Override
    public void close() {
        if (colorView != null) {
            colorView.close();
            colorView = null;
        }
        if (colorTexture != null) {
            colorTexture.close();
            colorTexture = null;
        }
    }
}
