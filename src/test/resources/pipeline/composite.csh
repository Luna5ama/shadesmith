#version 460 compatibility
#include "/common.glsl"

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

void main() {
    values[0] = transient_a_sample(vec2(0.25));
}
