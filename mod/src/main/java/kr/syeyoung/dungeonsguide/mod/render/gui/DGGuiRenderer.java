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

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import kr.syeyoung.dungeonsguide.mod.render.gui.state.DGRenderState;
import kr.syeyoung.dungeonsguide.mod.render.gui.state.ItemState;
import kr.syeyoung.dungeonsguide.mod.render.gui.state.ShaderShapeState;
import kr.syeyoung.dungeonsguide.mod.shader.ShaderUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Vector4f;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Draws the render states of one StencilImage (D71/D76). Mirrors vanilla's GuiRenderer: contiguous
 * states with the same pipeline, texture setup and scissor become one staged draw, then one render
 * pass issues them all. O1: no stencil on 26.3, clipping is scissor-only; StencilState is a no-op and
 * mask geometry (stencilMask) is skipped so masked content renders without its mask.
 */
public class DGGuiRenderer implements AutoCloseable {
    public static final DGGuiRenderer INSTANCE = new DGGuiRenderer();

    private static final int SLOT_TEXTURE_SIZE = 16;

    private final StagedVertexBuffer vertexBuffer = new StagedVertexBuffer(() -> "Dungeons Guide gui", 786432);
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("Dungeons Guide gui");
    private final Projection projection = new Projection();
    private final DynamicGpuDataStorageMapped<ShaderUniforms> shaderUniforms =
            new DynamicGpuDataStorageMapped<>("Dungeons Guide uniforms", 64, GpuBuffer.USAGE_UNIFORM, 16);
    private GuiItemAtlas itemAtlas;

    // no render phase hook on 26.3: the image is drawn during extraction, before vanilla's gui pass
    // composites it back as one textured element (D76/D80)
    public void submit(StencilImage image, List<DGRenderState> states) {
        renderInto(image, states, null);
    }

    public void renderInto(StencilImage image, List<DGRenderState> states, GpuBufferSlice fog) {
        if (states.isEmpty() || image.getColorView() == null) return;
        ProfilerFiller profiler = Profiler.get();
        profiler.push("Dungeons Guide gui");
        try {
            prepareItems(states);
            List<Draw> draws = upload(states);
            draw(image, draws);
        } finally {
            vertexBuffer.endDraw();
            vertexBuffer.endFrame();
            shaderUniforms.endFrame();
            if (itemAtlas != null) itemAtlas.endFrame();
            profiler.pop();
        }
    }

    // items render into a shared atlas texture first (D100); slot views must exist before vertex upload
    private void prepareItems(List<DGRenderState> states) {
        Set<Object> identities = new HashSet<>();
        for (DGRenderState state : states)
            if (state instanceof ItemState item) identities.add(item.getItem().getModelIdentity());
        if (identities.isEmpty()) return;

        if (itemAtlas == null || !itemAtlas.tryPrepareFor(identities)) {
            int textureSize = GuiItemAtlas.computeTextureSizeFor(SLOT_TEXTURE_SIZE, identities.size());
            if (itemAtlas != null) itemAtlas.close();
            itemAtlas = new GuiItemAtlas(Minecraft.getInstance().gameRenderer.featureRenderDispatcher(), textureSize, SLOT_TEXTURE_SIZE);
        }
        for (DGRenderState state : states)
            if (state instanceof ItemState item) item.setSlot(itemAtlas.getOrUpdate(item.getItem()));
    }

    private record Draw(StagedVertexBuffer.Draw draw, RenderPipeline pipeline, TextureSetup textureSetup,
                        Rectangle scissor, String uniformName, GpuBufferSlice uniforms) {}

    private List<Draw> upload(List<DGRenderState> states) {
        List<Draw> draws = new ArrayList<>();
        RenderPipeline pipeline = null;
        TextureSetup textureSetup = null;
        Rectangle scissor = null;
        for (DGRenderState state : states) {
            // O1 degradation: mask-only draws write no color (they were stencil writes on 26.1)
            if (state.stencilMask()) continue;
            RenderPipeline statePipeline = state.pipeline();
            if (statePipeline == null) continue; // stencil steps are no-ops now
            // shader shapes carry per-draw uniforms, they can't share a draw
            boolean ownDraw = state instanceof ShaderShapeState;
            TextureSetup stateTextureSetup = state.textureSetup();
            Rectangle stateScissor = state.scissor();
            if (ownDraw || !Objects.equals(statePipeline, pipeline) || !Objects.equals(stateTextureSetup, textureSetup) || !Objects.equals(stateScissor, scissor)) {
                String uniformName = null;
                GpuBufferSlice uniforms = null;
                if (state instanceof ShaderShapeState shape) {
                    uniformName = shape.getUniforms().blockName();
                    uniforms = shaderUniforms.writeData(shape.getUniforms());
                }
                draws.add(new Draw(vertexBuffer.appendDraw(statePipeline.getVertexFormatBinding(0), statePipeline.getPrimitiveTopology()),
                        statePipeline, stateTextureSetup, stateScissor, uniformName, uniforms));
                pipeline = statePipeline;
                textureSetup = stateTextureSetup;
                scissor = stateScissor;
            }
            VertexConsumer consumer = vertexBuffer.getVertexBuilder(draws.get(draws.size() - 1).draw());
            state.buildVertices(consumer);
        }
        for (Draw d : draws) vertexBuffer.requestIndexCount(d.draw());
        vertexBuffer.upload();
        RenderSystem.resizeAllAutoStorageIndexBuffers();
        return draws;
    }

    private void draw(StencilImage image, List<Draw> draws) {
        projection.setupOrtho(1000.0f, 11000.0f, image.getWidth(), image.getHeight(), true);
        RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new org.joml.Matrix4f().setTranslation(0, 0, -11000));

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Dungeons Guide gui", image.getColorView(), Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            for (Draw d : draws) {
                StagedVertexBuffer.ExecuteInfo info = vertexBuffer.getExecuteInfo(d.draw());
                if (info == null) continue;
                pass.setPipeline(RenderSystem.getCompiledPipeline(d.pipeline()));
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                if (d.scissor() != null)
                    pass.enableScissor(d.scissor().x, d.scissor().y, d.scissor().width, d.scissor().height);
                else
                    pass.disableScissor();
                if (d.textureSetup().texure0() != null)
                    pass.setUniform("Sampler0", d.textureSetup().texure0(), d.textureSetup().sampler0());
                if (d.textureSetup().texure1() != null)
                    pass.setUniform("Sampler1", d.textureSetup().texure1(), d.textureSetup().sampler1());
                if (d.textureSetup().texure2() != null)
                    pass.setUniform("Sampler2", d.textureSetup().texure2(), d.textureSetup().sampler2());
                if (d.uniforms() != null)
                    pass.setUniform(d.uniformName(), d.uniforms());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
    }

    @Override
    public void close() {
        vertexBuffer.close();
        projectionMatrixBuffer.close();
        shaderUniforms.close();
        if (itemAtlas != null) {
            itemAtlas.close();
            itemAtlas = null;
        }
    }
}
