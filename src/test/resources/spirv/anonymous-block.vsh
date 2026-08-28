#version 460 compatibility

layout(std430) readonly buffer GlobalData {
    vec4 globalValue;
    vec4 retainedPadding;
};

out vec2 sharedCoord;

void main() {
    sharedCoord = globalValue.xy;
    gl_Position = vec4(globalValue.zw, 0.0, 1.0);
}
