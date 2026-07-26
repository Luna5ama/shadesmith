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
    val aabbData: List<Byte> = emptyList()
)

internal const val BLOCK_MODEL_ROTATION_BITS = 9
internal const val BLOCK_MODEL_ROTATION_MASK = 0x1FFu
private const val BLOCK_MODEL_AABB_SIZE_SCALE = 1.4142135623730951

internal data class BlockModelPalette(
    val modelIdByState: Map<BlockState, UInt>,
    val geometries: List<List<GeometryQuad>>,
    val aabbs: List<GeometryAABB>,
    val unsupported: List<UnsupportedGeometry>
)

private data class CanonicalBlockModel(
    val rotation: UInt,
    val quads: List<GeometryQuad>
)

internal fun buildBlockModelPalette(models: BlockModelsResult): BlockModelPalette {
    val geometryMetadata = linkedMapOf<List<GeometryQuad>, UInt>()
    val aabbs = mutableListOf<GeometryAABB>()
    val modelIdByState = linkedMapOf<BlockState, UInt>()
    for (model in models.supported) {
        if (model.property.isFullCube) continue
        val canonical = canonicalBlockModel(model.quads)
        val metadata = geometryMetadata.getOrPut(canonical.quads) {
            val offset = aabbs.size
            val canonicalAABBs = rotateBlockModelAABBs(model.aabbs, canonical.rotation)
            require(offset <= 32767) { "Block model AABB offset exceeds packed UInt32 capacity" }
            require(canonicalAABBs.size <= 255) { "Block model AABB count exceeds packed UInt32 capacity" }
            aabbs += canonicalAABBs
            (canonicalAABBs.size.toUInt() shl 24) or (offset.toUInt() shl BLOCK_MODEL_ROTATION_BITS)
        }
        val modelData = metadata or canonical.rotation
        val previous = modelIdByState.putIfAbsent(model.state, modelData)
        require(previous == null || previous == modelData) {
            "Block state has conflicting model geometry: " + model.state
        }
    }
    return BlockModelPalette(modelIdByState, geometryMetadata.keys.toList(), aabbs, models.unsupported)
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

private fun rotateBlockModelAABBs(aabbs: List<GeometryAABB>, rotation: UInt): List<GeometryAABB> {
    val quaternion = blockModelRotationQuaternion(rotation)
    return aabbs.map { aabb ->
        val offset = rotateBlockModelVector(
            GeometryVertex(aabb.origin.x - 0.5, aabb.origin.y - 0.5, aabb.origin.z - 0.5),
            rotation
        )
        GeometryAABB(
            (quaternion * aabb.quaternion).normalized(),
            GeometryVertex(offset.x + 0.5, offset.y + 0.5, offset.z + 0.5),
            aabb.size
        )
    }
}

private fun blockModelRotationQuaternion(rotation: UInt): GeometryQuaternion {
    val x = rotateBlockModelVector(GeometryVertex(1.0, 0.0, 0.0), rotation)
    val y = rotateBlockModelVector(GeometryVertex(0.0, 1.0, 0.0), rotation)
    val z = rotateBlockModelVector(GeometryVertex(0.0, 0.0, 1.0), rotation)
    val trace = x.x + y.y + z.z
    val quaternion = when {
        trace > 0.0 -> {
            val scale = kotlin.math.sqrt(trace + 1.0) * 2.0
            GeometryQuaternion((y.z - z.y) / scale, (z.x - x.z) / scale, (x.y - y.x) / scale, scale / 4.0)
        }
        x.x > y.y && x.x > z.z -> {
            val scale = kotlin.math.sqrt(1.0 + x.x - y.y - z.z) * 2.0
            GeometryQuaternion(scale / 4.0, (x.y + y.x) / scale, (z.x + x.z) / scale, (y.z - z.y) / scale)
        }
        y.y > z.z -> {
            val scale = kotlin.math.sqrt(1.0 + y.y - x.x - z.z) * 2.0
            GeometryQuaternion((x.y + y.x) / scale, scale / 4.0, (y.z + z.y) / scale, (z.x - x.z) / scale)
        }
        else -> {
            val scale = kotlin.math.sqrt(1.0 + z.z - x.x - y.y) * 2.0
            GeometryQuaternion((z.x + x.z) / scale, (y.z + z.y) / scale, scale / 4.0, (x.y - y.x) / scale)
        }
    }
    return quaternion.normalized()
}

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
        appendLine("vec3 _voxel_rotateBlockModelQuaternion(vec4 quaternion, vec3 value) {")
        appendLine("    return value + 2.0 * cross(quaternion.xyz, cross(quaternion.xyz, value) + quaternion.w * value);")
        appendLine("}")
        appendLine()
        appendLine("bool _voxel_intersectBlockModelAABB(")
        appendLine("    vec3 rayOrigin, vec3 rayDir, uint aabbIndex, inout float hitT, inout vec3 hitNormal")
        appendLine(") {")
        appendLine("    int texelIndex = int(aabbIndex * 3u);")
        appendLine("    vec4 quaternion = normalize(texelFetch(usam_blockModelAABBs, texelIndex, 0) * 2.0 - 1.0);")
        appendLine("    vec3 origin = texelFetch(usam_blockModelAABBs, texelIndex + 1, 0).xyz;")
        appendLine("    vec3 halfSize = texelFetch(usam_blockModelAABBs, texelIndex + 2, 0).xyz * " +
            BLOCK_MODEL_AABB_SIZE_SCALE + " * 0.5;")
        appendLine("    vec4 inverseQuaternion = vec4(-quaternion.xyz, quaternion.w);")
        appendLine("    vec3 localOrigin = _voxel_rotateBlockModelQuaternion(inverseQuaternion, rayOrigin - origin);")
        appendLine("    vec3 localDir = _voxel_rotateBlockModelQuaternion(inverseQuaternion, rayDir);")
        appendLine("    float entryT = -uintBitsToFloat(0x7F800000u);")
        appendLine("    float exitT = uintBitsToFloat(0x7F800000u);")
        appendLine("    vec3 entryNormal = vec3(0.0);")
        appendLine("    vec3 exitNormal = vec3(0.0);")
        appendLine("    for (int axis = 0; axis < 3; ++axis) {")
        appendLine("        if (abs(localDir[axis]) <= 1e-8) {")
        appendLine("            if (abs(localOrigin[axis]) > halfSize[axis] + 1e-6) return false;")
        appendLine("            continue;")
        appendLine("        }")
        appendLine("        float nearT = (-halfSize[axis] - localOrigin[axis]) / localDir[axis];")
        appendLine("        float farT = (halfSize[axis] - localOrigin[axis]) / localDir[axis];")
        appendLine("        float nearSign = -1.0;")
        appendLine("        float farSign = 1.0;")
        appendLine("        if (nearT > farT) {")
        appendLine("            float swapT = nearT;")
        appendLine("            nearT = farT;")
        appendLine("            farT = swapT;")
        appendLine("            nearSign = 1.0;")
        appendLine("            farSign = -1.0;")
        appendLine("        }")
        appendLine("        if (nearT > entryT) {")
        appendLine("            entryT = nearT;")
        appendLine("            entryNormal = vec3(0.0);")
        appendLine("            entryNormal[axis] = nearSign;")
        appendLine("        }")
        appendLine("        if (farT < exitT) {")
        appendLine("            exitT = farT;")
        appendLine("            exitNormal = vec3(0.0);")
        appendLine("            exitNormal[axis] = farSign;")
        appendLine("        }")
        appendLine("        if (entryT > exitT) return false;")
        appendLine("    }")
        appendLine("    bool startsInside = entryT < 0.0;")
        appendLine("    float t = startsInside ? exitT : entryT;")
        appendLine("    if (!(t >= 0.0 && t <= hitT)) return false;")
        appendLine("    vec3 localNormal = startsInside ? -exitNormal : entryNormal;")
        appendLine("    hitT = t;")
        appendLine("    hitNormal = _voxel_rotateBlockModelQuaternion(quaternion, localNormal);")
        appendLine("    return true;")
        appendLine("}")
        appendLine()
        appendLine("bool voxel_intersectBlockModel(")
        appendLine("    uint modelData, vec3 rayOrigin, vec3 rayDir,")
        appendLine("    out float hitT, out vec3 hitNormal")
        appendLine(") {")
        appendLine("    uint rotation = modelData & " + BLOCK_MODEL_ROTATION_MASK + "u;")
        appendLine("    uint aabbOffset = (modelData >> " + BLOCK_MODEL_ROTATION_BITS + "u) & 0x7FFFu;")
        appendLine("    uint aabbCount = modelData >> 24u;")
        appendLine("    rayOrigin = _voxel_rotateBlockModelVector(rotation, rayOrigin - vec3(0.5)) + vec3(0.5);")
        appendLine("    rayDir = _voxel_rotateBlockModelVector(rotation, rayDir);")
        appendLine("    hitT = uintBitsToFloat(0x7F800000u);")
        appendLine("    hitNormal = vec3(0.0);")
        appendLine("    bool hit = false;")
        appendLine("    for (uint i = 0u; i < aabbCount; ++i) {")
        appendLine("        hit = _voxel_intersectBlockModelAABB(rayOrigin, rayDir, aabbOffset + i, hitT, hitNormal) || hit;")
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
    return BlockModelGLSLOutput(shader, diagnostics, counts, encodeBlockModelAABBs(palette.aabbs))
}

