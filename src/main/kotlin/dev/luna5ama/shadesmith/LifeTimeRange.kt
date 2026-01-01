package dev.luna5ama.shadesmith

import java.util.*

/**
 * Represents the lifetime range of a texture across rendering passes
 */
sealed interface LifeTimeRange {
    val sortOrder: Int
    fun rangeBitSet(): BitSet

    /**
     * A transient texture that exists for a contiguous range of passes
     */
    data class Transient(val range: IntRange) : LifeTimeRange {
        override val sortOrder get() = range.first

        override fun rangeBitSet(): BitSet {
            val bitSet = BitSet()
            bitSet.set(range.first, range.last + 1)
            return bitSet
        }
    }

    /**
     * A history texture that is read in early passes and written in later passes
     */
    data class History(val lastRead: Int, val firstWrite: Int, val total: Int) : LifeTimeRange {
        override val sortOrder get() = -1

        override fun rangeBitSet(): BitSet {
            val bitSet = BitSet()
            bitSet.set(0, lastRead + 1)
            bitSet.set(firstWrite, total)
            return bitSet
        }
    }
}

