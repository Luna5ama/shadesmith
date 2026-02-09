package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

data class BlockProperty(
    val block: String,
    val variants: List<String>,
    val isFullCube: Boolean,
    val luminance: Int,
    val isOpaque: Boolean,
    val tags: Set<Tags>
) {
    enum class Tags {
        SmallFlower,
        Flower
    }

    companion object {
        fun fromJsonObject(jsonObject: JsonObject): BlockProperty {
            val block = jsonObject["block"]!!.jsonPrimitive.content
            val variantsRaw = jsonObject["variants"]!!
            val variants = if (variantsRaw is JsonArray) {
                variantsRaw.map { it.jsonPrimitive.content }
            } else {
                listOf(variantsRaw.jsonPrimitive.content)
            }
            fun booleanTag(name: String) = jsonObject[name]!!.jsonPrimitive.content == "Yes"
            val isFullCube = booleanTag("full_cube")
            val luminance = jsonObject["luminance"]!!.jsonPrimitive.int
            val isOpaque = booleanTag("opaque")
            val tagSmallFlower = booleanTag("tag_small_flower")
            val tagFlower = booleanTag("tag_flower")
            val tags = buildSet {
                if (tagSmallFlower) add(Tags.SmallFlower)
                if (tagFlower) add(Tags.Flower)
            }
            return BlockProperty(block, variants, isFullCube, luminance, isOpaque, tags)
        }
    }
}