/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2023  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.shader;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * DG's shaders as render pipelines, one per shader (D78). Sources are assets/dungeonsguide/shaders/*.vsh / *.fsh,
 * shaders that only had a fragment part use vanilla's gui vertex shader, which does what the fixed function pipeline did.
 */
public class ShaderManager {
    private static final Map<String, ShaderProgram> shaders = new HashMap<>();

    static {
        register("shaders/roundrect", Identifier.withDefaultNamespace("core/gui"), "RoundRect");
        register("shaders/chromaroundrect", Identifier.fromNamespaceAndPath("dungeonsguide", "chromaroundrect"), "RoundRect");
        register("shaders/chromacircle", Identifier.withDefaultNamespace("core/gui"), "ChromaCircle");
        register("shaders/donut", Identifier.withDefaultNamespace("core/gui"), "Donut");
        register("shaders/etherwarppreview", Identifier.withDefaultNamespace("core/gui"), "EtherwarpPreview");
        // the world 'repeat' shader is registered by DGWorldRenderer's agent, not here
    }

    private static void register(String name, Identifier vertexShader, String uniformBlock) {
        String shaderName = name.substring("shaders/".length());
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("dungeonsguide", "pipeline/" + shaderName))
                .withVertexShader(vertexShader)
                .withFragmentShader(Identifier.fromNamespaceAndPath("dungeonsguide", shaderName))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                        .withUniform(uniformBlock, UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                // shapes like the etherwarp preview's triangle fans come in any winding
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
        shaders.put(name, new ShaderProgram(name, pipeline));
    }

    // dgdebug reloadshader. vanilla reloads shader sources with resources, this drops the compiled programs
    public static void onResourceReload() {
        // 26.3: compiled programs live in a PipelineCache; swap it out, clear it, swap it back
        PipelineCache cache = RenderSystem.setCurrentPipelineCache(null);
        if (cache != null) {
            RenderSystem.setCurrentPipelineCache(cache);
            cache.clear();
        }
    }

    public static ShaderProgram getShader(String str) {
        return shaders.get(str);
    }
}
