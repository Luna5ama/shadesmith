#version 460 compatibility
#extension GL_KHR_shader_subgroup_arithmetic : require

layout(std430) buffer OutputBuffer {
    uint values[];
};

layout(local_size_x = 32) in;

void main() {
    uint sum = subgroupAdd(gl_LocalInvocationID.x);
    if (subgroupElect()) {
        values[0] = sum;
    }
}
