#version 150
uniform sampler2D SourceColor;
layout(std140) uniform SsaaData { vec4 Parameters; };
out vec4 fragColor;

float lanczos(float x) {
    x = abs(x);
    if (x < 0.00001) return 1.0;
    if (x >= 2.0) return 0.0;
    float p = 3.14159265359 * x;
    return sin(p) * sin(p * 0.5) / (p * p * 0.5);
}

void main() {
    int scale = int(Parameters.x);
    bool horizontal = Parameters.y < 0.5;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 dimensions = textureSize(SourceColor, 0);
    // GUI entity textures contain premultiplied alpha. Average every subpixel,
    // including transparent ones, without negative lobes or an opaque background.
    if (Parameters.z > 0.5) {
        vec4 sum = vec4(0);
        for (int i = 0; i < 4; i++) {
            if (i >= scale) break;
            ivec2 source = horizontal ? ivec2(pixel.x * scale + i, pixel.y)
                                     : ivec2(pixel.x, pixel.y * scale + i);
            sum += texelFetch(SourceColor, clamp(source, ivec2(0), dimensions - 1), 0);
        }
        fragColor = sum / float(scale);
        return;
    }
    float center = (float(horizontal ? pixel.x : pixel.y) + 0.5) * float(scale) - 0.5;
    int first = int(floor(center)) - 2 * scale + 1;
    vec3 sum = vec3(0);
    float weights = 0.0;
    vec3 minimum = vec3(1), maximum = vec3(0);
    for (int i = 0; i < 16; i++) {
        if (i >= 4 * scale) break;
        int coordinate = first + i;
        ivec2 source = horizontal ? ivec2(coordinate, pixel.y) : ivec2(pixel.x, coordinate);
        vec3 color = texelFetch(SourceColor, clamp(source, ivec2(0), dimensions - 1), 0).rgb;
        float distance = (float(coordinate) - center) / float(scale);
        float weight = lanczos(distance);
        sum += color * weight;
        weights += weight;
        // Bound negative-lobe ringing by the contributing source colors.
        minimum = min(minimum, color);
        maximum = max(maximum, color);
    }
    fragColor = vec4(clamp(sum / weights, minimum, maximum), 1.0);
}
