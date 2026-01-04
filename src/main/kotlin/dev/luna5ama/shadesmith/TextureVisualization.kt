package dev.luna5ama.shadesmith

import org.jetbrains.letsPlot.*
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.tooltips.layerTooltips
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Generates visualizations for texture allocation and usage patterns
 */
object TextureVisualization {

    /**
     * Generate an ASCII-based timeline showing texture lifetimes across passes
     */
    fun generateASCIITimeline(
        lifeTime: Map<String, LifeTimeRange>,
        passCount: Int,
        passNames: List<String>
    ): String = buildString {
        appendLine("=".repeat(80))
        appendLine("TEXTURE LIFETIME VISUALIZATION")
        appendLine("=".repeat(80))
        appendLine()

        val maxNameLength = lifeTime.keys.maxOfOrNull { it.length } ?: 0
        val colWidth = maxNameLength + 2

        // Header
        append(" ".repeat(colWidth))
        append("│")
        passNames.forEach { name ->
            append(" ${name.take(3).padEnd(3)} ")
        }
        appendLine()
        append("─".repeat(colWidth))
        append("┼")
        append("─".repeat(passCount * 5))
        appendLine()

        // Group textures by type
        val transientTextures = lifeTime.filter { it.value is LifeTimeRange.Transient }
            .toList()
            .sortedBy { (it.second as LifeTimeRange.Transient).range.first }
        val historyTextures = lifeTime.filter { it.value is LifeTimeRange.History }
        val persistentTextures = lifeTime.filter { it.value is LifeTimeRange.Persistent }

        if (transientTextures.isNotEmpty()) {
            appendLine("TRANSIENT TEXTURES:")
            transientTextures.forEach { (name, range) ->
                range as LifeTimeRange.Transient
                append(name.padEnd(colWidth))
                append("│")
                repeat(passCount) { pass ->
                    if (pass in range.range) {
                        append(" ███ ")
                    } else {
                        append("     ")
                    }
                }
                appendLine()
            }
        }

        if (historyTextures.isNotEmpty()) {
            appendLine()
            appendLine("HISTORY TEXTURES:")
            historyTextures.forEach { (name, range) ->
                range as LifeTimeRange.History
                append(name.padEnd(colWidth))
                append("│")
                repeat(passCount) { pass ->
                    when {
                        pass <= range.lastRead -> append(" RRR ")
                        pass >= range.firstWrite -> append(" WWW ")
                        else -> append("     ")
                    }
                }
                appendLine()
            }
        }

        if (persistentTextures.isNotEmpty()) {
            appendLine()
            appendLine("PERSISTENT TEXTURES:")
            persistentTextures.forEach { (name, range) ->
                range as LifeTimeRange.Persistent
                append(name.padEnd(colWidth))
                append("│")
                repeat(passCount) {
                    append(" PPP ")
                }
                appendLine(" (${range.width}x${range.height})")
            }
        }

        appendLine("=".repeat(80))
    }

    /**
     * Generate ASCII visualization of texture atlas packing
     */
    fun generateASCIIAtlasPacking(
        slots: Map<TextureFormat, *>,
        @Suppress("UNUSED_PARAMETER") config: Config
    ): String = buildString {
        appendLine("=".repeat(80))
        appendLine("TEXTURE ATLAS PACKING VISUALIZATION")
        appendLine("=".repeat(80))
        appendLine()

        slots.forEach { (format, allocInfoAny) ->
            // Use reflection to access properties since AllocationInfo is defined in another file
            val allocInfo = allocInfoAny!!
            @Suppress("UNCHECKED_CAST")
            val tileIDMap = allocInfo::class.members
                .first { it.name == "tileID" }
                .call(allocInfo) as Map<String, Int>
            val tileCount = allocInfo::class.members
                .first { it.name == "tileCount" }
                .call(allocInfo) as Int

            appendLine("Format: $format ($tileCount tiles)")
            appendLine("─".repeat(60))

            // Calculate grid dimensions
            val cxArray = arrayOf(0, 0, 1, 1, 0, 1, 2, 2, 2, 0, 1, 2, 3, 3, 3, 3, 0, 1, 2, 3)
            val cyArray = arrayOf(0, 1, 0, 1, 2, 2, 0, 1, 2, 3, 3, 3, 0, 1, 2, 3, 4, 4, 4, 4)
            val xSize = (0..<tileCount).maxOfOrNull { cxArray[it] }?.plus(1) ?: 1
            val ySize = (0..<tileCount).maxOfOrNull { cyArray[it] }?.plus(1) ?: 1

            // Create reverse mapping: tileID -> texture names
            val tileToTextures = mutableMapOf<Int, MutableList<String>>()
            tileIDMap.forEach { (texName, tileID) ->
                tileToTextures.getOrPut(tileID) { mutableListOf() }.add(texName)
            }

            // Draw grid
            for (y in 0..<ySize) {
                for (x in 0..<xSize) {
                    val tileID = (0..<tileCount).firstOrNull {
                        cxArray[it] == x && cyArray[it] == y
                    }

                    if (tileID != null) {
                        append("┌────────┐ ")
                    } else {
                        append("           ")
                    }
                }
                appendLine()

                for (x in 0..<xSize) {
                    val tileID = (0..<tileCount).firstOrNull {
                        cxArray[it] == x && cyArray[it] == y
                    }

                    if (tileID != null) {
                        val textures = tileToTextures[tileID] ?: emptyList()
                        val label = if (textures.isNotEmpty()) {
                            textures.first().take(8).padEnd(8)
                        } else {
                            "Tile$tileID".take(8).padEnd(8)
                        }
                        append("│$label│ ")
                    } else {
                        append("           ")
                    }
                }
                appendLine()

                for (x in 0..<xSize) {
                    val tileID = (0..<tileCount).firstOrNull {
                        cxArray[it] == x && cyArray[it] == y
                    }

                    if (tileID != null) {
                        append("└────────┘ ")
                    } else {
                        append("           ")
                    }
                }
                appendLine()
            }

            // List all textures in this format
            appendLine("\nTextures:")
            tileToTextures.toSortedMap().forEach { (tileID, textures) ->
                appendLine("  Tile $tileID: ${textures.joinToString(", ")}")
            }
            appendLine()
        }

        appendLine("=".repeat(80))
    }

