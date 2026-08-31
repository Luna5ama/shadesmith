#version 460 compatibility
#include "/common.glsl"

//#define SETTING_BRANCH
const int colortex0Format = RGBA16F; // Pack-global Iris registry entry

layout(location = 0) out vec4 finalColor;

void main() {
    finalColor = vec4(1.0);
}
