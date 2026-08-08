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
        val material = layout[0]
        assertEquals("R32UI", material.format.name)
        assertEquals(fields.take(6).map { it.name }, material.fields.map { it.field })
        assertEquals(listOf(0, 4, 8, 16, 24, 28), material.fields.map { it.bitOffset })
        assertEquals(listOf(4, 4, 8, 8, 4, 4), material.fields.map { it.bitWidth })
        assertEquals(List(6) { false }, material.fields.map { it.signed })
        val flags = layout[1]
        assertEquals("R32UI", flags.format.name)
        assertEquals(fields.drop(6).map { it.name }, flags.fields.map { it.field })
        assertEquals(listOf(0, 4, 5), flags.fields.map { it.bitOffset })
        assertEquals(listOf(4, 1, 1), flags.fields.map { it.bitWidth })
        assertEquals(listOf(true, false, false), flags.fields.map { it.signed })
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
        assertEquals(0xF0E47F90u, decodePackedUInt(row[0]))
        assertEquals(0xEu, decodePackedUInt(row[1]))
    }

    @Test
    fun `full cube provider maps property flag to the packed fullCube bit`() {
        val row = assembler.provide(
            BlockState("stone"),
            BlockProperty(blockID = "stone", isFullCube = true)
        ).single().second

        val flags = decodePackedUInt(row[1])
        assertEquals(1u, (flags shr 4) and 0x1u)
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
