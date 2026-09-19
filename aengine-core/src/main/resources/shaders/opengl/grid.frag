#version 330 core
layout (location = 0) out vec4 color;

in vec3 v_WorldPos;
uniform vec4 u_GridColor;

// Normalizes line thickness to prevent vanishing at diagonal angles
float getGrid(vec2 coord, float stepSize, vec2 deriv) {
    // Determine the distance to the nearest grid line
    vec2 grid = abs(fract(coord / stepSize - 0.5) - 0.5);
    
    // Scale by the derivative to get pixel-space width, then clamp
    // to ensure lines never drop below 1.0 pixel of thickness
    vec2 line = grid / (deriv / stepSize);
    float dist = min(line.x, line.y);
    
    // Use a wider smoothstep range (0.0 to 1.5) to keep lines visible at angles
    return 1.0 - smoothstep(0.0, 1.5, dist);
}

void main() {
    vec2 coord = v_WorldPos.xz;
    vec2 deriv = fwidth(coord);
    float depth = 1.0 / gl_FragCoord.w;

    vec4 finalColor = u_GridColor;

    // Calculate alpha for each grid level
    float alpha1  = getGrid(coord, 1.0, deriv)  * smoothstep(30.0, 10.0, depth);
    float alpha4  = getGrid(coord, 4.0, deriv)  * smoothstep(100.0, 50.0, depth);
    float alpha8  = getGrid(coord, 8.0, deriv)  * smoothstep(300.0, 100.0, depth);
    float alpha16 = getGrid(coord, 16.0, deriv) * smoothstep(800.0, 300.0, depth);
    float alpha32 = getGrid(coord, 32.0, deriv) * smoothstep(2000.0, 800.0, depth);

    // Accumulate the grid lines
    float totalAlpha = clamp(alpha1 + alpha4 + alpha16 + alpha32, 0.0, 1.0);
    finalColor = mix(finalColor, vec4(0.0, 0.0, 0.0, 1.0), totalAlpha * 0.5);

    // World Axis Origin lines
    // We scale the origin line width by the derivative to keep them consistently visible
    if (abs(coord.x) < deriv.x * 2.0 || abs(coord.y) < deriv.y * 2.0) {
        finalColor = vec4(0.2, 0.2, 0.2, 1.0); 
    }

    color = finalColor;
}
