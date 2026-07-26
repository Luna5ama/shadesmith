package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.ZipFile

internal data class ModelApplication(
    val model: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
)

internal data class ModelElement(
    val from: GeometryVertex,
    val to: GeometryVertex,
    val rotation: ElementRotation?,
    val faces: List<String>
)

internal sealed interface ElementRotation {
    val origin: GeometryVertex

    data class AxisAngle(
        override val origin: GeometryVertex,
        val axis: Char,
        val angle: Double,
        val rescale: Boolean
    ) : ElementRotation

    data class Euler(
        override val origin: GeometryVertex,
        val x: Double,
        val y: Double,
        val z: Double
    ) : ElementRotation
}

internal sealed interface MultipartCondition {
    fun matches(state: Map<String, String>): Boolean
    fun collectDomains(domains: MutableMap<String, MutableSet<String>>)
}

private data class PropertyCondition(val property: String, val values: Set<String>) : MultipartCondition {
    override fun matches(state: Map<String, String>) = state[property] in values

    override fun collectDomains(domains: MutableMap<String, MutableSet<String>>) {
        domains.getOrPut(property, ::linkedSetOf).addAll(values)
    }
}

private data class AllCondition(val children: List<MultipartCondition>) : MultipartCondition {
    override fun matches(state: Map<String, String>) = children.all { it.matches(state) }
    override fun collectDomains(domains: MutableMap<String, MutableSet<String>>) =
        children.forEach { it.collectDomains(domains) }
}

private data class AnyCondition(val children: List<MultipartCondition>) : MultipartCondition {
    override fun matches(state: Map<String, String>) = children.any { it.matches(state) }
    override fun collectDomains(domains: MutableMap<String, MutableSet<String>>) =
        children.forEach { it.collectDomains(domains) }
}

internal data class MultipartPart(
    val condition: MultipartCondition?,
    val applications: List<ModelApplication>
)

internal class BlockModelResources(private val zip: ZipFile) {
    private val jsonCache = mutableMapOf<String, JsonObject>()
    private val modelCache = mutableMapOf<String, List<ModelElement>>()

    fun blockState(blockID: String): JsonObject? {
        val name = blockID.substringAfter(':')
        return json("assets/minecraft/blockstates/$name.json", required = false)
    }

    fun modelElements(reference: String): List<ModelElement> = resolveModel(reference, linkedSetOf())

    private fun resolveModel(reference: String, stack: MutableSet<String>): List<ModelElement> {
        val normalized = normalizeModelReference(reference)
        modelCache[normalized]?.let { return it }
        if (!stack.add(normalized)) throw UnsupportedModel("Cyclic model parent chain: ${(stack + normalized).joinToString(" -> ")}")

        val path = "assets/minecraft/models/$normalized.json"
        val objectJson = json(path, required = false) ?: throw UnsupportedModel("Missing model resource: $path")
        val elements = if ("elements" in objectJson) {
            objectJson.getValue("elements").jsonArray.map(::parseElement)
        } else {
            val parent = objectJson["parent"]?.jsonPrimitive?.contentOrNull
                ?: throw UnsupportedModel("Empty or special model: $path")
            resolveModel(parent, stack)
        }
        stack.remove(normalized)
        modelCache[normalized] = elements
        return elements
    }

    private fun normalizeModelReference(reference: String): String {
        val namespace = reference.substringBefore(':', "minecraft")
        val path = reference.substringAfter(':', reference)
        if (namespace != "minecraft") throw UnsupportedModel("Unsupported model namespace: $reference")
        if (path.startsWith("builtin/")) throw UnsupportedModel("Unsupported builtin model: $reference")
        if (!path.startsWith("block/")) throw UnsupportedModel("Unsupported non-block model: $reference")
        return path
    }

    private fun json(path: String, required: Boolean): JsonObject? {
        jsonCache[path]?.let { return it }
        val entry = zip.getEntry(path) ?: return if (required) error("Missing JSON resource: $path") else null
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val parsed = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("Malformed JSON resource: $path", exception)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed JSON resource: $path", exception)
        }
        jsonCache[path] = parsed
        return parsed
    }
}

