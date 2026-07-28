package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

internal data class BlockModelGLSLOutput(
    val shader: String,
    val unsupportedDiagnostics: String,
    val counts: GeometryCounts,
    val quadData: List<Byte> = emptyList()
)

internal const val BLOCK_MODEL_ROTATION_BITS = 9
internal const val BLOCK_MODEL_ROTATION_MASK = 0x1FFu

internal data class BlockModelPalette(
    val modelIdByState: Map<BlockState, List<UInt>>,
    val geometries: List<List<GeometryQuad>>,
    val quads: List<GeometryQuad>,
    val unsupported: List<UnsupportedGeometry>
)

private data class CanonicalBlockModel(
    val rotation: UInt,
    val quads: List<GeometryQuad>
)

internal fun buildBlockModelPalette(models: BlockModelsResult): BlockModelPalette {
    val geometryMetadata = linkedMapOf<List<GeometryQuad>, UInt>()
    val quads = mutableListOf<GeometryQuad>()
    val modelIdByState = linkedMapOf<BlockState, List<UInt>>()
    for (model in models.supported) {
        val rows = if (model.property.isFullCube || model.quads.isEmpty()) List(64) { 0u } else List(64) { mask ->
            val filtered = model.quads
                .filter { quad -> quad.cullFace == null || mask.toUInt() and quad.cullFace != 0u }
                .map { it.copy(cullFace = null) }
            if (filtered.isEmpty()) return@List 0u
            val canonical = canonicalBlockModel(filtered)
            val metadata = geometryMetadata.getOrPut(canonical.quads) {
                val offset = quads.size
                require(offset <= 32767) { "Block model quad offset exceeds packed UInt32 capacity" }
                require(canonical.quads.size <= 255) { "Block model quad count exceeds packed UInt32 capacity" }
                quads += canonical.quads
                (canonical.quads.size.toUInt() shl 24) or (offset.toUInt() shl BLOCK_MODEL_ROTATION_BITS)
            }
            metadata or canonical.rotation
        }
        val previous = modelIdByState.putIfAbsent(model.state, rows)
        require(previous == null || previous == rows) {
            "Block state has conflicting model geometry: " + model.state
        }
    }
    return BlockModelPalette(modelIdByState, geometryMetadata.keys.toList(), quads, models.unsupported)
}

private fun canonicalBlockModel(quads: List<GeometryQuad>): CanonicalBlockModel =
    blockModelRotations.asSequence()
        .map { rotation -> CanonicalBlockModel(rotation, rotateBlockModelGeometry(quads, rotation)) }
        .minWithOrNull { left, right ->
            compareBlockModelGeometry(left.quads, right.quads)
                .takeIf { it != 0 }
                ?: left.rotation.compareTo(right.rotation)
        }!!

internal fun rotateBlockModelGeometry(quads: List<GeometryQuad>, rotation: UInt): List<GeometryQuad> =
    quads.map { quad ->
        val vertices = quad.vertices.map { vertex ->
            rotateBlockModelVector(
                GeometryVertex(vertex.x - 0.5, vertex.y - 0.5, vertex.z - 0.5),
                rotation
            ).let { GeometryVertex(it.x + 0.5, it.y + 0.5, it.z + 0.5).quantized() }
        }
        val first = vertices.indices.minBy { vertices[it] }
        GeometryQuad(
            List(vertices.size) { vertices[(first + it) % vertices.size] },
            rotateBlockModelVector(quad.normal, rotation),
            quad.cullFace?.let { cullFace ->
                cullFaceBit(rotateBlockModelVector(cullFaceDirection(cullFace), rotation))
            }
        )
    }.distinct().sorted()

private fun rotateBlockModelVector(vertex: GeometryVertex, rotation: UInt) = GeometryVertex(
    rotateBlockModelComponent(vertex, rotation),
    rotateBlockModelComponent(vertex, rotation shr 3),
    rotateBlockModelComponent(vertex, rotation shr 6)
).quantized()

private fun rotateBlockModelComponent(vertex: GeometryVertex, transform: UInt): Double {
    val value = when (transform and 3u) {
        0u -> vertex.x
        1u -> vertex.y
        else -> vertex.z
    }
    return if (transform and 4u == 0u) value else -value
}

