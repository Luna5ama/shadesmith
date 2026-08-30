#version 460 compatibility
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_NV_shader_subgroup_partitioned : require

layout(std430) buffer OutputBuffer {
    uint values[];
};

layout(local_size_x = 32) in;

void main() {
    uint lane = gl_LocalInvocationID.x;
    uvec4 partitionMask = subgroupPartitionNV(lane & 3u);
    values[lane * 2u] = subgroupPartitionedAddNV(1u, partitionMask);
    values[lane * 2u + 1u] = subgroupPartitionedMaxNV(lane, partitionMask);
}