    /**
     * Generate HTML/SVG visualizations using lets-plot - one file per visualization
     */
    fun generateHTMLVisualizations(
        lifeTime: Map<String, LifeTimeRange>,
        slots: Map<TextureFormat, *>,
        passNames: List<String>,
        outputPath: Path,
        config: Config,
        accessInfos: List<Pair<*, AccessInfo>>
    ) {
        // Generate single combined timeline plot with all formats (sorted by format then name)
        try {
            val plot = generateCombinedTimelinePlot(lifeTime, passNames, config, accessInfos)
            ggsave(plot, (outputPath / "texture_lifetime.html").toString())
        } catch (e: Exception) {
            println("Warning: Could not generate timeline plot: ${e.message}")
        }

//        // Generate atlas packing plots (one per format)
//        slots.forEach { (format, allocInfoAny) ->
//            try {
//                val allocInfo = allocInfoAny!!
//                @Suppress("UNCHECKED_CAST")
//                val tileIDMap = allocInfo::class.members
//                    .first { it.name == "tileID" }
//                    .call(allocInfo) as Map<String, Int>
//
//                val plot = generateAtlasPackingPlot(format, tileIDMap)
//                ggsave(plot, (outputPath / "atlas_packing_${format.name}.html").toString())
//            } catch (e: Exception) {
//                println("Warning: Could not generate atlas packing plot for $format: ${e.message}")
//            }
//        }

        // Generate single combined tile lifetime plot with all formats
        try {
            val plot = generateCombinedTileLifetimePlot(lifeTime, slots, passNames, config, accessInfos)
            ggsave(plot, (outputPath / "tile_lifetime.html").toString())
        } catch (e: Exception) {
            println("Warning: Could not generate tile lifetime plot: ${e.message}")
        }
    }

    data class AccessInfo(val reads: Set<String>, val writes: Set<String>)

