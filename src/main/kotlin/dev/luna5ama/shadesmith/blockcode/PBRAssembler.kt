package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.TextureFormat

data class LUTData(val format: TextureFormat, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LUTData

        if (format != other.format) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

interface PBRAssembler {
    fun BlockScope.provide(): Sequence<Pair<BlockState, List<LUTData>>>
}

fun PBRAssembler.provide(blockState: BlockState, property: BlockProperty) =
    with(BlockScope(blockState, property)) {
        provide()
    }
