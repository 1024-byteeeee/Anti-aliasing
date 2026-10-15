#version 150
uniform sampler2D LineColor;
uniform sampler2D LineDepth;
uniform sampler2D SceneDepth;
out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 line = texelFetch(LineColor, pixel, 0);
    if (line.a == 0.0) discard;
    // The saved depth is copied after the line depth pass. An equal value
    // therefore cannot make coplanar red and blue lines occlude each other.
    float captured = texelFetch(LineDepth, pixel, 0).r;
    float current = texelFetch(SceneDepth, pixel, 0).r;
    if (current < captured) discard;
    fragColor = line;
}
