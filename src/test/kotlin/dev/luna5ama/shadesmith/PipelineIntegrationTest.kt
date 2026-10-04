package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
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
    fun shadersPropertiesProgramPredicateParticipatesInRootCacheIdentity() = withWorkspace { workspace ->
        val input = workspace.resolve("input").createDirectories()
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        input.resolve("composite.csh").writeText(
            """
                #version 460 compatibility
                layout(local_size_x = 1) in;
                void main() {}
            """.trimIndent(),
        )
        val shadersProperties = input.resolve("shaders.properties")
        shadersProperties.writeText("program.composite.enabled=true\n")

        fun run(): OptimizedShaderFile {
            val ioContext = IOContext(input, output)
            val pipeline = ShaderPipeline(
                artifacts,
                cacheIdentityProvider = { "1".repeat(64) },
            )
            val result = context(ioContext) {
                pipeline.optimize(listOf(requireNotNull(ioContext.readInputRoot("composite.csh"))))
            }.single()
            pipeline.publishCache()
            return result
        }

        assertEquals(0, run().cacheHits)
        assertEquals(1, run().cacheHits)
        shadersProperties.writeText("program.composite.enabled=true\n# unrelated property still belongs to the cache contract\n")
        assertEquals(0, run().cacheHits)
        shadersProperties.writeText("#if SETTING_UNUSED\nprogram.composite.enabled=true\n#endif\n")
        assertEquals(0, run().cacheHits)
    }

    @Test
    fun compilerCopySemanticsDriveDeterministicLifecycleOutputs() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        val properties = workspace.resolve("shadesmith.shaders.properties")
        copyFixture(input)

        val firstResult = Main.runShaderPipeline(input, output, artifacts, properties)
        val firstSnapshot = snapshot(output, properties, input.resolve("base/Textile.glsl"))
        val firstPerformance = performance(artifacts)
        val firstOutputs = artifacts.resolve("outputs.tsv").readText()
        val firstBoundaries = artifacts.resolve("boundaries.tsv").readText()
        val secondResult = Main.runShaderPipeline(input, output, artifacts, properties)
        val secondSnapshot = snapshot(output, properties, input.resolve("base/Textile.glsl"))
        val performance = performance(artifacts)

        assertEquals(firstSnapshot, secondSnapshot)
        assertEquals(firstOutputs, artifacts.resolve("outputs.tsv").readText())
        assertEquals(firstBoundaries, artifacts.resolve("boundaries.tsv").readText())
        assertEquals(firstResult.map { it.textureAccess }, secondResult.map { it.textureAccess })
        assertEquals(firstResult.map { it.file.path.name }.sorted(), firstResult.map { it.file.path.name })
        assertEquals(setOf("transient_a"), access(firstResult, "composite.csh").reads)
        assertEquals(emptySet(), access(firstResult, "composite1.csh").reads)
        assertEquals(setOf("transient_b"), access(firstResult, "composite1.csh").writes)
        val branchShader = firstResult.single { it.file.path.name == "composite2.csh" }
        assertEquals(ShaderProcessingMode.SPIRV_ROUND_TRIP, branchShader.processingMode, branchShader.fallbackReason)
        assertEquals(setOf("transient_branch"), branchShader.textureAccess.reads)
        assertFalse("transient_a" in access(firstResult, "composite2.csh").reads)

        val emittedBranchShader = output.resolve("composite2.csh").readText()
        assertContains(emittedBranchShader, "#ifdef SETTING_BRANCH")
        assertContains(emittedBranchShader, "#define SM_SETTING_BRANCH true")
        assertContains(emittedBranchShader, "if (SM_SETTING_BRANCH)")
        assertFalse("#define transient_branch_sample(x)" in emittedBranchShader)
        assertFalse("SPIRV_CROSS_CONSTANT_ID_" in emittedBranchShader)
        assertFalse("constant_id" in emittedBranchShader)
        assertFalse("shadesmith_resource_" in emittedBranchShader)
        assertContains(emittedBranchShader, "//#define SETTING_BRANCH")
        assertFalse("SETTING_BRANCH" in output.resolve("composite.csh").readText())
        assertFalse("SETTING_BRANCH" in output.resolve("composite1.csh").readText())
        val finalShader = output.resolve("final.fsh").readText()
        assertContains(finalShader, "//#define SETTING_BRANCH")
        val exactFormatRegistry = """
            /*
            const int colortex0Format = RGBA16F; // Pack-global Iris registry entry
            const int shadowcolor0Format = R16F; // Pack-global Iris registry entry
            */
        """.trimIndent() + "\n"
        assertContains(finalShader, exactFormatRegistry)
        assertEquals(1, Regex.escape(exactFormatRegistry).toRegex().findAll(finalShader).count())
        assertFalse("colortex0Format" in emittedBranchShader)
        assertFalse("shadowcolor0Format" in emittedBranchShader)
        assertContains(properties.readText(), "image.uimg_rgba16f=usam_rgba16f RGBA RGBA16F HALF_FLOAT false true 1.0 1.0")
        assertEquals("4", performance.getValue("validated_modules"))
        assertEquals("4", firstPerformance.getValue("cache_misses"))
        assertEquals("4", firstPerformance.getValue("cache_publications"))
        assertTrue(firstPerformance.getValue("external_processes").toInt() > 0)
        assertEquals("4", performance.getValue("cache_hits"))
        assertEquals("0", performance.getValue("cache_misses"))
        assertEquals("0", performance.getValue("cache_publications"))
        assertEquals("0", performance.getValue("materialized_compiler_modules"))
        assertEquals("0", performance.getValue("clang_processes"))
        assertEquals("0", performance.getValue("glslang_processes"))
        assertEquals("0", performance.getValue("spirv_opt_processes"))
        assertEquals("0", performance.getValue("spirv_cross_processes"))
        assertEquals("0", performance.getValue("external_processes"))
        val hostFragment = firstResult.single { it.file.path.name == "voxy_hook.glsl" }
        assertEquals(ShaderProcessingMode.PRESERVED_HOST_INTEGRATION, hostFragment.processingMode)
        assertEquals(hostFragment.file.code, output.resolve("voxy_hook.glsl").readText())
        assertContains(artifacts.resolve("boundaries.tsv").readText(), "voxy_hook.glsl\tfrag")
        val outputs = artifacts.resolve("outputs.tsv").readText()
        assertContains(outputs.lineSequence().first(), "optimized_entities\trestored_entities\trestored_bytes\tactivation_predicate")
        assertContains(outputs, "composite2.csh\tcomp\tSPIRV_ROUND_TRIP")
        assertContains(outputs, "SETTING_BRANCH")
        assertContains(outputs, "transient_branch")

        input.resolve("composite1.csh").writeText(input.resolve("composite1.csh").readText() + "\n// cache invalidation\n")
        Main.runShaderPipeline(input, output, artifacts, properties)
        val invalidated = performance(artifacts)
        assertEquals("3", invalidated.getValue("cache_hits"))
        assertEquals("1", invalidated.getValue("cache_misses"))
        assertEquals("1", invalidated.getValue("cache_publications"))
        assertTrue(invalidated.getValue("external_processes").toInt() > 0)
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
        assertEquals(0, cacheEntries(artifacts))

        copyFixture(input)
        Main.runShaderPipeline(input, output, artifacts, properties)
        val performance = performance(artifacts)
        assertEquals("4", performance.getValue("validated_modules"))
        assertTrue(performance.getValue("external_processes").toInt() > 0)
        assertEquals(4, cacheEntries(artifacts))
    }

    @Test
    fun invalidStructuralSignatureFailsClosedToExactSource() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        input.createDirectories()
        val source = """
            #version 460 compatibility
            #define SETTING_FORMAT 0 //[0 1]
            #if SETTING_FORMAT == 0
            layout(r32ui) uniform uimage2D target;
            #else
            layout(rgba16f) uniform image2D target;
            #endif
            layout(local_size_x = 1) in;
            void main() { uint value = imageAtomicAdd(target, ivec2(0), 1u); }
        """.trimIndent()
        input.resolve("composite.csh").writeText(source)
        input.resolve("final.fsh").writeText(
            """
                #version 460 compatibility
                out vec4 color;
                void main() { color = vec4(1.0); }
            """.trimIndent(),
        )
        val ioContext = IOContext(input, output)

        val result = context(ioContext) {
            ShaderPipeline(
                artifacts,
                capabilityProvider = {
                    OpenGlSpirvCapabilities("test", true, artifacts.resolve("capabilities"))
                },
                cacheIdentityProvider = { null },
            ).optimize(
                listOf(
                    requireNotNull(ioContext.readInputRoot("composite.csh")),
                    requireNotNull(ioContext.readInputRoot("final.fsh")),
                ),
            )
        }.single { it.file.path.name == "composite.csh" }

        assertEquals(ShaderProcessingMode.PRESERVED_STRUCTURAL, result.processingMode)
        assertEquals(source, result.file.code)
        assertContains(result.fallbackReason.orEmpty(), "structural module round-trip failed closed")
        assertContains(result.fallbackReason.orEmpty(), "OpenGL SPIR-V compilation")
        assertContains(artifacts.resolve("boundaries.tsv").readText(), "composite.csh")
    }

    @Test
    fun macroExpandedLoopStaysInsideRestoredFunctionBody() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        input.createDirectories()
        val source = """
            #version 460 compatibility
            #define SETTING_FORMAT 0 //[0 1]
            #ifndef PRINT_UTIL_GLSL
            #define PRINT_UTIL_GLSL
            #define printString(string) { \
                uint[] characters = uint[] string; \
                for (int i = 0; i < characters.length(); ++i) printChar(characters[i]); \
            }
            const uint _t = 1u;
            void printChar(uint character) {}
            void printValue() { printString((_t)); }
            #endif
            #if SETTING_FORMAT == 0
            layout(r32ui) uniform uimage2D target;
            #else
            layout(rgba16f) uniform image2D target;
            #endif
            layout(local_size_x = 1) in;
            uniform int limit;
            void main() {
                for (int index = 0; index < limit; ++index) {
                    printValue();
                    #if SETTING_FORMAT == 0
                    imageStore(target, ivec2(index), uvec4(1u));
                    #else
                    imageStore(target, ivec2(index), vec4(1.0));
                    #endif
                }
            }
        """.trimIndent()
        input.resolve("composite.csh").writeText(source)
        input.resolve("final.fsh").writeText(
            """
                #version 460 compatibility
                out vec4 color;
                void main() { color = vec4(1.0); }
            """.trimIndent(),
        )
        val ioContext = IOContext(input, output)

        val result = context(ioContext) {
            ShaderPipeline(
                artifacts,
                capabilityProvider = {
                    OpenGlSpirvCapabilities("test", true, artifacts.resolve("capabilities"))
                },
                cacheIdentityProvider = { null },
            ).optimize(
                listOf(
                    requireNotNull(ioContext.readInputRoot("composite.csh")),
                    requireNotNull(ioContext.readInputRoot("final.fsh")),
                ),
            )
        }.single { it.file.path.name == "composite.csh" }

        assertEquals(ShaderProcessingMode.SPIRV_ROUND_TRIP, result.processingMode, result.fallbackReason)
        val emitted = result.file.code
        assertContains(emitted, "#define printString(string) { \\")
        assertContains(emitted, "void printValue() { printString((_t)); }")
        assertEquals(1, Regex("(?m)^#define printString").findAll(emitted).count())
        assertFalse(Regex("(?m)^i < characters\\.length\\(\\);").containsMatchIn(emitted))
        assertFalse(Regex("(?m)^\\+\\+i\\) printChar").containsMatchIn(emitted))
    }

    @Test
    fun lifecycleResolutionFailureDoesNotPublishDeferredCacheEntries() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        val properties = workspace.resolve("shadesmith.shaders.properties")
        copyFixture(input)
        input.resolve("shadesmith.json").writeText(
            input.resolve("shadesmith.json").readText().replace(
                "\"transient_branch\": \"RGBA16F\"",
                "\"transient_branch\": \"RGBA16F\",\n    \"transient_unused\": \"RGBA16F\"",
            ),
        )

        val exception = assertFailsWith<IllegalStateException> {
            Main.runShaderPipeline(input, output, artifacts, properties)
        }

        assertContains(exception.message.orEmpty(), "transient_unused must be read/written")
        assertEquals(0, cacheEntries(artifacts))
        assertEquals("0", performance(artifacts).getValue("cache_publications"))
    }

    @Test
    fun parallelFailuresRetainOrderedUniqueDiagnosticsAndArtifacts() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        val artifacts = workspace.resolve("artifacts")
        input.createDirectories()
        val invalid = { symbol: String ->
            """
                #version 460 compatibility
                layout(local_size_x = 1) in;
                void main() {
                    $symbol = 1;
                }
            """.trimIndent()
        }
        input.resolve("composite.csh").writeText(invalid("missingFirst"))
        input.resolve("composite1.csh").writeText(invalid("missingSecond"))
        val ioContext = IOContext(input, output)

        val exception = context(ioContext) {
            assertFailsWith<ShaderPipelineException> {
                ShaderPipeline(artifacts, parallelism = 2).optimize(
                    listOf(
                        requireNotNull(ioContext.readInputRoot("composite1.csh")),
                        requireNotNull(ioContext.readInputRoot("composite.csh")),
                    ),
                )
            }
        }

        assertEquals(listOf("composite.csh", "composite1.csh"), exception.failures.map { it.sourceName })
        assertTrue(exception.failures.all { it.stage == "comp" })
        assertTrue(exception.failures.all { it.phase == "OpenGL SPIR-V compilation" })
        assertTrue(exception.failures.all { it.command.contains("glslang") })
        assertEquals(2, exception.failures.map { it.artifactDirectory }.toSet().size)
        exception.failures.forEach { failure ->
            val artifact = Path.of(failure.artifactDirectory)
            assertTrue(artifact.resolve("input.glsl").isRegularFile())
            assertTrue(artifact.resolve("compiler.glsl").isRegularFile())
            assertTrue(artifact.resolve("logs").listDirectoryEntries("*.log").any { it.fileSize() > 0 })
        }
        val manifest = artifacts.resolve("failures.tsv").readText()
        assertTrue(manifest.indexOf("composite.csh") < manifest.indexOf("composite1.csh"))
        assertContains(manifest, "OpenGL SPIR-V compilation")
        assertContains(manifest, "glslang")
    }

    private fun access(files: List<OptimizedShaderFile>, name: String): TextureAccess {
        return files.single { it.file.path.name == name }.textureAccess
    }

    private fun copyFixture(target: Path) {
        target.createDirectories()
        listOf(
            "shadesmith.json",
            "common.glsl",
            "composite.csh",
            "composite1.csh",
            "composite2.csh",
            "final.fsh",
            "voxy_hook.glsl",
        )
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

    private fun performance(artifacts: Path): Map<String, String> {
        return artifacts.resolve("performance.tsv").readText().lineSequence().drop(1).filter { '\t' in it }.associate { line ->
            val (name, value) = line.split('\t', limit = 2)
            name to value
        }
    }

    private fun cacheEntries(artifacts: Path): Int {
        val cache = artifacts.resolve("cache")
        if (!cache.exists()) return 0
        return Files.walk(cache).use { paths -> paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".cache") }.count().toInt() }
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
