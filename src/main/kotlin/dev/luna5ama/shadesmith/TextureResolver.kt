package dev.luna5ama.shadesmith

import java.util.*
import kotlin.io.path.name

private val READ_REGEX =
    """(imageLoad|texture\w*?|texel\w*?)\(\s*(?:uimg|usam)_((?:transient|pingpong|history)_$IDENTIFIER_REGEX_STR)\s*,\s*(.+?)\s*,\s*(.*?)\)""".toRegex()
private val WRITE_REGEX = """(imageStore)\((?:uimg|usam)_((?:transient|pingpong|history)_$IDENTIFIER_REGEX_STR)\s*,(.+?)\s*,(.*)\);""".toRegex()

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

//    accessInfos.forEach { (key, accessInfo) ->
//        println("$key Reads: ${accessInfo.reads}, Writes: ${accessInfo.writes}")
//    }

    val lifeTime = config.formats.keys.associateWith { texName ->
        val exists = accessInfos.indices.filter {
            val accessInfo = accessInfos[it].second
            texName in accessInfo.reads || texName in accessInfo.writes
        }
        exists.min()..exists.max()
    }

    println("Lifetime:")
    lifeTime.forEach {
        println("${it.key}: ${accessInfos[it.value.first].first}..${accessInfos[it.value.last].first}" )
    }

    data class AllocationInfo(val usage: BitSet = BitSet(), val tileID: MutableMap<String, Int> = mutableMapOf())

    val slots = EnumMap<TextureFormat, AllocationInfo>(TextureFormat::class.java)

    for (i in accessInfos.indices) {
        lifeTime.forEach { (texName, range) ->
            val format = config.formats[texName]!!
            val slotInfo = slots.getOrPut(format, ::AllocationInfo)
            if (texName !in slotInfo.tileID && i in range) {
                val tileID = slotInfo.usage.nextClearBit(0)
                slotInfo.usage.set(tileID)
                slotInfo.tileID[texName] = tileID
            }
        }

        lifeTime.forEach { (texName, range) ->
            val format = config.formats[texName]!!
            val slotInfo = slots.getOrPut(format, ::AllocationInfo)
            if (texName in slotInfo.tileID && i == range.last) {
                val tileID = slotInfo.tileID[texName]!!
                slotInfo.usage.clear(tileID)
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