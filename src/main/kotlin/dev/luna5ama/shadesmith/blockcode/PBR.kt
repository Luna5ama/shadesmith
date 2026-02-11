package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import dev.luna5ama.shadesmith.TextureFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

context(ioContext: IOContext)
fun generateHardcodedPBR() {
    println("Generating hardcoded PBR data...")
    val blockPropertiesPath = ioContext.inputPath.resolve("block.properties")
    val texturesPath = ioContext.inputPath.resolve("textures")

    // Read all blocks from blocklist.json
    println("Loading block list...")
    val blockListJson = loadBlockList()
    val blocks = parseBlocks(blockListJson)
    println("Loaded ${blocks.size} unique block types")

    // Generate PBR data for all blocks
    println("Generating PBR data for blocks...")
    val blockStateToPBRData = mutableMapOf<BlockState, List<LUTData>>()

    for ((minecraftId, statePropertyMap) in blocks) {
        // For each block type, we need to pass each state separately to the assembler
        for ((blockState, property) in statePropertyMap) {
            val pbrData = PBRAssemblerImpl.provide(blockState, property)
            for ((state, data) in pbrData) {
                blockStateToPBRData[state] = data
            }
        }
    }
    println("Generated PBR data for ${blockStateToPBRData.size} block states")

    // Create palette by deduplicating PBR data
    println("Creating palette...")
    val pbrDataToPalette = mutableMapOf<List<LUTData>, Int>()
    val reservedMaterialIDs = listOf(
        listOf("water", "flowing_water")
    )

    val reserved = reservedMaterialIDs.asSequence()
        .withIndex()
        .flatMap { (index, blockNames) -> blockNames.map { BlockState(it) to (index + 1) } }
        .toMap()
    val blockStateToMaterialId = reserved.toMutableMap()
    var nextMaterialId = reservedMaterialIDs.size

    for ((state, data) in blockStateToPBRData) {
        if (state in blockStateToMaterialId) {
            continue // Skip reserved IDs
        }
        val materialId = pbrDataToPalette.getOrPut(data) {
            ++nextMaterialId
        }
        blockStateToMaterialId[state] = materialId
    }
    val palette = pbrDataToPalette.toList() +
            reserved.entries
                .distinctBy { it.value }
                .map { (state, id) -> blockStateToPBRData[state]!! to id }
    println("Created palette with ${pbrDataToPalette.size} unique material IDs")

    // Write block.properties
    println("Writing block.properties...")
    writeBlockProperties(blockPropertiesPath, blockStateToMaterialId)

    // Write LUT files
    println("Writing LUT files...")
    writeLUTFiles(texturesPath, palette)
    println("Hardcoded PBR generation complete!")
}

private fun loadBlockList(): JsonArray {
    val resourceStream = object {}.javaClass.getResourceAsStream("/blockdata/blocklist.json")
        ?: error("Could not find blocklist.json in resources")
    val jsonText = resourceStream.bufferedReader().use { it.readText() }
    return Json.parseToJsonElement(jsonText).jsonArray
}

private fun loadBlockNameMapping(): Map<String, String> {
    val resourceStream = object {}.javaClass.getResourceAsStream("/blockdata/list_of_block_by_version.csv")
        ?: error("Could not find list_of_block_by_version.csv in resources")
    val mapping = mutableMapOf<String, String>()
    resourceStream.bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
            val parts = line.split(',')
            if (parts.size >= 2) {
                val displayName = parts[0].trim()
                val minecraftId = parts[1].trim()
                mapping[displayName] = minecraftId
            }
        }
    }
    return mapping
}

