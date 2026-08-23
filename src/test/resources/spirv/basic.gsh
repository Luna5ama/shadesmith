#version 460 compatibility

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

in vec2 texCoord[];
out vec2 geometryTexCoord;

void main() {
    for (int i = 0; i < 3; ++i) {
        geometryTexCoord = texCoord[i];
        gl_Position = gl_in[i].gl_Position;
        EmitVertex();
    }
    EndPrimitive();
}
