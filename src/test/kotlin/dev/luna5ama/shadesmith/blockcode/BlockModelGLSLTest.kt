package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockModelGLSLTest {
    @Test
    fun `generates pbr mappings and models for vanilla blockstates absent from bundled blocklist`() {
        val temporary = Files.createTempDirectory("shadesmith-pbr-corpus-")
        val input = temporary.resolve("input")
        val output = temporary.resolve("output")
        val clientJar = temporary.resolve("client.jar")
        val futureSlab = "future_test_slab"
        input.createDirectories()
        output.createDirectories()

        try {
            val entries = mapOf(
                "assets/minecraft/blockstates/$futureSlab.json" to
                    """{"variants":{"type=bottom":{"model":"minecraft:block/${futureSlab}_bottom"},"type=top":{"model":"minecraft:block/${futureSlab}_top"}}}""",
                "assets/minecraft/blockstates/stone.json" to
                    """{"variants":{"":{"model":"minecraft:block/stone"}}}""",
                "assets/minecraft/models/block/${futureSlab}_bottom.json" to boxModel(0, 8),
                "assets/minecraft/models/block/${futureSlab}_top.json" to boxModel(8, 16),
                "assets/minecraft/models/block/stone.json" to boxModel(0, 16)
            )
            ZipOutputStream(Files.newOutputStream(clientJar)).use { zip ->
                for ((name, contents) in entries.toSortedMap()) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }

            with(IOContext(input, output)) { generateHardcodedPBR(assets(clientJar)) }

            val blockProperties = input.resolve("block.properties").readText()
            assertTrue(blockProperties.contains("$futureSlab:type=bottom"), "Missing bottom selector mapping for $futureSlab")
            assertTrue(blockProperties.contains("$futureSlab:type=top"), "Missing top selector mapping for $futureSlab")
            val bottomId = materialIdFor(blockProperties, "$futureSlab:type=bottom")
            val topId = materialIdFor(blockProperties, "$futureSlab:type=top")
            val stoneId = materialIdFor(blockProperties, "stone")
            assertTrue(blockProperties.lineSequence().single { it.startsWith("block.1 = ") }.contains("water"))

            val shader = input.resolve("techniques/voxel/BlockModels.glsl").readText()
            val packedLut = input.resolve("textures/pbr_lut_0.bin").readBytes()
            val modelLut = input.resolve("textures/pbr_lut_1.bin").readBytes()
            val bottomModelId = decode(modelLut, bottomId) shr BLOCK_MODEL_ROTATION_BITS
            val topModelId = decode(modelLut, topId) shr BLOCK_MODEL_ROTATION_BITS
            assertTrue(bottomModelId > 0u)
            assertTrue(topModelId > 0u)
            assertTrue(
                shader.contains("if (modelID == ${bottomModelId}u)") ||
                    shader.contains("if (modelID == ${topModelId}u)")
            )
            assertTrue(bottomModelId == topModelId || shader.contains("} else {"))
            assertFalse(shader.contains("switch"))
            assertEquals(1u, (decode(packedLut, stoneId) shr 28) and 1u)
            assertEquals(0u, decode(modelLut, stoneId))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps model boundary hits when dda interval times round inward`() {
        val position = 0.9825858f
        val direction = 0.15522195f
        val invDirection = 1.0f / direction
        val ddaEntryT = Math.fma(1.0f, invDirection, -position * invDirection)
        val modelT = -(position - 1.0f) / direction
        assertTrue(modelT < ddaEntryT)
        assertTrue(Float.fromBits(0x3F800001) > 1.0f)

        val entryFace = quad(
            vertex(0.0, 0.3125, 0.375),
            vertex(0.0, 0.3125, 0.625),
            vertex(0.0, 0.6875, 0.625),
            vertex(0.0, 0.6875, 0.375)
        )
        assertNotNull(intersectQuad(vertex((position - 1.0f).toDouble(), 0.4, 0.5), vertex(direction.toDouble(), 0.98787963, 1e-7), entryFace))

        val model = state("boundary", 2, listOf(entryFace)).model
        val shader = generateBlockModelGLSL(
            buildBlockModelPalette(BlockModelsResult(listOf(model), emptyList())),
            assets()
        ).shader
        assertTrue(shader.contains("if (!(t >= 0.0 && t <= hitT)) return false;"))
        assertFalse(shader.contains("tMin"))
        assertFalse(shader.contains("tMax"))
    }

    @Test
    fun `keeps distant edge hits when float reconstruction crosses the quad boundary`() {
        val originX = -670.0273f
        val originY = 240.47926f
        val originZ = 759.6277f
        val directionX = 0.84266585f
        val directionY = -0.3024409f
        val directionZ = -0.95451576f
        val face = quad(
            vertex(0.0, 0.0, 0.0),
            vertex(0.0, 0.0, 1.0),
            vertex(0.0, 1.0, 1.0),
            vertex(0.0, 1.0, 0.0)
        )
        val origin = vertex(originX.toDouble(), originY.toDouble(), originZ.toDouble())
        val direction = vertex(directionX.toDouble(), directionY.toDouble(), directionZ.toDouble())
        assertNotNull(intersectQuad(origin, direction, face))

        val distance = -originX / directionX
        val reconstructedY = originY + directionY * distance
        assertTrue(reconstructedY < -1e-6f)
        assertTrue(reconstructedY > -5e-5f)

        val model = state("distant_edge", 2, listOf(face)).model
        val shader = generateBlockModelGLSL(
            buildBlockModelPalette(BlockModelsResult(listOf(model), emptyList())),
            assets()
        ).shader
        assertTrue(shader.contains("const float edgeTolerance = 5e-5;"))
        assertEquals(4, Regex("< -edgeTolerance").findAll(shader).count())
    }

    @Test
    fun `deduplicates quarter turn model rotations and preserves their transforms`() {
        // Given
        val full = state("full", 2, cube(), isFullCube = true)
        val shape = state("shape", 3, box(0.0, 0.0, 0.0, 0.25, 0.5, 1.0))
        val shapeAlias = state("shape_alias", 4, shape.model.quads.reversed())
        val rotated90 = state("rotated_90", 5, box(0.0, 0.0, 0.75, 1.0, 0.5, 1.0))
        val rotated180 = state("rotated_180", 6, box(0.75, 0.0, 0.0, 1.0, 0.5, 1.0))
        val rotated270 = state("rotated_270", 7, box(0.0, 0.0, 0.0, 1.0, 0.5, 0.25))
        val imprecise = state("imprecise", 8, listOf(quad(
            vertex(0.49999999999999994, 0.0, 0.0),
            vertex(1.0, 0.0, 1.0),
            vertex(1.0, 1.0, 1.0),
            vertex(0.5, 1.0, 0.0)
        )))
        val equivalent = listOf(shape, rotated90, rotated180, rotated270)
        val models = BlockModelsResult(
            listOf(full, shape, shapeAlias, rotated90, rotated180, rotated270, imprecise).map { it.model },
            emptyList()
        )

        // When
        val palette = buildBlockModelPalette(models)
        val first = generateBlockModelGLSL(palette, assets())
        val second = generateBlockModelGLSL(palette, assets())

        // Then
        assertEquals(first, second)
        assertEquals(palette.modelIdByState.getValue(shape.model.state), palette.modelIdByState.getValue(shapeAlias.model.state))
        val modelData = equivalent.map { palette.modelIdByState.getValue(it.model.state) }
        assertEquals(1, modelData.map { it shr BLOCK_MODEL_ROTATION_BITS }.distinct().size)
        assertEquals(4, modelData.distinct().size)
        equivalent.zip(modelData).forEach { (model, data) ->
            assertEquals(
                palette.geometries.first(),
                rotateBlockModelGeometry(model.model.quads, data and BLOCK_MODEL_ROTATION_MASK)
            )
        }
        assertEquals(2, palette.geometries.size)
        assertTrue(first.shader.contains("uint modelID = modelData >> 9u;"))
        assertTrue(first.shader.contains("_voxel_unrotateBlockModelVector"))
        val modelFunction = first.shader.substringAfter("bool voxel_intersectBlockModel(")
        assertEquals(1, Regex("(?m)^    bool hit = false;$").findAll(modelFunction).count())
        assertEquals(1, Regex("_voxel_unrotateBlockModelVector").findAll(modelFunction).count())
        assertEquals(1, modelFunction.lineSequence().count { it.trimStart().startsWith("return ") })
        assertFalse(modelFunction.contains("if (modelID < 2u)"))
        assertTrue(modelFunction.contains("} else {"))
        assertFalse(first.shader.contains("switch"))
        assertFalse(first.shader.contains("0.499999"))
        val impreciseModelId = palette.modelIdByState.getValue(imprecise.model.state) shr BLOCK_MODEL_ROTATION_BITS
        assertTrue(palette.geometries[impreciseModelId.toInt() - 1].any { quad ->
            quad.vertices.any { vertex -> vertex.x == 0.5 || vertex.y == 0.5 || vertex.z == 0.5 }
        })
        assertFalse(Regex("\\b(?:const\\s+)?vec[234]\\s+\\w+\\s*\\[").containsMatchIn(first.shader))
        assertTrue(first.shader.contains("version_metadata_sha1: metadata-sha1"))
        assertTrue(first.shader.contains("client_sha1: client-sha1"))
    }

    @Test
    fun `writes byte identical tracked and package shaders with deterministic diagnostics`() {
        // Given
        val temporary = java.nio.file.Files.createTempDirectory("shadesmith-model-output-")
        val input = temporary.resolve("input")
        val output = temporary.resolve("output")
        input.resolve("techniques/voxel").createDirectories()
        output.createDirectories()
        val generated = BlockModelGLSLOutput(
            shader = "generated shader\n",
            unsupportedDiagnostics = "{\"unsupported\":[]}\n",
            counts = GeometryCounts(1, 1, 1)
        )

        try {
            // When
            with(IOContext(input, output)) { writeBlockModelOutputs(generated) }

            // Then
            val tracked = input.resolve("techniques/voxel/BlockModels.glsl")
            val packaged = output.resolve("techniques/voxel/BlockModels.glsl")
            assertTrue(tracked.readBytes().contentEquals(packaged.readBytes()))
            assertEquals(generated.unsupportedDiagnostics, output.resolve("techniques/voxel/BlockModels.unsupported.json").readText())
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `expands selectors keeps unsupported pbr states and assigns stable reserved ids`() {
        // Given
        val properties = linkedMapOf(
            BlockState("water") to BlockProperty(blockID = "water"),
            BlockState("slab", mapOf("lit" to "false")) to BlockProperty(blockID = "slab"),
            BlockState("slab", mapOf("lit" to "true")) to BlockProperty(blockID = "slab", luminance = 7),
            BlockState("weighted") to BlockProperty(blockID = "weighted", luminance = 3),
            BlockState(BlockNames.ShortGrass) to BlockProperty(blockID = BlockNames.ShortGrass)
        )
        val bottom = ExpandedGeometryState(
            BlockState("slab", mapOf("type" to "bottom", "lit" to "false")),
            properties.getValue(BlockState("slab", mapOf("lit" to "false"))),
            box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        )
        val top = ExpandedGeometryState(
            BlockState("slab", mapOf("type" to "top", "lit" to "true")),
            properties.getValue(BlockState("slab", mapOf("lit" to "true"))),
            box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0)
        )
        val models = BlockModelsResult(
            supported = listOf(bottom, top),
            unsupported = listOf(
                UnsupportedGeometry("weighted", mapOf("kind" to "a"), "Weighted alternatives have different geometry"),
                UnsupportedGeometry("weighted", mapOf("kind" to "b"), "Weighted alternatives have different geometry"),
                UnsupportedGeometry("water", emptyMap(), "Fluid geometry is unsupported"),
                UnsupportedGeometry(BlockNames.ShortGrass, emptyMap(), "Cross model is unsupported")
            )
        )

        // When
        val modelPalette = buildBlockModelPalette(models)
        val first = preparePBRGeneration(properties, models, modelPalette)
        val second = preparePBRGeneration(properties, models, modelPalette)

        // Then
        assertEquals(first.blockStateToMaterialId, second.blockStateToMaterialId)
        assertEquals(1, first.blockStateToMaterialId.getValue(BlockState("water")))
        assertTrue(BlockState("grass") in first.blockStateToMaterialId)
        assertTrue(BlockState("weighted", mapOf("kind" to "a")) in first.blockStateToMaterialId)
        assertTrue(BlockState("weighted", mapOf("kind" to "b")) in first.blockStateToMaterialId)
        assertFalse(BlockState("slab", mapOf("lit" to "false")) in first.blockStateToMaterialId)
        assertTrue(first.blockStateToMaterialId.keys.any { it.blockID == "slab" && it.states.keys == setOf("lit", "type") })
        assertTrue(first.blockStateToMaterialId.values.max() < 16384)

        val palette = first.palette.associate { (data, materialId) -> materialId to data }
        val slabId = first.blockStateToMaterialId.getValue(bottom.state)
        val weightedId = first.blockStateToMaterialId.getValue(BlockState("weighted", mapOf("kind" to "a")))
        val grassId = first.blockStateToMaterialId.getValue(BlockState("grass"))
        assertTrue(decode(palette.getValue(slabId)[1]) > 0u)
        assertEquals(0u, decode(palette.getValue(weightedId)[1]))
        assertEquals(0u, decode(palette.getValue(grassId)[1]))
    }

    @Test
    fun `quad mirror hits slab top and rejects an outside ray`() {
        // Given
        val top = quad(
            vertex(0.0, 0.5, 0.0),
            vertex(0.0, 0.5, 1.0),
            vertex(1.0, 0.5, 1.0),
            vertex(1.0, 0.5, 0.0)
        )

        // When
        val hit = intersectQuad(vertex(0.5, 2.0, 0.5), vertex(0.0, -1.0, 0.0), top)
        val miss = intersectQuad(vertex(1.5, 2.0, 0.5), vertex(0.0, -1.0, 0.0), top)

        // Then
        assertEquals(1.5, assertNotNull(hit).first, 1e-6)
        assertEquals(vertex(0.0, 1.0, 0.0), hit.second)
        assertEquals(null, miss)
    }

    private data class FixtureState(val model: ExpandedGeometryState, val materialId: Int)

    private fun state(blockID: String, materialId: Int, quads: List<GeometryQuad>, isFullCube: Boolean = false) =
        FixtureState(
            ExpandedGeometryState(
                BlockState(blockID),
                BlockProperty(blockID = blockID, isFullCube = isFullCube),
                quads
            ),
            materialId
        )

    private fun assets(clientJar: Path = Path.of("client.jar")) = MinecraftAssets(
        versionId = "test-release",
        versionMetadata = MinecraftVersionMetadataProvenance(URI.create("https://example.test/version.json"), "metadata-sha1"),
        clientSha1 = "client-sha1",
        clientSize = 0,
        clientJar = clientJar
    )

    private fun boxModel(fromY: Int, toY: Int) =
        """{"elements":[{"from":[0,$fromY,0],"to":[16,$toY,16],"faces":{"up":{"texture":"#all"}}}]}"""

    private fun materialIdFor(properties: String, state: String): Int = properties.lineSequence()
        .first { line -> line.substringAfter(" = ").split(' ').contains(state) }
        .substringAfter("block.")
        .substringBefore(" = ")
        .toInt()

    private fun decode(data: ByteArray, materialId: Int): UInt = ByteBuffer
        .wrap(data, materialId * Int.SIZE_BYTES, Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).int.toUInt()

    private fun cube() = box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = listOf(
        quad(vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1), vertex(x0, y1, z0)),
        quad(vertex(x1, y0, z1), vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1)),
        quad(vertex(x0, y0, z1), vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1)),
        quad(vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1), vertex(x1, y1, z0)),
        quad(vertex(x1, y0, z0), vertex(x0, y0, z0), vertex(x0, y1, z0), vertex(x1, y1, z0)),
        quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
    )

    private fun quad(a: GeometryVertex, b: GeometryVertex, c: GeometryVertex, d: GeometryVertex): GeometryQuad {
        val normal = (b - a).cross(c - a).normalized()
        return GeometryQuad(listOf(a, b, c, d), normal)
    }

    private fun intersectQuad(origin: GeometryVertex, direction: GeometryVertex, quad: GeometryQuad): Pair<Double, GeometryVertex>? {
        val denominator = quad.normal.dot(direction)
        if (abs(denominator) <= 1e-6) return null
        val distance = quad.normal.dot(quad.vertices.first() - origin) / denominator
        if (distance < 0.0 || !distance.isFinite()) return null
        val point = origin + direction * distance
        if (quad.vertices.indices.any { index ->
                val a = quad.vertices[index]
                val b = quad.vertices[(index + 1) % quad.vertices.size]
                (b - a).cross(point - a).dot(quad.normal) < -1e-6
            }) return null
        val normal = if (denominator < 0.0) quad.normal else quad.normal * -1.0
        return distance to normal
    }

    private fun decode(data: LUTData): UInt = ByteBuffer.wrap(data.data)
        .order(ByteOrder.nativeOrder()).int.toUInt()

    private fun vertex(x: Double, y: Double, z: Double) = GeometryVertex(x, y, z)
    private operator fun GeometryVertex.plus(other: GeometryVertex) = vertex(x + other.x, y + other.y, z + other.z)
    private operator fun GeometryVertex.minus(other: GeometryVertex) = vertex(x - other.x, y - other.y, z - other.z)
    private operator fun GeometryVertex.times(scale: Double) = vertex(x * scale, y * scale, z * scale)
    private fun GeometryVertex.cross(other: GeometryVertex) = vertex(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    private fun GeometryVertex.dot(other: GeometryVertex) = x * other.x + y * other.y + z * other.z
    private fun GeometryVertex.normalized(): GeometryVertex {
        val length = kotlin.math.sqrt(dot(this))
        return vertex(x / length, y / length, z / length)
    }
}