internal fun parseApplications(element: JsonElement): List<ModelApplication> {
    val alternatives = when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.map { it.jsonObject }
        else -> throw IllegalArgumentException("Model application must be an object or array: $element")
    }
    if (alternatives.isEmpty()) throw UnsupportedModel("Empty weighted model application")
    return alternatives.map { objectJson ->
        ModelApplication(
            model = objectJson["model"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Model application is missing model: $objectJson"),
            x = objectJson.number("x", 0.0),
            y = objectJson.number("y", 0.0),
            z = objectJson.number("z", 0.0)
        )
    }
}

internal fun parseSelector(raw: String): Map<String, String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyMap()
    val result = sortedMapOf<String, String>()
    for (assignment in trimmed.split(',')) {
        val parts = assignment.split('=', limit = 2)
        require(parts.size == 2 && parts[0].trim().isNotEmpty() && parts[1].trim().isNotEmpty()) {
            "Invalid blockstate selector: $raw"
        }
        require(result.put(parts[0].trim(), parts[1].trim()) == null) { "Duplicate selector property: $raw" }
    }
    return result
}

internal fun parseMultipartParts(array: JsonArray): List<MultipartPart> = array.map { element ->
    val objectJson = element.jsonObject
    MultipartPart(
        condition = objectJson["when"]?.let(::parseCondition),
        applications = parseApplications(objectJson["apply"]
            ?: throw IllegalArgumentException("Multipart part is missing apply: $objectJson"))
    )
}

private fun parseCondition(element: JsonElement): MultipartCondition {
    return when (element) {
        is JsonArray -> AllCondition(element.map(::parseCondition))
        is JsonObject -> {
            val children = element.map { (key, value) ->
                when (key) {
                    "AND" -> AllCondition(conditionChildren(value))
                    "OR" -> AnyCondition(conditionChildren(value))
                    else -> {
                        val values = value.jsonPrimitive.content.split('|').map(String::trim).filter(String::isNotEmpty).toSet()
                        require(values.isNotEmpty()) { "Empty multipart property condition: $key" }
                        PropertyCondition(key, values)
                    }
                }
            }
            AllCondition(children)
        }
        else -> throw IllegalArgumentException("Multipart condition must be an object or array: $element")
    }
}

private fun conditionChildren(element: JsonElement) = when (element) {
    is JsonArray -> element.map(::parseCondition)
    is JsonObject -> listOf(parseCondition(element))
    else -> throw IllegalArgumentException("AND/OR condition must contain objects: $element")
}

private fun parseElement(element: JsonElement): ModelElement {
    val objectJson = element.jsonObject
    val faces = objectJson["faces"]?.jsonObject?.keys?.sorted()
        ?: throw IllegalArgumentException("Model element is missing explicit faces: $objectJson")
    val rotation = objectJson["rotation"]?.jsonObject?.let(::parseElementRotation)
    return ModelElement(objectJson.vertex("from"), objectJson.vertex("to"), rotation, faces)
}

private fun parseElementRotation(json: JsonObject): ElementRotation {
    val origin = json.vertex("origin")
    val axis = json["axis"]?.jsonPrimitive?.contentOrNull?.singleOrNull()
    return if (axis != null) {
        require(axis in "xyz") { "Invalid element rotation axis: $axis" }
        ElementRotation.AxisAngle(
            origin = origin,
            axis = axis,
            angle = json.getValue("angle").jsonPrimitive.double,
            rescale = json["rescale"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } else {
        ElementRotation.Euler(
            origin = origin,
            x = json.getValue("x").jsonPrimitive.double,
            y = json.getValue("y").jsonPrimitive.double,
            z = json.getValue("z").jsonPrimitive.double
        )
    }
}

private fun JsonObject.vertex(name: String): GeometryVertex {
    val values = getValue(name).jsonArray
    require(values.size == 3) { "$name must contain three coordinates" }
    return GeometryVertex(values[0].jsonPrimitive.double / 16.0, values[1].jsonPrimitive.double / 16.0, values[2].jsonPrimitive.double / 16.0)
}

private fun JsonObject.number(name: String, default: Double) = this[name]?.jsonPrimitive?.double ?: default

internal class UnsupportedModel(message: String) : RuntimeException(message)
