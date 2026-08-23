#version 460 compatibility
//#define SETTING_TINT

#if defined(SETTING_TINT)
#define APPLY_TINT(value) ((value) * vec4(1.0, 0.5, 0.25, 1.0))
#else
#define APPLY_TINT(value) (value)
#endif

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D colorTexture;

void main() {
    fragColor = APPLY_TINT(texture(colorTexture, texCoord));
}
