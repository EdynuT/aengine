#version 330 core
layout (location = 0) in vec3 a_Position;

uniform mat4 u_ViewProjection;
uniform mat4 u_Transform;

out vec3 v_WorldPos;

void main() {
    vec4 world = u_Transform * vec4(a_Position, 1.0);
    v_WorldPos = world.xyz;
    gl_Position = u_ViewProjection * world;
}
