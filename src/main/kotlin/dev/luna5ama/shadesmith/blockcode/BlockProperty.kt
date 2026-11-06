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
        private typealias StateSelector = Map<String, String>

        private val stateSelectorSeparator = Regex("<br\\s*/?>")

        private class BlockPropertyBuilder(
            private val block: String,
            private val blockID: String,
            private val isOpaque: Boolean,
            private val tags: Set<Tags>
        ) {
            var isFullCube: Boolean = false
            var luminance: Int = 0

            fun build(): BlockProperty {
                return BlockProperty(
                    block = block,
                    blockID = blockID,
                    isFullCube = isFullCube,
                    luminance = luminance,
                    isOpaque = isOpaque,
                    tags = tags
                )
            }
        }

        private interface ParsedStateAwareField {
            val name: String
            val selectors: Set<StateSelector>

            fun applyTo(builder: BlockPropertyBuilder, state: StateSelector)
        }

        private class ParsedStateAwareFieldImpl<T>(
            override val name: String,
            private val values: Map<StateSelector, T>,
            private val assignValue: BlockPropertyBuilder.(T) -> Unit
        ) : ParsedStateAwareField {
            override val selectors: Set<StateSelector> = values.keys

            override fun applyTo(builder: BlockPropertyBuilder, state: StateSelector) {
                assignValue(builder, resolveStateAwareValue(name, values, state))
            }
        }

        private class StateAwareFieldDefinition<T>(
            private val name: String,
            private val defaultValue: T,
            private val parsePrimitive: (JsonPrimitive) -> T,
            private val assignValue: BlockPropertyBuilder.(T) -> Unit
        ) {
            fun parse(jsonObject: JsonObject): ParsedStateAwareField {
                return ParsedStateAwareFieldImpl(
                    name = name,
                    values = parseStateAwareValues(jsonObject[name], defaultValue, parsePrimitive),
                    assignValue = assignValue
                )
            }
        }

        fun fromJsonObject(jsonObject: JsonObject): Map<BlockState, BlockProperty> {
            val block = jsonObject["block"]!!.jsonPrimitive.content
            val blockID = jsonObject["block_id"]!!.jsonPrimitive.content.substringAfterLast(':')

            val stateAwareFields = listOf(
                StateAwareFieldDefinition("full_cube", false, ::parseYesNo) { isFullCube = it },
                StateAwareFieldDefinition("luminance", 0, { primitive -> primitive.int }) { luminance = it }
            ).map { it.parse(jsonObject) }

            val isOpaque = parsePrimitiveYesNoFlag(jsonObject, "opaque")
            val result = linkedMapOf<BlockState, BlockProperty>()
            val tagSmallFlower = parsePrimitiveYesNoFlag(jsonObject, "tag_small_flowers")
            val tagFlower = parsePrimitiveYesNoFlag(jsonObject, "tag_flowers")
            val tags = buildSet {
                if (tagSmallFlower) add(Tags.SmallFlower)
                if (tagFlower) add(Tags.Flower)
            }

            for (state in combineStateSelectors(stateAwareFields)) {
                val builder = BlockPropertyBuilder(block, blockID, isOpaque, tags)
                for (field in stateAwareFields) {
                    field.applyTo(builder, state)
                }

                val blockState = BlockState(blockID, state)
                val property = builder.build()
                println("$blockState -> $property")
                check(result.putIfAbsent(blockState, property) == null) { "Duplicate block state: $blockState" }
            }

            return result
        }

        private fun parsePrimitiveYesNoFlag(jsonObject: JsonObject, name: String): Boolean {
            val element = jsonObject[name] as? JsonPrimitive ?: return false
            return parseYesNo(element)
        }

        private fun parseYesNo(primitive: JsonPrimitive): Boolean {
            return when (primitive.content.trim().lowercase()) {
                "yes", "true" -> true
                "no", "false" -> false
                else -> error("Unsupported boolean value: ${primitive.content}")
            }
        }

        private fun <T> parseStateAwareValues(
            element: JsonElement?,
            defaultValue: T,
            parsePrimitive: (JsonPrimitive) -> T
        ): Map<StateSelector, T> {
            if (element == null) {
                return linkedMapOf(emptyMap<String, String>() to defaultValue)
            }

            return when (element) {
                is JsonPrimitive -> linkedMapOf(emptyMap<String, String>() to parsePrimitive(element))
                is JsonObject -> buildStateAwareValues(element, emptyMap(), parsePrimitive)
                    .ifEmpty { linkedMapOf(emptyMap<String, String>() to defaultValue) }
                else -> error("Unsupported state-aware value: $element")
            }
        }

        private fun <T> buildStateAwareValues(
            element: JsonObject,
            inheritedSelector: StateSelector,
            parsePrimitive: (JsonPrimitive) -> T
        ): Map<StateSelector, T> {
            val result = linkedMapOf<StateSelector, T>()

            for ((rawSelector, nestedElement) in element.entries) {
                for (selector in parseStateSelectors(rawSelector)) {
                    val mergedSelector = mergeSelectors(inheritedSelector, selector)
                        ?: error("Conflicting state selector while parsing '$rawSelector'")

                    when (nestedElement) {
                        is JsonPrimitive -> putSelectorValue(result, mergedSelector, parsePrimitive(nestedElement))
                        is JsonObject -> {
                            for ((nestedSelector, nestedValue) in buildStateAwareValues(
                                nestedElement,
                                mergedSelector,
                                parsePrimitive
                            )) {
                                putSelectorValue(result, nestedSelector, nestedValue)
                            }
                        }
                        else -> error("Unsupported nested state-aware value: $nestedElement")
                    }
                }
            }

            return result
        }

        private fun parseStateSelectors(rawSelector: String): List<StateSelector> {
            return stateSelectorSeparator.split(rawSelector)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { selector ->
                    val separatorIndex = selector.indexOf(':')
                    require(separatorIndex >= 0) { "Invalid state selector: $selector" }
                    linkedMapOf(
                        selector.substring(0, separatorIndex).trim() to
                            selector.substring(separatorIndex + 1).trim()
                    )
                }
        }

        private fun combineStateSelectors(fields: List<ParsedStateAwareField>): Set<StateSelector> {
            return fields.fold(linkedSetOf(emptyMap<String, String>())) { states, field ->
                val nextStates = linkedSetOf<StateSelector>()

                for (state in states) {
                    for (selector in field.selectors) {
                        mergeSelectors(state, selector)?.let(nextStates::add)
                    }
                }

                nextStates
            }
        }

        private fun mergeSelectors(left: StateSelector, right: StateSelector): StateSelector? {
            if (left.isEmpty()) return LinkedHashMap(right)
            if (right.isEmpty()) return LinkedHashMap(left)

            val merged = LinkedHashMap(left)
            for ((key, value) in right) {
                val existingValue = merged[key]
                if (existingValue != null && existingValue != value) return null
                merged[key] = value
            }
            return merged
        }

        private fun <T> resolveStateAwareValue(
            name: String,
            values: Map<StateSelector, T>,
            state: StateSelector
        ): T {
            val matchingEntries = values.entries.filter { (selector, _) ->
                selector.all { (key, value) -> state[key] == value }
            }
            require(matchingEntries.isNotEmpty()) { "Missing value for '$name' at state $state" }

            val maxSpecificity = matchingEntries.maxOf { it.key.size }
            val mostSpecificEntries = matchingEntries.filter { it.key.size == maxSpecificity }
            val distinctValues = mostSpecificEntries.map { it.value }.distinct()

            require(distinctValues.size == 1) {
                "Ambiguous value for '$name' at state $state: ${mostSpecificEntries.map { it.key to it.value }}"
            }

            return distinctValues.first()
        }

        private fun <T> putSelectorValue(
            values: MutableMap<StateSelector, T>,
            selector: StateSelector,
            value: T
        ) {
            val existingValue = values.putIfAbsent(selector, value)
            require(existingValue == null || existingValue == value) {
                "Conflicting values for state selector $selector: $existingValue vs $value"
            }
        }
    }
}
