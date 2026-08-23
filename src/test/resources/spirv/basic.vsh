#version 460 compatibility

in vec3 position;
in vec2 uv;
out vec2 texCoord;
uniform mat4 transform;

void main() {
    texCoord = uv;
    gl_Position = transform * vec4(position, 1.0);
}
