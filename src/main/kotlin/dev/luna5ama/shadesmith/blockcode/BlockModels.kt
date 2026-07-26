package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

internal data class GeometryVertex(val x: Double, val y: Double, val z: Double) : Comparable<GeometryVertex> {
    override fun compareTo(other: GeometryVertex): Int =
        compareValuesBy(this, other, GeometryVertex::x, GeometryVertex::y, GeometryVertex::z)
}

internal data class GeometryQuad(
    val vertices: List<GeometryVertex>,
    val normal: GeometryVertex
) : Comparable<GeometryQuad> {
    override fun compareTo(other: GeometryQuad): Int {
        for (index in vertices.indices) {
            val comparison = vertices[index].compareTo(other.vertices[index])
            if (comparison != 0) return comparison
        }
        return normal.compareTo(other.normal)
    }
}

internal data class ExpandedGeometryState(
    val state: BlockState,
    val property: BlockProperty,
    val quads: List<GeometryQuad>
)

internal data class UnsupportedGeometry(
    val blockID: String,
    val selector: Map<String, String>,
    val reason: String
)

internal data class GeometryCounts(
    val states: Int,
    val geometries: Int,
    val quads: Int
)

internal data class BlockModelsResult(
    val supported: List<ExpandedGeometryState>,
    val unsupported: List<UnsupportedGeometry>
) {
    val counts: GeometryCounts
        get() {
            val geometries = supported.map(ExpandedGeometryState::quads).distinct()
            return GeometryCounts(supported.size, geometries.size, geometries.sumOf(List<GeometryQuad>::size))
        }
}

internal object BlockModels {
    fun load(clientJar: Path, properties: Map<BlockState, BlockProperty>): BlockModelsResult {
        ZipFile(clientJar.toFile()).use { zip ->
            val resources = BlockModelResources(zip)
            val supported = mutableListOf<ExpandedGeometryState>()
            val unsupported = mutableListOf<UnsupportedGeometry>()

            for ((blockID, blockProperties) in properties.entries.groupBy { it.key.blockID }.toSortedMap()) {
                val simpleID = blockID.substringAfter(':')
                if (simpleID in fluidBlocks) {
                    unsupported += UnsupportedGeometry(blockID, emptyMap(), "Fluid geometry is unsupported")
                    continue
                }
                val blockStateJson = resources.blockState(blockID)
                if (blockStateJson == null) {
                    unsupported += UnsupportedGeometry(blockID, emptyMap(), "Missing blockstate resource")
                    continue
                }

                val expanded = expandBlockState(blockID, blockStateJson, resources)
                for ((selector, resolution) in expanded.unsupported) {
                    unsupported += UnsupportedGeometry(blockID, selector, resolution)
                }
                supported += mergeProperties(blockID, expanded.supported, blockProperties)
            }

            return BlockModelsResult(
                supported = supported.sortedWith(compareBy({ it.state.blockID }, { selectorKey(it.state.states) })),
                unsupported = unsupported.sortedWith(compareBy({ it.blockID }, { selectorKey(it.selector) }, { it.reason }))
            )
        }
    }

    private fun expandBlockState(
        blockID: String,
        json: JsonObject,
        resources: BlockModelResources
    ): ExpandedBlockState {
        val variants = json["variants"]
        val multipart = json["multipart"]
        require((variants == null) != (multipart == null)) {
            "Blockstate $blockID must contain exactly one of variants or multipart"
        }
        return if (variants != null) {
            expandVariants(blockID, variants.jsonObject, resources)
        } else {
            expandMultipart(blockID, multipart!!.jsonArray, resources)
        }
    }