private fun compareBlockModelGeometry(left: List<GeometryQuad>, right: List<GeometryQuad>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

private val blockModelRotations: List<UInt> = buildList {
    for (x in 1..3) {
        for (y in 1..3) {
            if (y == x) continue
            for (z in 1..3) {
                if (z == x || z == y) continue
                for (signs in 0..7) {
                    val rotation = listOf(
                        if (signs and 1 == 0) x else -x,
                        if (signs and 2 == 0) y else -y,
                        if (signs and 4 == 0) z else -z
                    )
                    if (rotationDeterminant(rotation) == 1) add(encodeBlockModelRotation(rotation))
                }
            }
        }
    }
}.sorted()

private fun rotationDeterminant(rotation: List<Int>): Int {
    var inversions = 0
    for (left in rotation.indices) {
        for (right in left + 1 until rotation.size) {
            if (kotlin.math.abs(rotation[left]) > kotlin.math.abs(rotation[right])) inversions++
        }
    }
    var determinant = if (inversions and 1 == 0) 1 else -1
    for (axis in rotation) if (axis < 0) determinant = -determinant
    return determinant
}

private fun encodeBlockModelRotation(rotation: List<Int>): UInt {
    var result = 0u
    for ((outputAxis, axis) in rotation.withIndex()) {
        val sourceAxis = (kotlin.math.abs(axis) - 1).toUInt()
        val transform = sourceAxis or if (axis < 0) 4u else 0u
        result = result or (transform shl (outputAxis * 3))
    }
    return result
}

internal fun generateBlockModelGLSL(
    palette: BlockModelPalette,
    assets: MinecraftAssets
): BlockModelGLSLOutput {
    val counts = GeometryCounts(
        states = palette.modelIdByState.size,
        geometries = palette.geometries.size,
        quads = palette.quads.size
    )
    val shader = buildString {
        appendLine("// Generated by Shadesmith from Minecraft ${assets.versionId}")
        appendLine("// version_metadata_sha1: ${assets.versionMetadata.sha1}")
        appendLine("// client_sha1: ${assets.clientSha1}")
        appendLine("#ifndef INCLUDE_techniques_voxel_BlockModels_glsl")
        appendLine("#define INCLUDE_techniques_voxel_BlockModels_glsl a")
        appendLine()
        appendLine("float _voxel_rotateBlockModelComponent(uint transform, vec3 value) {")
        appendLine("    float result = value[int(transform & 3u)];")
        appendLine("    return (transform & 4u) == 0u ? result : -result;")
        appendLine("}")
        appendLine()
        appendLine("vec3 _voxel_rotateBlockModelVector(uint rotation, vec3 value) {")
        appendLine("    return vec3(")
        appendLine("        _voxel_rotateBlockModelComponent(rotation, value),")
        appendLine("        _voxel_rotateBlockModelComponent(rotation >> 3u, value),")
        appendLine("        _voxel_rotateBlockModelComponent(rotation >> 6u, value)")
        appendLine("    );")
        appendLine("}")
        appendLine()
        appendLine("vec3 _voxel_unrotateBlockModelVector(uint rotation, vec3 value) {")
        appendLine("    vec3 result = vec3(0.0);")
        appendLine("    result[int(rotation & 3u)] = (rotation & 4u) == 0u ? value.x : -value.x;")
        appendLine("    rotation >>= 3u;")
        appendLine("    result[int(rotation & 3u)] = (rotation & 4u) == 0u ? value.y : -value.y;")
        appendLine("    rotation >>= 3u;")
        appendLine("    result[int(rotation & 3u)] = (rotation & 4u) == 0u ? value.z : -value.z;")
        appendLine("    return result;")
        appendLine("}")
        appendLine()
        appendLine("bool _voxel_intersectBlockModelQuad(")
        appendLine("    vec3 rayOrigin, vec3 rayDir, uint quadIndex, inout float hitT, inout vec3 hitNormal")
        appendLine(") {")
        appendLine("    int texelIndex = int(quadIndex * 2u);")
        appendLine("    vec4 originNormalX = texelFetch(usam_blockModelQuads, texelIndex, 0);")
        appendLine("    vec4 normalYZHalfSize = texelFetch(usam_blockModelQuads, texelIndex + 1, 0);")
        appendLine("    vec3 origin = originNormalX.xyz;")
        appendLine("    vec3 normal = normalize(vec3(originNormalX.w, normalYZHalfSize.xy) * (255.0 / 126.0) - 1.0);")
        appendLine("    vec2 halfSize = normalYZHalfSize.zw + 1e-6;")
        appendLine("    float denominator = dot(normal, rayDir);")
        appendLine("    if (abs(denominator) <= 1e-8) return false;")
        appendLine("    float t = dot(normal, origin - rayOrigin) / denominator;")
        appendLine("    if (!(t >= 0.0 && t <= hitT)) return false;")
        appendLine("    vec3 axis = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);")
        appendLine("    vec3 u = normalize(cross(axis, normal));")
        appendLine("    vec3 v = cross(normal, u);")
        appendLine("    vec3 offset = rayOrigin + rayDir * t - origin;")
        appendLine("    vec2 projected = abs(vec2(dot(offset, u), dot(offset, v)));")
        appendLine("    if (any(greaterThan(projected, halfSize))) return false;")
        appendLine("    hitT = t;")
        appendLine("    hitNormal = denominator < 0.0 ? normal : -normal;")
        appendLine("    return true;")
        appendLine("}")
        appendLine()
        appendLine("bool voxel_intersectBlockModel(")
        appendLine("    uint modelData, vec3 rayOrigin, vec3 rayDir,")
        appendLine("    out float hitT, out vec3 hitNormal")
        appendLine(") {")
        appendLine("    uint rotation = modelData & " + BLOCK_MODEL_ROTATION_MASK + "u;")
        appendLine("    uint quadOffset = (modelData >> " + BLOCK_MODEL_ROTATION_BITS + "u) & 0x7FFFu;")
        appendLine("    uint quadCount = modelData >> 24u;")
        appendLine("    rayOrigin = _voxel_rotateBlockModelVector(rotation, rayOrigin - vec3(0.5)) + vec3(0.5);")
        appendLine("    rayDir = _voxel_rotateBlockModelVector(rotation, rayDir);")
        appendLine("    hitT = uintBitsToFloat(0x7F800000u);")
        appendLine("    hitNormal = vec3(0.0);")
        appendLine("    bool hit = false;")
        appendLine("    for (uint i = 0u; i < quadCount; ++i) {")
        appendLine("        hit = _voxel_intersectBlockModelQuad(rayOrigin, rayDir, quadOffset + i, hitT, hitNormal) || hit;")
        appendLine("    }")
        appendLine("    if (hit) hitNormal = _voxel_unrotateBlockModelVector(rotation, hitNormal);")
        appendLine("    return hit;")
        appendLine("}")
        appendLine()
        appendLine("#endif")
    }

    val diagnostics = buildJsonObject {
        put("minecraftRelease", assets.versionId)
        put("versionMetadataSha1", assets.versionMetadata.sha1)
        put("clientSha1", assets.clientSha1)
        putJsonObject("counts") {
            put("states", counts.states)
            put("geometries", counts.geometries)
            put("quads", counts.quads)
        }
        put("unsupported", buildJsonArray {
            for (unsupported in palette.unsupported) {
                add(buildJsonObject {
                    put("blockId", unsupported.blockID)
                    put("selector", buildJsonObject {
                        unsupported.selector.toSortedMap().forEach { (key, value) -> put(key, value) }
                    })
                    put("reason", unsupported.reason)
                })
            }
        })
    }.toString() + "\n"
    return BlockModelGLSLOutput(shader, diagnostics, counts, encodeBlockModelQuads(palette.quads))
}

private fun encodeBlockModelQuads(quads: List<GeometryQuad>): List<Byte> = buildList(quads.size * 8) {
    for (quad in quads) {
        val origin = GeometryVertex(
            quad.vertices.sumOf(GeometryVertex::x) / quad.vertices.size,
            quad.vertices.sumOf(GeometryVertex::y) / quad.vertices.size,
            quad.vertices.sumOf(GeometryVertex::z) / quad.vertices.size
        )
        val originBytes = listOf(origin.x.uNormByte(), origin.y.uNormByte(), origin.z.uNormByte())
        val normalBytes = listOf(
            quad.normal.x.signedUNormByte(),
            quad.normal.y.signedUNormByte(),
            quad.normal.z.signedUNormByte()
        )
        val decodedOrigin = GeometryVertex(originBytes[0].uNorm(), originBytes[1].uNorm(), originBytes[2].uNorm())
        val decodedNormal = GeometryVertex(
            normalBytes[0].signedUNorm(),
            normalBytes[1].signedUNorm(),
            normalBytes[2].signedUNorm()
        ).normalized()
        val axis = if (kotlin.math.abs(decodedNormal.z) < 0.999) {
            GeometryVertex(0.0, 0.0, 1.0)
        } else {
            GeometryVertex(0.0, 1.0, 0.0)
        }
        val u = axis.cross(decodedNormal).normalized()
        val v = decodedNormal.cross(u)
        var halfSizeU = 0.0
        var halfSizeV = 0.0
        for (vertex in quad.vertices) {
            val relative = GeometryVertex(
                vertex.x - decodedOrigin.x,
                vertex.y - decodedOrigin.y,
                vertex.z - decodedOrigin.z
            )
            halfSizeU = maxOf(halfSizeU, kotlin.math.abs(relative.dot(u)))
            halfSizeV = maxOf(halfSizeV, kotlin.math.abs(relative.dot(v)))
        }
        addAll(originBytes)
        add(normalBytes[0])
        add(normalBytes[1])
        add(normalBytes[2])
        add(halfSizeU.uNormByte(RoundingMode.CEILING))
        add(halfSizeV.uNormByte(RoundingMode.CEILING))
    }
}

private fun Byte.uNorm() = toUByte().toDouble() / 255.0

private fun Byte.signedUNorm() = toUByte().toDouble() / 126.0 - 1.0

private fun Double.signedUNormByte(): Byte {
    require(this in -1.0..1.0) { "Block model quad normal component is outside [-1, 1]: $this" }
    return BigDecimal.valueOf(this).add(BigDecimal.ONE).multiply(BigDecimal.valueOf(126L))
        .setScale(0, RoundingMode.HALF_UP).toInt().toByte()
}

private fun Double.uNormByte(roundingMode: RoundingMode = RoundingMode.HALF_UP): Byte {
    require(this in 0.0..1.0) { "Block model quad value is outside [0, 1]: $this" }
    return BigDecimal.valueOf(this).multiply(BigDecimal.valueOf(255L))
        .setScale(0, roundingMode).toInt().toByte()
}

context(ioContext: IOContext)
internal fun writeBlockModelOutputs(generated: BlockModelGLSLOutput) {
    val relativePath = "techniques/voxel/BlockModels.glsl"
    val trackedPath = ioContext.inputPath.resolve(relativePath)
    trackedPath.parent.createDirectories()
    trackedPath.writeText(generated.shader)
    ioContext.writeOutput(ioContext.outputPath.resolve(relativePath), generated.shader)
    val quadRelativePath = "textures/block_model_quads.bin"
    val trackedQuadPath = ioContext.inputPath.resolve(quadRelativePath)
    trackedQuadPath.parent.createDirectories()
    trackedQuadPath.writeBytes(generated.quadData.toByteArray())
    val outputQuadPath = ioContext.outputPath.resolve(quadRelativePath)
    outputQuadPath.parent.createDirectories()
    outputQuadPath.writeBytes(generated.quadData.toByteArray())
    ioContext.writeOutput(
        ioContext.outputPath.resolve("techniques/voxel/BlockModels.unsupported.json"),
        generated.unsupportedDiagnostics
    )
}

private fun GeometryVertex.quantized() = GeometryVertex(
    x.canonicalDecimal().toDouble(),
    y.canonicalDecimal().toDouble(),
    z.canonicalDecimal().toDouble()
)

private fun Double.canonicalDecimal() = BigDecimal.valueOf(this).setScale(6, RoundingMode.HALF_UP)

private fun GeometryVertex.cross(other: GeometryVertex) = GeometryVertex(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x
)

private fun GeometryVertex.dot(other: GeometryVertex) = x * other.x + y * other.y + z * other.z

private fun GeometryVertex.normalized(): GeometryVertex {
    val length = kotlin.math.sqrt(dot(this))
    return GeometryVertex(x / length, y / length, z / length)
}
