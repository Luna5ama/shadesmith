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

class PBRAssembler(val fields: List<PBRField>) {
    val layout: PBRLayout = planPBRLayout(fields)
    val defaultData: List<LUTData> = defaultPackedPBRData(fields, layout)

    fun BlockScope.provide(): Sequence<Pair<BlockState, List<LUTData>>> = sequence {
        val fieldData = fields.map { field ->
            field to field.provider.provideErased()
        }

        val allStates = linkedSetOf<BlockState>()
        for ((_, data) in fieldData) {
            allStates.addAll(data.keys)
        }

        fun resolveValue(
            field: PBRField,
            data: Map<BlockState, PBRValue<*, *>>,
            state: BlockState
        ): PBRValue<*, *> {
            return data[state] ?: data[baseState] ?: field.provider.defaultValue
        }

        for (state in allStates) {
            val values = fieldData.associate { (field, data) ->
                field.name to resolveValue(field, data, state)
            }
            yield(
                state to packPBRRow(layout, values)
            )
        }
    }
}

fun PBRAssembler.provide(blockState: BlockState, property: BlockProperty) =
    with(BlockScope(blockState, property)) {
        provide()
    }
