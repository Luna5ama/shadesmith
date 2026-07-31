package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.TextureFormat
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val PACKED_UNIT_BITS = 32

data class PBRField(
    val name: String,
    val provider: PBRProvider<out PBRValue<*, *>>
)

fun <P : PBRValue<*, *>> pbrField(name: String, provider: PBRProvider<P>): PBRField {
    return PBRField(name, provider)
}

fun defaultPBRFields(blockModelIds: Map<BlockState, UInt> = emptyMap()): List<PBRField> = listOf(
    pbrField("sss", SSS),
    pbrField("emissive", Emissive),
    pbrField("ior", IOR),
    pbrField("roughness", Roughness),
    pbrField("metalIndex", MetalIndex),
    pbrField("dielectric", Dielectric),
    pbrField("emissiveMultiplier", EmissiveMultiplier),
    pbrField("fullCube", FullCubeFlag),
    pbrField("smallFoliage", SmallFoliageFlag),
    pbrField("blockModel", BlockModelID(blockModelIds)),
)

@Serializable
data class PBRLayoutFieldEntry(
    val field: String,
    val encoding: String,
    val bitOffset: Int,
    val bitWidth: Int,
    val signed: Boolean
)

@Serializable
data class PBRLayoutTextureEntry(
    val index: Int,
    val format: TextureFormat,
    val bitsPerEntry: Int,
    val fields: List<PBRLayoutFieldEntry>
)

typealias PBRLayout = List<PBRLayoutTextureEntry>

fun planPBRLayout(fields: List<PBRField>, unitBits: Int = PACKED_UNIT_BITS): PBRLayout {
    require(unitBits == PACKED_UNIT_BITS) { "Only 32-bit packed units are currently supported" }

    val textures = mutableListOf<PBRLayoutTextureEntry>()
    var textureIndex = 0
    var bitCursor = 0
    var textureFields = mutableListOf<PBRLayoutFieldEntry>()

    fun flushTexture() {
        if (textureFields.isEmpty()) return
        textures.add(
            PBRLayoutTextureEntry(
                index = textureIndex,
                format = TextureFormat.R32UI,
                bitsPerEntry = unitBits,
                fields = textureFields.toList()
            )
        )
        textureIndex++
        bitCursor = 0
        textureFields = mutableListOf()
    }

    for (field in fields) {
        val defaultValue = field.provider.defaultValue
        val bitWidth = defaultValue.packedBitWidth()
        require(bitWidth in 1..unitBits) {
            "Unsupported packed bit width $bitWidth for field '${field.name}'"
        }

        if (bitCursor + bitWidth > unitBits) {
            flushTexture()
        }

        textureFields.add(
            PBRLayoutFieldEntry(
                field = field.name,
                encoding = defaultValue.encodingName(),
                bitOffset = bitCursor,
                bitWidth = bitWidth,
                signed = defaultValue.isSignedEncoding()
            )
        )
        bitCursor += bitWidth
    }

    flushTexture()
    return textures
}

fun packPBRRow(layout: PBRLayout, valuesByField: Map<String, PBRValue<*, *>>): List<LUTData> {
    return layout.map { texture ->
        var packed = 0u

        for (field in texture.fields) {
            val value = valuesByField[field.field]
                ?: error("Missing value for packed field '${field.field}'")
            val rawBits = value.packedRawBits()
            val mask = field.bitMask()
            require((rawBits and mask) == rawBits) {
                "Value for '${field.field}' does not fit ${field.bitWidth} bits: 0x${rawBits.toString(16)}"
            }
            packed = packed or ((rawBits and mask) shl field.bitOffset)
        }

        LUTData(TextureFormat.R32UI, packed.toNativeByteArray())
    }
}

fun defaultPackedPBRData(fields: List<PBRField>, layout: PBRLayout): List<LUTData> {
    val defaults = fields.associate { it.name to it.provider.defaultValue }
    return packPBRRow(layout, defaults)
}

fun PBRValue<*, *>.encodingName(): String {
    return when (this) {
        is PBRValue.Bool -> "bool"
        is PBRValue.Int4 -> "int4"
        is PBRValue.UInt4 -> "uint4"
        is PBRValue.UInt8 -> "uint8"
        is PBRValue.UInt32 -> "uint32"
        is PBRValue.Unorm4 -> "unorm4"
        is PBRValue.Unorm8 -> "unorm8"
        is PBRValue.F16 -> "f16"
    }
}

private fun PBRValue<*, *>.packedBitWidth(): Int {
    return when (this) {
        is PBRValue.Bool -> 1
        is PBRValue.Int4 -> 4
        is PBRValue.UInt4 -> 4
        is PBRValue.UInt8 -> 8
        is PBRValue.UInt32 -> 32
        is PBRValue.Unorm4 -> 4
        is PBRValue.Unorm8 -> 8
        is PBRValue.F16 -> 16
    }
}

private fun PBRValue<*, *>.isSignedEncoding(): Boolean {
    return this is PBRValue.Int4
}

private fun PBRValue<*, *>.packedRawBits(): UInt {
    return when (this) {
        is PBRValue.Bool -> (rawData.toInt() and 0x1).toUInt()
        is PBRValue.Int4 -> (rawData.toInt() and 0xF).toUInt()
        is PBRValue.UInt4 -> (rawData.toInt() and 0xF).toUInt()
        is PBRValue.UInt8 -> (rawData.toInt() and 0xFF).toUInt()
        is PBRValue.UInt32 -> rawData
        is PBRValue.Unorm4 -> (rawData.toInt() and 0xF).toUInt()
        is PBRValue.Unorm8 -> (rawData.toInt() and 0xFF).toUInt()
        is PBRValue.F16 -> (rawData.toInt() and 0xFFFF).toUInt()
    }
}

private fun PBRLayoutFieldEntry.bitMask(): UInt {
    return if (bitWidth == PACKED_UNIT_BITS) UInt.MAX_VALUE else ((1u shl bitWidth) - 1u)
}

private fun UInt.toNativeByteArray(): ByteArray {
    return ByteBuffer.allocate(4)
        .order(ByteOrder.nativeOrder())
        .putInt(toInt())
        .array()
}
