package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpirvOptimizerTest {
    @Test
    fun roundTripsRepresentativeComputeVertexFragmentAndGeometryShaders() = withWorkspace { workspace ->
        val fixtures = listOf(
            "dead-code.csh" to ShaderStage.COMPUTE,
            "basic.vsh" to ShaderStage.VERTEX,
            "basic.fsh" to ShaderStage.FRAGMENT,
            "basic.gsh" to ShaderStage.GEOMETRY,
        )

        fixtures.forEach { (name, stage) ->
            val result = SpirvOptimizer(workspace.resolve(stage.glslangName)).optimize(
                SpirvOptimizationRequest(name, stage, fixture(name)),
            )
            val module = result.modules.single()

            assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, name)
            assertEquals(
                listOf(SpirvTool.GLSLANG, SpirvTool.SPIRV_OPT, SpirvTool.SPIRV_CROSS, SpirvTool.GLSLANG),
                module.invocations.map { it.tool },
                name,
            )
            assertEquals(SpirvToolchain.OPTIMIZER_PASSES, module.invocations[1].command.drop(1).dropLast(3), name)
            assertContains(
                module.invocations[0].command.joinToString(" "),
                "--target-env opengl",
                message = name,
            )
            val crossCommand = module.invocations[2].command
            assertTrue(crossCommand.windowed(3).contains(listOf("--no-es", "--version", "460")), name)
            assertTrue("--glsl-force-flattened-io-blocks" in crossCommand, name)
            assertTrue("--combined-samplers-inherit-bindings" in crossCommand, name)
            assertTrue("--remove-unused-variables" in crossCommand, name)
            assertTrue(module.validationSpirv.isRegularFile(), name)
            assertTrue(module.validationSpirv.fileSize() > 0, name)
            assertContains(module.source, "#version 460 compatibility", message = name)
            assertContains(module.source, "void main()", message = name)
        }
    }

    @Test
    fun compilesSubgroupOperationsWithOpenGlSemanticsAtSpirv13() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("subgroup.csh", ShaderStage.COMPUTE, fixture("subgroup.csh")),
        )
        val module = result.modules.single()

        assertTrue(
            module.invocations.first().command.windowed(4).contains(
                listOf("--target-env", "opengl", "--target-env", "spirv1.3"),
            ),
        )
        assertContains(result.source, "#extension GL_KHR_shader_subgroup_arithmetic : require")
        assertTrue(module.validationSpirv.isRegularFile())
    }

    @Test
    fun removesRealDeadFunctionAndDeadBranchFromComputeSpirv() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("dead-code.csh", ShaderStage.COMPUTE, fixture("dead-code.csh")),
        )
        val module = result.modules.single()

        assertTrue(module.optimizedSpirvSize < module.originalSpirvSize)
        assertFalse(module.source.contains("deadHelper"))
        assertFalse(module.source.contains("if (false)"))
        assertFalse(module.source.contains("gl_WorkGroupSize"))
        assertContains(module.source, "inputTexture")
        assertContains(module.source, "unusedTexture")
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("unusedTexture"))
        assertContains(module.source, "uniform float deadReferencedUniform = 1.0;")
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("deadReferencedUniform"))
        assertContains(module.source, "struct DeadRecord")
        assertContains(module.source, "readonly buffer DeadBuffer")
        assertContains(module.source, "DeadRecord deadValues[];")
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("DeadRecord"))
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("DeadBuffer"))
        assertContains(module.source, "outputImage")
        assertContains(module.source, "exposure")
        assertContains(module.source, "readonly buffer DataBuffer")
        assertContains(module.source, "readonly buffer FoldedArrayBuffer")
        assertContains(module.source, "float foldedWeights[8 * 4];")
        assertContains(module.source, "uniform Params")
        assertContains(module.source, "float weights[];")
        assertContains(module.source, "vec4 tint;")
        assertContains(module.source, "layout(local_size_x = 8, local_size_y = 4, local_size_z = 1) in;")
        assertContains(
            module.source,
            "const ivec3 workGroups = ivec3(32, 18, 1); // Iris dispatch contract",
        )
        assertContains(module.source, "#extension GL_ARB_shader_image_load_store : require")
        assertContains(module.source, "#pragma optimize(on)")
        assertContains(module.source, "//#define FIXTURE_DEBUG")
        assertFalse(module.source.contains("binding ="))
        assertFalse(module.source.contains("location ="))
    }

    @Test
    fun producesDeterministicSourceSpirvAndArtifactPaths() = withWorkspace { workspace ->
        val request = SpirvOptimizationRequest("basic.fsh", ShaderStage.FRAGMENT, fixture("basic.fsh"))
        val optimizer = SpirvOptimizer(workspace)

        val first = optimizer.optimize(request)
        val restored = first.source
        val firstModule = first.modules.single()
        val firstSpirv = firstModule.optimizedSpirv.readBytes()
        val second = optimizer.optimize(request)

        assertContains(firstModule.artifactDirectory.resolve("compiler.glsl").readText(), "colortex3Format = 0;")
        assertContains(firstModule.artifactDirectory.resolve("validation.glsl").readText(), "colortex3Format = 0;")
        assertContains(restored, "/* RENDERTARGETS:3 */")
        assertContains(restored, "const int noiseTextureResolution = 256;")
        assertContains(restored, "const float sunPathRotation = -20.0; //[-90.0 -20.0 0.0 20.0 90.0]")
        assertContains(restored, "const int colortex3Format = RGBA16F; // Iris string directive")
        assertContains(restored, "const bool colortex3Clear = false;")
        assertContains(restored, "const vec4 colortex3ClearColor = vec4(0.25, 0.5, 0.75, 1.0);")
        assertFalse(restored.contains("colortex4Format"))
        assertEquals(first.source, second.source)
        assertEquals(first.artifactDirectory, second.artifactDirectory)
        assertEquals(first.modules.single().artifactDirectory, second.modules.single().artifactDirectory)
        assertTrue(firstSpirv.contentEquals(second.modules.single().optimizedSpirv.readBytes()))
    }

    @Test
    fun preservesExplicitResourceBindingLocationAndInterpolationContracts() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest(
                "explicit-contract.fsh",
                ShaderStage.FRAGMENT,
                fixture("explicit-contract.fsh"),
            ),
        )
        val source = result.source

        assertContains(source, "layout(location = 3) flat in highp vec2 texCoord;")
        assertContains(source, "layout(location = 1) out vec4 fragColor;")
        assertContains(source, "layout(binding = 5) uniform sampler2D colorTexture;")
        assertTrue(result.modules.single().validationSpirv.isRegularFile())
    }

    @Test
    fun reusesCompilerOnlySamplerBindingsAfterPortableLimitAndSeparatesImages() = withWorkspace { workspace ->
        val source = buildString {
            appendLine("#version 460 compatibility")
            appendLine("layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;")
            repeat(81) { appendLine("uniform sampler2D sampler$it;") }
            appendLine("layout(rgba32ui) uniform writeonly uimage2D outputImage;")
            appendLine("void main() {")
            appendLine("    vec4 value = texture(sampler80, vec2(0.5));")
            appendLine("    imageStore(outputImage, ivec2(0), uvec4(value));")
            appendLine("}")
        }
        val protected = PreprocessorProtection.protect(source, "binding-namespaces.csh")
        val patch = OpenGlShaderPatcher().patch(protected, ShaderStage.COMPUTE)
        val generated = patch.generatedLayouts.associateBy { it.key }

        assertEquals(79, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "sampler79")).value)
        assertEquals(0, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "sampler80")).value)
        assertEquals(0, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "outputImage")).value)

        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("binding-namespaces.csh", ShaderStage.COMPUTE, source),
        )
        assertTrue(result.modules.single().validationSpirv.isRegularFile())
        assertContains(result.source, "uniform sampler2D sampler80;")
        assertContains(result.source, "layout(rgba32ui) uniform writeonly uimage2D outputImage;")
    }

    @Test
    fun restoresAnonymousBlocksExactlyAndLinksStagesAfterOptimization() = withWorkspace { workspace ->
        val optimizer = SpirvOptimizer(workspace.resolve("optimizer"))
        val vertex = optimizer.optimize(
            SpirvOptimizationRequest(
                "anonymous-block.vsh",
                ShaderStage.VERTEX,
                fixture("anonymous-block.vsh"),
            ),
        ).source
        val fragment = optimizer.optimize(
            SpirvOptimizationRequest(
                "anonymous-block.fsh",
                ShaderStage.FRAGMENT,
                fixture("anonymous-block.fsh"),
            ),
        ).source

        assertContains(vertex, "readonly buffer GlobalData")
        assertContains(vertex, "sharedCoord = globalValue.xy;")
        assertFalse(Regex("""\b_[0-9]+\s*\.""").containsMatchIn(vertex))
        assertContains(fragment, "readonly buffer GlobalData")
        assertFalse(Regex("""}\s+_[0-9]+\s*;""").containsMatchIn(fragment))

        val linkDirectory = workspace.resolve("linked")
        Files.createDirectories(linkDirectory)
        val vertexPath = linkDirectory.resolve("anonymous-block.vert")
        val fragmentPath = linkDirectory.resolve("anonymous-block.frag")
        Files.writeString(vertexPath, vertex.replaceFirst("#version 460 compatibility", "#version 460 core"))
        Files.writeString(fragmentPath, fragment.replaceFirst("#version 460 compatibility", "#version 460 core"))
        val output = linkDirectory.resolve("anonymous-block.spv")
        val command = listOf(
            "glslang",
            "--target-env",
            "opengl",
            "--target-env",
            "spirv1.3",
            "--auto-map-bindings",
            "--auto-map-locations",
            "-l",
            vertexPath.toString(),
            fragmentPath.toString(),
            "-o",
            output.toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, log)
        assertTrue(output.isRegularFile())
    }

    @Test
    fun preservesSourceAndOptimizesExplicitCompilerModulesConcurrently() = withWorkspace { workspace ->
        val original = fixture("macro-heavy.fsh")
        val executor = Executors.newFixedThreadPool(2)
        val firstCompiles = CountDownLatch(2)
        val activeProcesses = AtomicInteger()
        val maximumProcesses = AtomicInteger()
        val runner = SpirvProcessRunner { invocation, workingDirectory, stdoutPath, stderrPath ->
            val active = activeProcesses.incrementAndGet()
            maximumProcesses.accumulateAndGet(active, ::maxOf)
            try {
                if (invocation.tool == SpirvTool.GLSLANG && invocation.input.fileName.toString() == "compiler.glsl") {
                    firstCompiles.countDown()
                    check(firstCompiles.await(30, TimeUnit.SECONDS)) { "Shader compiler modules did not compile concurrently" }
                }
                ProcessBuilder(invocation.command)
                    .directory(workingDirectory.toFile())
                    .redirectOutput(stdoutPath.toFile())
                    .redirectError(stderrPath.toFile())
                    .start()
                    .waitFor()
            } finally {
                activeProcesses.decrementAndGet()
            }
        }
        val result = try {
            SpirvOptimizer(workspace, processRunner = runner, moduleExecutor = executor).optimize(
                SpirvOptimizationRequest(
                    sourceName = "macro-heavy.fsh",
                    stage = ShaderStage.FRAGMENT,
                    source = original,
                    compilerModules = listOf(
                        SpirvCompilerModule(
                            "tint-on",
                            fixture("macro-heavy-on.fsh"),
                        ),
                        SpirvCompilerModule(
                            "tint-off",
                            fixture("macro-heavy-off.fsh"),
                        ),
                    ),
                ),
            )
        } finally {
            executor.shutdownNow()
        }

        assertEquals(SpirvEmissionMode.PRESERVED_COMPILER_COPY, result.emissionMode)
        assertEquals(original, result.source)
        assertContains(result.source, "#if defined(SETTING_TINT)")
        assertContains(result.source, "#define APPLY_TINT(value)")
        assertContains(result.source, "//#define SETTING_TINT")
        assertEquals(listOf("tint-on", "tint-off"), result.modules.map { it.name })
        assertTrue(maximumProcesses.get() >= 2)
        assertTrue(result.modules.all { it.validationSpirv.isRegularFile() && it.validationSpirv.fileSize() > 0 })
        assertTrue(result.modules.all { "APPLY_TINT" !in it.source })
    }

    @Test
    fun refusesBlockedSourceWithoutACompilerCopyBeforeInvokingAnyTool() = withWorkspace { workspace ->
        var invoked = false
        val optimizer = SpirvOptimizer(
            workspace,
            processRunner = SpirvProcessRunner { _, _, _, _ ->
                invoked = true
                error("tool must not run")
            },
        )

        val exception = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                ),
            )
        }

        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, exception.phase)
        assertContains(exception.message.orEmpty(), "no compiler-copy module")
        assertFalse(invoked)
        assertTrue(exception.artifactDirectory.resolve("original.glsl").isRegularFile())
        assertTrue(exception.artifactDirectory.listDirectoryEntries("*.spv").isEmpty())
    }

    @Test
    fun refusesBlankOrDuplicateCompilerModuleNames() = withWorkspace { workspace ->
        val optimizer = SpirvOptimizer(workspace)
        val duplicate = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                    compilerModules = listOf(
                        SpirvCompilerModule("same", fixture("macro-heavy-off.fsh")),
                        SpirvCompilerModule("same", fixture("macro-heavy-on.fsh")),
                    ),
                ),
            )
        }
        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, duplicate.phase)
        assertContains(duplicate.message.orEmpty(), "duplicates=[same]")

        val blank = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                    compilerModules = listOf(
                        SpirvCompilerModule("", fixture("macro-heavy-off.fsh")),
                    ),
                ),
            )
        }
        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, blank.phase)
        assertContains(blank.message.orEmpty(), "non-blank")
    }

    @Test
    fun resourceAndGeneratedLayoutMismatchesFailLoudly() {
        val source = fixture("basic.fsh")
        val protected = PreprocessorProtection.protect(source, "basic.fsh")
        val patcher = OpenGlShaderPatcher()
        val patch = patcher.patch(protected, ShaderStage.FRAGMENT)

        val missingResource = patch.compilerSource.replace(
            Regex("(?m)^layout\\([^\n]+colorTexture;\\n?"),
            "",
        )
        val restoredMissingResource = patcher.restore(missingResource, patch)
        assertContains(restoredMissingResource, "uniform sampler2D colorTexture;")

        val samplerLayout = patch.generatedLayouts.single { it.key.name == "colorTexture" }
        val changedBinding = patch.compilerSource.replace(
            "${samplerLayout.qualifier} = ${samplerLayout.value}",
            "${samplerLayout.qualifier} = ${samplerLayout.value + 7}",
        )
        val bindingException = assertFailsWith<OpenGlShaderPatchException> {
            patcher.restore(changedBinding, patch)
        }
        assertContains(bindingException.reason, "changed from")

        val computePatch = patcher.patch(
            PreprocessorProtection.protect(fixture("dead-code.csh"), "dead-code.csh"),
            ShaderStage.COMPUTE,
        )
        val changedBlock = computePatch.compilerSource.replace("float weights[];", "vec2 weights[];")
        val blockException = assertFailsWith<OpenGlShaderPatchException> {
            patcher.restore(changedBlock, computePatch)
        }
        assertContains(blockException.reason, "DataBuffer")
        assertContains(blockException.reason, "block declaration changed")
    }

    @Test
    fun refusesUsingAnIrisFormatDirectiveAsShaderCode() {
        val source = """
            #version 460 compatibility
            const int colortex0Format = RGBA16F;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(float(colortex0Format));
            }
        """.trimIndent()

        val exception = assertFailsWith<OpenGlShaderPatchException> {
            OpenGlShaderPatcher().patch(
                PreprocessorProtection.protect(source, "referenced-format.fsh"),
                ShaderStage.FRAGMENT,
            )
        }

        assertEquals(2, exception.sourceLine)
        assertContains(exception.reason, "colortex0Format")
        assertContains(exception.reason, "referenced by shader code")
    }

    @Test
    fun refusesUnparsedResourceDeclarations() {
        val source = """
            #version 460 compatibility
            uniform sampler2D firstTexture, secondTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(firstTexture, vec2(0.5));
            }
        """.trimIndent()

        val exception = assertFailsWith<OpenGlShaderPatchException> {
            OpenGlShaderPatcher().patch(
                PreprocessorProtection.protect(source, "comma-resource.fsh"),
                ShaderStage.FRAGMENT,
            )
        }

        assertEquals(2, exception.sourceLine)
        assertContains(exception.reason, "unsupported top-level")
    }

    @Test
    fun compileFailureKeepsInputsAndToolLogsWithPhaseContext() = withWorkspace { workspace ->
        val invalid = """
            #version 460 compatibility
            layout(local_size_x = 1) in;
            void main() {
                missingSymbol = 1;
            }
        """.trimIndent()

        val exception = assertFailsWith<SpirvRoundTripException> {
            SpirvOptimizer(workspace).optimize(
                SpirvOptimizationRequest("invalid.csh", ShaderStage.COMPUTE, invalid),
            )
        }

        assertEquals(SpirvRoundTripPhase.COMPILE, exception.phase)
        assertContains(exception.message.orEmpty(), "invalid.csh")
        assertContains(exception.message.orEmpty(), "[comp]")
        assertTrue(exception.artifactDirectory.resolve("input.glsl").isRegularFile())
        assertTrue(exception.artifactDirectory.resolve("compiler.glsl").isRegularFile())
        val logs = exception.artifactDirectory.resolve("logs")
        assertTrue(logs.exists())
        assertTrue(
            logs.listDirectoryEntries("*.log").any { it.fileSize() > 0 },
            "glslang diagnostics should be retained in stdout or stderr",
        )
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/spirv/$name")) {
            "Missing SPIR-V fixture $name"
        }.readText()
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith spirv optimizer test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