    private fun expandVariants(
        blockID: String,
        variants: JsonObject,
        resources: BlockModelResources
    ): ExpandedBlockState {
        require(variants.isNotEmpty()) { "Blockstate $blockID has no variants" }
        val parsed = variants.entries.map { (rawSelector, apply) ->
            parseSelector(rawSelector) to parseApplications(apply)
        }
        val domains = sortedMapOf<String, MutableSet<String>>()
        parsed.forEach { (selector, _) ->
            selector.forEach { (property, value) -> domains.getOrPut(property, ::sortedSetOf).add(value) }
        }
        val resolutions = linkedMapOf<Map<String, String>, GeometryResolution>()
        for (state in cartesian(domains)) {
            val matching = parsed.filter { (selector, _) -> selector.all { state[it.key] == it.value } }
            require(matching.size == 1) {
                "Blockstate $blockID has ${if (matching.isEmpty()) "no" else "overlapping"} variant for $state"
            }
            resolutions[state] = resolveApplications(matching.single().second, resources)
        }
        return minimize(domains, resolutions)
    }

    private fun expandMultipart(
        blockID: String,
        multipart: JsonArray,
        resources: BlockModelResources
    ): ExpandedBlockState {
        require(multipart.isNotEmpty()) { "Blockstate $blockID has empty multipart definitions" }
        val parts = parseMultipartParts(multipart)
        val domains = sortedMapOf<String, MutableSet<String>>()
        parts.mapNotNull(MultipartPart::condition).forEach { it.collectDomains(domains) }
        for ((property, values) in domains) {
            if (values.all { it == "true" || it == "false" }) {
                values += listOf("false", "true")
            } else {
                require(values.size > 1) {
                    "Multipart property '$property' has an unknown non-Boolean domain: $values"
                }
            }
        }

        val resolutions = linkedMapOf<Map<String, String>, GeometryResolution>()
        for (state in cartesian(domains)) {
            val selected = parts.filter { it.condition?.matches(state) != false }
            resolutions[state] = try {
                val quads = selected.flatMap { resolveApplications(it.applications, resources).quadsOrThrow() }
                if (quads.isEmpty()) GeometryResolution.Unsupported("Empty or special multipart geometry")
                else GeometryResolution.Supported(normalizeQuads(quads))
            } catch (exception: UnsupportedModel) {
                GeometryResolution.Unsupported(exception.message ?: "Unsupported model")
            }
        }
        return minimize(domains, resolutions)
    }

    private fun resolveApplications(
        applications: List<ModelApplication>,
        resources: BlockModelResources
    ): GeometryResolution {
        return try {
            val alternatives = applications.map { application ->
                val elements = resources.modelElements(application.model)
                if (elements.isEmpty()) throw UnsupportedModel("Empty or special model: ${application.model}")
                normalizeQuads(elements.flatMap { elementQuads(it, application) })
            }
            val distinct = alternatives.distinct()
            if (distinct.size != 1) {
                GeometryResolution.Unsupported("Weighted alternatives have different geometry")
            } else {
                GeometryResolution.Supported(distinct.single())
            }
        } catch (exception: UnsupportedModel) {
            GeometryResolution.Unsupported(exception.message ?: "Unsupported model")
        }
    }

    private fun mergeProperties(
        blockID: String,
        geometries: Map<Map<String, String>, List<GeometryQuad>>,
        properties: List<Map.Entry<BlockState, BlockProperty>>
    ): List<ExpandedGeometryState> {
        val merged = linkedMapOf<BlockState, ExpandedGeometryState>()
        for ((geometrySelector, quads) in geometries) {
            for ((propertyState, property) in properties) {
                val selector = mergeSelectors(geometrySelector, propertyState.states) ?: continue
                val state = BlockState(blockID, selector)
                val value = ExpandedGeometryState(state, property, quads)
                val previous = merged.putIfAbsent(state, value)
                require(previous == null || previous == value) {
                    "Conflicting merged block state: $state"
                }
            }
        }
        return merged.values.toList()
    }

