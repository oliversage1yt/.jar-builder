#version 450

uniform sampler2D InSampler;

// this version of the game compiles shaders to SPIR-V, which wants a location on every input and output
layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// the gray spreads out from the middle of the screen (where the rock that was launched is): inside this distance the world is gray
const float RADIUS = 0.82;
const float SOFT = 0.07;

void main() {
    vec4 color = texture(InSampler, texCoord);
    float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    // distance from the middle, with the width stretched to a 16:9 screen so the wave is round
    float d = length((texCoord - vec2(0.5)) * vec2(1.7778, 1.0));
    float amount = 1.0 - smoothstep(RADIUS - SOFT, RADIUS, d);
    fragColor = vec4(mix(color.rgb, vec3(gray), amount), 1.0);
}
