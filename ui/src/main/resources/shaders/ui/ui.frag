#version 330 core

// Shapes are evaluated as signed distance fields rather than tessellated.
//
// A rounded rectangle is four vertices and one distance function, not an arc approximated
// by triangles: the corner stays exact at any size, and the antialiased edge comes from the
// distance itself instead of Dear ImGui's extra fringe geometry. Borders, shadows and
// gradients become parameters of this same function rather than more vertices.
//
// Textured quads share this program rather than getting their own. Switching shader program
// between draw commands would cost a bind per switch, and a frame alternating panels and
// images switches constantly; branching on a vertex attribute keeps the frame on one
// pipeline.

in vec2  v_LocalPos;
in vec2  v_HalfSize;
in float v_Radius;
in vec4  v_Color;
in vec2  v_TexCoord;
in float v_Mode;

uniform sampler2D u_Texture;

out vec4 FragColor;

/**
 * Signed distance from p to a box of half-extents b with corner radius r.
 * Negative inside, zero on the edge, positive outside.
 */
float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    if (v_Mode > 0.5) {
        // Textured: the quad is the shape, the sample is tinted by the vertex colour.
        vec4 sampled = texture(u_Texture, v_TexCoord);
        FragColor = sampled * v_Color;
        if (FragColor.a <= 0.0) discard;
        return;
    }

    float dist = sdRoundedBox(v_LocalPos, v_HalfSize, v_Radius);

    // fwidth gives the distance change across one pixel, so the edge is one pixel wide at
    // any zoom or DPI instead of a fixed blur.
    float edge  = fwidth(dist);
    float alpha = 1.0 - smoothstep(-edge, edge, dist);

    if (alpha <= 0.0) discard;

    FragColor = vec4(v_Color.rgb, v_Color.a * alpha);
}
