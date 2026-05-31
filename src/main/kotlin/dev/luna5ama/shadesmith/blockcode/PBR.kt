package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import dev.luna5ama.shadesmith.TextureFormat
import kotlinx.serialization.encodeToString
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
    val assembler = PBRAssembler(defaultPBRFields())
    val defaultPBRData = assembler.defaultData

    // Read all blocks from blocklist.json
    println("Loading block list...")
    val blockListJson = loadBlockList()
    val blocks = parseBlocks(blockListJson)
    println("Loaded ${blocks.size} unique block types")

    // Generate PBR data for all blocks
    println("Generating PBR data for blocks...")
    val blockStateToPBRData = mutableMapOf<BlockState, List<LUTData>>()

    for ((blockState, property) in blocks) {
        // For each block type, we need to pass each state separately to the assembler
        val pbrData = assembler.provide(blockState, property)
        for ((state, data) in pbrData) {
            blockStateToPBRData[state] = data
        }
    }
    println("Generated PBR data for ${blockStateToPBRData.size} block states")

    // Create palette with dedicated IDs per block state, except reserved mappings.
    println("Creating palette...")
    val reservedMaterialIDs = listOf(
        listOf("water", "flowing_water")
    )

    val reservedByState = reservedMaterialIDs.asSequence()
        .withIndex()
        .flatMap { (index, blockNames) -> blockNames.map { BlockState(it) to (index + 1) } }
        .toMap()
    val reservedByName = reservedByState
        .entries
        .groupBy({ it.key.blockID }, { it.value })
        .mapValues { (_, ids) -> ids.first() }

    val blockStateToMaterialId = mutableMapOf<BlockState, Int>()
    var nextMaterialId = reservedMaterialIDs.size

    for (state in blockStateToPBRData.keys) {
        val reservedMaterialId = reservedByState[state] ?: reservedByName[state.blockID]
        if (reservedMaterialId != null) {
            blockStateToMaterialId[state] = reservedMaterialId
            continue
        }
        blockStateToMaterialId[state] = ++nextMaterialId
    }

    val materialIdToPBRData = mutableMapOf<Int, List<LUTData>>()
    for ((state, materialId) in blockStateToMaterialId) {
        val pbrData = blockStateToPBRData[state] ?: continue
        val existing = materialIdToPBRData[materialId]
        if (existing == null) {
            materialIdToPBRData[materialId] = pbrData
        } else {
            require(existing == pbrData) {
                "Material ID $materialId is assigned to multiple block states with different PBR data"
            }
        }
    }

    val palette = materialIdToPBRData.entries
        .sortedBy { it.key }
        .map { (materialId, data) -> data to materialId }
    println("Created palette with ${palette.size} material IDs")

    // Write block.properties
    println("Writing block.properties...")
    writeBlockProperties(blockPropertiesPath, blockStateToMaterialId)

    // Write LUT files
    println("Writing LUT files...")
    writeLUTFiles(texturesPath, palette, defaultPBRData)
    println("Hardcoded PBR generation complete!")


    println("PBR layout:")
    assembler.layout.forEach { entry ->
        println("Texture ${entry.index}: format=${entry.format}")
        val pad = entry.fields.maxOf { it.field.length }
        entry.fields.forEach { field ->
            print("\t")
            print("${field.field}:".padEnd(pad + 1))
            print("encoding=${field.encoding},".padEnd(12))
            print('\t')
            print("bitOffset=${field.bitOffset},\t")
            print("bitWidth=${field.bitWidth},\t")
            print("signed=${field.signed}")
            println()
        }
    }
}

private fun loadBlockList(): JsonArray {
    val resourceStream = object {}.javaClass.getResourceAsStream("/blockdata/blocklist.json")
        ?: error("Could not find blocklist.json in resources")
    val jsonText = resourceStream.bufferedReader().use { it.readText() }
    return Json.parseToJsonElement(jsonText).jsonArray
}

private fun parseBlocks(blockListJson: JsonArray): Map<BlockState, BlockProperty> {
    val blocks = mutableMapOf<BlockState, BlockProperty>()

    for (element in blockListJson) {
        val jsonObject = element.jsonObject
        val blockStateProperties = BlockProperty.fromJsonObject(jsonObject)
        blocks.putAll(blockStateProperties)
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
                    append(state.blockID)
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

private fun writeLUTFiles(
    texturesPath: Path,
    pbrDataToPalette: List<Pair<List<LUTData>, Int>>,
    defaultPBRData: List<LUTData>
) {
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
        val prepended = pbrDataToPalette + (defaultPBRData to 0)

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
