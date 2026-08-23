#version 460 compatibility

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D colorTexture;

void main() {
    fragColor = texture(colorTexture, texCoord) * vec4(1.0, 0.5, 0.25, 1.0);
}
