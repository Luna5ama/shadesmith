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

vec2 _textile_uvToUV(vec2 uv, vec2 tileOffsetF, vec2 tileSizeF, vec2 textureSizeRcp) {
    vec2 textureTexelPos = clamp(uv * tileSizeF, vec2(0.5), tileSizeF - 0.5) + tileOffsetF;
    return saturate(textureTexelPos * textureSizeRcp);
}

vec2 _textile_uvToGatherUV(vec2 uv, vec2 tileOffsetF, vec2 tileSizeF, vec2 textureSizeRcp) {
    vec2 textureTexelPos = clamp(uv * tileSizeF, vec2(1.0), tileSizeF - 1.0) + tileOffsetF;
    return saturate(textureTexelPos * textureSizeRcp);
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

    accessInfos.forEach {
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

    data class AllocationInfo(val usage: BitSet = BitSet(), val tileID: MutableMap<String, Int> = mutableMapOf(), var tileCount: Int = 0)

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
                slotInfo.tileCount = maxOf(slotInfo.tileCount, tileID + 1)
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
                append(" ivec2(uval_mainImageSizeI.x * ")
                append(cx)
                append(", uval_mainImageSizeI.y * ")
                append(cy)
                append(")\n")

                append("#define ")
                append(offsetF)
                append(" vec2(uval_mainImageSizeI.x * ")
                append(cx)
                append(", uval_mainImageSize.y * ")
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