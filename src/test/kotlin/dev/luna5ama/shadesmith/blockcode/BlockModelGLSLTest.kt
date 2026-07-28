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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockModelGLSLTest {
    @Test
    fun `builds 64 pre-culled metadata rows and deduplicates filtered geometry`() {
        val bits = listOf(1u, 2u, 4u, 8u, 16u, 32u)
        val alwaysQuad = quad(
            vertex(0.2, 0.2, 0.25),
            vertex(0.8, 0.2, 0.25),
            vertex(0.8, 0.8, 0.25),
            vertex(0.2, 0.8, 0.25)
        )
        val mixedState = BlockState("mixed")
        val alwaysState = BlockState("always")
        val conditionalState = BlockState("conditional")
        val emptyState = BlockState("empty")
        val fullCubeState = BlockState("full")
        val mixedQuads = listOf(alwaysQuad) + bits.mapIndexed { index, bit ->
            val z = 0.3 + index * 0.1
            quad(
                vertex(0.2, 0.2, z),
                vertex(0.8, 0.2, z),
                vertex(0.8, 0.8, z),
                vertex(0.2, 0.8, z),
                bit
            )
        }
        val palette = buildBlockModelPalette(BlockModelsResult(
            listOf(
                model(mixedState, mixedQuads),
                model(alwaysState, listOf(alwaysQuad)),
                model(conditionalState, listOf(alwaysQuad.copy(cullFace = 1u))),
                model(emptyState, emptyList()),
                model(fullCubeState, cube(), isFullCube = true)
            ),
            emptyList()
        ))

        val mixedRows = palette.modelIdByState.getValue(mixedState)
        assertEquals(64, mixedRows.size)
        for (openMask in 0 until 64) {
            val metadata = mixedRows[openMask]
            val expectedCount = 1 + bits.count { bit -> openMask.toUInt() and bit != 0u }
            assertEquals(expectedCount.toUInt(), metadata shr 25, "openMask=$openMask")
            val rotation = metadata and BLOCK_MODEL_ROTATION_MASK
            val quadOffset = (metadata shr BLOCK_MODEL_ROTATION_BITS) and 0xFFFFu
            assertEquals(
                rotation or (quadOffset shl BLOCK_MODEL_ROTATION_BITS) or (expectedCount.toUInt() shl 25),
                metadata,
                "rotation9 | quadOffset16<<9 | quadCount7<<25 for openMask=$openMask"
            )
        }
        assertEquals(1u, mixedRows[0] shr 25)
        assertEquals(7u, mixedRows[63] shr 25)

        val alwaysRows = palette.modelIdByState.getValue(alwaysState)
        val conditionalRows = palette.modelIdByState.getValue(conditionalState)
        assertTrue(alwaysRows.all { metadata -> metadata == alwaysRows[0] })
        for (openMask in 0 until 64) {
            val expected = if (openMask and 1 == 0) 0u else alwaysRows[openMask]
            assertEquals(expected, conditionalRows[openMask], "deduplicated row for openMask=$openMask")
        }
        assertTrue(palette.modelIdByState.getValue(emptyState).all { it == 0u })
        assertTrue(palette.modelIdByState.getValue(fullCubeState).all { it == 0u })
    }

    @Test
    fun `encodes conservative two-texel quads and emits array-free quad intersection glsl`() {
        val state = BlockState("tilted")
        val source = quad(
            vertex(0.2, 0.2, 0.2),
            vertex(0.8, 0.2, 0.4),
            vertex(0.8, 0.8, 0.4),
            vertex(0.2, 0.8, 0.2)
        )
        val palette = buildBlockModelPalette(BlockModelsResult(listOf(model(state, listOf(source))), emptyList()))
        val generated = generateBlockModelGLSL(palette, assets())
        val metadata = palette.modelIdByState.getValue(state)[0]
        val quadOffset = ((metadata shr BLOCK_MODEL_ROTATION_BITS) and 0xFFFFu).toInt()
        val bytes = generated.quadData.slice(quadOffset * 8 until quadOffset * 8 + 8)

        assertEquals(paddedQuadTextureByteCount(1), generated.quadData.size)
        assertEquals(8, bytes.size)
        assertTrue(generated.quadData.drop(8).all { it == 0.toByte() })
        val encodedSource = palette.geometries.single().single()
        val expectedOrigin = encodedSource.vertices.reduce { sum, vertex -> sum + vertex } * 0.25
        val origin = vertex(
            decodeUNorm(bytes[0]),
            decodeUNorm(bytes[1]),
            decodeUNorm(bytes[2])
        )
        assertTrue(abs(origin.x - expectedOrigin.x) <= 1.0 / 255.0)
        assertTrue(abs(origin.y - expectedOrigin.y) <= 1.0 / 255.0)
        assertTrue(abs(origin.z - expectedOrigin.z) <= 1.0 / 255.0)
        val sourceNormalComponents = listOf(encodedSource.normal.x, encodedSource.normal.y, encodedSource.normal.z)
        val encodedNormalComponents = bytes.slice(3..5).map { it.toUByte().toInt() }
        val exactZeroComponents = sourceNormalComponents.indices.filter { sourceNormalComponents[it] == 0.0 }
        assertTrue(exactZeroComponents.isNotEmpty())
        exactZeroComponents.forEach { index ->
            assertEquals(126, encodedNormalComponents[index], "exact zero normal component $index")
        }
        val normal = vertex(
            decodeSignedUNorm(bytes[3]),
            decodeSignedUNorm(bytes[4]),
            decodeSignedUNorm(bytes[5])
        ).normalized()
        assertTrue(normal.dot(encodedSource.normal) >= 1.0 - 3.0 / 126.0)
        val axis = if (abs(normal.z) < 0.999) vertex(0.0, 0.0, 1.0) else vertex(0.0, 1.0, 0.0)
        val u = axis.cross(normal).normalized()
        val v = normal.cross(u)
        val halfSizeU = decodeUNorm(bytes[6]) + 1e-6
        val halfSizeV = decodeUNorm(bytes[7]) + 1e-6
        for (vertex in encodedSource.vertices) {
            val relative = vertex - origin
            assertTrue(abs(relative.dot(u)) <= halfSizeU, "encoded U half size excludes $vertex")
            assertTrue(abs(relative.dot(v)) <= halfSizeV, "encoded V half size excludes $vertex")
        }

        val shader = generated.shader
        assertEquals(2, Regex("texelFetch\\(usam_blockModelQuads").findAll(shader).count())
        assertTrue(shader.contains("ivec2 texelCoord = ivec2(int((quadIndex << 1u) & 0x7FFFu), int(quadIndex >> 14u));"))
        assertTrue(shader.contains("texelCoord + ivec2(1, 0)"))
        assertTrue(shader.contains("vec2 halfSize"))
        assertTrue(shader.contains("dot(normal, rayDir)"))
        assertTrue(shader.contains("cross(axis, normal)"))
        assertTrue(shader.contains("uint rotation = modelData & 0x1FFu;"))
        assertTrue(shader.contains("uint quadOffset = (modelData >> 9u) & 0xFFFFu;"))
        assertTrue(shader.contains("uint quadCount = modelData >> 25u;"))
        assertFalse(shader.contains("aabb", ignoreCase = true))
        assertFalse(Regex("\\b(?:const\\s+)?vec[234]\\s+\\w+\\s*\\[").containsMatchIn(shader))
    }

    @Test
    fun `orients quad normals against the ray`() {
        val shader = generateBlockModelGLSL(
            BlockModelPalette(emptyMap(), emptyList(), emptyList(), emptyList()),
            assets()
        ).shader
        assertTrue(shader.contains("hitNormal = denominator < 0.0 ? normal : -normal;"))
        assertEquals(vertex(0.0, 0.0, 1.0), assertNotNull(intersectQuad(
            vertex(0.5, 0.5, 1.0), vertex(0.0, 0.0, -1.0), cube().last()
        )).second)
        assertEquals(vertex(0.0, 0.0, -1.0), assertNotNull(intersectQuad(
            vertex(0.5, 0.5, -1.0), vertex(0.0, 0.0, 1.0), cube()[4]
        )).second)
    }

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
            val maxMaterialId = blockProperties.lineSequence().mapNotNull { line ->
                line.substringBefore(" = ").removePrefix("block.").toIntOrNull()
            }.max()
            assertEquals((maxMaterialId + 1) * 64 * 4, modelLut.size)
            val rows = listOf(bottomId, topId, rotatedId).map { materialId ->
                listOf(
                    decodeModelMetadata(modelLut, materialId, 0),
                    decodeModelMetadata(modelLut, materialId, 63)
                )
            }
            assertEquals(listOf(1u, 1u, 2u), rows.map { it[0] shr 25 })
            assertEquals(rows.map { it[0] }, rows.map { it[1] }, "unculled quads survive masks 0 and 63")
            assertEquals(0u, decodeModelMetadata(modelLut, stoneId, 0))
            assertEquals(0u, decodeModelMetadata(modelLut, stoneId, 63))

            assertTrue(shader.contains("texelFetch(usam_blockModelQuads"))
            assertTrue(shader.contains("uint quadCount = modelData >> 25u;"))
            assertTrue(shader.contains("for (uint i = 0u; i < quadCount; ++i)"))
            assertFalse(shader.contains("switch"))
            assertFalse(shader.contains("modelID"))
            assertFalse(shader.contains("aabb", ignoreCase = true))
            assertFalse(Regex("\\b(?:const\\s+)?vec[234]\\s+\\w+\\s*\\[").containsMatchIn(shader))

            val trackedQuads = input.resolve("textures/block_model_quads.bin")
            val packagedQuads = output.resolve("textures/block_model_quads.bin")
            assertTrue(Files.exists(trackedQuads), "Missing tracked block-model quad texture")
            assertTrue(Files.exists(packagedQuads), "Missing packaged block-model quad texture")
            val quadBytes = trackedQuads.readBytes()
            assertTrue(quadBytes.contentEquals(packagedQuads.readBytes()))
            val expectedQuadCount = (0..maxMaterialId).maxOf { materialId ->
                (0 until 64).maxOf { openMask ->
                    val metadata = decodeModelMetadata(modelLut, materialId, openMask)
                    ((metadata shr 9) and 0xFFFFu) + (metadata shr 25)
                }
            }.toInt()
            assertEquals(paddedQuadTextureByteCount(expectedQuadCount), quadBytes.size)
            assertTrue(quadBytes.drop(expectedQuadCount * 8).all { it == 0.toByte() })
            assertEquals(1u, (decode(packedLut, stoneId) shr 28) and 1u)
            Files.walk(temporary).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().contains("aabb", ignoreCase = true) })
            }
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
        assertTrue(shader.contains("float t = dot(normal, origin - rayOrigin) / denominator;"))
        assertTrue(shader.contains("if (any(greaterThan(projected, halfSize))) return false;"))
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
        assertTrue(shader.contains("abs(denominator) <= 1e-8"))
        assertTrue(shader.contains("vec2 halfSize = normalYZHalfSize.zw + 1e-6;"))
        assertTrue(shader.contains("if (any(greaterThan(projected, halfSize))) return false;"))
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
        val modelRows = equivalent.map { palette.modelIdByState.getValue(it.model.state) }
        assertTrue(modelRows.all { rows -> rows.all { it == rows[0] } })
        val modelData = modelRows.map { it[0] }
        assertEquals(1, modelData.map { (it shr BLOCK_MODEL_ROTATION_BITS) and 0xFFFFu }.distinct().size)
        assertTrue(modelData.all { it shr 25 == shape.model.quads.size.toUInt() })
        assertEquals(4, modelData.distinct().size)
        equivalent.zip(modelData).forEach { (model, data) ->
            assertEquals(
                palette.geometries.first(),
                rotateBlockModelGeometry(model.model.quads, data and BLOCK_MODEL_ROTATION_MASK)
            )
        }
        assertEquals(2, palette.geometries.size)
        assertTrue(first.shader.contains("uint quadOffset = (modelData >> 9u) & 0xFFFFu;"))
        assertTrue(first.shader.contains("uint quadCount = modelData >> 25u;"))
        assertTrue(first.shader.contains("_voxel_unrotateBlockModelVector"))
        val modelFunction = first.shader.substringAfter("bool voxel_intersectBlockModel(")
        assertEquals(1, Regex("(?m)^    bool hit = false;$").findAll(modelFunction).count())
        assertEquals(1, Regex("_voxel_unrotateBlockModelVector").findAll(modelFunction).count())
        assertEquals(1, modelFunction.lineSequence().count { it.trimStart().startsWith("return ") })
        assertFalse(modelFunction.contains("modelID"))
        assertEquals(paddedQuadTextureByteCount(palette.quads.size), first.quadData.size)
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
            counts = GeometryCounts(1, 1, 1),
            quadData = listOf(1, 2, 3, 4, 5, 6, 7, 8).map(Int::toByte)
        )

        try {
            // When
            with(IOContext(input, output)) { writeBlockModelOutputs(generated) }

            // Then
            val tracked = input.resolve("techniques/voxel/BlockModels.glsl")
            val packaged = output.resolve("techniques/voxel/BlockModels.glsl")
            assertTrue(tracked.readBytes().contentEquals(packaged.readBytes()))
            assertTrue(input.resolve("textures/block_model_quads.bin").readBytes().contentEquals(
                output.resolve("textures/block_model_quads.bin").readBytes()
            ))
            assertEquals(
                listOf("block_model_quads.bin"),
                Files.list(input.resolve("textures")).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
            )
            assertEquals(generated.unsupportedDiagnostics, output.resolve("techniques/voxel/BlockModels.unsupported.json").readText())
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses BigDecimal UNORM rounding and rejects more than 127 quads per row`() {
        val square = quad(
            vertex(0.2, 0.2, 0.5),
            vertex(0.8, 0.2, 0.5),
            vertex(0.8, 0.8, 0.5),
            vertex(0.2, 0.8, 0.5)
        )
        val palette = buildBlockModelPalette(BlockModelsResult(
            listOf(model(BlockState("quantized"), listOf(square))),
            emptyList()
        ))
        val bytes = generateBlockModelGLSL(palette, assets()).quadData
        assertEquals(listOf(128, 128, 128), bytes.take(3).map { it.toUByte().toInt() })
        val normalBytes = bytes.slice(3..5).map { it.toUByte().toInt() }
        assertEquals(2, normalBytes.count { it == 126 })
        assertTrue(normalBytes.any { it == 0 || it == 252 })

        val tooMany = List(128) { index ->
            val z = (index + 1.0) / 129.0
            quad(vertex(0.2, 0.2, z), vertex(0.8, 0.2, z), vertex(0.8, 0.8, z), vertex(0.2, 0.8, z))
        }
        assertFailsWith<IllegalArgumentException> {
            buildBlockModelPalette(BlockModelsResult(listOf(model(BlockState("oversized"), tooMany)), emptyList()))
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
        assertEquals(modelPalette.modelIdByState.getValue(bottom.state), first.modelMetadataByMaterialId.getValue(slabId))
        assertTrue(first.modelMetadataByMaterialId.getValue(weightedId).all { it == 0u })
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

    private fun decodeModelMetadata(data: ByteArray, materialId: Int, openMask: Int): UInt = ByteBuffer
        .wrap(data, 4 * (materialId * 64 + openMask), 4)
        .order(ByteOrder.LITTLE_ENDIAN).int.toUInt()

    private fun cube() = box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = listOf(
        quad(vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1), vertex(x0, y1, z0)),
        quad(vertex(x1, y0, z1), vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1)),
        quad(vertex(x0, y0, z1), vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1)),
        quad(vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1), vertex(x1, y1, z0)),
        quad(vertex(x1, y0, z0), vertex(x0, y0, z0), vertex(x0, y1, z0), vertex(x1, y1, z0)),
        quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
    )

    private fun model(
        state: BlockState,
        quads: List<GeometryQuad>,
        isFullCube: Boolean = false
    ) = ExpandedGeometryState(state, BlockProperty(blockID = state.blockID, isFullCube = isFullCube), quads)

    private fun quad(
        a: GeometryVertex,
        b: GeometryVertex,
        c: GeometryVertex,
        d: GeometryVertex,
        cullFace: UInt? = null
    ): GeometryQuad {
        val normal = (b - a).cross(c - a).normalized()
        return GeometryQuad(listOf(a, b, c, d), normal, cullFace)
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

    private fun decodeSignedUNorm(value: Byte) = value.toUByte().toDouble() / 126.0 - 1.0

    private fun paddedQuadTextureByteCount(quadCount: Int): Int {
        val bytes = quadCount * 8
        val rowBytes = 32768 * 4
        return (bytes + rowBytes - 1) / rowBytes * rowBytes
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
