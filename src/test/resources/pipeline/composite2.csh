#version 460 compatibility
#include "/common.glsl"

//#define SETTING_BRANCH

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

vec4 deadAccess() {
    return transient_a_sample(vec2(0.75));
}

void main() {
#ifdef SETTING_BRANCH
    values[0] = transient_branch_sample(vec2(0.75));
#else
    values[0] = vec4(0.0);
#endif
}
