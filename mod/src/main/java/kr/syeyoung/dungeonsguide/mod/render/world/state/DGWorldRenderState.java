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
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Something DG draws in the world. Recorded by RenderUtils during RenderWorldEvent, submitted by DGWorldRenderer on
 * COLLECT_SUBMITS (D81). Positions are world positions; the pose stack is camera relative, so states subtract camera.
 *
 * States writing plain custom geometry return their render type from batchKey: DGWorldRenderer merges every
 * consecutive run of states sharing a type into a single submitCustomGeometry, so an arrow field costs one
 * submit instead of one submit per segment. States using other submit APIs (text, block models) return null
 * and are submitted one by one.
 */
public interface DGWorldRenderState {
    void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera);

    // the render type this state would submit, or null when it can't join a batched geometry submit
    default RenderType batchKey() {
        return null;
    }

    // appends this state's vertices; called from inside a batched submitCustomGeometry
    default void writeVertices(PoseStack.Pose pose, VertexConsumer consumer, Vec3 camera) {}
}
