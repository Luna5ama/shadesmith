package dev.luna5ama.shadesmith.blockcode

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockModelsTest {
    @Test
    fun `variants expand partial selectors and remove only proven irrelevant properties`() = withJar(
        mapOf(
            blockstate("partial") to """{"variants":{
              "facing=north,powered=false":{"model":"minecraft:block/north"},
              "powered=true,facing=north":{"model":"minecraft:block/north"},
              "facing=south,powered=false":{"model":"minecraft:block/south"},
              "powered=true,facing=south":{"model":"minecraft:block/south"}}}""",
            model("north") to elementModel(0, 0, 0, 8, 16, 16),
            model("south") to elementModel(8, 0, 0, 16, 16, 16)
        )
    ) { jar ->
        val properties = mapOf(
            BlockState("partial", mapOf("lit" to "false")) to BlockProperty(blockID = "partial"),
            BlockState("partial", mapOf("lit" to "true")) to BlockProperty(blockID = "partial", luminance = 7)
        )

        val result = BlockModels.load(jar, properties)

        assertEquals(4, result.supported.size)
        assertEquals(setOf("facing", "lit"), result.supported.flatMap { it.state.states.keys }.toSet())
        assertFalse(result.supported.any { "powered" in it.state.states })
        assertEquals(GeometryCounts(4, 2, 2), result.counts)
    }

    @Test
    fun `door stair slab and fence model selectors remain geometry relevant`() = withJar(
        buildMap {
            put(blockstate("door"), variants("open", "false" to "left", "true" to "right"))
            put(blockstate("stairs"), variants("facing", "north" to "left", "south" to "right"))
            put(blockstate("slab"), variants("type", "bottom" to "bottom", "top" to "top"))
            put(blockstate("fence"), """{"multipart":[
              {"apply":{"model":"minecraft:block/post"}},
              {"when":{"north":"true"},"apply":{"model":"minecraft:block/north_arm"}}
            ]}""")
            put(model("left"), elementModel(0, 0, 0, 4, 16, 16))
            put(model("right"), elementModel(12, 0, 0, 16, 16, 16))
            put(model("bottom"), elementModel(0, 0, 0, 16, 8, 16))
            put(model("top"), elementModel(0, 8, 0, 16, 16, 16))
            put(model("post"), elementModel(6, 0, 6, 10, 16, 10))
            put(model("north_arm"), elementModel(7, 6, 0, 9, 15, 6))
        }
    ) { jar ->
        val properties = listOf("door", "stairs", "slab", "fence")
            .associate { BlockState(it) to BlockProperty(blockID = it) }

        val states = BlockModels.load(jar, properties).supported.map { it.state }

        assertEquals(setOf("false", "true"), states.filter { it.blockID == "door" }.map { it.states["open"] }.toSet())
        assertEquals(setOf("north", "south"), states.filter { it.blockID == "stairs" }.map { it.states["facing"] }.toSet())
        assertEquals(setOf("bottom", "top"), states.filter { it.blockID == "slab" }.map { it.states["type"] }.toSet())
        assertEquals(setOf("false", "true"), states.filter { it.blockID == "fence" }.map { it.states["north"] }.toSet())
    }

    @Test
    fun `multipart evaluates nested conjunction disjunction alternatives and unconditional parts`() = withJar(
        mapOf(
            blockstate("multipart") to """{"multipart":[
              {"apply":{"model":"minecraft:block/base"}},
              {"when":{"AND":[{"OR":[{"north":"true"},{"east":"true"}]},{"axis":"x|z"}]},
               "apply":{"model":"minecraft:block/arm"}}
            ]}""",
            model("base") to elementModel(6, 6, 6, 10, 10, 10),
            model("arm") to elementModel(0, 7, 7, 16, 9, 9)
        )
    ) { jar ->
        val result = BlockModels.load(
            jar,
            mapOf(BlockState("multipart") to BlockProperty(blockID = "multipart"))
        )

        assertEquals(3, result.supported.size)
        assertEquals(setOf("east", "north"), result.supported.flatMap { it.state.states.keys }.toSet())
        assertEquals(setOf(1, 2), result.supported.map { it.quads.size }.toSet())
    }

    @Test
    fun `parent elements inherit replace and report cycle or missing parent`() = withJar(
        mapOf(
            blockstate("inherit") to singleVariant("child"),
            blockstate("replace") to singleVariant("replacement"),
            blockstate("cycle") to singleVariant("cycle_a"),
            blockstate("missing") to singleVariant("missing_child"),
            model("parent") to elementModel(0, 0, 0, 8, 16, 16),
            model("child") to """{"parent":"minecraft:block/parent"}""",
            model("replacement") to """{"parent":"minecraft:block/parent","elements":[${element(8, 0, 0, 16, 16, 16)}]}""",
            model("cycle_a") to """{"parent":"minecraft:block/cycle_b"}""",
            model("cycle_b") to """{"parent":"minecraft:block/cycle_a"}""",
            model("missing_child") to """{"parent":"minecraft:block/does_not_exist"}"""
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("inherit", "replace", "cycle", "missing"))

        assertEquals(0.0, result.supported.single { it.state.blockID == "inherit" }.quads.single().vertices.minOf { it.x })
        assertEquals(0.5, result.supported.single { it.state.blockID == "replace" }.quads.single().vertices.minOf { it.x })
        assertEquals(setOf("cycle", "missing"), result.unsupported.map { it.blockID }.toSet())
        assertTrue(result.unsupported.any { "cycle" in it.reason.lowercase() })
        assertTrue(result.unsupported.any { "missing" in it.reason.lowercase() })
    }

    @Test
    fun `element pivot rotation rescale and state rotations produce finite planar normalized quads`() = withJar(
        mapOf(
            blockstate("rotated") to """{"variants":{"":{"model":"minecraft:block/rotated","x":90,"y":90,"z":90}}}""",
            model("rotated") to """{"elements":[{
              "from":[4,4,4],"to":[8,12,12],
              "rotation":{"origin":[8,8,8],"axis":"y","angle":45,"rescale":true},
              "faces":{"west":{}}}]}"""
        )
    ) { jar ->
        val quad = BlockModels.load(jar, properties("rotated")).supported.single().quads.single()

        assertEquals(4, quad.vertices.size)
        assertTrue(quad.vertices.flatMap { listOf(it.x, it.y, it.z) }.all(Double::isFinite))
        assertTrue(listOf(quad.normal.x, quad.normal.y, quad.normal.z).all(Double::isFinite))
        assertEquals(1.0, quad.normal.length(), 1e-6)
        assertTrue(GeometryVertex(0.5, 0.75, 0.0) in quad.vertices, quad.vertices.toString())
        val plane = quad.vertices.map { (it - quad.vertices.first()).dot(quad.normal) }
        assertTrue(plane.all { kotlin.math.abs(it) <= 1e-6 })
    }

    @Test
    fun `blockstate x rotation negates the Mojang JSON angle`() = withJar(
        mapOf(
            blockstate("state_x") to """{"variants":{"":{"model":"minecraft:block/state_x","x":90}}}""",
            model("state_x") to """{"elements":[${elementWithFaces(0, 0, 0, 16, 16, 16, "north")}] }"""
        )
    ) { jar ->
        val quad = BlockModels.load(jar, properties("state_x")).supported.single().quads.single()

        assertTrue(GeometryVertex(1.0, 0.0, 1.0) in quad.vertices, quad.vertices.toString())
        assertEquals(GeometryVertex(0.0, -1.0, 0.0), quad.normal)
    }

    @Test
    fun `element Euler rotation uses positive x y z order from 26_2 hanging sign template`() = withJar(
        mapOf(
            blockstate("euler") to singleVariant("euler"),
            model("euler") to """{"elements":[{
              "from":[4,0,4],"to":[12,8,12],
              "rotation":{"x":180,"y":-67.5,"z":-180,"origin":[8,0,8]},
              "faces":{"north":{}}}]}"""
        )
    ) { jar ->
        val quad = BlockModels.load(jar, properties("euler")).supported.single().quads.single()

        assertTrue(GeometryVertex(0.635299, 0.0, 0.826641) in quad.vertices, quad.vertices.toString())
        assertEquals(0.923879, quad.normal.x, 1e-6)
        assertEquals(0.0, quad.normal.y, 1e-6)
        assertEquals(0.382684, quad.normal.z, 1e-6)
        val plane = quad.vertices.map { (it - quad.vertices.first()).dot(quad.normal) }
        assertTrue(plane.all { kotlin.math.abs(it) <= 1e-6 })
    }

    @Test
    fun `weighted alternatives collapse identical geometry and reject differing geometry`() = withJar(
        mapOf(
            blockstate("same") to """{"variants":{" ":[{"model":"minecraft:block/a","weight":1},{"model":"minecraft:block/a_alias","weight":4}]}}""",
            blockstate("different") to """{"variants":{" ":[{"model":"minecraft:block/a"},{"model":"minecraft:block/b"}]}}""",
            model("a") to elementModel(0, 0, 0, 8, 16, 16),
            model("a_alias") to """{"parent":"minecraft:block/a"}""",
            model("b") to elementModel(8, 0, 0, 16, 16, 16)
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("same", "different"))

        assertEquals(listOf("same"), result.supported.map { it.state.blockID })
        assertEquals(listOf("different"), result.unsupported.map { it.blockID })
        assertTrue(result.unsupported.single().reason.contains("weighted", ignoreCase = true))
    }

    @Test
    fun `model geometry extending outside block bounds is unsupported`() = withJar(
        mapOf(
            blockstate("protruding") to singleVariant("protruding"),
            blockstate("weighted_protruding") to """{"variants":{"":[
              {"model":"minecraft:block/protruding"},{"model":"minecraft:block/protruding_alias"}]}}""",
            model("protruding") to """{"elements":[${elementWithFaces(-0.000032, 0.0, 0.0, 16.0, 16.0, 16.0, "west")}] }""",
            model("protruding_alias") to """{"parent":"minecraft:block/protruding"}"""
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("protruding", "weighted_protruding"))

        assertEquals(
            listOf(
                UnsupportedGeometry("protruding", emptyMap(), "Model geometry extends outside block bounds"),
                UnsupportedGeometry("weighted_protruding", emptyMap(), "Model geometry extends outside block bounds")
            ),
            result.unsupported
        )
        assertTrue(result.supported.isEmpty())
    }

    @Test
    fun `block bounds include exact edges and epsilon noise`() = withJar(
        mapOf(
            blockstate("boundary") to singleVariant("boundary"),
            blockstate("noise") to singleVariant("noise"),
            model("boundary") to """{"elements":[${elementWithFaces(0.0, 0.0, 0.0, 16.0, 16.0, 16.0, "west", "east")}] }""",
            model("noise") to """{"elements":[${elementWithFaces(-0.000016, 0.0, 0.0, 16.000016, 16.0, 16.0, "west", "east")}] }"""
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("boundary", "noise"))

        assertEquals(setOf("boundary", "noise"), result.supported.map { it.state.blockID }.toSet())
        assertTrue(result.unsupported.isEmpty())
    }

    @Test
    fun `multipart rejects the entire state when one part extends outside block bounds`() = withJar(
        mapOf(
            blockstate("multipart_bounds") to """{"multipart":[
              {"apply":{"model":"minecraft:block/base"}},
              {"apply":{"model":"minecraft:block/protruding"}}]}""",
            model("base") to elementModel(4, 4, 4, 12, 12, 12),
            model("protruding") to """{"elements":[${elementWithFaces(0.0, 0.0, 0.0, 16.000032, 16.0, 16.0, "east")}] }"""
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("multipart_bounds"))

        assertEquals(
            listOf(UnsupportedGeometry("multipart_bounds", emptyMap(), "Model geometry extends outside block bounds")),
            result.unsupported
        )
        assertTrue(result.supported.isEmpty())
    }

    @Test
    fun `builtin empty fluid missing blockstate and malformed resources are explicit`() = withJar(
        mapOf(
            blockstate("builtin") to singleVariant("builtin_model"),
            blockstate("empty") to singleVariant("empty_model"),
            blockstate("malformed") to singleVariant("bad"),
            model("builtin_model") to """{"parent":"builtin/entity"}""",
            model("empty_model") to """{"elements":[]}""",
            model("bad") to "{"
        )
    ) { jar ->
        val result = BlockModels.load(jar, properties("builtin", "empty", "water", "missing_state"))
        assertEquals(setOf("builtin", "empty", "water", "missing_state"), result.unsupported.map { it.blockID }.toSet())
        assertTrue(result.unsupported.any { it.blockID == "water" && "fluid" in it.reason.lowercase() })

        assertFailsWith<IllegalArgumentException> { BlockModels.load(jar, properties("malformed")) }
    }

    @Test
    fun `output ordering is deterministic and conflicting merged selectors fail`() = withJar(
        mapOf(
            blockstate("ordered") to """{"variants":{
              "kind=b":{"model":"minecraft:block/b"},
              "kind=a":{"model":"minecraft:block/a"}}}""",
            model("a") to """{"elements":[${elementWithFaces(0, 0, 0, 16, 16, 16, "south", "north")}] }""",
            model("b") to elementModel(8, 0, 0, 16, 16, 16)
        )
    ) { jar ->
        val input = linkedMapOf(BlockState("ordered") to BlockProperty(blockID = "ordered"))
        val first = BlockModels.load(jar, input)
        val second = BlockModels.load(jar, input)
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first.supported.map { it.state.states.getValue("kind") })
        assertEquals(first.supported.first().quads.sorted(), first.supported.first().quads)

        val conflicts = linkedMapOf(
            BlockState("ordered") to BlockProperty(blockID = "ordered", luminance = 0),
            BlockState("ordered", mapOf("kind" to "a")) to BlockProperty(blockID = "ordered", luminance = 1)
        )
        assertFailsWith<IllegalArgumentException> { BlockModels.load(jar, conflicts) }
    }

    @Test
    fun `overlapping variants and unknown multipart non boolean domains fail`() = withJar(
        mapOf(
            blockstate("overlap") to """{"variants":{"":{"model":"minecraft:block/a"},"kind=a":{"model":"minecraft:block/b"}}}""",
            blockstate("unknown") to """{"multipart":[{"when":{"shape":"north"},"apply":{"model":"minecraft:block/a"}}]}""",
            model("a") to elementModel(0, 0, 0, 8, 16, 16),
            model("b") to elementModel(8, 0, 0, 16, 16, 16)
        )
    ) { jar ->
        assertFailsWith<IllegalArgumentException> { BlockModels.load(jar, properties("overlap")) }
        assertFailsWith<IllegalArgumentException> { BlockModels.load(jar, properties("unknown")) }
    }

    private fun variants(property: String, vararg values: Pair<String, String>) =
        """{"variants":{${values.joinToString(",") { (value, model) -> "\"$property=$value\":{\"model\":\"minecraft:block/$model\"}" }}}}"""

    private fun singleVariant(model: String) = """{"variants":{"":{"model":"minecraft:block/$model"}}}"""

    private fun properties(vararg blocks: String) = blocks.associate {
        BlockState(it) to BlockProperty(blockID = it)
    }

    private fun blockstate(name: String) = "assets/minecraft/blockstates/$name.json"
    private fun model(name: String) = "assets/minecraft/models/block/$name.json"

    private fun elementModel(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int) =
        """{"elements":[${element(x0, y0, z0, x1, y1, z1)}]}"""

    private fun element(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int) =
        elementWithFaces(x0, y0, z0, x1, y1, z1, "west")

    private fun elementWithFaces(
        x0: Number, y0: Number, z0: Number, x1: Number, y1: Number, z1: Number, vararg faces: String
    ) = """{"from":[$x0,$y0,$z0],"to":[$x1,$y1,$z1],"faces":{${faces.joinToString(",") { "\"$it\":{}" }}}}"""

    private fun withJar(entries: Map<String, String>, block: (Path) -> Unit) {
        val path = Files.createTempFile("shadesmith-block-models-", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                for ((name, contents) in entries.toSortedMap()) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private fun GeometryVertex.length() = kotlin.math.sqrt(dot(this))
    private operator fun GeometryVertex.minus(other: GeometryVertex) =
        GeometryVertex(x - other.x, y - other.y, z - other.z)
    private fun GeometryVertex.dot(other: GeometryVertex) = x * other.x + y * other.y + z * other.z
}
