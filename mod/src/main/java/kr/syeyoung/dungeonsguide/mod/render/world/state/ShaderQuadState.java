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

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import kr.syeyoung.dungeonsguide.mod.shader.ShaderProgram;
import kr.syeyoung.dungeonsguide.mod.shader.ShaderUniforms;
import lombok.AllArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * A quad drawn with a DG shader and its own uniform block, so DGWorldRenderer's world pass draws it with its own
 * render pass instead of the buffer source (render types can't carry per-draw uniforms).
 */
@AllArgsConstructor
public class ShaderQuadState implements DGWorldRenderState {
    private final ShaderProgram shader;
    private final ShaderUniforms uniforms;
    private final Identifier texture;
    private final Vec3[] corners;
    private final float[][] uvs;
    private final int color;
    private final boolean depth;

    private record BatchKey(RenderPipeline pipeline, Identifier texture, ByteBuffer uniformBytes) {}

    /**
     * Draws a run of consecutive shader quads. Quads sharing pipeline, texture and uniform values go into one mesh
     * and one render pass: a route display records hundreds of arrow quads per frame, and a buffer pair plus a render
     * pass for each one took the game to ~5 fps (profiled on Hypixel, 2026-11-22).
     */
    public static void drawBatched(List<ShaderQuadState> quads, RenderTarget target, Function<ShaderQuadState, RenderPipeline> pipelineFor, Vec3 camera) {
        Map<BatchKey, List<ShaderQuadState>> groups = new LinkedHashMap<>();
        for (ShaderQuadState quad : quads) {
            ByteBuffer uniformBytes = ByteBuffer.allocate(quad.uniforms.blockSize());
            quad.writeUniforms(uniformBytes);
            groups.computeIfAbsent(new BatchKey(pipelineFor.apply(quad), quad.texture, uniformBytes), k -> new ArrayList<>()).add(quad);
        }
        for (Map.Entry<BatchKey, List<ShaderQuadState>> group : groups.entrySet()) {
            group.getValue().get(0).draw(group.getValue(), target, group.getKey().pipeline(), camera);
        }
    }

    // draws all of quads, which share this quad's texture and uniform values, with one render pass
    private void draw(List<ShaderQuadState> quads, RenderTarget target, RenderPipeline pipeline, Vec3 camera) {
        MeshData mesh;
        try (ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(pipeline.getVertexFormat().getVertexSize() * 4 * quads.size())) {
            BufferBuilder builder = new BufferBuilder(byteBufferBuilder, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
            for (ShaderQuadState quad : quads) {
                for (int i = 0; i < 4; i++) {
                    builder.addVertex((float) (quad.corners[i].x - camera.x), (float) (quad.corners[i].y - camera.y), (float) (quad.corners[i].z - camera.z))
                            .setUv(quad.uvs[i][0], quad.uvs[i][1]).setColor(quad.color);
                }
            }
            mesh = builder.build();
            if (mesh == null) return;

            ByteBuffer uniformData = MemoryUtil.memAlloc(uniforms.blockSize());
            try (GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "Dungeons Guide world shader quad", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                 GpuBuffer uniformBuffer = RenderSystem.getDevice().createBuffer(() -> "Dungeons Guide " + uniforms.blockName() + " UBO", GpuBuffer.USAGE_UNIFORM, writeUniforms(uniformData))) {
                AbstractTexture abstractTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Dungeons Guide world shader quad",
                        target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms()
                            .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f()));
                    renderPass.setUniform(uniforms.blockName(), uniformBuffer);
                    renderPass.setPipeline(pipeline);
                    renderPass.bindTexture("Sampler0", abstractTexture.getTextureView(), abstractTexture.getSampler());
                    renderPass.setVertexBuffer(0, vertexBuffer);
                    renderPass.setIndexBuffer(indices.getBuffer(mesh.drawState().indexCount()), indices.type());
                    renderPass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
            } finally {
                MemoryUtil.memFree(uniformData);
                mesh.close();
            }
        }
    }

    // not through the buffer source: DGWorldRenderer's world pass calls draw at this state's place in the order
    @Override
    public void submit(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camera) {
    }

    private ByteBuffer writeUniforms(ByteBuffer buffer) {
        uniforms.write(buffer);
        buffer.rewind();
        return buffer;
    }

    public ShaderProgram getShader() {
        return shader;
    }

    public boolean isDepth() {
        return depth;
    }
}
