package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.*

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
        fun fromJsonObject(jsonObject: JsonObject): Map<BlockState, BlockProperty> {
            val block = jsonObject["block"]!!.jsonPrimitive.content
            val variantsRaw = jsonObject["variants"]!!
            val variants = if (variantsRaw is JsonArray) {
                variantsRaw.map { it.jsonPrimitive.content }
            } else {
                listOf(variantsRaw.jsonPrimitive.content)
            }

            fun booleanTag(name: String, variantName: String? = null, stateKey: String? = null): Boolean {
                val element = jsonObject[name] ?: return false
                return when (element) {
                    is JsonPrimitive -> element.content == "Yes"
                    is JsonObject -> {
                        if (variantName != null && element.containsKey(variantName)) {
                            val variantElement = element[variantName]!!
                            when (variantElement) {
                                is JsonPrimitive -> variantElement.content == "Yes"
                                is JsonObject -> {
                                    if (stateKey != null && variantElement.containsKey(stateKey)) {
                                        variantElement[stateKey]!!.jsonPrimitive.content == "Yes"
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }

            fun parseStateKey(stateKey: String): Map<String, String> {
                // Parse "lit: false" or "candles: 1" into a map
                val parts = stateKey.split(":")
                if (parts.size != 2) return emptyMap()
                return mapOf(parts[0].trim() to parts[1].trim())
            }

            fun getLuminanceData(variantName: String? = null): Map<Map<String, String>, Int> {
                val element = jsonObject["luminance"] ?: return mapOf(emptyMap<String, String>() to 0)
                return when (element) {
                    is JsonPrimitive -> mapOf(emptyMap<String, String>() to element.int)
                    is JsonObject -> {
                        // First try to find by variant name
                        if (variantName != null && element.containsKey(variantName)) {
                            val variantElement = element[variantName]!!
                            when (variantElement) {
                                is JsonPrimitive -> mapOf(emptyMap<String, String>() to variantElement.int)
                                is JsonObject -> {
                                    // Parse state-specific luminance values
                                    val result = mutableMapOf<Map<String, String>, Int>()
                                    for ((stateKey, value) in variantElement.entries) {
                                        when (value) {
                                            is JsonPrimitive -> {
                                                result[parseStateKey(stateKey)] = value.int
                                            }
                                            is JsonObject -> {
                                                // Nested state keys (e.g., candles: 1 -> lit: false)
                                                val stateKey1 = parseStateKey(stateKey)
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
                                    result.ifEmpty { mapOf(emptyMap<String, String>() to 0) }
                                }
                                else -> mapOf(emptyMap<String, String>() to 0)
                            }
                        } else {
                            // No variant name match, check if all keys are state keys (e.g., "candles: 1")
                            // This handles the case where luminance is keyed by states, not variants
                            val result = mutableMapOf<Map<String, String>, Int>()

                            for ((key, value) in element.entries) {
                                val stateKey1 = parseStateKey(key)
                                if (stateKey1.isNotEmpty()) {
                                    // This is a state key, check if value is primitive or object
                                    when (value) {
                                        is JsonPrimitive -> {
                                            result[stateKey1] = value.int
                                        }
                                        is JsonObject -> {
                                            // Nested state keys (e.g., candles: 1 -> lit: false)
                                            for ((nestedKey, nestedValue) in value.entries) {
                                                if (nestedValue is JsonPrimitive) {
                                                    val stateKey2 = parseStateKey(nestedKey)
                                                    // Combine both state keys
                                                    val combinedStates = stateKey1 + stateKey2
                                                    result[combinedStates] = nestedValue.int
                                                }
                                            }
                                        }
                                        else -> {}
                                    }
                                } else {
                                    // Not a state key, might be a variant name we don't match
                                }
                            }

                            if (result.isEmpty()) {
                                mapOf(emptyMap<String, String>() to 0)
                            } else {
                                result
                            }
                        }
                    }
                    else -> mapOf(emptyMap<String, String>() to 0)
                }
            }

            val isFullCube = booleanTag("full_cube")
            val isOpaque = booleanTag("opaque")

            val result = mutableMapOf<BlockState, BlockProperty>()

            for (variant in variants) {
                val luminanceData = getLuminanceData(variant)
                val tagSmallFlower = booleanTag("tag_small_flowers", variant)
                val tagFlower = booleanTag("tag_flowers", variant)

                if (luminanceData.keys.any { it.isNotEmpty() }) {
                    // Has state-specific luminance, create a BlockState for each
                    for ((states, luminance) in luminanceData) {
                        val tags = buildSet {
                            if (tagSmallFlower) add(Tags.SmallFlower)
                            if (tagFlower) add(Tags.Flower)
                        }
                        val blockState = BlockState(variant, states)
                        val property = BlockProperty(block, listOf(variant), isFullCube, luminance, isOpaque, tags)
                        result[blockState] = property
                    }
                } else {
                    // No state-specific properties, create a simple BlockState
                    val luminance = luminanceData.values.firstOrNull() ?: 0
                    val tags = buildSet {
                        if (tagSmallFlower) add(Tags.SmallFlower)
                        if (tagFlower) add(Tags.Flower)
                    }
                    val blockState = BlockState(variant, emptyMap())
                    val property = BlockProperty(block, listOf(variant), isFullCube, luminance, isOpaque, tags)
                    result[blockState] = property
                }
            }

            return result
        }
    }
}