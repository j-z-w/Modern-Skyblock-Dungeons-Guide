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
package kr.syeyoung.dungeonsguide.mod.render.world;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.syeyoung.dungeonsguide.mod.render.world.state.BlockModelState;
import kr.syeyoung.dungeonsguide.mod.render.world.state.DGWorldRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * DG world rendering on vanilla's submit nodes (26.3): RenderWorldEvent fires during level extraction and only
 * records states here; LevelRenderEvents.COLLECT_SUBMITS then submits them into the same frame's collector, which
 * draws them in the translucent custom geometry phase. Depth vs see-through is carried by each state's render type
 * pipeline, not by submission phase. Record order within a render type is preserved by batching.
 *
 * Degraded vs 26.1.2: ShaderQuadState's custom-pass draw is gone (repeat.fsh folded into a REPEAT sampler), and
 * WorldImageState is excluded (needs an offscreen pass plus a TextureManager-registered runtime texture).
 */
public class DGWorldRenderer {
    private static final List<DGWorldRenderState> states = new ArrayList<>();
    private static final List<BlockModelState> blockModels = new ArrayList<>();
    private static Vec3 camera = Vec3.ZERO;

    // called right before RenderWorldEvent fires for this frame's extraction
    public static void beginExtraction(Vec3 cameraPosition) {
        states.clear();
        blockModels.clear();
        camera = cameraPosition;
    }

    public static void record(DGWorldRenderState state) {
        states.add(state);
    }

    public static void record(BlockModelState state) {
        blockModels.add(state);
    }

    // COLLECT_SUBMITS: the recorded states append submit nodes for this frame
    public static void submitAll(LevelRenderContext context) {
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        // the render state's camera is already partial-tick interpolated and authoritative for this frame
        Vec3 frameCamera = context.levelState().cameraRenderState.pos;
        for (DGWorldRenderState state : states) {
            state.submit(collector, poseStack, frameCamera);
        }
        for (BlockModelState state : blockModels) {
            state.submit(poseStack, collector, frameCamera);
        }
    }

    // END_MAIN: if extraction was skipped (hidden level, F3+A) recorded states must not leak into the next frame
    public static void clear() {
        states.clear();
        blockModels.clear();
    }
}
