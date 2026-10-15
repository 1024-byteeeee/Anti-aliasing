#version 150
#moj_import <taa:line_position.glsl>
layout(std140) uniform LineData {
    mat4 ModelViewMat;
    mat4 ProjMat;
    vec4 ColorModulator;
    vec4 LineInfo;
};
in vec3 Position;
in vec3 LineEnd;
in vec4 Color;
in vec4 LineEndColor;
in vec2 LineCorner;
in float PixelWidth;
out vec4 vertexColor;
void main() {
    float colorPosition;
    gl_Position = taaLinePosition(ProjMat * ModelViewMat * vec4(Position, 1.0),
            ProjMat * ModelViewMat * vec4(LineEnd, 1.0), LineCorner, PixelWidth, LineInfo.xy, colorPosition);
    vertexColor = mix(Color, LineEndColor, colorPosition) * ColorModulator;
}
