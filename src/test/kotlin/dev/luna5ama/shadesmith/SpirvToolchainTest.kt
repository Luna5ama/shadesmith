package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpirvToolchainTest {
    @Test
    fun mapsEverySupportedShadesmithExtension() {
        val expected = mapOf(
            "shader.vsh" to ShaderStage.VERTEX,
            "shader.tcs" to ShaderStage.TESSELLATION_CONTROL,
            "shader.tes" to ShaderStage.TESSELLATION_EVALUATION,
            "shader.gsh" to ShaderStage.GEOMETRY,
            "shader.fsh" to ShaderStage.FRAGMENT,
            "shader.csh" to ShaderStage.COMPUTE,
        )

        expected.forEach { (name, stage) ->
            assertEquals(stage, ShaderStage.fromPath(Path.of(name)))
        }
        assertFailsWith<IllegalArgumentException> {
            ShaderStage.fromPath(Path.of("voxy_opaque.glsl"))
        }
        val hostFragment = ShaderEntryPoint.from(
            Path.of("custom_voxy_hook.glsl"),
            "void voxy_emitFragment(VoxyFragmentParameters parameters) {}",
        )
        assertEquals(ShaderStage.FRAGMENT, hostFragment.stage)
        assertEquals(ShaderEntryPointKind.HOST_INTEGRATION_FRAGMENT, hostFragment.kind)

        val standalone = ShaderEntryPoint.from(
            Path.of("custom_voxy_hook.glsl"),
            "#version 460\nvoid voxy_emitFragment(VoxyFragmentParameters parameters) {}\nvoid main() {}",
        )
        assertEquals(ShaderStage.FRAGMENT, standalone.stage)
        assertEquals(ShaderEntryPointKind.STANDALONE, standalone.kind)
    }

    @Test
    fun buildsOpenGlCompileCommandWithSeparatePathArguments() = withWorkspace { workspace ->
        val source = workspace.resolve("source files/input shader.glsl")
        source.parent.createDirectories()
        source.writeText("#version 460\nvoid main() {}")
        val output = workspace.resolve("SPIR-V files/output shader.spv")
        val executable = workspace.resolve("tool folder/glslang.exe").toString()
        val toolchain = SpirvToolchain(workspace, SpirvExecutables(glslang = executable))

        val invocation = toolchain.compileInvocation(ShaderStage.COMPUTE, source, output)

        assertEquals(
            listOf(
                executable,
                "--target-env",
                "opengl",
                "--target-env",
                "spirv1.3",
                "-S",
                "comp",
                "-o",
                output.toAbsolutePath().normalize().toString(),
                source.toAbsolutePath().normalize().toString(),
            ),
            invocation.command,
        )
        assertTrue("-V" !in invocation.command)
        assertTrue(invocation.command.none { it.contains("vulkan", ignoreCase = true) })
    }

    @Test
    fun buildsOptimizerCommandInRequestedOrder() = withWorkspace { workspace ->
        val input = workspace.resolve("input module.spv")
        val output = workspace.resolve("output module.spv")
        val toolchain = SpirvToolchain(workspace)

        val invocation = toolchain.optimizeInvocation(ShaderStage.COMPUTE, input, output)

        assertEquals(
            listOf("spirv-opt") + SpirvToolchain.OPTIMIZER_PASSES + listOf(
                input.toAbsolutePath().normalize().toString(),
                "-o",
                output.toAbsolutePath().normalize().toString(),
            ),
            invocation.command,
        )
    }

    @Test
    fun buildsCrossCommandWithRequestedOptions() = withWorkspace { workspace ->
        val input = workspace.resolve("input module.spv")
        val output = workspace.resolve("output shader.glsl")
        val toolchain = SpirvToolchain(workspace)

        val invocation = toolchain.decompileInvocation(ShaderStage.FRAGMENT, input, output)

        assertEquals(
            listOf(
                "spirv-cross",
                "--no-es",
                "--version",
                "460",
                input.toAbsolutePath().normalize().toString(),
                "--output",
                output.toAbsolutePath().normalize().toString(),
                "--glsl-force-flattened-io-blocks",
                "--combined-samplers-inherit-bindings",
                "--remove-unused-variables",
            ),
            invocation.command,
        )
    }

    @Test
    fun recordsSuccessfulExecutionAndKeepsPathArgumentsSeparate() = withWorkspace { workspace ->
        val input = workspace.resolve("input files/shader.glsl")
        input.parent.createDirectories()
        input.writeText("shader")
        val output = workspace.resolve("output files/shader.spv")
        val invocationRef = arrayOfNulls<SpirvInvocation>(1)
        val runner = SpirvProcessRunner { invocation, _, stdoutPath, stderrPath ->
            invocationRef[0] = invocation
            stdoutPath.writeText("stdout")
            stderrPath.writeText("stderr")
            invocation.output.parent.createDirectories()
            invocation.output.writeText("SPIR-V")
            0
        }
        val toolchain = SpirvToolchain(workspace, processRunner = runner)
        val invocation = toolchain.compileInvocation(ShaderStage.COMPUTE, input, output)

        val result = toolchain.execute(invocation)

        assertEquals(invocation, invocationRef[0])
        assertEquals(0, result.exitCode)
        assertEquals("stdout", result.stdoutPath.readText())
        assertEquals("stderr", result.stderrPath.readText())
        assertEquals(input.toAbsolutePath().normalize().toString(), invocation.command.last())
        assertTrue(output.exists())
    }

    @Test
    fun reusesIdenticalToolInputAcrossArtifactDirectories() = withWorkspace { workspace ->
        val cache = SpirvToolResultCache()
        val metrics = PipelineMetrics()
        var executions = 0
        val runner = SpirvProcessRunner { invocation, _, stdoutPath, stderrPath ->
            executions++
            stdoutPath.writeText("stdout")
            stderrPath.writeText("")
            invocation.output.parent.createDirectories()
            invocation.output.writeText("SPIR-V:${invocation.input.readText()}")
            0
        }
        val firstDirectory = workspace.resolve("first")
        val secondDirectory = workspace.resolve("second")
        firstDirectory.createDirectories()
        secondDirectory.createDirectories()
        val firstInput = firstDirectory.resolve("input.glsl").apply { writeText("same shader") }
        val secondInput = secondDirectory.resolve("input.glsl").apply { writeText("same shader") }
        val firstOutput = firstDirectory.resolve("output.spv")
        val secondOutput = secondDirectory.resolve("output.spv")
        val first = SpirvToolchain(
            firstDirectory,
            processRunner = runner,
            metrics = metrics,
            resultCache = cache,
        )
        val second = SpirvToolchain(
            secondDirectory,
            processRunner = runner,
            metrics = metrics,
            resultCache = cache,
        )

        first.execute(first.compileInvocation(ShaderStage.COMPUTE, firstInput, firstOutput))
        second.execute(second.compileInvocation(ShaderStage.COMPUTE, secondInput, secondOutput))

        assertEquals(firstOutput.readText(), secondOutput.readText())
        assertEquals(1, executions)
        assertEquals(1, metrics.snapshot().glslangProcesses)
        assertEquals(1, metrics.snapshot().toolCacheHits)
    }

    @Test
    fun reportsNonzeroExitWithDurableEvidencePaths() = withWorkspace { workspace ->
        val input = workspace.resolve("input.spv")
        input.writeText("SPIR-V")
        val output = workspace.resolve("output.spv")
        val runner = SpirvProcessRunner { invocation, _, stdoutPath, stderrPath ->
            stdoutPath.writeText("tool stdout")
            stderrPath.writeText("tool stderr")
            invocation.output.writeText("partial output")
            7
        }
        val toolchain = SpirvToolchain(workspace, processRunner = runner)
        val invocation = toolchain.optimizeInvocation(ShaderStage.COMPUTE, input, output)

        val exception = assertFailsWith<SpirvToolException> {
            toolchain.execute(invocation)
        }

        assertEquals(7, exception.exitCode)
        assertEquals(SpirvTool.SPIRV_OPT, exception.invocation.tool)
        assertEquals(ShaderStage.COMPUTE, exception.invocation.stage)
        assertContains(exception.message.orEmpty(), "comp shader")
        assertContains(exception.message.orEmpty(), input.toAbsolutePath().normalize().toString())
        assertContains(exception.message.orEmpty(), output.toAbsolutePath().normalize().toString())
        assertContains(exception.message.orEmpty(), exception.stdoutPath.toString())
        assertContains(exception.message.orEmpty(), exception.stderrPath.toString())
        assertEquals("tool stdout", exception.stdoutPath.readText())
        assertEquals("tool stderr", exception.stderrPath.readText())
        assertEquals("partial output", output.readText())
    }

    @Test
    fun reportsMissingExecutableFromSystemRunnerWithDurableEvidencePaths() = withWorkspace { workspace ->
        val input = workspace.resolve("input.spv")
        input.writeText("SPIR-V")
        val output = workspace.resolve("output.glsl")
        val missingExecutable = workspace.resolve("missing tools/spirv-cross.exe").toString()
        val toolchain = SpirvToolchain(workspace, SpirvExecutables(spirvCross = missingExecutable))
        val invocation = toolchain.decompileInvocation(ShaderStage.COMPUTE, input, output)

        val exception = assertFailsWith<SpirvToolException> {
            toolchain.execute(invocation)
        }

        assertEquals(null, exception.exitCode)
        assertTrue(exception.cause is IOException)
        assertContains(exception.message.orEmpty(), missingExecutable)
        assertTrue(exception.stdoutPath.exists())
        assertTrue(exception.stderrPath.exists())
    }

    @Test
    fun rejectsOutputOutsideOwnedWorkingDirectory() = withWorkspace { workspace ->
        val outside = workspace.parent.resolve("outside.spv")
        val toolchain = SpirvToolchain(workspace)

        assertFailsWith<IllegalArgumentException> {
            toolchain.optimizeInvocation(ShaderStage.COMPUTE, workspace.resolve("input.spv"), outside)
        }
    }

    @Test
    fun rejectsSuccessfulProcessWithoutExpectedOutput() = withWorkspace { workspace ->
        val input = workspace.resolve("input.spv")
        input.writeText("SPIR-V")
        val output = workspace.resolve("output.glsl")
        val toolchain = SpirvToolchain(workspace, processRunner = SpirvProcessRunner { _, _, _, _ -> 0 })
        val invocation = toolchain.decompileInvocation(ShaderStage.COMPUTE, input, output)

        val exception = assertFailsWith<SpirvToolException> {
            toolchain.execute(invocation)
        }

        assertEquals(0, exception.exitCode)
        assertContains(exception.message.orEmpty(), "did not create the expected output")
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith toolchain test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
