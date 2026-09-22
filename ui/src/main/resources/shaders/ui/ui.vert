#version 330 core

// Interface geometry is submitted in pixels with the origin at the top-left, the way UI
// code addresses the screen, and converted to clip space here.

layout (location = 0) in vec2  a_Position;   // screen position, pixels
layout (location = 1) in vec2  a_LocalPos;   // offset from the shape's centre, pixels
layout (location = 2) in vec2  a_HalfSize;   // shape half-extents, pixels
layout (location = 3) in float a_Radius;     // corner radius, pixels
layout (location = 4) in vec4  a_Color;
layout (location = 5) in vec2  a_TexCoord;
layout (location = 6) in float a_Mode;       // 0 = distance field, 1 = texture, 2 = text
layout (location = 7) in vec4  a_BorderColor;
layout (location = 8) in float a_BorderWidth; // pixels, measured inward; 0 = no border

uniform vec2 u_ViewportSize;

out vec2  v_LocalPos;
out vec2  v_HalfSize;
out float v_Radius;
out vec4  v_Color;
out vec2  v_TexCoord;
out float v_Mode;
out vec4  v_BorderColor;
out float v_BorderWidth;

void main() {
    v_LocalPos = a_LocalPos;
    v_HalfSize = a_HalfSize;
    v_Radius   = a_Radius;
    v_Color    = a_Color;
    v_TexCoord    = a_TexCoord;
    v_Mode        = a_Mode;
    v_BorderColor = a_BorderColor;
    v_BorderWidth = a_BorderWidth;

    vec2 ndc = vec2(
        (a_Position.x / u_ViewportSize.x) * 2.0 - 1.0,
        1.0 - (a_Position.y / u_ViewportSize.y) * 2.0
    );

    gl_Position = vec4(ndc, 0.0, 1.0);
}
