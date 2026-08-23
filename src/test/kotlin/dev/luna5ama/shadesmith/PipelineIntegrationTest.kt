package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineIntegrationTest {
    @Test
    fun optimizedVariantSemanticsDriveDeterministicLifecycleOutputs() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        val properties = workspace.resolve("shadesmith.shaders.properties")
        copyFixture(input)

        val firstResult = Main.runShaderPipeline(input, output, artifacts, properties)
        val firstSnapshot = snapshot(output, properties, input.resolve("base/Textile.glsl"))
        val secondResult = Main.runShaderPipeline(input, output, artifacts, properties)
        val secondSnapshot = snapshot(output, properties, input.resolve("base/Textile.glsl"))

        assertEquals(firstSnapshot, secondSnapshot)
        assertEquals(firstResult.map { it.textureAccess }, secondResult.map { it.textureAccess })
        assertEquals(setOf("transient_a"), access(firstResult, "composite.csh").reads)
        assertEquals(emptySet(), access(firstResult, "composite1.csh").reads)
        assertEquals(setOf("transient_b"), access(firstResult, "composite1.csh").writes)
        assertEquals(setOf("transient_branch"), access(firstResult, "composite2.csh").reads)
        assertFalse("transient_a" in access(firstResult, "composite2.csh").reads)

        val emittedBranchShader = output.resolve("composite2.csh").readText()
        assertContains(emittedBranchShader, "#ifdef SETTING_BRANCH")
        assertContains(emittedBranchShader, "#define transient_branch_sample(x)")
        assertFalse("shadesmith_resource_" in emittedBranchShader)
        assertContains(properties.readText(), "image.uimg_rgba16f=usam_rgba16f RGBA RGBA16F HALF_FLOAT false true 1.0 1.0")
    }

    @Test
    fun compileFailureRetainsPreviousOutputAndReportsSourceStageAndTool() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        val properties = workspace.resolve("shadesmith.shaders.properties")
        copyFixture(input)
        output.createDirectories()
        val sentinel = output.resolve("previous-output.txt")
        sentinel.writeText("keep")
        input.resolve("composite1.csh").writeText(
            input.resolve("composite1.csh").readText().replace("values[0] =", "missingSymbol ="),
        )

        val exception = assertFailsWith<SpirvRoundTripException> {
            Main.runShaderPipeline(input, output, artifacts, properties)
        }

        assertContains(exception.message.orEmpty(), "composite1.csh")
        assertContains(exception.message.orEmpty(), "[comp]")
        assertContains(exception.message.orEmpty(), "glslang")
        assertTrue(sentinel.isRegularFile())
        assertEquals("keep", sentinel.readText())
        assertFalse(properties.exists())
    }

    private fun access(files: List<OptimizedShaderFile>, name: String): TextureAccess {
        return files.single { it.file.path.name == name }.textureAccess
    }

    private fun copyFixture(target: Path) {
        target.createDirectories()
        listOf("shadesmith.json", "common.glsl", "composite.csh", "composite1.csh", "composite2.csh")
            .forEach { name ->
                val source = requireNotNull(javaClass.getResource("/pipeline/$name")) {
                    "Missing pipeline fixture $name"
                }
                target.resolve(name).writeText(source.readText())
            }
    }

    private fun snapshot(output: Path, properties: Path, textile: Path): Map<String, List<Byte>> {
        val result = linkedMapOf<String, List<Byte>>()
        Files.walk(output).use { paths ->
            paths.filter { it.isRegularFile() }
                .sorted()
                .forEach { path -> result[path.relativeTo(output).toString()] = path.readBytes().toList() }
        }
        result["../shadesmith.shaders.properties"] = properties.readBytes().toList()
        result["../input/base/Textile.glsl"] = textile.readBytes().toList()
        return result
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith pipeline integration test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