private fun encodeBlockModelAABBs(aabbs: List<GeometryAABB>): List<Byte> = buildList(aabbs.size * 12) {
    for (aabb in aabbs) {
        add(aabb.quaternion.x.signedUNormByte())
        add(aabb.quaternion.y.signedUNormByte())
        add(aabb.quaternion.z.signedUNormByte())
        add(aabb.quaternion.w.signedUNormByte())
        add(aabb.origin.x.uNormByte())
        add(aabb.origin.y.uNormByte())
        add(aabb.origin.z.uNormByte())
        add(0)
        add(aabb.size.x.sizeUNormByte())
        add(aabb.size.y.sizeUNormByte())
        add(aabb.size.z.sizeUNormByte())
        add(0)
    }
}

private fun Double.signedUNormByte(): Byte {
    require(this in -1.0..1.0) { "Block model AABB quaternion component is outside [-1, 1]: $this" }
    return ((this + 1.0) * 0.5).uNormByte()
}

private fun Double.sizeUNormByte(): Byte {
    require(this in 0.0..BLOCK_MODEL_AABB_SIZE_SCALE) {
        "Block model AABB size is outside [0, $BLOCK_MODEL_AABB_SIZE_SCALE]: $this"
    }
    return (this / BLOCK_MODEL_AABB_SIZE_SCALE).uNormByte()
}

private fun Double.uNormByte(): Byte {
    require(this in 0.0..1.0) { "Block model AABB value is outside [0, 1]: $this" }
    return BigDecimal.valueOf(this).multiply(BigDecimal.valueOf(255L))
        .setScale(0, RoundingMode.HALF_UP).toInt().toByte()
}

context(ioContext: IOContext)
internal fun writeBlockModelOutputs(generated: BlockModelGLSLOutput) {
    val relativePath = "techniques/voxel/BlockModels.glsl"
    val trackedPath = ioContext.inputPath.resolve(relativePath)
    trackedPath.parent.createDirectories()
    trackedPath.writeText(generated.shader)
    ioContext.writeOutput(ioContext.outputPath.resolve(relativePath), generated.shader)
    val aabbRelativePath = "textures/block_model_aabbs.bin"
    val trackedAABBPath = ioContext.inputPath.resolve(aabbRelativePath)
    trackedAABBPath.parent.createDirectories()
    trackedAABBPath.writeBytes(generated.aabbData.toByteArray())
    val outputAABBPath = ioContext.outputPath.resolve(aabbRelativePath)
    outputAABBPath.parent.createDirectories()
    outputAABBPath.writeBytes(generated.aabbData.toByteArray())
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
