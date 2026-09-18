#version 450

layout(push_constant) uniform PushConstants {
    vec2 screenSize; // 虚拟画布尺寸 (854.0, 480.0)
} pc;

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 fragUv;
layout(location = 1) out vec4 fragColor;

void main() {
    // 映射 854x480 的虚拟画布像素坐标到 Vulkan NDC 坐标 (-1.0 ~ 1.0)
    vec2 ndc = (inPos / pc.screenSize) * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
    fragUv = inUv;
    fragColor = inColor;
}