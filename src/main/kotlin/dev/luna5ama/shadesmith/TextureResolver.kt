package dev.luna5ama.shadesmith

import org.intellij.lang.annotations.Language
import java.util.*
import kotlin.io.path.name

private val READ_REGEX =
    """^[^#\n\r]+((?:transient|history)_$IDENTIFIER_REGEX_STR)_(sample|gather|fetch|load)\(""".toRegex(RegexOption.MULTILINE)
private val WRITE_REGEX = """^[^#\n\r]+((?:transient|history)_$IDENTIFIER_REGEX_STR)_(store)\(""".toRegex(RegexOption.MULTILINE)


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

@Language("GLSL")
private val textTileTemplate = """
#define saturate(x) clamp(x, 0.0, 1.0)
ivec2 _textile_texelToTexel(ivec2 texelPos, ivec2 tileOffset, ivec2 tileSize) {
    return clamp(texelPos, ivec2(0), tileSize - 1) + tileOffset;
}

vec2 _textile_uvToUV(vec2 uv, vec2 tileOffsetF, vec2 tileSizeF, vec2 atlastSizeRcp) {
    vec2 textureTexelPos = clamp(uv * tileSizeF, vec2(0.5), tileSizeF - 0.5) + tileOffsetF;
    return saturate(textureTexelPos * atlastSizeRcp);
}

vec2 _textile_uvToGatherUV(vec2 uv, vec2 tileOffsetF, vec2 tileSizeF, vec2 atlastSizeRcp) {
    vec2 textureTexelPos = clamp(uv * tileSizeF, vec2(1.0), tileSizeF - 1.0) + tileOffsetF;
    return saturate(textureTexelPos * atlastSizeRcp);
}
#undef saturate
""".trim().trimIndent()

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
                .map { it.groupValues[1] }
                .toSet()

            val writes = WRITE_REGEX.findAll(file.code)
                .map { it.groupValues[1] }
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

    println("Accesses:")
    accessInfos.forEach {
        if (it.second.reads.isEmpty() && it.second.writes.isEmpty()) return@forEach
        println("${it.first}: reads: ${it.second.reads} writes: ${it.second.writes}")
    }

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
                val firstWriteIndex = accessInfos.indices.firstOrNull {
                    val accessInfo = accessInfos[it].second
                    texName in accessInfo.writes
                } ?: accessInfos.lastIndex
                val lastReadIndex = accessInfos.indices.lastOrNull {
                    val accessInfo = accessInfos[it].second
                    it <= firstWriteIndex && texName in accessInfo.reads
                } ?: 0
                LifeTimeRange.History(lastReadIndex, firstWriteIndex)

            }
            else -> {
                error("Unknown texture type prefix for texture: $texName")
            }
        }
    }

    println("\nLifetime:")
    lifeTime.forEach {
        println("${it.key} (${config.formats[it.key]}): ${it.value}" )
    }

    data class AllocationInfo(val tileID: MutableMap<String, Int> = mutableMapOf(), var tileCount: Int = 0)

    data class AllocationEvent(val time: Int, val texName: String, val isStart: Boolean)

    val slots = EnumMap<TextureFormat, AllocationInfo>(TextureFormat::class.java)

    // Optimal interval coloring algorithm: process events sorted by time
    config.formats.keys.groupBy { config.formats[it]!! }.forEach { (format, textures) ->
        val slotInfo = slots.getOrPut(format, ::AllocationInfo)
        val usage = BitSet()

        // Build all allocation/deallocation events
        val events = mutableListOf<AllocationEvent>()

        textures.forEach { texName ->
            val range = lifeTime[texName]!!
            when (range) {
                is LifeTimeRange.Transient -> {
                    events.add(AllocationEvent(range.range.first, texName, true))
                    events.add(AllocationEvent(range.range.last + 1, texName, false))
                }
                is LifeTimeRange.History -> {
                    // History textures wrap around: active [0, lastRead] and [firstWrite, end]
                    // So they deallocate after lastRead and reallocate before firstWrite
                    events.add(AllocationEvent(0, texName, true))
                    events.add(AllocationEvent(range.lastRead + 1, texName, false))
                    events.add(AllocationEvent(range.firstWrite, texName, true))
                    events.add(AllocationEvent(accessInfos.size, texName, false))
                }
            }
        }

        // Sort by time, with deallocations before allocations at same time
        events.sortWith(compareBy({ it.time }, { !it.isStart }))

        // Process events in order
        events.forEach { event ->
            if (event.isStart) {
                // Allocate: find lowest available slot
                val tileID = usage.nextClearBit(0)
                usage.set(tileID)
                slotInfo.tileID[event.texName] = tileID
                slotInfo.tileCount = maxOf(slotInfo.tileCount, tileID + 1)
            } else {
                // Deallocate: free the slot
                val tileID = slotInfo.tileID[event.texName]
                if (tileID != null) {
                    usage.clear(tileID)
                }
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

    val cxArray = arrayOf(0, 0, 1, 1, 0, 1, 2, 2, 2)
    val cyArray = arrayOf(0, 1, 0, 1, 2, 2, 0, 1, 2)
    val xSizeArray = arrayOf(1, 1, 2, 2, 2, 2, 3, 3, 3)
    val ySizeArray = arrayOf(1, 2, 2, 2, 3, 3, 3, 3, 3)

    val textTileCode = buildString {

        slots.forEach { (format, allocationInfo) ->
            fun prefix(tileID: Int) = "_${format.name}_$tileID"
            fun offsetStr(tileID: Int) = "${prefix(tileID)}_OFFSET"
            fun offsetFStr(tileID: Int) = "${prefix(tileID)}_OFFSET_F"
            fun sizeStr(tileID: Int) = "${prefix(tileID)}_SIZE"
            fun sizeFStr(tileID: Int) = "${prefix(tileID)}_SIZE_F"
            fun sizeRCPStr(tileID: Int) = "${prefix(tileID)}_SIZE_RCP"
            fun uvToUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_UV"
            fun uvToGatherUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_GATHER_UV"
            fun texelToTexelStr(tileID: Int) = "${prefix(tileID)}_TEXEL_TO_TEXEL"

            var xSize = 1
            var ySize = 1
            repeat(allocationInfo.tileCount) {
               val cx = cxArray[it]
               val cy = cyArray[it]
                xSize = xSizeArray[it]
                ySize = ySizeArray[it]

                val offsetStr = offsetStr(it)
                val offsetF = offsetFStr(it)
                val sizeStr = sizeStr(it)
                val sizeFStr = sizeFStr(it)
                val sizeRCPStr = sizeRCPStr(it)
                append("#define ")
                append(offsetStr)
                append(" uval_mainImageSizeI * ivec2(")
                append(cx)
                append(", ")
                append(cy)
                append(")\n")

                append("#define ")
                append(offsetF)
                append(" uval_mainImageSize * vec2(")
                append(cx)
                append(", ")
                append(cy)
                append(")\n")

                append("#define ")
                append(sizeStr)
                append(" uval_mainImageSizeI\n")

                append("#define ")
                append(sizeFStr)
                append(" uval_mainImageSize\n")

                append("#define ")
                append(sizeRCPStr)
                append(" uval_mainImageSizeRcp\n")

                append("#define ")
                append(texelToTexelStr(it))
                append("(texelPos) _textile_texelToTexel(texelPos, ")
                append(offsetStr)
                append(", ")
                append(sizeStr)
                append(")\n")

                append("#define ")
                append(uvToUVStr(it))
                append("(uv) _textile_uvToUV(uv, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(sizeRCPStr)
                append(")\n")

                append("#define ")
                append(uvToGatherUVStr(it))
                append("(uv) _textile_uvToGatherUV(uv, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(sizeRCPStr)
                append(")\n")
            }

            val formatLowercase = format.name.lowercase()
            val usamFormat = "usam_$formatLowercase"
            val uimgFormat = "uimg_$formatLowercase"

            allocationInfo.tileID.forEach {
                append("#define ")
                append(it.key)
                append("_sample(x) texture(")
                append(usamFormat)
                append(", ")
                append(uvToUVStr(it.value))
                append("(x))\n")

                append("#define ")
                append(it.key)
                append("_gather(x, c) textureGather(")
                append(usamFormat)
                append(", ")
                append(uvToGatherUVStr(it.value))
                append("(x), c)\n")

                append("#define ")
                append(it.key)
                append("_fetch(x) texelFetch(")
                append(usamFormat)
                append(", ")
                append(texelToTexelStr(it.value))
                append("(x), 0)\n")

                append("#define ")
                append(it.key)
                append("_load(x) imageLoad(")
                append(uimgFormat)
                append(", ")
                append(texelToTexelStr(it.value))
                append("(x))\n")

                append("#define ")
                append(it.key)
                append("_store(x, v) imageStore(")
                append(uimgFormat)
                append(", ")
                append(texelToTexelStr(it.value))
                append("(x), v)\n")
            }

            println("$format: $xSize x $ySize (${allocationInfo.tileCount})")
        }
    }

    ioContext.writeOutput(ShaderFile(ioContext.resolveInputPath("/Base/Textile.glsl"), textTileTemplate + "\n\n" + textTileCode))

    return inputFiles
}