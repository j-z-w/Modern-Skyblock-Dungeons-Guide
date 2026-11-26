package kr.syeyoung.dungeonsguide.mod.render.world.state;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineStripStateTest {
    @Test
    void lineWidthIsWrittenForEveryVertex() {
        LineStripState state = new LineStripState(Arrays.asList(
                new Vec3(0, 0, 0),
                new Vec3(1, 0, 0),
                new Vec3(2, 0, 0)
        ), new int[]{-1, -1, -1}, 7.5f, false, false);
        List<Float> widths = new ArrayList<>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(
                VertexConsumer.class.getClassLoader(),
                new Class[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if ("setLineWidth".equals(method.getName())) widths.add((Float) args[0]);
                    if (method.getReturnType() == VertexConsumer.class) return proxy;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0.0f;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });

        state.writeVertices(new PoseStack().last(), consumer, Vec3.ZERO);

        assertEquals(Arrays.asList(7.5f, 7.5f, 7.5f, 7.5f), widths);
    }

    @Test
    void minecraftCoreLinesShaderConsumesTheLineWidthAttribute() throws Exception {
        String shader;
        try (InputStream in = LineStripStateTest.class.getClassLoader()
                .getResourceAsStream("assets/minecraft/shaders/core/rendertype_lines.vsh")) {
            assertNotNull(in);
            shader = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(shader.contains("in float LineWidth"));
        assertTrue(shader.contains("lineOffset"));
    }
}
