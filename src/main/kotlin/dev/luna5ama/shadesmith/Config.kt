package dev.luna5ama.shadesmith

import kotlinx.serialization.Serializable

@Serializable
enum class TextureFormat {
    // Floating-point
    RGBA32F,
    RGBA16F,
    RG32F,
    RG16F,
    R11F_G11F_B10F,
    R32F,
    R16F,
    RGBA16,
    RGB10_A2,
    RGBA8,
    RG16,
    RG8,
    R16,
    R8,
    RGBA16_SNORM,
    RGBA8_SNORM,
    RG16_SNORM,
    RG8_SNORM,
    R16_SNORM,
    R8_SNORM,

    // Signed integer
    RGBA32I,
    RGBA16I,
    RGBA8I,
    RG32I,
    RG16I,
    RG8I,
    R32I,
    R16I,
    R8I,

    // Unsigned integer
    RGBA32UI,
    RGBA16UI,
    RGB10_A2UI,
    RGBA8UI,
    RG32UI,
    RG16UI,
    RG8UI,
    R32UI,
    R16UI,
    R8UI,
}

@Serializable
data class FixedSizedTexture(
    val width: Int,
    val height: Int,
    val format: TextureFormat,
)

@Serializable
data class Config(
    val screen: Map<String, TextureFormat> = emptyMap(),
    val fixed: Map<String, FixedSizedTexture> = emptyMap(),
)