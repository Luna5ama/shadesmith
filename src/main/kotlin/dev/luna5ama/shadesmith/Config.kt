package dev.luna5ama.shadesmith

import kotlinx.serialization.Serializable

@Serializable
enum class TextureFormat {
    RGBA32UI,
    RGBA32F,
    RGBA16F,
    RGBA8,
    RG32UI,
    R32UI,
    R32F,
}

@Serializable
data class Config(
    val formats: Map<String, TextureFormat>
)