    /**
     * Generate combined timeline plot with all textures sorted by format then name
     */
    private fun generateCombinedTimelinePlot(
        lifeTime: Map<String, LifeTimeRange>,
        passNames: List<String>,
        config: Config,
        accessInfos: List<Pair<*, AccessInfo>>
    ): Plot {
        val data = mutableMapOf<String, List<Any>>()
        val textureNames = mutableListOf<String>()
        val passIndices = mutableListOf<Int>()
        val passLabels = mutableListOf<String>()
        val states = mutableListOf<String>()
        val formats = mutableListOf<String>()

        // Group textures by format, then sort by format then name
        val texturesByFormat = lifeTime.entries
            .groupBy { (texName, _) -> config.screen[texName] ?: config.fixed[texName]?.format ?: TextureFormat.RGBA8 }
            .toSortedMap()

        texturesByFormat.forEach { (format, entries) ->
            val textures = entries.sortedBy { it.key }.map { it.key to it.value to format }
            textures.forEach { (pair, format) ->
                val (texName, range) = pair
                val texNameWithFormat = "$texName\t\t[${format.name}]"  // Add format suffix

                when (range) {
                    is LifeTimeRange.Transient -> {
                        range.range.forEach { pass ->
                            val accessInfo = accessInfos[pass].second
                            val hasRead = texName in accessInfo.reads
                            val hasWrite = texName in accessInfo.writes

                            val state = when {
                                hasRead && hasWrite -> "ReadWrite"
                                hasRead -> "Read"
                                hasWrite -> "Write"
                                else -> "Active"
                            }

                            textureNames.add(texNameWithFormat)
                            passIndices.add(pass)
                            passLabels.add(passNames.getOrElse(pass) { "Pass$pass" })
                            states.add(state)
                            formats.add(format.name)
                        }
                    }
                    is LifeTimeRange.History -> {
                        (0..<range.total).forEach { pass ->
                            val accessInfo = accessInfos[pass].second
                            val hasRead = texName in accessInfo.reads
                            val hasWrite = texName in accessInfo.writes

                            val isActive = pass <= range.lastRead || pass >= range.firstWrite

                            if (isActive) {
                                val state = when {
                                    hasRead && hasWrite -> "ReadWrite"
                                    hasRead -> "Read"
                                    hasWrite -> "Write"
                                    else -> "Active"
                                }

                                textureNames.add(texNameWithFormat)
                                passIndices.add(pass)
                                passLabels.add(passNames.getOrElse(pass) { "Pass$pass" })
                                states.add(state)
                                formats.add(format.name)
                            }
                        }
                    }
                    is LifeTimeRange.Persistent -> {
                        (0..<range.total).forEach { pass ->
                            val accessInfo = accessInfos[pass].second
                            val hasRead = texName in accessInfo.reads
                            val hasWrite = texName in accessInfo.writes

                            val state = when {
                                hasRead && hasWrite -> "ReadWrite"
                                hasRead -> "Read"
                                hasWrite -> "Write"
                                else -> "Active"
                            }

                            textureNames.add(texNameWithFormat)
                            passIndices.add(pass)
                            passLabels.add(passNames.getOrElse(pass) { "Pass$pass" })
                            states.add(state)
                            formats.add(format.name)
                        }
                    }
                }
            }

            // Add separator rows between formats
            val sepTotalLength = 32 - format.name.length
            val firstSepLength = sepTotalLength / 2
            val secondSepLength = sepTotalLength - firstSepLength
            textureNames.add("${"─".repeat(firstSepLength)} ${format.name} ${"─".repeat(secondSepLength)}")  // Separator line
            passIndices.add(passNames.size)
            passLabels.add("")
            states.add("Active")  // Use Active color for separator
            formats.add(format.name)
        }

        data["texture"] = textureNames
        data["pass"] = passIndices
        data["passLabel"] = passLabels
        data["state"] = states
        data["format"] = formats

        return letsPlot(data) +
                geomTile(color = "white", size = 2.0) {  // 2x larger blocks
                    x = "pass"
                    y = "texture"
                    fill = "state"
                } +
                labs(
                    title = "Texture Lifetime",
                    x = "Pass",
                    y = "Texture"
                ) +
                ggsize(1920, 1080)  // 2x more space per texture (60px)
    }

    private fun generateAtlasPackingPlot(
        format: TextureFormat,
        tileIDMap: Map<String, Int>
    ): Plot {
        val cxArray = arrayOf(0, 0, 1, 1, 0, 1, 2, 2, 2, 0, 1, 2, 3, 3, 3, 3, 0, 1, 2, 3)
        val cyArray = arrayOf(0, 1, 0, 1, 2, 2, 0, 1, 2, 3, 3, 3, 0, 1, 2, 3, 4, 4, 4, 4)

        val data = mutableMapOf<String, List<Any>>()
        val xCoords = mutableListOf<Double>()
        val yCoords = mutableListOf<Double>()
        val labels = mutableListOf<String>()
        val tileIDs = mutableListOf<String>()

        // Group textures by tile and sort by tile ID
        val tileToTextures = mutableMapOf<Int, MutableList<String>>()
        tileIDMap.forEach { (texName, tileID) ->
            tileToTextures.getOrPut(tileID) { mutableListOf() }.add(texName)
        }

        // Sort tiles by ID for consistent display
        tileToTextures.toSortedMap().forEach { (tileID, textures) ->
            val x = cxArray[tileID].toDouble()
            val y = cyArray[tileID].toDouble()

            // Add tile background
            xCoords.add(x)
            yCoords.add(y)
            tileIDs.add("Tile $tileID")

            // Sort textures by name within each tile
            val sortedTextures = textures.sorted()

            // Create full label with all texture names (no truncation, chart is bigger now)
            val label = sortedTextures.joinToString("\n")
            labels.add(label)
        }

        data["x"] = xCoords
        data["y"] = yCoords
        data["tileID"] = tileIDs
        data["label"] = labels

        // Make chart much larger to accommodate full texture names
        val maxX = xCoords.maxOrNull()?.toInt() ?: 0
        val maxY = yCoords.maxOrNull()?.toInt() ?: 0
        val width = 400 + maxX * 300
        val height = 400 + maxY * 300

        return letsPlot(data) +
                geomTile(color = "black", size = 2.0, fill = "#E3F2FD") {
                    x = "x"
                    y = "y"
                } +
                geomText(size = 10, fontface = "bold") {
                    x = "x"
                    y = "y"
                    label = "label"
                } +
                labs(
                    title = "Atlas Packing - $format (${tileToTextures.size} tiles)",
                    x = "Tile X",
                    y = "Tile Y"
                ) +
                ggsize(width, height)
    }

