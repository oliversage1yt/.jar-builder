#version 450

uniform sampler2D InSampler;

// this version of the game compiles shaders to SPIR-V, which wants a location on every input and output
layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    // the brightness the eye sees (Rec. 709 weights): the same value in red, green and blue is gray
    float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(vec3(gray), 1.0);
}
