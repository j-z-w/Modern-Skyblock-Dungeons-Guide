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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A block's model in the world, through vanilla's block model submit.
 *
 * Degraded vs 26.1.2: depth=false can no longer be honored. submitBlockModel draws with the model's own
 * (always depth-tested) block render types and there is no no-depth override in the submit API. The flag is
 * ignored; callers keep their see-through box marker (e.g. the superboom cyan box) to show the target anyway.
 */
@AllArgsConstructor
public class BlockModelState {
    private final BlockState blockState;
    // the model's block space (0..1) to world
    private final Matrix4f transform;
    // false used to draw over the world; ignored on 26.3, see class javadoc
    private final boolean depth;

    public boolean isDepth() {
        return depth;
    }

    public void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera) {
        BlockModelRenderState renderState = new BlockModelRenderState();
        // 26.3 keeps the resolver on the entity render dispatcher as a private field; it is a thin
        // wrapper over the model manager, so resolving through our own is equivalent
        new BlockModelResolver(Minecraft.getInstance().getModelManager())
                .update(renderState, blockState, DisplayRenderer.BLOCK_DISPLAY_CONTEXT);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        poseStack.mulPose(transform);
        renderState.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
