#version 150

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    // the brightness the eye sees (Rec. 709 weights): the same value in red, green and blue is gray
    float gray = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(vec3(gray), 1.0);
}
