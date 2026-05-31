package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.*

data class BlockProperty(
    val block: String = "",
    val blockID: String = "",
    val isFullCube: Boolean = false,
    val luminance: Int = 0,
    val isOpaque: Boolean = true,
    val tags: Set<Tags> = emptySet()
) {
    enum class Tags {
        SmallFlower,
        Flower
    }

    companion object {
        fun fromJsonObject(jsonObject: JsonObject): Map<BlockState, BlockProperty> {
            val block = jsonObject["block"]!!.jsonPrimitive.content
            val blockID = jsonObject["block_id"]!!.jsonPrimitive.content.substringAfterLast(':')

            fun booleanTag(name: String): Boolean {
                val element = jsonObject[name] ?: return false
                return when (element) {
                    is JsonPrimitive -> element.content == "Yes"
                    is JsonObject -> false
                    else -> false
                }
            }

            fun parseStateKey(stateKey: String): Map<String, String> {
                // Parse "lit: false" or "candles: 1" into a map
                val parts = stateKey.split(":")
                if (parts.size != 2) return emptyMap()
                return mapOf(parts[0].trim() to parts[1].trim())
            }

            fun getLuminanceData(): Map<Map<String, String>, Int> {
                val element = jsonObject["luminance"] ?: return mapOf(emptyMap<String, String>() to 0)
                return when (element) {
                    is JsonPrimitive -> mapOf(emptyMap<String, String>() to element.int)
                    is JsonObject -> {
                        val result = mutableMapOf<Map<String, String>, Int>()

                        for ((key, value) in element.entries) {
                            val stateKey1 = parseStateKey(key)
                            if (stateKey1.isEmpty()) continue

                            when (value) {
                                is JsonPrimitive -> {
                                    result[stateKey1] = value.int
                                }
                                is JsonObject -> {
                                    // Nested state keys (e.g., candles: 1 -> lit: false)
                                    for ((nestedKey, nestedValue) in value.entries) {
                                        if (nestedValue is JsonPrimitive) {
                                            val stateKey2 = parseStateKey(nestedKey)
                                            val combinedStates = stateKey1 + stateKey2
                                            result[combinedStates] = nestedValue.int
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        if (result.isEmpty()) mapOf(emptyMap<String, String>() to 0) else result
                    }
                    else -> mapOf(emptyMap<String, String>() to 0)
                }
            }

            val isFullCube = booleanTag("full_cube")
            val isOpaque = booleanTag("opaque")

            val result = mutableMapOf<BlockState, BlockProperty>()
            val luminanceData = getLuminanceData()
            val tagSmallFlower = booleanTag("tag_small_flowers")
            val tagFlower = booleanTag("tag_flowers")
            val tags = buildSet {
                if (tagSmallFlower) add(Tags.SmallFlower)
                if (tagFlower) add(Tags.Flower)
            }

            if (luminanceData.keys.any { it.isNotEmpty() }) {
                // Has state-specific luminance, create a BlockState for each
                for ((states, luminance) in luminanceData) {
                    val blockState = BlockState(blockID, states)
                    val property = BlockProperty(block, blockID, isFullCube, luminance, isOpaque, tags)
                    check(result.putIfAbsent(blockState, property) == null) { "Duplicate block state: $blockState" }
                }
            } else {
                // No state-specific properties, create a simple BlockState
                val luminance = luminanceData.values.firstOrNull() ?: 0
                val blockState = BlockState(blockID, emptyMap())
                val property = BlockProperty(block, blockID, isFullCube, luminance, isOpaque, tags)
                check(result.putIfAbsent(blockState, property) == null) { "Duplicate block state: $blockState" }
            }

            return result
        }
    }
}
