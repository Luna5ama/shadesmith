#version 460 compatibility
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_arithmetic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_shuffle : require
#extension GL_KHR_shader_subgroup_clustered : require
#extension GL_KHR_shader_subgroup_quad : require

layout(std430) buffer OutputBuffer {
    uint values[];
};

layout(local_size_x = 32) in;

void main() {
    uint lane = gl_LocalInvocationID.x;
    uint sum = subgroupAdd(lane);
    uint shuffled = subgroupShuffleXor(lane, 1u);
    uint last = subgroupBroadcast(lane, gl_SubgroupSize - 1u);
    uint clustered = subgroupClusteredXor(lane, 4u);
    uint quad = subgroupQuadSwapHorizontal(lane);
    uint prefix = subgroupExclusiveAdd(lane);
    uint minimum = subgroupMin(lane);
    uint maximum = subgroupMax(lane);
    uint bits = subgroupOr(1u << (lane & 15u));
    if (subgroupElect()) {
        values[0] = sum;
        values[1] = shuffled;
        values[2] = last;
        values[3] = clustered;
        values[4] = quad;
        values[5] = prefix;
        values[6] = minimum;
        values[7] = maximum;
        values[8] = bits;
    }
}
