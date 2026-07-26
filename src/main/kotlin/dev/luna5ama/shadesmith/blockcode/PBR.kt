package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import dev.luna5ama.shadesmith.TextureFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.math.asin
import kotlin.math.atan2

private val readableJson = Json { prettyPrint = true }

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
    writeLUTFiles(texturesPath, generation.palette, generation.defaultPBRData)

    val blockModelGLSL = generateBlockModelGLSL(modelPalette, assets)
    writeBlockModelOutputs(blockModelGLSL)
    val reportPath = (ioContext.outputPath.parent ?: ioContext.outputPath).resolve("block_model_aabbs.json")
    ioContext.writeOutput(reportPath, blockModelAABBInfo(models))
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
    val layout: PBRLayout
)

internal fun preparePBRGeneration(
    properties: Map<BlockState, BlockProperty>,
    models: BlockModelsResult,
    modelPalette: BlockModelPalette
): PBRGeneration {
    val expandedProperties = expandBlockProperties(properties, models)
    val assembler = PBRAssembler(defaultPBRFields(modelPalette.modelIdByState))
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
    return PBRGeneration(
        blockStateToMaterialId = blockStateToMaterialId,
        palette = materialIdToPBRData.map { (materialId, data) -> data to materialId },
        defaultPBRData = assembler.defaultData,
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

internal fun blockModelAABBInfo(models: BlockModelsResult): String {
    val root = buildJsonObject {
        for (model in models.supported) {
            put(blockStateKey(model.state), buildJsonArray {
                for (aabb in model.aabbs) {
                    val rotation = aabb.quaternion.toEulerDegrees()
                    add(buildJsonObject {
                        put("origin", vectorJson(aabb.origin.x, aabb.origin.y, aabb.origin.z))
                        put("size", vectorJson(aabb.size.x, aabb.size.y, aabb.size.z))
                        put("rotation", vectorJson(rotation.x, rotation.y, rotation.z))
                    })
                }
            })
        }
    }
    return readableJson.encodeToString(root) + "\n"
}

private fun blockStateKey(state: BlockState) = buildString {
    append(state.blockID)
    if (state.states.isNotEmpty()) {
        append(":")
        append(state.states.entries.joinToString(":") { "${it.key}=${it.value}" })
    }
}

private fun GeometryQuaternion.toEulerDegrees(): GeometryVertex {
    val sinY = (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
    val yAngle = asin(sinY)
    val xAngle: Double
    val zAngle: Double
    if (kotlin.math.abs(sinY) >= 1.0 - 1e-10) {
        xAngle = 0.0
        zAngle = atan2(-2.0 * (x * y - w * z), 1.0 - 2.0 * (x * x + z * z))
    } else {
        xAngle = atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))
        zAngle = atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
    }
    return GeometryVertex(Math.toDegrees(xAngle), Math.toDegrees(yAngle), Math.toDegrees(zAngle))
}

private fun vectorJson(x: Double, y: Double, z: Double): JsonObject = buildJsonObject {
    put("x", x.readableNumber())
    put("y", y.readableNumber())
    put("z", z.readableNumber())
}

private fun Double.readableNumber(): BigDecimal = BigDecimal.valueOf(this)
    .setScale(6, RoundingMode.HALF_UP)
    .stripTrailingZeros()

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