    private fun minimize(
        domains: Map<String, Set<String>>,
        resolutions: Map<Map<String, String>, GeometryResolution>
    ): ExpandedBlockState {
        val unsupported = resolutions.mapNotNull { (selector, resolution) ->
            (resolution as? GeometryResolution.Unsupported)?.let { selector to it.reason }
        }
        val supported = linkedMapOf<Map<String, String>, List<GeometryQuad>>()
        val byGeometry = resolutions.entries
            .mapNotNull { (selector, resolution) ->
                (resolution as? GeometryResolution.Supported)?.let { selector to it.quads }
            }
            .groupBy({ it.second }, { it.first })

        for ((geometry, selectors) in byGeometry) {
            var cubes = selectors.toSet()
            var changed: Boolean
            do {
                changed = false
                for ((property, domain) in domains) {
                    val groups = cubes.filter { property in it }.groupBy { it - property }
                    for ((reduced, group) in groups) {
                        if (group.mapTo(sortedSetOf()) { it.getValue(property) } == domain.toSortedSet()) {
                            cubes = cubes - group.toSet() + reduced.toSortedMap()
                            changed = true
                        }
                    }
                }
            } while (changed)
            for (selector in cubes.sortedBy(::selectorKey)) {
                val previous = supported.put(selector.toSortedMap(), geometry)
                require(previous == null || previous == geometry) { "Overlapping minimized geometry selector: $selector" }
            }
        }
        return ExpandedBlockState(supported.toSortedMap(compareBy(::selectorKey)), unsupported)
    }

    private fun elementQuads(element: ModelElement, application: ModelApplication): List<GeometryQuad> {
        val corners = faceVertices(element.from, element.to)
        return element.faces.mapNotNull { face ->
            val vertices = corners[face] ?: throw IllegalArgumentException("Unknown model face: $face")
            val transformed = vertices.map { vertex ->
                val elementVertex = element.rotation?.let { rotateElement(vertex, it) } ?: vertex
                rotateState(elementVertex, application)
            }
            normalizeQuad(transformed)
        }
    }

