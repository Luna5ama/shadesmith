#version 460 compatibility
#include "/common.glsl"

//#define SETTING_BRANCH

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

void main() {
    transient_b_store(ivec2(0), vec4(0.5));
    values[0] = vec4(1.0);
}
