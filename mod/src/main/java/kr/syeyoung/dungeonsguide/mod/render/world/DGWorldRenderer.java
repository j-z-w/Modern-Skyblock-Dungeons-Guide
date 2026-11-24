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
import kr.syeyoung.dungeonsguide.mod.utils.DGPerf;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
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
    // deferred custom-geometry consumers capture these per-batch lists. They are pooled because
    // 26.3 executes solid/translucent feature phases inside the main frame-graph pass, before
    // Fabric's END_MAIN injection at that pass's return; clear() below runs after those consumers
    private static final List<List<DGWorldRenderState>> batchPool = new ArrayList<>();
    private static int batchesInUse;
    private static Vec3 camera = Vec3.ZERO;
    // -Ddg.nobatch measures the old one-submit-per-state cost for the benchmark
    private static final boolean NO_BATCH = Boolean.getBoolean("dg.nobatch");

    // called right before RenderWorldEvent fires for this frame's extraction
    public static void beginExtraction(Vec3 cameraPosition) {
        states.clear();
        blockModels.clear();
        releaseBatches();
        camera = cameraPosition;
    }

    private static List<DGWorldRenderState> borrowBatch() {
        if (batchesInUse == batchPool.size()) batchPool.add(new ArrayList<>());
        return batchPool.get(batchesInUse++);
    }

    private static void releaseBatches() {
        for (int i = 0; i < batchesInUse; i++) batchPool.get(i).clear();
        batchesInUse = 0;
    }

    public static void record(DGWorldRenderState state) {
        states.add(state);
    }

    public static void record(BlockModelState state) {
        blockModels.add(state);
    }

    // COLLECT_SUBMITS: the recorded states append submit nodes for this frame. Consecutive states sharing a
    // render type go into one submitCustomGeometry, so a route's hundreds of quads are a handful of submits
    // instead of one per quad (26.1.2's 87%-of-frame problem). Batches preserve per-type submission order.
    public static void submitAll(LevelRenderContext context) {
        long t0 = DGPerf.nanos();
        long a0 = DGPerf.alloc();
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        // the render state's camera is already partial-tick interpolated and authoritative for this frame
        Vec3 frameCamera = context.levelState().cameraRenderState.pos;

        int submits = 0;
        int n = states.size();
        for (int i = 0; i < n; ) {
            DGWorldRenderState state = states.get(i);
            RenderType key = state.batchKey();
            if (key == null || NO_BATCH) {
                state.submit(collector, poseStack, frameCamera);
                submits++;
                i++;
                continue;
            }
            int to = i + 1;
            while (to < n && states.get(to).batchKey() == key) to++;
            List<DGWorldRenderState> batch = borrowBatch();
            for (int j = i; j < to; j++) batch.add(states.get(j));
            collector.submitCustomGeometry(poseStack, key, (pose, consumer) -> {
                for (DGWorldRenderState s : batch) s.writeVertices(pose, consumer, frameCamera);
            });
            submits++;
            i = to;
        }
        for (BlockModelState state : blockModels) {
            state.submit(poseStack, collector, frameCamera);
            submits++;
        }

        DGPerf.submitCalls += submits;
        DGPerf.submittedStates += n + blockModels.size();
        DGPerf.submitNanos += DGPerf.nanos() - t0;
        DGPerf.submitBytes += DGPerf.alloc() - a0;
    }

    // how many states were recorded this frame, for the benchmark
    public static int stateCount() {
        return states.size() + blockModels.size();
    }

    // END_MAIN: if extraction was skipped (hidden level, F3+A) recorded states must not leak into the next frame
    public static void clear() {
        DGPerf.frames++;
        states.clear();
        blockModels.clear();
        releaseBatches();
    }
}