    private fun faceVertices(from: GeometryVertex, to: GeometryVertex): Map<String, List<GeometryVertex>> {
        val (x0, y0, z0) = from
        val (x1, y1, z1) = to
        return mapOf(
            "west" to listOf(v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0)),
            "east" to listOf(v(x1, y0, z1), v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1)),
            "down" to listOf(v(x0, y0, z1), v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1)),
            "up" to listOf(v(x0, y1, z0), v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0)),
            "north" to listOf(v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0)),
            "south" to listOf(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1))
        )
    }

    private fun rotateElement(vertex: GeometryVertex, rotation: ElementRotation): GeometryVertex {
        var offset = vertex - rotation.origin
        when (rotation) {
            is ElementRotation.AxisAngle -> {
                if (rotation.rescale) {
                    val scale = 1.0 / cos(Math.toRadians(rotation.angle))
                    offset = when (rotation.axis) {
                        'x' -> v(offset.x, offset.y * scale, offset.z * scale)
                        'y' -> v(offset.x * scale, offset.y, offset.z * scale)
                        else -> v(offset.x * scale, offset.y * scale, offset.z)
                    }
                }
                offset = rotate(offset, rotation.axis, rotation.angle)
            }
            is ElementRotation.Euler -> {
                offset = rotate(offset, 'x', rotation.x)
                offset = rotate(offset, 'y', rotation.y)
                offset = rotate(offset, 'z', rotation.z)
            }
        }
        return offset + rotation.origin
    }

    private fun rotateState(vertex: GeometryVertex, application: ModelApplication): GeometryVertex {
        val center = v(0.5, 0.5, 0.5)
        var offset = vertex - center
        offset = rotate(offset, 'x', -application.x)
        offset = rotate(offset, 'y', -application.y)
        offset = rotate(offset, 'z', -application.z)
        return offset + center
    }

    private fun rotate(vertex: GeometryVertex, axis: Char, degrees: Double): GeometryVertex {
        if (degrees == 0.0) return vertex
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        return when (axis) {
            'x' -> v(vertex.x, vertex.y * c - vertex.z * s, vertex.y * s + vertex.z * c)
            'y' -> v(vertex.x * c + vertex.z * s, vertex.y, -vertex.x * s + vertex.z * c)
            else -> v(vertex.x * c - vertex.y * s, vertex.x * s + vertex.y * c, vertex.z)
        }
    }

    private fun normalizeQuads(quads: List<GeometryQuad>): List<GeometryQuad> {
        val normalized = quads.distinct().sorted()
        if (normalized.any { quad ->
                quad.vertices.any { vertex ->
                    vertex.x < -epsilon || vertex.x > 1.0 + epsilon ||
                        vertex.y < -epsilon || vertex.y > 1.0 + epsilon ||
                        vertex.z < -epsilon || vertex.z > 1.0 + epsilon
                }
            }) {
            throw UnsupportedModel("Model geometry extends outside block bounds")
        }
        return normalized
    }

    private fun normalizeQuad(vertices: List<GeometryVertex>): GeometryQuad? {
        require(vertices.size == 4)
        val quantized = vertices.map(::quantize)
        require(quantized.all(GeometryVertex::isFinite)) { "Non-finite model geometry" }
        val normal = (quantized[1] - quantized[0]).cross(quantized[2] - quantized[0])
        val length = normal.length()
        if (length <= epsilon) return null
        val unitNormal = quantize(normal / length)
        require(abs((quantized[3] - quantized[0]).dot(unitNormal)) <= epsilon * 2.0) {
            "Non-planar model geometry: $quantized"
        }
        val first = quantized.indices.minBy { quantized[it] }
        val ordered = List(4) { quantized[(first + it) % 4] }
        return GeometryQuad(ordered, unitNormal)
    }

    private fun cartesian(domains: Map<String, Set<String>>): List<Map<String, String>> {
        var result = listOf<Map<String, String>>(emptyMap())
        for ((property, values) in domains) {
            result = result.flatMap { state -> values.sorted().map { value -> (state + (property to value)).toSortedMap() } }
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

    private fun quantize(vertex: GeometryVertex) = v(quantize(vertex.x), quantize(vertex.y), quantize(vertex.z))
    private fun quantize(value: Double): Double {
        val result = round(value / epsilon) * epsilon
        return if (result == -0.0) 0.0 else result
    }

    private fun v(x: Double, y: Double, z: Double) = GeometryVertex(x, y, z)

    private sealed interface GeometryResolution {
        data class Supported(val quads: List<GeometryQuad>) : GeometryResolution
        data class Unsupported(val reason: String) : GeometryResolution

        fun quadsOrThrow() = when (this) {
            is Supported -> quads
            is Unsupported -> throw UnsupportedModel(reason)
        }
    }

    private data class ExpandedBlockState(
        val supported: Map<Map<String, String>, List<GeometryQuad>>,
        val unsupported: List<Pair<Map<String, String>, String>>
    )

    private const val epsilon = 1e-6
    private val fluidBlocks = setOf("water", "flowing_water", "lava", "flowing_lava")
}

private operator fun GeometryVertex.plus(other: GeometryVertex) =
    GeometryVertex(x + other.x, y + other.y, z + other.z)

private operator fun GeometryVertex.minus(other: GeometryVertex) =
    GeometryVertex(x - other.x, y - other.y, z - other.z)

private operator fun GeometryVertex.div(scale: Double) = GeometryVertex(x / scale, y / scale, z / scale)

private fun GeometryVertex.cross(other: GeometryVertex) = GeometryVertex(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x
)

private fun GeometryVertex.dot(other: GeometryVertex) = x * other.x + y * other.y + z * other.z
private fun GeometryVertex.length() = sqrt(dot(this))
private fun GeometryVertex.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
