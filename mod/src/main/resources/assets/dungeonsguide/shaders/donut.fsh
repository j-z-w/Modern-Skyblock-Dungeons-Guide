#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform Donut {
    float radius;
    float thickness;
    vec2 centerPos;
    float smoothness;
};

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 stuff = gl_FragCoord.xy - centerPos;
    float sdf = abs(length(stuff)-radius);
    float clampd = 1.0 - smoothstep(thickness, thickness+smoothness, sdf);

    fragColor = vertexColor * vec4(1.0, 1.0, 1.0, clampd);
}
