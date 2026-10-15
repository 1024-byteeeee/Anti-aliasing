#version 150
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <taa:line_position.glsl>
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
            ProjMat * ModelViewMat * vec4(LineEnd, 1.0), LineCorner, PixelWidth, ScreenSize, colorPosition);
    vertexColor = mix(Color, LineEndColor, colorPosition) * ColorModulator;
}
