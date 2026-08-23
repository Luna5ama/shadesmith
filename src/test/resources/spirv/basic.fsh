#version 460 compatibility

/* RENDERTARGETS:3 */
const int noiseTextureResolution = 256;
const float sunPathRotation = -20.0; //[-90.0 -20.0 0.0 20.0 90.0]
const int colortex3Format = RGBA16F; // Iris string directive
const bool colortex3Clear = false;
const vec4 colortex3ClearColor = vec4(0.25, 0.5, 0.75, 1.0);
/*
const int colortex4Format = RGBA32F;
*/

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D colorTexture;
uniform float alpha;

void main() {
    fragColor = texture(colorTexture, texCoord) * alpha;
}
