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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Through-wall versions of vanilla's world render types (D81): 1.8.9 drew these with the depth test off.
 * Depth tested draws use vanilla's render types as they are.
 *
 * Textured quads run on an explicit world-space position_tex_color pipeline (GUI_TEXTURED_SNIPPET is gui space).
 * OIT gap: there is no vanilla OIT shader for position_tex_color, so textured quads keep their normal pipeline
 * under 'Improved Transparency' and may mislayer against real translucents.
 */
public class DGRenderTypes {
    private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    private static final DepthStencilState NO_DEPTH_WRITE = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false);

    private static final RenderPipeline FILLED_SEE_THROUGH_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("dungeonsguide", "pipeline/filled_see_through"))
                    .withCull(false)
                    .withDepthStencilState(NO_DEPTH)
                    .build());
    private static final RenderPipeline LINES_SEE_THROUGH_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("dungeonsguide", "pipeline/lines_see_through"))
                    .withDepthStencilState(NO_DEPTH)
                    .build());
    private static final RenderPipeline BEACON_BEAM_SEE_THROUGH_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("dungeonsguide", "pipeline/beacon_beam_see_through"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(NO_DEPTH)
                    .build());

    private static RenderPipeline.Builder texturedQuadBuilder(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("dungeonsguide", name))
                .withVertexShader(Identifier.withDefaultNamespace("core/position_tex_color"))
                .withFragmentShader(Identifier.withDefaultNamespace("core/position_tex_color"))
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false);
    }
    private static final RenderPipeline TEXTURED_QUAD_PIPELINE = RenderPipelines.register(
            texturedQuadBuilder("pipeline/textured_quad")
                    .withDepthStencilState(NO_DEPTH_WRITE)
                    .build());
    private static final RenderPipeline TEXTURED_QUAD_SEE_THROUGH_PIPELINE = RenderPipelines.register(
            texturedQuadBuilder("pipeline/textured_quad_see_through")
                    .withDepthStencilState(NO_DEPTH)
                    .build());

    // OIT: vanilla swaps a type's pipeline for its OitPipelineSet when 'Improved Transparency' is on
    private static final OitPipelineSet FILLED_SEE_THROUGH_OIT = RenderPipelines.register(
            OitPipelineSet.builder("dungeonsguide/filled_see_through",
                            RenderPipeline.builder(RenderPipelines.OIT_DEBUG_FILLED_SNIPPET))
                    .withoutDepthTest()
                    .build());
    private static final OitPipelineSet LINES_SEE_THROUGH_OIT = RenderPipelines.register(
            OitPipelineSet.builder("dungeonsguide/lines_see_through",
                            RenderPipeline.builder(RenderPipelines.OIT_LINES_SNIPPET))
                    .withoutDepthTest()
                    .build());

    private static final RenderType FILLED_SEE_THROUGH = RenderType.create("dungeonsguide_filled_see_through",
            RenderSetup.builder(FILLED_SEE_THROUGH_PIPELINE).sortOnUpload().setOitPipelines(FILLED_SEE_THROUGH_OIT).createRenderSetup());
    private static final RenderType LINES_SEE_THROUGH = RenderType.create("dungeonsguide_lines_see_through",
            RenderSetup.builder(LINES_SEE_THROUGH_PIPELINE).setOitPipelines(LINES_SEE_THROUGH_OIT).createRenderSetup());
    private static final RenderType BEACON_BEAM_SEE_THROUGH = RenderType.create("dungeonsguide_beacon_beam_see_through",
            RenderSetup.builder(BEACON_BEAM_SEE_THROUGH_PIPELINE).withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION).sortOnUpload().createRenderSetup());

    private static final Map<Identifier, RenderType> TEXTURED_QUAD = new HashMap<>();
    private static final Map<Identifier, RenderType> TEXTURED_QUAD_SEE_THROUGH = new HashMap<>();
    private static final Map<Identifier, RenderType> TEXTURED_QUAD_REPEAT = new HashMap<>();
    private static final Map<Identifier, RenderType> TEXTURED_QUAD_REPEAT_SEE_THROUGH = new HashMap<>();

    public static RenderType filledSeeThrough() {
        return FILLED_SEE_THROUGH;
    }

    public static RenderType linesSeeThrough() {
        return LINES_SEE_THROUGH;
    }

    public static RenderType beaconBeamSeeThrough() {
        return BEACON_BEAM_SEE_THROUGH;
    }

    public static RenderType texturedQuad(Identifier texture) {
        return TEXTURED_QUAD.computeIfAbsent(texture, t -> RenderType.create("dungeonsguide_textured_quad",
                RenderSetup.builder(TEXTURED_QUAD_PIPELINE).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
    }

    public static RenderType texturedQuadSeeThrough(Identifier texture) {
        return TEXTURED_QUAD_SEE_THROUGH.computeIfAbsent(texture, t -> RenderType.create("dungeonsguide_textured_quad_see_through",
                RenderSetup.builder(TEXTURED_QUAD_SEE_THROUGH_PIPELINE).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
    }

    // REPEAT wrap replaces the repeat shader: the sampler wraps uv like fract(uv) did (D128)
    public static RenderType texturedQuadRepeat(Identifier texture) {
        return TEXTURED_QUAD_REPEAT.computeIfAbsent(texture, t -> RenderType.create("dungeonsguide_textured_quad_repeat",
                RenderSetup.builder(TEXTURED_QUAD_PIPELINE)
                        .withTexture("Sampler0", t, () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                        .sortOnUpload().createRenderSetup()));
    }

    public static RenderType texturedQuadRepeatSeeThrough(Identifier texture) {
        return TEXTURED_QUAD_REPEAT_SEE_THROUGH.computeIfAbsent(texture, t -> RenderType.create("dungeonsguide_textured_quad_repeat_see_through",
                RenderSetup.builder(TEXTURED_QUAD_SEE_THROUGH_PIPELINE)
                        .withTexture("Sampler0", t, () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                        .sortOnUpload().createRenderSetup()));
    }
}
