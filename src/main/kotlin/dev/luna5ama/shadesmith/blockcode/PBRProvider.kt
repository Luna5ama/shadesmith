package dev.luna5ama.shadesmith.blockcode

sealed interface PBRValue<T, R> {
    val value: T
    val rawData: R

    sealed interface Identity<T> : PBRValue<T, T> {
        override val rawData: T
            get() = value
    }

    data class Bool(override val value: Boolean) : PBRValue<Boolean, UByte> {
        override val rawData: UByte
            get() = if (value) 1u else 0u
    }

    data class Int4(override val value: Byte) : Identity<Byte>

    data class UInt4(override val value: UByte) : Identity<UByte>
    data class UInt8(override val value: UByte) : Identity<UByte>
    data class UInt32(override val value: UInt) : Identity<UInt>
    data class Unorm4(override val value: Float) : PBRValue<Float, UByte> {
        override val rawData: UByte
            get() = (value * 15.0f).toInt().coerceIn(0, 15).toUByte()
    }

    data class Unorm8(override val value: Float) : PBRValue<Float, UByte> {
        override val rawData: UByte
            get() = (value * 255.0f).toInt().coerceIn(0, 255).toUByte()
    }

    data class F16(override val value: Float) : PBRValue<Float, UShort> {
        override val rawData: UShort
            get() = java.lang.Float.floatToFloat16(value).toUShort()
    }
}

class BlockScope(val baseState: BlockState, val property: BlockProperty) {
    fun nameEndsWith(vararg suffixes: String): Boolean = suffixes.any { baseState.blockID.endsWith(it) }
    fun nameEquals(vararg names: String): Boolean = names.any { baseState.blockID == it }
    fun nameContains(vararg substrings: String): Boolean = substrings.any { baseState.blockID.contains(it) }
}

interface PBRProvider<P : PBRValue<*, *>> {
    val defaultValue: P
    fun BlockScope.provide(): Sequence<Pair<BlockState, P>>
}

context(scope: BlockScope)
fun <P : PBRValue<*, *>> PBRProvider<P>.provide() = with(scope) {
    if (baseState.blockID.isEmpty()) sequenceOf(baseState to defaultValue)
    else provide()
}.toMap()

context(scope: BlockScope)
@Suppress("UNCHECKED_CAST")
fun PBRProvider<out PBRValue<*, *>>.provideErased(): Map<BlockState, PBRValue<*, *>> {
    return (this as PBRProvider<PBRValue<*, *>>).provide()
}
