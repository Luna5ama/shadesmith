package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertFailsWith
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
        val futureRotated = "future_test_rotated"
        input.createDirectories()
        output.createDirectories()

        try {
            val entries = mapOf(
                "assets/minecraft/blockstates/$futureSlab.json" to
                    """{"variants":{"type=bottom":{"model":"minecraft:block/${futureSlab}_bottom"},"type=top":{"model":"minecraft:block/${futureSlab}_top"}}}""",
                "assets/minecraft/blockstates/stone.json" to
                    """{"variants":{"":{"model":"minecraft:block/stone"}}}""",
                "assets/minecraft/blockstates/$futureRotated.json" to
                    """{"variants":{"":{"model":"minecraft:block/$futureRotated","x":90,"y":90,"z":90}}}""",
                "assets/minecraft/models/block/${futureSlab}_bottom.json" to boxModel(0, 8),
                "assets/minecraft/models/block/${futureSlab}_top.json" to boxModel(8, 16),
                "assets/minecraft/models/block/$futureRotated.json" to """{"elements":[
                  {"from":[4,4,4],"to":[8,12,12],
                   "rotation":{"origin":[8,8,8],"axis":"y","angle":45,"rescale":true},
                   "faces":{"west":{}}},
                  {"from":[8,8,8],"to":[12,12,12],"faces":{"east":{}}}
                ]}""",
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
            val rotatedId = materialIdFor(blockProperties, futureRotated)
            val stoneId = materialIdFor(blockProperties, "stone")
            assertTrue(blockProperties.lineSequence().single { it.startsWith("block.1 = ") }.contains("water"))

            val shader = input.resolve("techniques/voxel/BlockModels.glsl").readText()
            val packedLut = input.resolve("textures/pbr_lut_0.bin").readBytes()
            val modelLut = input.resolve("textures/pbr_lut_1.bin").readBytes()
            val modelData = listOf(bottomId, topId, rotatedId).map { decode(modelLut, it) }
            assertTrue(shader.contains("texelFetch(usam_blockModelAABBs"))
            assertTrue(shader.contains("uint aabbCount = modelData >> 24u;"))
            assertTrue(shader.contains("for (uint i = 0u; i < aabbCount; ++i)"))
            assertFalse(shader.contains("switch"))
            assertFalse(shader.contains("modelID"))
            assertFalse(Regex("\\b(?:const\\s+)?vec[234]\\s+\\w+\\s*\\[").containsMatchIn(shader))
            assertEquals(listOf(1u, 1u, 2u), modelData.map { it shr 24 })

            val trackedAABBs = input.resolve("textures/block_model_aabbs.bin")
            val packagedAABBs = output.resolve("textures/block_model_aabbs.bin")
            assertTrue(Files.exists(trackedAABBs), "Missing tracked block-model AABB texture")
            assertTrue(Files.exists(packagedAABBs), "Missing packaged block-model AABB texture")
            val aabbBytes = trackedAABBs.readBytes()
            assertTrue(aabbBytes.contentEquals(packagedAABBs.readBytes()))
            val expectedAABBCount = modelData.maxOf { ((it shr 9) and 0x7FFFu) + (it shr 24) }.toInt()
            assertEquals(expectedAABBCount * 12, aabbBytes.size)

            val rotatedData = modelData.last()
            val rotatedAABB = aabbBytes.copyOfRange(
                (((rotatedData shr 9) and 0x7FFFu) * 12u).toInt(),
                (((rotatedData shr 9) and 0x7FFFu) * 12u + 12u).toInt()
            )
            val step = 1.0 / 255.0
            val decodedQuaternion = DoubleArray(4) { rotatedAABB[it].toUByte().toDouble() / 255.0 * 2.0 - 1.0 }
            val expectedQuaternion = canonicalQuaternion(rotatedData and BLOCK_MODEL_ROTATION_MASK)
                .times(axisQuaternion('z', -90.0))
                .times(axisQuaternion('y', -90.0))
                .times(axisQuaternion('x', -90.0))
                .times(axisQuaternion('y', 45.0))
                .normalized()
            assertTrue(kotlin.math.abs(decodedQuaternion.zip(expectedQuaternion.values).sumOf { it.first * it.second }) >= 1.0 - step * 4.0)
            val decodedOrigin = DoubleArray(3) { rotatedAABB[4 + it].toUByte().toDouble() / 255.0 }
            val decodedSize = DoubleArray(3) {
                rotatedAABB[8 + it].toUByte().toDouble() / 255.0 * kotlin.math.sqrt(2.0)
            }
            val expectedOrigin = rotateBlockModelTestVector(doubleArrayOf(0.625, 0.5, 0.375), rotatedData and BLOCK_MODEL_ROTATION_MASK, true)
            assertTrue(decodedOrigin.zip(expectedOrigin.asList()).all { kotlin.math.abs(it.first - it.second) <= step })
            assertEquals(1u, (decode(packedLut, stoneId) shr 28) and 1u)
            assertEquals(0u, decode(modelLut, stoneId))

            val aabbInfo = Json.parseToJsonElement(temporary.resolve("block_model_aabbs.json").readText()).jsonObject
            val rotatedInfo = aabbInfo.getValue(futureRotated).jsonArray
            assertEquals(2, rotatedInfo.size)
            val firstOrigin = rotatedInfo[0].jsonObject.getValue("origin").jsonObject
            val secondRotation = rotatedInfo[1].jsonObject.getValue("rotation").jsonObject
            assertEquals(0.625, firstOrigin.getValue("x").jsonPrimitive.double)
            assertEquals(0.0, secondRotation.getValue("x").jsonPrimitive.double)
            assertEquals(-90.0, secondRotation.getValue("y").jsonPrimitive.double)
            assertTrue("$futureSlab:type=bottom" in aabbInfo)
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
        assertTrue(shader.contains("float t = startsInside ? exitT : entryT;"))
        assertTrue(shader.contains("vec3 localNormal = startsInside ? -exitNormal : entryNormal;"))
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
        assertTrue(shader.contains("abs(localDir[axis]) <= 1e-8"))
        assertTrue(shader.contains("* 0.5 + 1e-6;"))
        assertTrue(shader.contains("abs(localOrigin[axis]) > halfSize[axis]"))
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
        assertEquals(1, modelData.map { (it shr BLOCK_MODEL_ROTATION_BITS) and 0x7FFFu }.distinct().size)
        assertTrue(modelData.all { it shr 24 == 1u })
        assertEquals(4, modelData.distinct().size)
        equivalent.zip(modelData).forEach { (model, data) ->
            assertEquals(
                palette.geometries.first(),
                rotateBlockModelGeometry(model.model.quads, data and BLOCK_MODEL_ROTATION_MASK)
            )
        }
        assertEquals(2, palette.geometries.size)
        assertTrue(first.shader.contains("uint aabbOffset = (modelData >> 9u) & 0x7FFFu;"))
        assertTrue(first.shader.contains("uint aabbCount = modelData >> 24u;"))
        assertTrue(first.shader.contains("_voxel_unrotateBlockModelVector"))
        val modelFunction = first.shader.substringAfter("bool voxel_intersectBlockModel(")
        assertEquals(1, Regex("(?m)^    bool hit = false;$").findAll(modelFunction).count())
        assertEquals(1, Regex("_voxel_unrotateBlockModelVector").findAll(modelFunction).count())
        assertEquals(1, modelFunction.lineSequence().count { it.trimStart().startsWith("return ") })
        assertFalse(modelFunction.contains("modelID"))
        assertEquals(24, first.aabbData.size)
        assertFalse(first.shader.contains("switch"))
        assertFalse(first.shader.contains("0.499999"))
        assertTrue(palette.geometries.flatten().any { quad ->
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
            assertTrue(input.resolve("textures/block_model_aabbs.bin").readBytes().contentEquals(
                output.resolve("textures/block_model_aabbs.bin").readBytes()
            ))
            assertEquals(generated.unsupportedDiagnostics, output.resolve("techniques/voxel/BlockModels.unsupported.json").readText())
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses BigDecimal conservative aabb bytes and rejects oversized model lists`() {
        val aabb = GeometryAABB(
            GeometryQuaternion(0.0, 0.0, 0.0, 1.0),
            vertex(0.5, 0.5, 0.5),
            vertex(0.3, 0.3, 0.3)
        )
        val model = ExpandedGeometryState(
            BlockState("quantized"),
            BlockProperty(blockID = "quantized"),
            cube(),
            listOf(aabb)
        )

        val bytes = generateBlockModelGLSL(
            BlockModelPalette(emptyMap(), emptyList(), listOf(aabb), emptyList()),
            assets()
        ).aabbData

        assertEquals(listOf(128, 128, 128, 255), bytes.slice(0..3).map { it.toUByte().toInt() })
        assertEquals(listOf(128, 128, 128), bytes.slice(4..6).map { it.toUByte().toInt() })
        assertTrue(bytes.slice(8..10).all { it.toUByte().toInt() >= 54 })
        val rescaledSize = 1.2727922061357853
        val rescaled = generateBlockModelGLSL(
            BlockModelPalette(emptyMap(), emptyList(), listOf(
                aabb.copy(size = vertex(rescaledSize, rescaledSize, rescaledSize))
            ), emptyList()),
            assets()
        )
        val decodedSize = rescaled.aabbData[8].toUByte().toDouble() / 255.0 * kotlin.math.sqrt(2.0)
        assertTrue(decodedSize >= rescaledSize)
        assertTrue(decodedSize - rescaledSize < kotlin.math.sqrt(2.0) / 255.0 * 8.0)
        assertTrue(rescaled.shader.contains("* 1.4142135623730951 * 0.5 + 1e-6;"))
        assertFailsWith<IllegalArgumentException> {
            buildBlockModelPalette(BlockModelsResult(listOf(model.copy(aabbs = List(256) { aabb })), emptyList()))
        }
    }

    @Test
    fun `encoded aabbs conservatively contain their source corners`() {
        val halfAngle = Math.toRadians(22.5) * 0.5
        val aabbs = listOf(
            GeometryAABB(
                GeometryQuaternion(0.0, 0.0, 0.0, 1.0),
                vertex(0.5, 0.25, 0.5),
                vertex(1.0, 0.5, 1.0)
            ),
            GeometryAABB(
                GeometryQuaternion(0.0, kotlin.math.sin(halfAngle), 0.0, kotlin.math.cos(halfAngle)),
                vertex(0.46875, 0.5, 0.53125),
                vertex(0.0625, 1.0, 0.5)
            )
        )
        val generated = generateBlockModelGLSL(
            BlockModelPalette(emptyMap(), emptyList(), aabbs, emptyList()),
            assets()
        )

        aabbs.forEachIndexed { index, source ->
            val offset = index * 12
            val decodedQuaternion = GeometryQuaternion(
                decodeSignedUNorm(generated.aabbData[offset]),
                decodeSignedUNorm(generated.aabbData[offset + 1]),
                decodeSignedUNorm(generated.aabbData[offset + 2]),
                decodeSignedUNorm(generated.aabbData[offset + 3])
            ).normalized()
            val decodedOrigin = vertex(
                decodeUNorm(generated.aabbData[offset + 4]),
                decodeUNorm(generated.aabbData[offset + 5]),
                decodeUNorm(generated.aabbData[offset + 6])
            )
            val decodedHalfSize = vertex(
                decodeSizeUNorm(generated.aabbData[offset + 8]) * 0.5 + 1e-6,
                decodeSizeUNorm(generated.aabbData[offset + 9]) * 0.5 + 1e-6,
                decodeSizeUNorm(generated.aabbData[offset + 10]) * 0.5 + 1e-6
            )
            val inverseQuaternion = GeometryQuaternion(
                -decodedQuaternion.x,
                -decodedQuaternion.y,
                -decodedQuaternion.z,
                decodedQuaternion.w
            )

            for (x in listOf(-0.5, 0.5)) {
                for (y in listOf(-0.5, 0.5)) {
                    for (z in listOf(-0.5, 0.5)) {
                        val sourceCorner = source.origin + rotate(
                            source.quaternion,
                            vertex(source.size.x * x, source.size.y * y, source.size.z * z)
                        )
                        val decodedCorner = rotate(inverseQuaternion, sourceCorner - decodedOrigin)
                        assertTrue(
                            abs(decodedCorner.x) <= decodedHalfSize.x &&
                                abs(decodedCorner.y) <= decodedHalfSize.y &&
                                abs(decodedCorner.z) <= decodedHalfSize.z,
                            "encoded AABB $index excludes source corner $sourceCorner at decoded $decodedCorner"
                        )
                    }
                }
            }
        }
        assertTrue(generated.shader.contains("* 0.5 + 1e-6;"))
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
                quads,
                listOf(aabb(quads))
            ),
            materialId
        )

    private fun aabb(quads: List<GeometryQuad>): GeometryAABB {
        val vertices = quads.flatMap(GeometryQuad::vertices)
        val minimum = vertex(vertices.minOf(GeometryVertex::x), vertices.minOf(GeometryVertex::y), vertices.minOf(GeometryVertex::z))
        val maximum = vertex(vertices.maxOf(GeometryVertex::x), vertices.maxOf(GeometryVertex::y), vertices.maxOf(GeometryVertex::z))
        return GeometryAABB(
            GeometryQuaternion(0.0, 0.0, 0.0, 1.0),
            vertex((minimum.x + maximum.x) * 0.5, (minimum.y + maximum.y) * 0.5, (minimum.z + maximum.z) * 0.5),
            vertex(maximum.x - minimum.x, maximum.y - minimum.y, maximum.z - minimum.z)
        )
    }

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

    private fun decodeUNorm(value: Byte) = value.toUByte().toDouble() / 255.0

    private fun decodeSignedUNorm(value: Byte) = decodeUNorm(value) * 2.0 - 1.0

    private fun decodeSizeUNorm(value: Byte) = decodeUNorm(value) * kotlin.math.sqrt(2.0)

    private fun rotate(quaternion: GeometryQuaternion, value: GeometryVertex): GeometryVertex {
        val rotated = quaternion * GeometryQuaternion(value.x, value.y, value.z, 0.0) *
            GeometryQuaternion(-quaternion.x, -quaternion.y, -quaternion.z, quaternion.w)
        return vertex(rotated.x, rotated.y, rotated.z)
    }

    private data class TestQuaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
        val values get() = listOf(x, y, z, w)

        fun times(other: TestQuaternion) = TestQuaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z
        )

        fun normalized(): TestQuaternion {
            val length = kotlin.math.sqrt(values.sumOf { it * it })
            return TestQuaternion(x / length, y / length, z / length, w / length)
        }
    }

    private fun axisQuaternion(axis: Char, degrees: Double): TestQuaternion {
        val radians = Math.toRadians(degrees) * 0.5
        val sine = kotlin.math.sin(radians)
        val cosine = kotlin.math.cos(radians)
        return when (axis) {
            'x' -> TestQuaternion(sine, 0.0, 0.0, cosine)
            'y' -> TestQuaternion(0.0, sine, 0.0, cosine)
            else -> TestQuaternion(0.0, 0.0, sine, cosine)
        }
    }

    private fun canonicalQuaternion(rotation: UInt): TestQuaternion {
        val x = rotateBlockModelTestVector(doubleArrayOf(1.0, 0.0, 0.0), rotation, false)
        val y = rotateBlockModelTestVector(doubleArrayOf(0.0, 1.0, 0.0), rotation, false)
        val z = rotateBlockModelTestVector(doubleArrayOf(0.0, 0.0, 1.0), rotation, false)
        val trace = x[0] + y[1] + z[2]
        if (trace > 0.0) {
            val scale = kotlin.math.sqrt(trace + 1.0) * 2.0
            return TestQuaternion((y[2] - z[1]) / scale, (z[0] - x[2]) / scale, (x[1] - y[0]) / scale, scale / 4.0)
        }
        if (x[0] > y[1] && x[0] > z[2]) {
            val scale = kotlin.math.sqrt(1.0 + x[0] - y[1] - z[2]) * 2.0
            return TestQuaternion(scale / 4.0, (x[1] + y[0]) / scale, (z[0] + x[2]) / scale, (y[2] - z[1]) / scale)
        }
        if (y[1] > z[2]) {
            val scale = kotlin.math.sqrt(1.0 + y[1] - x[0] - z[2]) * 2.0
            return TestQuaternion((x[1] + y[0]) / scale, scale / 4.0, (y[2] + z[1]) / scale, (z[0] - x[2]) / scale)
        }
        val scale = kotlin.math.sqrt(1.0 + z[2] - x[0] - y[1]) * 2.0
        return TestQuaternion((z[0] + x[2]) / scale, (y[2] + z[1]) / scale, scale / 4.0, (x[1] - y[0]) / scale)
    }

    private fun rotateBlockModelTestVector(value: DoubleArray, rotation: UInt, aroundCenter: Boolean): DoubleArray {
        val input = if (aroundCenter) DoubleArray(3) { value[it] - 0.5 } else value
        val result = DoubleArray(3) { outputAxis ->
            val transform = rotation shr (outputAxis * 3)
            val component = input[(transform and 3u).toInt()]
            if (transform and 4u == 0u) component else -component
        }
        if (aroundCenter) for (axis in result.indices) result[axis] += 0.5
        return result
    }

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
