#version 460 core
#extension GL_ARB_gpu_shader_int64 : require
#pragma optimize(on)

#if defined(VERTEX_SHADER)
layout(location = 0) in vec3 vaPosition;
#define IO_QUALIFIER out
#elif defined(FRAGMENT_SHADER)
layout(location = 0) out vec4 outColor;
#define IO_QUALIFIER in
#endif

#if SETTING_GROUP_SIZE == 8
#define WORK_GROUP_SIZE 8
const ivec3 workGroups = ivec3(8, 1, 1);
#elif SETTING_GROUP_SIZE == 16
#define WORK_GROUP_SIZE 16
const ivec3 workGroups = ivec3(16, 1, 1);
#endif
