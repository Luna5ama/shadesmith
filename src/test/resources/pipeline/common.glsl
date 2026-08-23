uniform sampler2D atlas;
layout(rgba16f) uniform image2D atlasImage;

#define transient_a_sample(x) texture(atlas, x)
#define transient_b_store(x, v) imageStore(atlasImage, x, v)
#define transient_branch_sample(x) texture(atlas, x)

layout(std430, binding = 0) buffer OutputBuffer {
    vec4 values[];
};
