#version 460 compatibility

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D colorTexture;

void main() {
    fragColor = texture(colorTexture, texCoord);
}
