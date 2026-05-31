package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlockPropertyTest {
    @Test
    fun `parses state specific luminance`() {
        val properties = parseBlockProperties(
            """
            {
              "block": "Cake with White Candle",
              "full_cube": "No",
              "luminance": {
                "lit: false": 0,
                "lit: true": 3
              },
              "opaque": "Yes",
              "tag_small_flowers": "No",
              "tag_flowers": "No",
              "block_id": "minecraft:white_candle_cake"
            }
            """.trimIndent()
        )

        assertEquals(2, properties.size)
        assertEquals(
            BlockProperty(
                block = "Cake with White Candle",
                blockID = "white_candle_cake",
                isFullCube = false,
                luminance = 0,
                isOpaque = true,
                tags = emptySet()
            ),
            properties[BlockState("white_candle_cake", mapOf("lit" to "false"))]
        )
        assertEquals(
            BlockProperty(
                block = "Cake with White Candle",
                blockID = "white_candle_cake",
                isFullCube = false,
                luminance = 3,
                isOpaque = true,
                tags = emptySet()
            ),
            properties[BlockState("white_candle_cake", mapOf("lit" to "true"))]
        )
    }

    @Test
    fun `parses state specific full cube alternatives`() {
        val properties = parseBlockProperties(
            """
            {
              "block": "Waxed Weathered Cut Copper Slab",
              "full_cube": {
                "type: bottom<br>type: top": "No",
                "type: double": "Yes"
              },
              "luminance": 0,
              "opaque": "Yes",
              "tag_small_flowers": "No",
              "tag_flowers": "No",
              "block_id": "minecraft:waxed_weathered_cut_copper_slab"
            }
            """.trimIndent()
        )

        assertEquals(3, properties.size)
        assertEquals(false, properties.requireProperty("waxed_weathered_cut_copper_slab", "type", "bottom").isFullCube)
        assertEquals(false, properties.requireProperty("waxed_weathered_cut_copper_slab", "type", "top").isFullCube)
        assertEquals(true, properties.requireProperty("waxed_weathered_cut_copper_slab", "type", "double").isFullCube)
        assertEquals(0, properties.requireProperty("waxed_weathered_cut_copper_slab", "type", "double").luminance)
    }

    @Test
    fun `combines independent state specific fields`() {
        val properties = parseBlockProperties(
            """
            {
              "block": "Example Combined States",
              "full_cube": {
                "type: bottom<br>type: top": "No",
                "type: double": "Yes"
              },
              "luminance": {
                "lit: false": 0,
                "lit: true": 7
              },
              "opaque": "Yes",
              "tag_small_flowers": "No",
              "tag_flowers": "No",
              "block_id": "minecraft:example_combined_states"
            }
            """.trimIndent()
        )

        assertEquals(6, properties.size)
        assertEquals(
            false,
            properties.requireProperty(
                "example_combined_states",
                "type", "bottom",
                "lit", "true"
            ).isFullCube
        )
        assertEquals(
            7,
            properties.requireProperty(
                "example_combined_states",
                "type", "bottom",
                "lit", "true"
            ).luminance
        )
        assertEquals(
            true,
            properties.requireProperty(
                "example_combined_states",
                "type", "double",
                "lit", "false"
            ).isFullCube
        )
        assertEquals(
            0,
            properties.requireProperty(
                "example_combined_states",
                "type", "double",
                "lit", "false"
            ).luminance
        )
    }

    @Test
    fun `flattens nested state specific values`() {
        val properties = parseBlockProperties(
            """
            {
              "block": "Black Candle",
              "full_cube": "No",
              "luminance": {
                "candles: 1": {
                  "lit: false": 0,
                  "lit: true": 3
                },
                "candles: 2": {
                  "lit: false": 0,
                  "lit: true": 6
                }
              },
              "opaque": "No",
              "tag_small_flowers": "No",
              "tag_flowers": "No",
              "block_id": "minecraft:black_candle"
            }
            """.trimIndent()
        )

        assertEquals(4, properties.size)
        assertEquals(
            3,
            properties.requireProperty(
                "black_candle",
                "candles", "1",
                "lit", "true"
            ).luminance
        )
        assertEquals(
            6,
            properties.requireProperty(
                "black_candle",
                "candles", "2",
                "lit", "true"
            ).luminance
        )
        assertEquals(
            false,
            properties.requireProperty(
                "black_candle",
                "candles", "2",
                "lit", "false"
            ).isOpaque
        )
    }

    private fun parseBlockProperties(json: String): Map<BlockState, BlockProperty> {
        return BlockProperty.fromJsonObject(Json.parseToJsonElement(json).jsonObject)
    }

    private fun Map<BlockState, BlockProperty>.requireProperty(blockID: String, vararg states: String): BlockProperty {
        require(states.size % 2 == 0) { "States must be key/value pairs" }
        val stateMap = buildMap {
            for (index in states.indices step 2) {
                put(states[index], states[index + 1])
            }
        }
        return assertNotNull(this[BlockState(blockID, stateMap)])
    }
}

