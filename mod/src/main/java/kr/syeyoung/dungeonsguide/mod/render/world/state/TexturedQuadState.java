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
import kr.syeyoung.dungeonsguide.mod.render.world.DGRenderTypes;
import lombok.AllArgsConstructor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * One textured quad (straight alpha), corners in order with a uv per corner, tinted by color.
 * repeat samples with a REPEAT-wrapped sampler, so uvs may run outside 0..1 (NeoRoute's animated arrows,
 * which used to wrap in repeat.fsh).
 */
@AllArgsConstructor
public class TexturedQuadState implements DGWorldRenderState {
    private final Identifier texture;
    private final Vec3[] corners;
    private final float[][] uvs;
    private final int color;
    private final boolean depth;
    private final boolean repeat;

    public TexturedQuadState(Identifier texture, Vec3[] corners, float[][] uvs, int color, boolean depth) {
        this(texture, corners, uvs, color, depth, false);
    }

    @Override
    public void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera) {
        RenderType type;
        if (depth) {
            type = repeat ? DGRenderTypes.texturedQuadRepeat(texture) : DGRenderTypes.texturedQuad(texture);
        } else {
            type = repeat ? DGRenderTypes.texturedQuadRepeatSeeThrough(texture) : DGRenderTypes.texturedQuadSeeThrough(texture);
        }
        collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
            for (int i = 0; i < 4; i++) {
                Vec3 corner = corners[i];
                consumer.addVertex(pose, (float) (corner.x - camera.x), (float) (corner.y - camera.y), (float) (corner.z - camera.z))
                        .setUv(uvs[i][0], uvs[i][1]).setColor(color);
            }
        });
    }
}
