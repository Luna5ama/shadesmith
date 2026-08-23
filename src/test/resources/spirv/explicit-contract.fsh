#version 460 compatibility

layout(location = 3) flat in highp vec2 texCoord;
layout(location = 1) out vec4 fragColor;
layout(binding = 5) uniform sampler2D colorTexture;

void main() {
    fragColor = texture(colorTexture, texCoord);
}
