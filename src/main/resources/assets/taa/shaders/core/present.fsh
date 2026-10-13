#version 150

uniform sampler2D ResolvedColor;
uniform sampler2D HistoryColor;
layout(std140) uniform TaaData {
    mat4 InverseCurrentVP;
    mat4 PreviousFromCurrent;
    vec4 ResolveParams;
    vec4 OutputParams;
    vec4 Viewport;
    vec4 Jitter;
    mat4 InverseHandVP;
    mat4 PreviousHandVP;
};
in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 dimensions = textureSize(ResolvedColor, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dimensions - 1);
    vec4 center = texelFetch(ResolvedColor, pixel, 0);
    if (OutputParams.z > 2.5) {
        fragColor = vec4(mix(vec3(1.0, 0.15, 0.1), vec3(0.1, 0.85, 0.4), center.a), 1.0);
        return;
    }
    if (OutputParams.z > 1.5) {
        fragColor = vec4(texelFetch(HistoryColor, pixel, 0).rgb, 1.0);
        return;
    }
    if (Jitter.w < 0.5 || (Jitter.w < 1.5 && OutputParams.y <= 0.0)) {
        fragColor = vec4(center.rgb, 1.0);
        return;
    }
    vec3 north = texelFetch(ResolvedColor, clamp(pixel + ivec2(0, 1), ivec2(0), dimensions - 1), 0).rgb;
    vec3 south = texelFetch(ResolvedColor, clamp(pixel + ivec2(0, -1), ivec2(0), dimensions - 1), 0).rgb;
    vec3 east = texelFetch(ResolvedColor, clamp(pixel + ivec2(1, 0), ivec2(0), dimensions - 1), 0).rgb;
    vec3 west = texelFetch(ResolvedColor, clamp(pixel + ivec2(-1, 0), ivec2(0), dimensions - 1), 0).rgb;
        if (Jitter.w > 1.5) {
            vec3 nw = texelFetch(ResolvedColor, clamp(pixel + ivec2(-1, 1), ivec2(0), dimensions - 1), 0).rgb;
            vec3 ne = texelFetch(ResolvedColor, clamp(pixel + ivec2(1, 1), ivec2(0), dimensions - 1), 0).rgb;
            vec3 sw = texelFetch(ResolvedColor, clamp(pixel + ivec2(-1, -1), ivec2(0), dimensions - 1), 0).rgb;
            vec3 se = texelFetch(ResolvedColor, clamp(pixel + ivec2(1, -1), ivec2(0), dimensions - 1), 0).rgb;
            vec3 minimum = min(center.rgb, min(min(north, south), min(east, west))) + min(min(nw, ne), min(sw, se));
            vec3 maximum = max(center.rgb, max(max(north, south), max(east, west))) + max(max(nw, ne), max(sw, se));
            vec3 contrast = clamp(min(minimum, 2.0 - maximum) / max(maximum, vec3(0.000001)), 0.0, 1.0);
            vec3 coefficient = -sqrt(contrast) / (8.0 - 3.0 * OutputParams.w);
            fragColor = vec4(clamp((center.rgb + (north + south + east + west) * coefficient)
                                      / (1.0 + 4.0 * coefficient), 0.0, 1.0), 1.0);
        } else {
            // Unsharp mask; strength is independently configurable.
            fragColor = vec4(clamp(center.rgb + (center.rgb - (north + south + east + west) * 0.25) * OutputParams.y, 0.0, 1.0), 1.0);
        }
}
