package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.IOContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal data class BlockModelGLSLOutput(
    val shader: String,
    val unsupportedDiagnostics: String,
    val counts: GeometryCounts
)

internal const val BLOCK_MODEL_ROTATION_BITS = 9
internal const val BLOCK_MODEL_ROTATION_MASK = 0x1FFu

internal data class BlockModelPalette(
    val modelIdByState: Map<BlockState, UInt>,
    val geometries: List<List<GeometryQuad>>,
    val unsupported: List<UnsupportedGeometry>
)

private data class CanonicalBlockModel(
    val rotation: UInt,
    val quads: List<GeometryQuad>
)

internal fun buildBlockModelPalette(models: BlockModelsResult): BlockModelPalette {
    val geometryIds = linkedMapOf<List<GeometryQuad>, UInt>()
    val modelIdByState = linkedMapOf<BlockState, UInt>()
    for (model in models.supported) {
        if (model.property.isFullCube) continue
        val canonical = canonicalBlockModel(model.quads)
        val modelId = geometryIds.getOrPut(canonical.quads) {
            (geometryIds.size + 1).toUInt()
        }
        require(modelId <= UInt.MAX_VALUE shr BLOCK_MODEL_ROTATION_BITS) {
            "Block model ID exceeds packed R32UI capacity"
        }
        val modelData = (modelId shl BLOCK_MODEL_ROTATION_BITS) or canonical.rotation
        val previous = modelIdByState.putIfAbsent(model.state, modelData)
        require(previous == null || previous == modelData) {
            "Block state has conflicting model geometry: " + model.state
        }
    }
    return BlockModelPalette(modelIdByState, geometryIds.keys.toList(), models.unsupported)
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
            rotateBlockModelVector(quad.normal, rotation)
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
        quads = palette.geometries.sumOf(List<GeometryQuad>::size)
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
        appendLine("    vec3 rayOrigin, vec3 rayDir, vec3 v0, vec3 v1, vec3 v2, vec3 v3, vec3 normal,")
        appendLine("    inout float hitT, inout vec3 hitNormal")
        appendLine(") {")
        appendLine("    float denominator = dot(normal, rayDir);")
        appendLine("    if (abs(denominator) <= 1e-6) return false;")
        appendLine("    float t = dot(v0 - rayOrigin, normal) / denominator;")
        appendLine("    if (!(t >= 0.0 && t <= hitT)) return false;")
        appendLine("    vec3 point = rayOrigin + rayDir * t;")
        appendLine("    const float edgeTolerance = 5e-5;")
        appendLine("    if (dot(cross(v1 - v0, point - v0), normal) < -edgeTolerance) return false;")
        appendLine("    if (dot(cross(v2 - v1, point - v1), normal) < -edgeTolerance) return false;")
        appendLine("    if (dot(cross(v3 - v2, point - v2), normal) < -edgeTolerance) return false;")
        appendLine("    if (dot(cross(v0 - v3, point - v3), normal) < -edgeTolerance) return false;")
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
        appendLine("    uint modelID = modelData >> " + BLOCK_MODEL_ROTATION_BITS + "u;")
        appendLine("    rayOrigin = _voxel_rotateBlockModelVector(rotation, rayOrigin - vec3(0.5)) + vec3(0.5);")
        appendLine("    rayDir = _voxel_rotateBlockModelVector(rotation, rayDir);")
        appendLine("    hitT = uintBitsToFloat(0x7F800000u);")
        appendLine("    hitNormal = vec3(0.0);")
        appendLine("    bool hit = false;")
        appendBlockModelDispatch(palette.geometries, 0, palette.geometries.size, "    ")
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
    return BlockModelGLSLOutput(shader, diagnostics, counts)
}

private fun StringBuilder.appendBlockModelDispatch(
    geometries: List<List<GeometryQuad>>,
    fromIndex: Int,
    toIndex: Int,
    indent: String
) {
    if (fromIndex >= toIndex) return
    if (toIndex - fromIndex == 2) {
        appendLine(indent + "if (modelID == " + (fromIndex + 1) + "u) {")
        appendBlockModelQuads(geometries[fromIndex], indent + "    ")
        appendLine(indent + "} else {")
        appendBlockModelQuads(geometries[fromIndex + 1], indent + "    ")
        appendLine(indent + "}")
        return
    }
    if (toIndex - fromIndex == 1) {
        appendLine(indent + "if (modelID == " + (fromIndex + 1) + "u) {")
        appendBlockModelQuads(geometries[fromIndex], indent + "    ")
        appendLine(indent + "}")
        return
    }

    val middle = (fromIndex + toIndex) / 2
    appendLine(indent + "if (modelID < " + (middle + 1) + "u) {")
    appendBlockModelDispatch(geometries, fromIndex, middle, indent + "    ")
    appendLine(indent + "} else {")
    appendBlockModelDispatch(geometries, middle, toIndex, indent + "    ")
    appendLine(indent + "}")
}

private fun StringBuilder.appendBlockModelQuads(quads: List<GeometryQuad>, indent: String) {
    for (quad in quads) {
        append(indent + "hit = hit || _voxel_intersectBlockModelQuad(rayOrigin, rayDir, ")
        append(quad.vertices.joinToString(", ") { it.glsl() })
        append(", " + quad.normal.glsl() + ", hitT, hitNormal);")
        appendLine()
    }
}

context(ioContext: IOContext)
internal fun writeBlockModelOutputs(generated: BlockModelGLSLOutput) {
    val relativePath = "techniques/voxel/BlockModels.glsl"
    val trackedPath = ioContext.inputPath.resolve(relativePath)
    trackedPath.parent.createDirectories()
    trackedPath.writeText(generated.shader)
    ioContext.writeOutput(ioContext.outputPath.resolve(relativePath), generated.shader)
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

private fun GeometryVertex.glsl() = "vec3(${x.glsl()}, ${y.glsl()}, ${z.glsl()})"

private fun Double.canonicalDecimal() = BigDecimal.valueOf(this).setScale(6, RoundingMode.HALF_UP)

private fun Double.glsl(): String {
    val value = canonicalDecimal().stripTrailingZeros().toPlainString()
    return if ('.' in value) value else "$value.0"
}
