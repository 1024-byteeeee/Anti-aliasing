#version 150
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    if (vertexColor.a == 0.0) discard;
    // Coverage belongs to the rasterized line, not its vanilla alpha metadata.
    fragColor = vec4(vertexColor.rgb, 1.0);
}
