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

        assertEquals(1, layout.size)
        val texture = layout.single()
        assertEquals("R32UI", texture.format.name)
        assertEquals(32, texture.bitsPerEntry)
        assertEquals(fields.map { it.name }, texture.fields.map { it.field })
        assertEquals(listOf(0, 4, 8, 16, 24, 25, 26), texture.fields.map { it.bitOffset })
        assertEquals(listOf(4, 4, 8, 8, 1, 1, 4), texture.fields.map { it.bitWidth })
        assertEquals(listOf(false, false, false, false, false, false, true), texture.fields.map { it.signed })
    }

    @Test
    fun `assembler resolves provider overrides and packs values in expected bit slots`() {
        val rows = assembler.provide(
            BlockState(BlockNames.EndRod),
            BlockProperty(blockID = BlockNames.EndRod, luminance = 9)
        ).toList()

        val row = rows.single { it.first == BlockState(BlockNames.EndRod) }.second
        assertEquals(1, row.size)
        assertEquals("R32UI", row.single().format.name)
        assertEquals(0x38B47F90u, decodePackedUInt(row.single()))
    }

    @Test
    fun `full cube provider maps property flag to the packed fullCube bit`() {
        val row = assembler.provide(
            BlockState("stone"),
            BlockProperty(blockID = "stone", isFullCube = true)
        ).single().second

        val packed = decodePackedUInt(row.single())
        assertEquals(1u, (packed shr 25) and 0x1u)
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
