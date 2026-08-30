#version 460 compatibility
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_shuffle : require

layout(std430, binding = 0) buffer RayDataIndices {
    uint rayDataIndices[];
};

layout(local_size_x = 32) in;

shared uint temp[2][128];

void main() {
    uint lane = gl_LocalInvocationIndex;
    uvec4 blockIdx = uvec4(0u, 32u, 64u, 96u) + lane;
    uvec4 value = uvec4(
        rayDataIndices[blockIdx.x],
        rayDataIndices[blockIdx.y],
        rayDataIndices[blockIdx.z],
        rayDataIndices[blockIdx.w]
    );

    for (uint size = 2u; size <= gl_SubgroupSize; size <<= 1u) {
        for (uint stride = size >> 1u; stride > 0u; stride >>= 1u) {
            uvec4 pair = subgroupShuffleXor(value, stride);
            bvec4 lower = lessThan(blockIdx, blockIdx ^ stride);
            value = mix(max(value, pair), min(value, pair), lower);
        }
    }

    temp[0][blockIdx.x] = value.x;
    temp[0][blockIdx.y] = value.y;
    temp[0][blockIdx.z] = value.z;
    temp[0][blockIdx.w] = value.w;
    barrier();

    uint readBuffer = 0u;
    for (uint span = gl_SubgroupSize * 2u; span <= 128u; span <<= 1u) {
        for (uint stride = span >> 1u; stride >= gl_SubgroupSize; stride >>= 1u) {
            uint writeBuffer = readBuffer ^ 1u;
            uvec4 pairIdx = blockIdx ^ stride;
            uvec4 pair = uvec4(
                temp[readBuffer][pairIdx.x],
                temp[readBuffer][pairIdx.y],
                temp[readBuffer][pairIdx.z],
                temp[readBuffer][pairIdx.w]
            );
            bvec4 lower = lessThan(blockIdx, pairIdx);
            value = mix(max(value, pair), min(value, pair), lower);
            temp[writeBuffer][blockIdx.x] = value.x;
            temp[writeBuffer][blockIdx.y] = value.y;
            temp[writeBuffer][blockIdx.z] = value.z;
            temp[writeBuffer][blockIdx.w] = value.w;
            readBuffer = writeBuffer;
            barrier();
        }

        value = uvec4(
            temp[readBuffer][blockIdx.x],
            temp[readBuffer][blockIdx.y],
            temp[readBuffer][blockIdx.z],
            temp[readBuffer][blockIdx.w]
        );
        for (uint stride = gl_SubgroupSize >> 1u; stride > 0u; stride >>= 1u) {
            uvec4 pair = subgroupShuffleXor(value, stride);
            bvec4 lower = lessThan(blockIdx, blockIdx ^ stride);
            value = mix(max(value, pair), min(value, pair), lower);
        }
        temp[readBuffer][blockIdx.x] = value.x;
        temp[readBuffer][blockIdx.y] = value.y;
        temp[readBuffer][blockIdx.z] = value.z;
        temp[readBuffer][blockIdx.w] = value.w;
        barrier();
    }

    rayDataIndices[blockIdx.x] = temp[readBuffer][blockIdx.x];
    rayDataIndices[blockIdx.y] = temp[readBuffer][blockIdx.y];
    rayDataIndices[blockIdx.z] = temp[readBuffer][blockIdx.z];
    rayDataIndices[blockIdx.w] = temp[readBuffer][blockIdx.w];
}