private fun parseBlocks(blockListJson: JsonArray): Map<String, Map<BlockState, BlockProperty>> {
    val blocks = mutableMapOf<String, MutableMap<BlockState, BlockProperty>>()
    val nameMapping = loadBlockNameMapping()

    for (element in blockListJson) {
        val jsonObject = element.jsonObject
        // BlockProperty.fromJsonObject returns Map<BlockState, BlockProperty>
        val blockStateProperties = BlockProperty.fromJsonObject(jsonObject)

        // Convert each BlockState's variant name to Minecraft ID using the CSV mapping
        for ((blockState, property) in blockStateProperties) {
            val variantDisplayName = blockState.name

            // Clean up "No Translation Key: " prefix if present
            val cleanedName = if (variantDisplayName.startsWith("No Translation Key: ")) {
                variantDisplayName.substring("No Translation Key: ".length)
            } else {
                variantDisplayName
            }

            val minecraftId = nameMapping[cleanedName] ?: run {
                val name = cleanedName.lowercase().replace(" ", "_").replace("-", "_")
                println("No Minecraft ID found for block state '$variantDisplayName', falling back to guessed name '$name'")
                name
            }

            // Create a new BlockState with the Minecraft ID instead of display name
            val minecraftBlockState = BlockState(minecraftId, blockState.states)

            blocks.getOrPut(minecraftId) { mutableMapOf() }[minecraftBlockState] = property
        }
    }

    return blocks
}


private fun writeBlockProperties(path: Path, blockStateToMaterialId: Map<BlockState, Int>) {
    path.parent?.createDirectories()

    val materialIdToStates = mutableMapOf<Int, MutableList<BlockState>>()
    for ((state, materialId) in blockStateToMaterialId) {
        materialIdToStates.getOrPut(materialId) { mutableListOf() }.add(state)
    }

    path.bufferedWriter().use { writer ->
        writer.write("# Generated hardcoded PBR material IDs\n")
        writer.write("# Material ID to block states mapping\n\n")

        for ((materialId, states) in materialIdToStates.entries.sortedBy { it.key }) {
            val blockIds = states.joinToString(" ") { state ->
                buildString {
                    append(state.name)
                    if (state.states.isNotEmpty()) {
                        append(":")
                        append(state.states.entries.joinToString(":") { "${it.key}=${it.value}" })
                    }
                }
            }
            writer.write("block.$materialId = $blockIds\n")
        }
    }
}

private fun writeLUTFiles(texturesPath: Path, pbrDataToPalette: List<Pair<List<LUTData>, Int>>) {
    texturesPath.createDirectories()

    if (pbrDataToPalette.isEmpty()) {
        println("No PBR data to write")
        return
    }

    // Find the maximum number of LUTs needed
    val maxLUTs = pbrDataToPalette.maxOfOrNull { it.first.size } ?: 0
    println("Writing $maxLUTs LUT file(s)...")

    // Find the maximum material ID to determine texture size
    val maxMaterialId = pbrDataToPalette.maxOfOrNull { it.second } ?: 0
    println("Maximum material ID: $maxMaterialId")

    // Create one file per LUT index
    for (lutIndex in 0 until maxLUTs) {
        // Create a byte array for the entire LUT (indexed by material ID)
        // We need to find the format and size for this LUT
        var lutFormat: TextureFormat? = null
        var bytesPerEntry = 0

        // Scan all entries to determine the format for this LUT index
        for ((dataList, _) in pbrDataToPalette) {
            if (lutIndex < dataList.size) {
                val lutData = dataList[lutIndex]
                if (lutFormat == null) {
                    lutFormat = lutData.format
                    bytesPerEntry = lutData.data.size
                } else {
                    // Verify all entries have the same format
                    require(lutFormat == lutData.format) {
                        "Inconsistent LUT format at index $lutIndex"
                    }
                    require(bytesPerEntry == lutData.data.size) {
                        "Inconsistent data size at index $lutIndex"
                    }
                }
            }
        }

        if (lutFormat == null) continue

        // Create a buffer for the entire LUT (1D texture)
        val textureData = ByteArray((maxMaterialId + 1) * bytesPerEntry)
        val prepended =
            pbrDataToPalette + (PBRAssemblerImpl.provide(BlockState(""), BlockProperty()).first().second to 0)

        // Fill in the data for each material ID
        for ((dataList, materialId) in prepended) {
            if (lutIndex < dataList.size) {
                val lutData = dataList[lutIndex]
                val offset = materialId * bytesPerEntry
                System.arraycopy(lutData.data, 0, textureData, offset, bytesPerEntry)
            }
        }

        // Write the LUT file as raw binary data
        val lutPath = texturesPath.resolve("pbr_lut_$lutIndex.bin")
        lutPath.outputStream().use { output ->
            output.write(textureData)
        }

        println("Wrote LUT $lutIndex: format=$lutFormat, size=${maxMaterialId + 1} entries, ${textureData.size} bytes")
    }
}