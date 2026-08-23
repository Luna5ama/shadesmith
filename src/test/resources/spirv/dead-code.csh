#version 460 compatibility
#extension GL_ARB_shader_image_load_store : require
#pragma optimize(on)
//#define FIXTURE_DEBUG

uniform sampler2D inputTexture;
uniform sampler2D unusedTexture;
layout(rgba16f) uniform writeonly image2D outputImage;
uniform float exposure;
layout(std430) readonly buffer DataBuffer {
    float weights[];
};
layout(std140) uniform Params {
    vec4 tint;
};

layout(local_size_x = 8, local_size_y = 4) in;
const ivec3 workGroups = ivec3(32, 18, 1); // Iris dispatch contract

vec4 liveColor(vec2 uv) {
    return texture(inputTexture, uv) * exposure + tint * weights[0];
}

vec4 deadHelper(vec2 uv) {
    return vec4(uv, 17.0, 23.0);
}

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    vec2 uv = (vec2(pixel) + 0.5) / vec2(imageSize(outputImage));
    if (false) {
        imageStore(outputImage, pixel, deadHelper(uv));
    }
    imageStore(outputImage, pixel, liveColor(uv));
}
