#version 150

out vec2 texCoord;

void main() {
    // One oversized triangle covers the target without a diagonal seam or vertex attributes.
    vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = position;
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
