#version 150
uniform sampler2D SourceColor;
uniform ivec2 Origin;
out vec4 fragColor;
void main() {
    fragColor = texelFetch(SourceColor, ivec2(gl_FragCoord.xy) - Origin, 0);
}
