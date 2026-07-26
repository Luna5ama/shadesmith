package dev.luna5ama.shadesmith.blockcode

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PBRLayoutTest {
    private val fields = defaultPBRFields()
    private val assembler = PBRAssembler(fields)

    @Test
    fun `plans compact packed layout with minimal textures`() {
        val layout = planPBRLayout(fields)

        assertEquals(2, layout.size)
        val packed = layout[0]
        assertEquals("R32UI", packed.format.name)
        assertEquals(fields.dropLast(1).map { it.name }, packed.fields.map { it.field })
        assertEquals(listOf(0, 4, 8, 16, 24, 28, 29), packed.fields.map { it.bitOffset })
        assertEquals(listOf(4, 4, 8, 8, 4, 1, 1), packed.fields.map { it.bitWidth })
        assertEquals(listOf(false, false, false, false, true, false, false), packed.fields.map { it.signed })
        val model = layout[1]
        assertEquals("R32UI", model.format.name)
        assertEquals(listOf("blockModel"), model.fields.map { it.field })
        assertEquals(listOf(0), model.fields.map { it.bitOffset })
        assertEquals(listOf(32), model.fields.map { it.bitWidth })
    }

    @Test
    fun `assembler resolves provider overrides and packs values in expected bit slots`() {
        val rows = assembler.provide(
            BlockState(BlockNames.EndRod),
            BlockProperty(blockID = BlockNames.EndRod, luminance = 9)
        ).toList()

        val row = rows.single { it.first == BlockState(BlockNames.EndRod) }.second
        assertEquals(2, row.size)
        assertEquals("R32UI", row[0].format.name)
        assertEquals(0x0EB47F90u, decodePackedUInt(row[0]))
        assertEquals(0u, decodePackedUInt(row[1]))
    }

    @Test
    fun `full cube provider maps property flag to the packed fullCube bit`() {
        val row = assembler.provide(
            BlockState("stone"),
            BlockProperty(blockID = "stone", isFullCube = true)
        ).single().second

        val packed = decodePackedUInt(row.first())
        assertEquals(1u, (packed shr 28) and 0x1u)
    }

    @Test
    fun `block model id uses a dedicated r32ui texture`() {
        val state = BlockState("partial")
        val property = BlockProperty(blockID = "partial", luminance = 9)
        val baseline = PBRAssembler(defaultPBRFields()).provide(state, property).single().second
        val modeled = PBRAssembler(defaultPBRFields(mapOf(state to 37u))).provide(state, property).single().second

        assertEquals(decodePackedUInt(baseline[0]), decodePackedUInt(modeled[0]))
        assertEquals(0u, decodePackedUInt(baseline[1]))
        assertEquals(37u, decodePackedUInt(modeled[1]))
    }

    @Test
    fun `assembler emits alias states from provider output`() {
        val rows = assembler.provide(
            BlockState(BlockNames.ShortGrass),
            BlockProperty(blockID = BlockNames.ShortGrass)
        ).toList()

        assertTrue(rows.any { it.first == BlockState("grass") })
        assertTrue(rows.any { it.first == BlockState(BlockNames.ShortGrass) })
    }

    @Test
    fun `default data matches the generated layout size`() {
        val layout = assembler.layout
        val defaultData = assembler.defaultData

        assertEquals(layout.size, defaultData.size)
        assertEquals(List(layout.size) { 4 }, defaultData.map { it.data.size })
    }

    private fun decodePackedUInt(data: LUTData): UInt {
        return ByteBuffer.wrap(data.data)
            .order(ByteOrder.nativeOrder())
            .int
            .toUInt()
    }
}
