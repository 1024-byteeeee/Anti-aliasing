#version 150
uniform sampler2D CurrentColor;
uniform sampler2D CurrentDepth;
uniform sampler2D WorldDepth;
uniform sampler2D HistoryColor;
uniform sampler2D HistoryResidual;
uniform sampler2D HistoryDepth;
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
    ivec2 dimensions = textureSize(CurrentColor, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dimensions - 1);
    vec3 inputColor = texelFetch(CurrentColor, pixel, 0).rgb;
    // Vanilla clears main depth before hands; preserve world depth independently.
    float foregroundDepth = texelFetch(CurrentDepth, pixel, 0).r;
    bool foreground = foregroundDepth < 0.9999999;
    float depth = foreground ? foregroundDepth : texelFetch(WorldDepth, pixel, 0).r;
    // Separate foreground coverage and world depth in the two halves of DEPTH32.
    float storedDepth = foreground ? foregroundDepth * 0.5 : 0.5 + depth * 0.5;
    bool stationary = (foreground ? Viewport.w : Viewport.z) > 0.5;
    vec4 relative = (foreground ? InverseHandVP : InverseCurrentVP)
                    * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    relative /= relative.w;
    if (!foreground && depth >= 0.9999999) relative.w = 0.0;
    vec4 clip = (foreground ? PreviousHandVP : PreviousFromCurrent) * relative;
    vec2 historyUV = clip.xy / max(clip.w, 0.000001) * 0.5 + 0.5;
    // A stationary camera maps this output pixel exactly to itself. Do not
    // repeatedly filter history because of inverse-matrix roundoff at distance.
    if (stationary) historyUV = (vec2(pixel) + 0.5) / vec2(dimensions);
    bool usable = OutputParams.x > 0.5 && clip.w > 0.0 &&
                  all(greaterThan(historyUV, vec2(0))) && all(lessThan(historyUV, vec2(1)));
    // Carry sub-byte rounding error forward instead of losing weak details at
    // high history weights. Both textures use identical bilinear sampling.
    vec3 oldHigh = stationary ? texelFetch(HistoryColor, pixel, 0).rgb : texture(HistoryColor, historyUV).rgb;
    vec4 oldLow = stationary ? texelFetch(HistoryResidual, pixel, 0) : texture(HistoryResidual, historyUV);
    vec3 oldColor = oldHigh + (oldLow.rgb * 255.0 - 128.0) / (254.0 * 255.0);
    // Reject history crossing a hand silhouette, in both directions.
    float oldStoredDepth = stationary ? texelFetch(HistoryDepth, pixel, 0).r : texture(HistoryDepth, historyUV).r;
    bool oldForeground = oldStoredDepth < 0.5;
    bool sameLayer = foreground == oldForeground;
    float speed = stationary ? 0.0 : length((historyUV - texCoord) * Viewport.xy);
    // Static silhouettes change raster coverage within one pixel. Retain their
    // coverage history when the matching layer is adjacent; reject larger moves.
    if (usable && !sameLayer && speed < 1.5) {
        ivec2 oldPixel = ivec2(historyUV * vec2(dimensions));
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
            bool neighborLayer = texelFetch(HistoryDepth,
                    clamp(oldPixel + ivec2(x, y), ivec2(0), dimensions - 1), 0).r < 0.5;
            sameLayer = sameLayer || foreground == neighborLayer;
        }
    }
    usable = usable && sameLayer;
    vec3 lo = inputColor;
    vec3 hi = inputColor;
    float oldWorldDepth = (oldStoredDepth - 0.5) * 2.0;
    // Front/back faces of a glass block are one block apart. Treat that small
    // difference as the same fine surface rather than alternating disocclusion.
    float depthTolerance = max(0.0000002, (1.0 - oldWorldDepth) * 0.05);
    vec3 colorFootprint = fwidth(inputColor);
    bool matchingSurface = stationary && !foreground && !oldForeground && depth < 0.9999999
                    && abs(depth - oldWorldDepth) <= depthTolerance
                    && any(greaterThan(colorFootprint, vec3(2.0 / 255.0)));
    bool fineSurface = false;
    vec3 surfaceColor = vec3(0);
    float surfaceSamples = 0.0;
    bool checkCoverage = usable && stationary && !foreground && !oldForeground
                      && depth > oldWorldDepth + depthTolerance;
    bool supportedSurface = false;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        ivec2 neighborPixel = clamp(pixel + ivec2(x, y), ivec2(0), dimensions - 1);
        vec3 neighbor = texelFetch(CurrentColor, neighborPixel, 0).rgb;
        lo = min(lo, neighbor);
        hi = max(hi, neighbor);
        if (checkCoverage || matchingSurface) {
            float neighborDepth = texelFetch(WorldDepth, neighborPixel, 0).r;
            supportedSurface = supportedSurface || abs(neighborDepth - oldWorldDepth) <= depthTolerance;
            fineSurface = fineSurface || neighborDepth > depth + depthTolerance;
            vec3 difference = abs(neighbor - inputColor);
            if (matchingSurface && abs(neighborDepth - depth) <= depthTolerance
                    && max(difference.r, max(difference.g, difference.b)) < 0.25
                    && texelFetch(CurrentDepth, neighborPixel, 0).r >= 0.9999999) {
                surfaceColor += neighbor;
                surfaceSamples += 1.0;
            }
        }
    }
    bool missingCoverage = checkCoverage && supportedSurface;
    fineSurface = matchingSurface && fineSurface;
    if (fineSurface && surfaceSamples > 0.0) {
        inputColor = mix(inputColor, surfaceColor / surfaceSamples, 0.5);
        // The depth-supported fine edge needs a stable reconstructed sample,
        // rather than the alternating bright/dark texel of one jitter phase.
        inputColor = mix(inputColor, oldColor, 0.5);
    }
    if (missingCoverage) {
        // Reconstruct limited coverage of the adjacent fine surface without
        // turning its subpixel border into a full-width opaque edge.
        inputColor = mix(inputColor, oldColor, 0.95);
        storedDepth = oldStoredDepth;
    }
    gl_FragDepth = storedDepth;
    float motionFactor = ResolveParams.w > 0.5 ? (1.0 / 3.0 + 2.0 / 3.0 * exp(-speed)) : 1.0;
    float historyFraction = usable ? ResolveParams.z * motionFactor : 0.0;
    // A subpixel line can disappear from this frame's neighborhood. Allow a
    // small bounded color margin at rest; tighten it again during movement.
    vec3 margin = vec3((2.0 / 255.0) * exp(-4.0 * speed));
    vec3 clippedHistory = missingCoverage || fineSurface ? oldColor : clamp(oldColor, lo - margin, hi + margin);
    vec3 resolved = clamp(mix(inputColor, clippedHistory, historyFraction), 0.0, 1.0);
    vec3 rounded = floor(resolved * 255.0 + 0.5) / 255.0;
#ifdef RESIDUAL_PASS
    fragColor = vec4(clamp((resolved - rounded) * 254.0 + 128.0 / 255.0, 0.0, 1.0), 1.0);
#else
    fragColor = vec4(rounded, historyFraction);
#endif
}
