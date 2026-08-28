#version 460 compatibility

layout(std430) readonly buffer GlobalData {
    vec4 globalValue;
    vec4 retainedPadding;
};

in vec2 sharedCoord;
out vec4 fragmentColor;

void main() {
    fragmentColor = vec4(sharedCoord, 0.0, 1.0);
}
