package dev.luna5ama.shadesmith

import org.intellij.lang.annotations.Language
import java.util.*
import kotlin.io.path.name

private val REGEX_PREFIX = """^[^#\n\r]+((?:transient|history|persistent)_$IDENTIFIER_REGEX_STR)""".toRegex()
private val ATOMIC_REGEX = """atomic(?:Add|Min|Max|And|Or|Xor|Exchange|CompSwap)""".toRegex()
private val READ_REGEX =
    """${REGEX_PREFIX.pattern}_(sample|gather|gatherTexel|fetch|load|${ATOMIC_REGEX.pattern})\(""".toRegex(RegexOption.MULTILINE)
private val WRITE_REGEX =
    """^${REGEX_PREFIX.pattern}_(store|${ATOMIC_REGEX.pattern})\(""".toRegex(RegexOption.MULTILINE)

private tailrec fun findSlot(tiles: MutableList<BitSet>, allocateBitSet: BitSet, currSlot: Int): Int {
    while (tiles.lastIndex < currSlot) {
        tiles.add(BitSet())
    }
    val slotBitSet = tiles[currSlot]
    val intersection = allocateBitSet.clone() as BitSet
    intersection.and(slotBitSet)
    if (intersection.isEmpty) {
        slotBitSet.or(allocateBitSet)
        return currSlot
    } else {
        return findSlot(tiles, allocateBitSet, currSlot + 1)
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

vec2 _textile_texelToGatherUV(vec2 texelPos, vec2 tileOffsetF, vec2 tileSizeF, vec2 atlastSizeRcp) {
    vec2 textureTexelPos = clamp(texelPos, vec2(1.0), tileSizeF - 1.0) + tileOffsetF;
    return saturate(textureTexelPos * atlastSizeRcp);
}
#undef saturate
""".trim().trimIndent()

context(ioContext: IOContext)
fun resolveTextures(inputFiles: List<ShaderFile>) {
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
        .filter { it.compositeStyle }
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

//    println("Accesses:")
//    accessInfos.forEach {
//        if (it.second.reads.isEmpty() && it.second.writes.isEmpty()) return@forEach
//        println("${it.first}: reads: ${it.second.reads} writes: ${it.second.writes}")
//    }

    val lifeTime = mutableMapOf<String, LifeTimeRange>()

    // Add screen-based textures (transient and history)
    config.screen.keys.forEach { texName ->
        val typeStr = texName.substringBefore('_')
        when (typeStr) {
            "transient" -> {
                val exists = accessInfos.indices.filter {
                    val accessInfo = accessInfos[it].second
                    texName in accessInfo.reads || texName in accessInfo.writes
                }
                check(exists.size >= 1) { "Transient texture $texName must be read/written at least onrce." }
                lifeTime[texName] = LifeTimeRange.Transient(exists.min()..exists.max())
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
                lifeTime[texName] = LifeTimeRange.History(lastReadIndex, firstWriteIndex, accessInfos.size)
            }

            "persistent" -> {
                // Persistent textures from shader code (must also be in config.fixed)
                val fixedTexture = config.fixed[texName]
                    ?: error("Persistent texture $texName found in shader code but not defined in config.fixed")
                lifeTime[texName] = LifeTimeRange.Persistent(accessInfos.size, fixedTexture.width, fixedTexture.height)
            }

            else -> {
                error("Unknown texture type prefix for texture: $texName")
            }
        }
    }

    // Add persistent textures from config.fixed that weren't in screen
    config.fixed.forEach { (texName, fixedTexture) ->
        if (texName !in lifeTime) {
            lifeTime[texName] = LifeTimeRange.Persistent(accessInfos.size, fixedTexture.width, fixedTexture.height)
        }
    }

//    println("\nLifetime:")
//    lifeTime.forEach {
//        println("${it.key} (${config.screen[it.key] ?: config.fixed[it.key]?.format}): ${it.value}")
//    }

    data class AllocationInfo(val tileID: MutableMap<String, Int>, val tileCount: Int)
    data class FixedTileInfo(val width: Int, val height: Int, val offsetX: Int, val offsetY: Int)
    data class FixedAllocationInfo(
        val tileID: MutableMap<String, Int>,
        val tiles: MutableList<FixedTileInfo>,
        val atlasWidth: Int,
        val atlasHeight: Int
    )

    // Allocate screen-based textures (existing algorithm)
    val slots = config.screen.entries.groupBy { it.value }
        .mapValues { entry -> entry.value.map { it.key } }
        .mapValues { (format, texNames) ->
            val tileID = mutableMapOf<String, Int>()
            val tiles = mutableListOf<BitSet>()

            texNames.asSequence()
                .map { it to lifeTime[it]!! }
                .sortedBy { it.second.sortOrder }
                .forEach {
                    tileID[it.first] = findSlot(tiles, it.second.rangeBitSet(), 0)
                }

            AllocationInfo(tileID, tiles.size)
        }
        .toMap(EnumMap(TextureFormat::class.java))

    // Allocate fixed-size textures (separate atlases with absolute sizes)
    val fixedSlots = config.fixed.entries.groupBy { it.value.format }
        .mapValues { entry -> entry.value.map { it.key to it.value } }
        .mapValues { (format, textures) ->
            // Sort by height descending for shelf packing
            val sortedTextures = textures.sortedWith(
                compareByDescending<Pair<String, FixedSizedTexture>> { it.second.height }
                    .thenByDescending { it.second.width }
            )

            val minWidth = sortedTextures.maxOfOrNull { it.second.width } ?: 0
            val maxWidthLimit = maxOf(8192, minWidth)

            var bestArea = Long.MAX_VALUE
            var bestWidth = 0
            var bestHeight = 0
            var bestTiles = emptyList<FixedTileInfo>()

            // Try all possible widths from minWidth up to 8192 to find the smallest area
            for (wLimit in minWidth..maxWidthLimit) {
                var currentX = 0
                var currentY = 0
                var rowHeight = 0
                var maxW = 0
                val tiles = mutableListOf<FixedTileInfo>()

                for ((_, tex) in sortedTextures) {
                    if (currentX > 0 && currentX + tex.width > wLimit) {
                        currentX = 0
                        currentY += rowHeight
                        rowHeight = 0
                    }
                    tiles.add(FixedTileInfo(tex.width, tex.height, currentX, currentY))
                    currentX += tex.width
                    rowHeight = maxOf(rowHeight, tex.height)
                    maxW = maxOf(maxW, currentX)
                }
                val totalH = currentY + rowHeight
                val area = maxW.toLong() * totalH

                if (area < bestArea || (area == bestArea && maxOf(maxW, totalH) < maxOf(bestWidth, bestHeight))) {
                    bestArea = area
                    bestWidth = maxW
                    bestHeight = totalH
                    bestTiles = tiles
                }

                // If we've reached the minimum possible height (tallest texture), no point in increasing width further
                if (totalH == sortedTextures[0].second.height) break
            }

            val tileID = mutableMapOf<String, Int>()
            sortedTextures.forEachIndexed { index, (name, _) ->
                tileID[name] = index
            }

            FixedAllocationInfo(tileID, bestTiles.toMutableList(), bestWidth, bestHeight)
        }
        .toMap(EnumMap(TextureFormat::class.java))

//    println("\nAllocations:")
//    slots.forEach {
//        println("Format: ${it.key}")
//        it.value.tileID.forEach { (texName, tileID) ->
//            println("  Tex: $texName -> TileID: $tileID")
//        }
//    }

    // Generate visualizations
    val passNames = accessInfos.map { it.first.toString() }

    // Convert accessInfos to the format expected by visualization
    val accessInfosForViz = accessInfos.map { (sortKey, accessInfo) ->
        sortKey to TextureVisualization.AccessInfo(accessInfo.reads, accessInfo.writes)
    }

    // ASCII visualizations (printed to console)
//    println("\n" + TextureVisualization.generateASCIITimeline(lifeTime, accessInfos.size, passNames))
//    println("\n" + TextureVisualization.generateASCIIAtlasPacking(slots, config))

    // Try to generate HTML visualizations
    try {
        val outputPath = ioContext.outputPath.parent ?: ioContext.outputPath
        TextureVisualization.generateHTMLVisualizations(lifeTime, slots, passNames, outputPath, config, accessInfosForViz)
        println("\nHTML visualizations saved to: $outputPath")
        println("  - texture_lifetime.html (combined timeline for all formats)")
        println("  - tile_lifetime.html (combined tile view for all formats)")
        slots.keys.forEach { format ->
            println("  - atlas_packing_${format.name}.html")
        }
    } catch (e: Exception) {
        println("\nWarning: Could not generate HTML visualizations: ${e.message}")
        e.printStackTrace()
    }

    val cxArray = arrayOf(0, 0, 1, 1, 0, 1, 2, 2, 2, 0, 1, 2, 3, 3, 3, 3, 0, 1, 2, 3)
    val cyArray = arrayOf(0, 1, 0, 1, 2, 2, 0, 1, 2, 3, 3, 3, 0, 1, 2, 3, 4, 4, 4, 4)
    val xSizeArray = arrayOf(1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4)
    val ySizeArray = arrayOf(1, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5)

    val textTileCode = buildString {
        // Generate code for screen-based textures
        slots.forEach { (format, allocationInfo) ->
            fun prefix(tileID: Int) = "_shadesmith_${format.name}_$tileID"
            fun offsetStr(tileID: Int) = "${prefix(tileID)}_OFFSET"
            fun offsetFStr(tileID: Int) = "${prefix(tileID)}_OFFSET_F"
            fun sizeStr(tileID: Int) = "${prefix(tileID)}_SIZE"
            fun sizeFStr(tileID: Int) = "${prefix(tileID)}_SIZE_F"
            fun uvToUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_UV"
            fun texelToGatherUVStr(tileID: Int) = "${prefix(tileID)}_TEXEL_TO_GATHER_UV"
            fun uvToGatherUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_GATHER_UV"
            fun texelToTexelStr(tileID: Int) = "${prefix(tileID)}_TEXEL_TO_TEXEL"

            val xSize = xSizeArray[allocationInfo.tileCount - 1]
            val ySize = ySizeArray[allocationInfo.tileCount - 1]

            val atlasSize = "_shadesmith_${format.name}_ATLAS_SIZE"
            val atlasSizeI = "${atlasSize}_I"
            val atlasSizeRcp = "${atlasSize}_RCP"

            append("#define ")
            append(atlasSizeI)
            append(" (uval_mainImageSizeI * ivec2(")
            append(xSize)
            append(", ")
            append(ySize)
            append("))\n")

            append("#define ")
            append(atlasSize)
            append(" (uval_mainImageSize * vec2(")
            append(xSize)
            append(", ")
            append(ySize)
            append("))\n")

            append("#define ")
            append(atlasSizeRcp)
            append(" (vec2(1.0) / ")
            append(atlasSize)
            append(")\n")

            repeat(allocationInfo.tileCount) {
                val cx = cxArray[it]
                val cy = cyArray[it]

                val offsetStr = offsetStr(it)
                val offsetF = offsetFStr(it)
                val sizeStr = sizeStr(it)
                val sizeFStr = sizeFStr(it)
                append("#define ")
                append(offsetStr)
                append(" (uval_mainImageSizeI * ivec2(")
                append(cx)
                append(", ")
                append(cy)
                append("))\n")

                append("#define ")
                append(offsetF)
                append(" (uval_mainImageSize * vec2(")
                append(cx)
                append(", ")
                append(cy)
                append("))\n")

                append("#define ")
                append(sizeStr)
                append(" uval_mainImageSizeI\n")

                append("#define ")
                append(sizeFStr)
                append(" uval_mainImageSize\n")

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
                append(atlasSizeRcp)
                append(")\n")

                append("#define ")
                append(uvToGatherUVStr(it))
                append("(uv) _textile_uvToGatherUV(uv, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(atlasSizeRcp)
                append(")\n")

                append("#define ")
                append(texelToGatherUVStr(it))
                append("(texelPos) _textile_texelToGatherUV(texelPos, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(atlasSizeRcp)
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
                append("_gatherTexel(x, c) textureGather(")
                append(usamFormat)
                append(", ")
                append(texelToGatherUVStr(it.value))
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

                listOf("Add", "Min", "Max", "And", "Or", "Xor", "Exchange").forEach { atomicOp ->
                    append("#define ")
                    append(it.key)
                    append("_atomic")
                    append(atomicOp)
                    append("(x, v) imageAtomic")
                    append(atomicOp)
                    append("(")
                    append(uimgFormat)
                    append(", ")
                    append(texelToTexelStr(it.value))
                    append("(x), v)\n")
                }

                listOf("CompSwap").forEach { atomicOp ->
                    append("#define ")
                    append(it.key)
                    append("_atomic")
                    append(atomicOp)
                    append("(x, v1, v2) imageAtomic")
                    append(atomicOp)
                    append("(")
                    append(uimgFormat)
                    append(", ")
                    append(texelToTexelStr(it.value))
                    append("(x), v1, v2)\n")
                }
            }

            println("$format: $xSize x $ySize (${allocationInfo.tileCount})")
        }

        // Generate code for fixed-size textures
        fixedSlots.forEach { (format, allocationInfo) ->
            fun prefix(tileID: Int) = "_shadesmith_F${format.name}_$tileID"
            fun offsetStr(tileID: Int) = "${prefix(tileID)}_OFFSET"
            fun offsetFStr(tileID: Int) = "${prefix(tileID)}_OFFSET_F"
            fun sizeStr(tileID: Int) = "${prefix(tileID)}_SIZE"
            fun sizeFStr(tileID: Int) = "${prefix(tileID)}_SIZE_F"
            fun uvToUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_UV"
            fun texelToGatherUVStr(tileID: Int) = "${prefix(tileID)}_TEXEL_TO_GATHER_UV"
            fun uvToGatherUVStr(tileID: Int) = "${prefix(tileID)}_UV_TO_GATHER_UV"
            fun texelToTexelStr(tileID: Int) = "${prefix(tileID)}_TEXEL_TO_TEXEL"

            val atlasSize = "_shadesmith_F${format.name}_ATLAS_SIZE"
            val atlasSizeI = "${atlasSize}_I"
            val atlasSizeRcp = "${atlasSize}_RCP"

            append("#define ")
            append(atlasSizeI)
            append(" ivec2(")
            append(allocationInfo.atlasWidth)
            append(", ")
            append(allocationInfo.atlasHeight)
            append(")\n")

            append("#define ")
            append(atlasSize)
            append(" vec2(")
            append(allocationInfo.atlasWidth)
            append(".0, ")
            append(allocationInfo.atlasHeight)
            append(".0)\n")

            append("#define ")
            append(atlasSizeRcp)
            append(" (vec2(1.0) / ")
            append(atlasSize)
            append(")\n")

            allocationInfo.tiles.forEachIndexed { index, tile ->
                val offsetStr = offsetStr(index)
                val offsetF = offsetFStr(index)
                val sizeStr = sizeStr(index)
                val sizeFStr = sizeFStr(index)

                append("#define ")
                append(offsetStr)
                append(" ivec2(")
                append(tile.offsetX)
                append(", ")
                append(tile.offsetY)
                append(")\n")

                append("#define ")
                append(offsetF)
                append(" vec2(")
                append(tile.offsetX)
                append(".0, ")
                append(tile.offsetY)
                append(".0)\n")

                append("#define ")
                append(sizeStr)
                append(" ivec2(")
                append(tile.width)
                append(", ")
                append(tile.height)
                append(")\n")

                append("#define ")
                append(sizeFStr)
                append(" vec2(")
                append(tile.width)
                append(".0, ")
                append(tile.height)
                append(".0)\n")

                append("#define ")
                append(texelToTexelStr(index))
                append("(texelPos) _textile_texelToTexel(texelPos, ")
                append(offsetStr)
                append(", ")
                append(sizeStr)
                append(")\n")

                append("#define ")
                append(uvToUVStr(index))
                append("(uv) _textile_uvToUV(uv, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(atlasSizeRcp)
                append(")\n")

                append("#define ")
                append(uvToGatherUVStr(index))
                append("(uv) _textile_uvToGatherUV(uv, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(atlasSizeRcp)
                append(")\n")

                append("#define ")
                append(texelToGatherUVStr(index))
                append("(texelPos) _textile_texelToGatherUV(texelPos, ")
                append(offsetF)
                append(", ")
                append(sizeFStr)
                append(", ")
                append(atlasSizeRcp)
                append(")\n")
            }

            val formatLowercase = format.name.lowercase()
            val usamFormat = "usam_f$formatLowercase"
            val uimgFormat = "uimg_f$formatLowercase"

            allocationInfo.tileID.forEach { (texName, tileID) ->
                append("#define ")
                append(texName)
                append("_sample(x) texture(")
                append(usamFormat)
                append(", ")
                append(uvToUVStr(tileID))
                append("(x))\n")

                append("#define ")
                append(texName)
                append("_gather(x, c) textureGather(")
                append(usamFormat)
                append(", ")
                append(uvToGatherUVStr(tileID))
                append("(x), c)\n")

                append("#define ")
                append(texName)
                append("_gatherTexel(x, c) textureGather(")
                append(usamFormat)
                append(", ")
                append(texelToGatherUVStr(tileID))
                append("(x), c)\n")

                append("#define ")
                append(texName)
                append("_fetch(x) texelFetch(")
                append(usamFormat)
                append(", ")
                append(texelToTexelStr(tileID))
                append("(x), 0)\n")

                append("#define ")
                append(texName)
                append("_load(x) imageLoad(")
                append(uimgFormat)
                append(", ")
                append(texelToTexelStr(tileID))
                append("(x))\n")

                append("#define ")
                append(texName)
                append("_store(x, v) imageStore(")
                append(uimgFormat)
                append(", ")
                append(texelToTexelStr(tileID))
                append("(x), v)\n")

                listOf("Add", "Min", "Max", "And", "Or", "Xor", "Exchange").forEach { atomicOp ->
                    append("#define ")
                    append(texName)
                    append("_atomic")
                    append(atomicOp)
                    append("(x, v) imageAtomic")
                    append(atomicOp)
                    append("(")
                    append(uimgFormat)
                    append(", ")
                    append(texelToTexelStr(tileID))
                    append("(x), v)\n")
                }

                listOf("CompSwap").forEach { atomicOp ->
                    append("#define ")
                    append(texName)
                    append("_atomic")
                    append(atomicOp)
                    append("(x, v1, v2) imageAtomic")
                    append(atomicOp)
                    append("(")
                    append(uimgFormat)
                    append(", ")
                    append(texelToTexelStr(tileID))
                    append("(x), v1, v2)\n")
                }
            }

            println("Fixed $format: ${allocationInfo.atlasWidth} x ${allocationInfo.atlasHeight} (${allocationInfo.tiles.size} tiles)")
        }
    }

    val textileCode = textTileTemplate + "\n\n" + textTileCode
    val textileInputPath = ioContext.resolveInputPath("/base/Textile.glsl")
    ioContext.writeOutput(ShaderFile(textileInputPath, textileCode))
}