    /**
     * Generate combined tile lifetime plot - shows all tiles from all formats
     * Sorted by format then tile ID, with format prefix in labels
     * Y-axis: tiles, X-axis: passes, tooltip: texture names
     */
    private fun generateCombinedTileLifetimePlot(
        lifeTime: Map<String, LifeTimeRange>,
        slots: Map<TextureFormat, *>,
        passNames: List<String>,
        config: Config,
        accessInfos: List<Pair<*, AccessInfo>>
    ): Plot {
        val data = mutableMapOf<String, List<Any>>()
        val tileLabels = mutableListOf<String>()
        val passIndices = mutableListOf<Int>()
        val passLabels = mutableListOf<String>()
        val textureTooltips = mutableListOf<String>()
        val states = mutableListOf<String>()

        // Process all formats, sorted by format name
        val sortedFormats = slots.toSortedMap()
        var totalTiles = 0
        var isFirstFormat = true

        sortedFormats.forEach { (format, allocInfoAny) ->
            val allocInfo = allocInfoAny!!
            @Suppress("UNCHECKED_CAST")
            val tileIDMap = allocInfo::class.members
                .first { it.name == "tileID" }
                .call(allocInfo) as Map<String, Int>

            // Filter lifeTime to only include textures of this format
            val formatLifeTime = lifeTime.filter { (texName, _) ->
                config.screen[texName] == format || config.fixed[texName]?.format == format
            }

            // Invert the mapping: tileID -> textures
            val tileToTextures = mutableMapOf<Int, MutableList<String>>()
            tileIDMap.forEach { (texName, tileID) ->
                tileToTextures.getOrPut(tileID) { mutableListOf() }.add(texName)
            }

            // For each tile and each pass, determine what textures are active
            tileToTextures.toSortedMap().forEach { (tileID, textures) ->
                val tileLabel = "[${format.name}]\t\tTile $tileID"

                passNames.indices.forEach { pass ->
                    val accessInfo = accessInfos[pass].second

                    // Find which textures in this tile are active in this pass
                    val activeTextures = textures.filter { texName ->
                        val range = formatLifeTime[texName] ?: return@filter false
                        when (range) {
                            is LifeTimeRange.Transient -> pass in range.range
                            is LifeTimeRange.History -> pass <= range.lastRead || pass >= range.firstWrite
                            is LifeTimeRange.Persistent -> true  // Persistent textures are always active
                        }
                    }

                    if (activeTextures.isNotEmpty()) {
                        // Determine the state for this tile/pass
                        val hasAnyRead = activeTextures.any { it in accessInfo.reads }
                        val hasAnyWrite = activeTextures.any { it in accessInfo.writes }

                        val state = when {
                            hasAnyRead && hasAnyWrite -> "ReadWrite"
                            hasAnyRead -> "Read"
                            hasAnyWrite -> "Write"
                            else -> "Active"
                        }

                        tileLabels.add(tileLabel)
                        passIndices.add(pass)
                        passLabels.add(passNames.getOrElse(pass) { "Pass$pass" })
                        textureTooltips.add(activeTextures.sorted().joinToString("\n"))
                        states.add(state)
                    }
                }

                totalTiles++
            }

            // Add separator rows between formats
            val sepTotalLength = 16 - format.name.length
            val firstSepLength = sepTotalLength / 2
            val secondSepLength = sepTotalLength - firstSepLength
            tileLabels.add("${"─".repeat(firstSepLength)} ${format.name} ${"─".repeat(secondSepLength)}")  // Separator line
            passIndices.add(passNames.size)
            passLabels.add("")
            textureTooltips.add("")
            states.add("Active")  // Use Active color for separator
            totalTiles++
        }

        data["tile"] = tileLabels
        data["pass"] = passIndices
        data["passLabel"] = passLabels
        data["textures"] = textureTooltips
        data["state"] = states

        return letsPlot(data) +
                geomTile(color = "white", size = 2.0, tooltips = layerTooltips()  // 2x larger blocks
                    .line("Textures: @textures")
                    .line("Pass: @passLabel")
                    .line("State: @state")
                ) {
                    x = "pass"
                    y = "tile"
                    fill = "state"
                } +
                labs(
                    title = "Tile Lifetime",
                    x = "Pass",
                    y = "Tile"
                ) +
                ggsize(1920, 1080)  // 2x more space per tile (80px)
    }
}


