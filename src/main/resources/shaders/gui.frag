#version 450

layout(binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 fragUv;
layout(location = 1) in vec4 fragColor;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(uTexture, fragUv) * fragColor;
}