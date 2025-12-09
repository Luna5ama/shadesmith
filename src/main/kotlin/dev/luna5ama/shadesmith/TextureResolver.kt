package dev.luna5ama.shadesmith

import java.util.*
import kotlin.io.path.name

private val READ_REGEX =
    """(imageLoad|texture\w*?|texel\w*?)\(\s*(?:uimg|usam)_((?:transient|pingpong|history)_$IDENTIFIER_REGEX_STR)\s*,\s*(.+?)\s*,\s*(.*?)\)""".toRegex()
private val WRITE_REGEX = """(imageStore)\((?:uimg|usam)_((?:transient|pingpong|history)_$IDENTIFIER_REGEX_STR)\s*,(.+?)\s*,(.*)\);""".toRegex()


private sealed class LifeTimeRange {
    abstract operator fun contains(index: Int): Boolean

    data class Transient(val range: IntRange) : LifeTimeRange() {
        override fun contains(index: Int): Boolean {
            return index in range
        }
    }
    data class History(val lastRead: Int, val firstWrite: Int) : LifeTimeRange() {
        override fun contains(index: Int): Boolean {
            if (firstWrite - lastRead <= 1) return true
            @Suppress("ReplaceRangeToWithRangeUntil")
            return index !in (lastRead + 1) .. (firstWrite - 1)
        }
    }
}

context(ioContext: IOContext)
fun resolveTextures(inputFiles: List<ShaderFile>): List<ShaderFile> {
    data class AccessInfo(val file: ShaderFile, val reads: Set<String>, val writes: Set<String>)

    val config = ioContext.config

    data class SortKey(val type: PassPrefix, val num: Int) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            val typeComp = this.type.compareTo(other.type)
            if (typeComp != 0) return typeComp
            return this.num.compareTo(other.num)
        }

        override fun toString(): String {
            return "${type.actualName}$num"
        }
    }

    val accessInfos = inputFiles.parallelStream()
        .map { file ->
            val reads = READ_REGEX.findAll(file.code)
                .map { it.groupValues[2] }
                .toSet()

            val writes = WRITE_REGEX.findAll(file.code)
                .map { it.groupValues[2] }
                .toSet()

            AccessInfo(file, reads, writes)
        }
        .toList()
        .groupingBy {
            val name = it.file.path.name.substringBefore('.')
            val matchResult = PASS_NAME_REGEX.matchEntire(name)!!
            val (type, num, letter) = matchResult.destructured
            SortKey(PassPrefix.valueOf(type.uppercase()), num.toInt())
        }
        .reduce { _, acc, elem ->
            AccessInfo(
                file = acc.file,
                reads = acc.reads + elem.reads,
                writes = acc.writes + elem.writes
            )
        }
        .toList()

    val lifeTime = config.formats.keys.associateWith { texName ->
        val typeStr = texName.substringBefore('_')
        when (typeStr) {
            "transient" -> {
                val exists = accessInfos.indices.filter {
                    val accessInfo = accessInfos[it].second
                    texName in accessInfo.reads || texName in accessInfo.writes
                }
                LifeTimeRange.Transient(exists.min()..exists.max())
            }

            "history" -> {
                val firstWriteIndex = accessInfos.indices.first {
                    val accessInfo = accessInfos[it].second
                    texName in accessInfo.writes
                }
                val lastReadIndex = accessInfos.indices.last {
                    val accessInfo = accessInfos[it].second
                    it <= firstWriteIndex && texName in accessInfo.reads
                }
                LifeTimeRange.History(lastReadIndex, firstWriteIndex)

            }
            else -> {
                error("Unknown texture type prefix for texture: $texName")
            }
        }
    }

    println("Lifetime:")
    lifeTime.forEach {
        println("${it.key}: ${it.value}" )
    }

    data class AllocationInfo(val usage: BitSet = BitSet(), val tileID: MutableMap<String, Int> = mutableMapOf())

    val slots = EnumMap<TextureFormat, AllocationInfo>(TextureFormat::class.java)

    for (i in accessInfos.indices) {
        lifeTime.forEach { (texName, range) ->
            val format = config.formats[texName]!!
            val slotInfo = slots.getOrPut(format, ::AllocationInfo)
            if (texName in slotInfo.tileID && i !in range) {
                val tileID = slotInfo.tileID[texName]!!
                slotInfo.usage.clear(tileID)
            }
        }

        lifeTime.forEach { (texName, range) ->
            val format = config.formats[texName]!!
            val slotInfo = slots.getOrPut(format, ::AllocationInfo)
            if (texName !in slotInfo.tileID && i in range) {
                val tileID = slotInfo.usage.nextClearBit(0)
                slotInfo.usage.set(tileID)
                slotInfo.tileID[texName] = tileID
            }
        }
    }

    println("\nAllocations:")
    slots.forEach {
        println("Format: ${it.key}")
        it.value.tileID.forEach { (texName, tileID) ->
            println("  Tex: $texName -> TileID: $tileID")
        }
    }

    return inputFiles
}