package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import dev.luna5ama.shadesmith.TextureFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

context(ioContext: IOContext)
fun generateHardcodedPBR() = generateHardcodedPBR(acquireLatestMinecraftAssets())

context(ioContext: IOContext)
internal fun generateHardcodedPBR(assets: MinecraftAssets) {
    println("Generating hardcoded PBR data from Minecraft ${assets.versionId}...")
    val blockPropertiesPath = ioContext.inputPath.resolve("block.properties")
    val texturesPath = ioContext.inputPath.resolve("textures")

    println("Loading block list and vanilla block models...")
    val blocks = parseBlocks(loadBlockList()).toMutableMap()
    val bundledBlockIDs = blocks.keys.mapTo(hashSetOf(), BlockState::blockID)
    ZipFile(assets.clientJar.toFile()).use { zip ->
        zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .map { it.name }
            .filter { it.startsWith("assets/minecraft/blockstates/") && it.endsWith(".json") }
            .map { it.removePrefix("assets/minecraft/blockstates/").removeSuffix(".json") }
            .filter { it.isNotEmpty() && '/' !in it }
            .sorted()
            .filterNot { it in bundledBlockIDs }
            .forEach { blocks[BlockState(it)] = BlockProperty(blockID = it) }
    }
    val models = BlockModels.load(assets.clientJar, blocks)
    val modelPalette = buildBlockModelPalette(models)
    val generation = preparePBRGeneration(blocks, models, modelPalette)
    println("Generated PBR data for ${generation.blockStateToMaterialId.size} block states")
    println("Created palette with ${generation.palette.size} material IDs")

    println("Writing block.properties...")
    writeBlockProperties(blockPropertiesPath, generation.blockStateToMaterialId)
    println("Writing LUT files...")
    writeLUTFiles(
        texturesPath,
        generation.palette.map { (data, materialId) -> data.take(1) to materialId },
        generation.defaultPBRData.take(1)
    )
    writeBlockModelLUT(texturesPath.resolve("pbr_lut_1.bin"), generation.modelMetadataByMaterialId)

    val blockModelGLSL = generateBlockModelGLSL(modelPalette, assets)
    writeBlockModelOutputs(blockModelGLSL)
    println(
        "Generated block models: ${blockModelGLSL.counts.states} states, " +
            "${blockModelGLSL.counts.geometries} geometries, ${blockModelGLSL.counts.quads} quads"
    )
    println("Hardcoded PBR generation complete!")

    println("PBR layout:")
    generation.layout.forEach { entry ->
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

internal data class PBRGeneration(
    val blockStateToMaterialId: Map<BlockState, Int>,
    val palette: List<Pair<List<LUTData>, Int>>,
    val defaultPBRData: List<LUTData>,
    val modelMetadataByMaterialId: Map<Int, List<UInt>>,
    val layout: PBRLayout
)

internal fun preparePBRGeneration(
    properties: Map<BlockState, BlockProperty>,
    models: BlockModelsResult,
    modelPalette: BlockModelPalette
): PBRGeneration {
    val expandedProperties = expandBlockProperties(properties, models)
    val assembler = PBRAssembler(defaultPBRFields(modelPalette.modelIdByState.mapValues { (_, rows) -> rows[63] }))
    val blockStateToPBRData = linkedMapOf<BlockState, List<LUTData>>()
    for ((state, property) in expandedProperties.entries.sortedWith(compareBy({ it.key.blockID }, { selectorKey(it.key.states) }))) {
        for ((providedState, data) in assembler.provide(state, property)) {
            val previous = blockStateToPBRData.putIfAbsent(providedState, data)
            require(previous == null || previous == data) { "Conflicting PBR data for block state $providedState" }
        }
    }

    val blockStateToMaterialId = linkedMapOf<BlockState, Int>()
    var nextMaterialId = 1
    for (state in blockStateToPBRData.keys.sortedWith(compareBy({ it.blockID }, { selectorKey(it.states) }))) {
        blockStateToMaterialId[state] = if (state.blockID == "water" || state.blockID == "flowing_water") {
            1
        } else {
            ++nextMaterialId
        }
    }
    require((blockStateToMaterialId.values.maxOrNull() ?: 0) < 16384) {
        "Hardcoded PBR material IDs exceed the 16384-entry voxel material capacity"
    }

    val materialIdToPBRData = sortedMapOf<Int, List<LUTData>>()
    for ((state, materialId) in blockStateToMaterialId) {
        val data = blockStateToPBRData.getValue(state)
        val previous = materialIdToPBRData.putIfAbsent(materialId, data)
        require(previous == null || previous == data) {
            "Material ID $materialId is assigned to block states with different PBR data"
        }
    }
    val emptyModelMetadata = List(64) { 0u }
    val modelMetadataByMaterialId = sortedMapOf<Int, List<UInt>>()
    for ((state, materialId) in blockStateToMaterialId) {
        val metadata = modelPalette.modelIdByState[state] ?: emptyModelMetadata
        val previous = modelMetadataByMaterialId.putIfAbsent(materialId, metadata)
        require(previous == null || previous == metadata) {
            "Material ID $materialId is assigned to block states with different model geometry"
        }
    }
    return PBRGeneration(
        blockStateToMaterialId = blockStateToMaterialId,
        palette = materialIdToPBRData.map { (materialId, data) -> data to materialId },
        defaultPBRData = assembler.defaultData,
        modelMetadataByMaterialId = modelMetadataByMaterialId,
        layout = assembler.layout
    )
}

private fun expandBlockProperties(
    properties: Map<BlockState, BlockProperty>,
    models: BlockModelsResult
): Map<BlockState, BlockProperty> {
    val supportedByBlock = models.supported.groupBy { it.state.blockID }
    val unsupportedByBlock = models.unsupported.groupBy(UnsupportedGeometry::blockID)
    val result = linkedMapOf<BlockState, BlockProperty>()
    for ((blockID, blockProperties) in properties.entries.groupBy { it.key.blockID }.toSortedMap()) {
        val expanded = mutableListOf<Pair<BlockState, BlockProperty>>()
        supportedByBlock[blockID]?.forEach { expanded += it.state to it.property }
        unsupportedByBlock[blockID]?.forEach { unsupported ->
            for ((state, property) in blockProperties) {
                val selector = mergeSelectors(state.states, unsupported.selector) ?: continue
                expanded += BlockState(blockID, selector) to property
            }
        }
        val states = expanded.ifEmpty { blockProperties.map { it.key to it.value } }
        for ((state, property) in states) {
            val previous = result.putIfAbsent(state, property)
            require(previous == null || previous == property) { "Conflicting expanded block state: $state" }
        }
    }
    return result
}

private fun mergeSelectors(left: Map<String, String>, right: Map<String, String>): Map<String, String>? {
    val result = left.toSortedMap()
    for ((property, value) in right) {
        if (result[property]?.let { it != value } == true) return null
        result[property] = value
    }
    return result
}

private fun selectorKey(selector: Map<String, String>) =
    selector.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
private fun loadBlockList(): JsonArray {
    val resourceStream = object {}.javaClass.getResourceAsStream("/blockdata/blocklist.json")
        ?: error("Could not find blocklist.json in resources")
    val jsonText = resourceStream.bufferedReader().use { it.readText() }
    return Json.parseToJsonElement(jsonText).jsonArray
}

private fun parseBlocks(blockListJson: JsonArray): Map<BlockState, BlockProperty> {
    return blockListJson.asSequence()
        .flatMap {
            val jsonObject = it.jsonObject
            BlockProperty.fromJsonObject(jsonObject).toList()
        }
        .toMap()
}


private fun writeBlockProperties(path: Path, blockStateToMaterialId: Map<BlockState, Int>) {
    path.parent?.createDirectories()

    val materialIdToStates = blockStateToMaterialId.entries
        .groupingBy { it.value }
        .fold(listOf<BlockState>()) { states, entry -> states + entry.key }

    path.bufferedWriter().use { writer ->
        writer.write("# Generated hardcoded PBR material IDs\n")
        writer.write("# Material ID to block states mapping\n\n")

        for ((materialId, states) in materialIdToStates.toList().sortedBy { it.first }) {
            val blockIds = states.joinToString(" ", transform = ::blockStateKey)
            writer.write("block.$materialId = $blockIds\n")
        }
    }
}

private fun blockStateKey(state: BlockState) = buildString {
    append(state.blockID)
    if (state.states.isNotEmpty()) {
        append(":")
        append(state.states.entries.joinToString(":") { "${it.key}=${it.value}" })
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

private fun writeBlockModelLUT(path: Path, metadataByMaterialId: Map<Int, List<UInt>>) {
    val maxMaterialId = metadataByMaterialId.keys.maxOrNull() ?: 0
    val buffer = ByteBuffer.allocate((maxMaterialId + 1) * 64 * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for ((materialId, rows) in metadataByMaterialId) {
        require(rows.size == 64) { "Block model metadata must contain 64 cull-mask rows" }
        buffer.position(materialId * 64 * Int.SIZE_BYTES)
        rows.forEach { buffer.putInt(it.toInt()) }
    }
    path.outputStream().use { it.write(buffer.array()) }
    println("Wrote LUT 1: format=R32UI, size=${maxMaterialId + 1}x64 entries, ${buffer.capacity()} bytes")
}
