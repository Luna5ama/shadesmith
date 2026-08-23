package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal enum class ShaderStage(val glslangName: String) {
    VERTEX("vert"),
    TESSELLATION_CONTROL("tesc"),
    TESSELLATION_EVALUATION("tese"),
    GEOMETRY("geom"),
    FRAGMENT("frag"),
    COMPUTE("comp");

    companion object {
        fun fromPath(path: Path): ShaderStage {
            return when (path.extension.lowercase()) {
                "vsh", "vert" -> VERTEX
                "tcs", "tesc" -> TESSELLATION_CONTROL
                "tes", "tese" -> TESSELLATION_EVALUATION
                "gsh", "geom" -> GEOMETRY
                "fsh", "frag" -> FRAGMENT
                "csh", "comp" -> COMPUTE
                else -> throw IllegalArgumentException("Cannot infer shader stage from ${path.name}")
            }
        }

        fun fromEntryPoint(path: Path, source: String): ShaderStage {
            return if (path.extension.equals("glsl", ignoreCase = true) && VOXY_FRAGMENT_HOOK.containsMatchIn(source)) {
                FRAGMENT
            } else {
                fromPath(path)
            }
        }

        private val VOXY_FRAGMENT_HOOK = """\bvoid\s+voxy_emitFragment\s*\(""".toRegex()
    }
}

internal data class SpirvExecutables(
    val glslang: String = "glslang",
    val spirvOpt: String = "spirv-opt",
    val spirvCross: String = "spirv-cross",
) {
    init {
        require(glslang.isNotBlank()) { "glslang executable cannot be blank" }
        require(spirvOpt.isNotBlank()) { "spirv-opt executable cannot be blank" }
        require(spirvCross.isNotBlank()) { "spirv-cross executable cannot be blank" }
    }
}

internal enum class SpirvTool(val displayName: String) {
    GLSLANG("glslang"),
    SPIRV_OPT("spirv-opt"),
    SPIRV_CROSS("spirv-cross"),
}

internal data class SpirvInvocation(
    val tool: SpirvTool,
    val stage: ShaderStage?,
    val command: List<String>,
    val input: Path,
    val output: Path,
)

internal data class SpirvToolResult(
    val invocation: SpirvInvocation,
    val exitCode: Int,
    val stdoutPath: Path,
    val stderrPath: Path,
)

internal class SpirvToolException(
    val invocation: SpirvInvocation,
    val exitCode: Int?,
    val stdoutPath: Path,
    val stderrPath: Path,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append(invocation.tool.displayName)
        append(" failed")
        invocation.stage?.let {
            append(" for ")
            append(it.glslangName)
            append(" shader")
        }
        exitCode?.let {
            append(" with exit code ")
            append(it)
        }
        append(": ")
        append(detail)
        appendLine()
        append("Input: ")
        appendLine(invocation.input.absolutePathString())
        append("Output: ")
        appendLine(invocation.output.absolutePathString())
        append("Stdout: ")
        appendLine(stdoutPath.absolutePathString())
        append("Stderr: ")
        appendLine(stderrPath.absolutePathString())
        append("Command: ")
        append(invocation.command.joinToString(" ") { it.asDiagnosticArgument() })
    },
    cause,
)

internal fun interface SpirvProcessRunner {
    fun execute(
        invocation: SpirvInvocation,
        workingDirectory: Path,
        stdoutPath: Path,
        stderrPath: Path,
    ): Int
}

private object SystemSpirvProcessRunner : SpirvProcessRunner {
    override fun execute(
        invocation: SpirvInvocation,
        workingDirectory: Path,
        stdoutPath: Path,
        stderrPath: Path,
    ): Int {
        return ProcessBuilder(invocation.command)
            .directory(workingDirectory.toFile())
            .redirectOutput(stdoutPath.toFile())
            .redirectError(stderrPath.toFile())
            .start()
            .waitFor()
    }
}

internal class SpirvToolchain(
    workingDirectory: Path,
    private val executables: SpirvExecutables = SpirvExecutables(),
    private val processRunner: SpirvProcessRunner = SystemSpirvProcessRunner,
) {
    val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()

    init {
        this.workingDirectory.createDirectories()
    }

    fun compileInvocation(stage: ShaderStage, source: Path, output: Path): SpirvInvocation {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedOutput = ownedOutput(output)
        return SpirvInvocation(
            tool = SpirvTool.GLSLANG,
            stage = stage,
            command = listOf(
                executables.glslang,
                "--target-env",
                "opengl",
                "--target-env",
                "spirv1.3",
                "-S",
                stage.glslangName,
                "-o",
                normalizedOutput.absolutePathString(),
                normalizedSource.absolutePathString(),
            ),
            input = normalizedSource,
            output = normalizedOutput,
        )
    }

    fun optimizeInvocation(stage: ShaderStage, input: Path, output: Path): SpirvInvocation {
        val normalizedInput = input.toAbsolutePath().normalize()
        val normalizedOutput = ownedOutput(output)
        return SpirvInvocation(
            tool = SpirvTool.SPIRV_OPT,
            stage = stage,
            command = buildList {
                add(executables.spirvOpt)
                addAll(OPTIMIZER_PASSES)
                add(normalizedInput.absolutePathString())
                add("-o")
                add(normalizedOutput.absolutePathString())
            },
            input = normalizedInput,
            output = normalizedOutput,
        )
    }

    fun decompileInvocation(stage: ShaderStage, input: Path, output: Path): SpirvInvocation {
        val normalizedInput = input.toAbsolutePath().normalize()
        val normalizedOutput = ownedOutput(output)
        return SpirvInvocation(
            tool = SpirvTool.SPIRV_CROSS,
            stage = stage,
            command = listOf(
                executables.spirvCross,
                "--no-es",
                "--version",
                "460",
                normalizedInput.absolutePathString(),
                "--output",
                normalizedOutput.absolutePathString(),
                "--glsl-force-flattened-io-blocks",
                "--combined-samplers-inherit-bindings",
                "--remove-unused-variables",
            ),
            input = normalizedInput,
            output = normalizedOutput,
        )
    }

    fun execute(invocation: SpirvInvocation): SpirvToolResult {
        require(invocation.input.isRegularFile()) {
            "${invocation.tool.displayName} input is not a regular file: ${invocation.input}"
        }
        require(invocation.output.startsWith(workingDirectory)) {
            "${invocation.tool.displayName} output is outside the owned working directory: ${invocation.output}"
        }

        invocation.output.parent.createDirectories()
        Files.deleteIfExists(invocation.output)

        val logDirectory = workingDirectory.resolve("logs")
        logDirectory.createDirectories()
        val logStem = buildLogStem(invocation)
        val stdoutPath = logDirectory.resolve("$logStem.stdout.log")
        val stderrPath = logDirectory.resolve("$logStem.stderr.log")
        Files.writeString(stdoutPath, "")
        Files.writeString(stderrPath, "")

        val exitCode = try {
            processRunner.execute(invocation, workingDirectory, stdoutPath, stderrPath)
        } catch (e: IOException) {
            throw SpirvToolException(
                invocation,
                exitCode = null,
                stdoutPath,
                stderrPath,
                e.message ?: "unable to start process",
                e,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SpirvToolException(
                invocation,
                exitCode = null,
                stdoutPath,
                stderrPath,
                "interrupted while waiting for process",
                e,
            )
        }

        if (exitCode != 0) {
            throw SpirvToolException(
                invocation,
                exitCode,
                stdoutPath,
                stderrPath,
                "process returned a nonzero exit code",
            )
        }
        if (!invocation.output.isRegularFile()) {
            throw SpirvToolException(
                invocation,
                exitCode,
                stdoutPath,
                stderrPath,
                "process did not create the expected output",
            )
        }

        return SpirvToolResult(invocation, exitCode, stdoutPath, stderrPath)
    }

    private fun ownedOutput(path: Path): Path {
        val output = path.toAbsolutePath().normalize()
        require(output.startsWith(workingDirectory)) {
            "Output path is outside the owned working directory: $output"
        }
        return output
    }

    private fun buildLogStem(invocation: SpirvInvocation): String {
        val stage = invocation.stage?.glslangName ?: "module"
        val outputName = invocation.output.fileName.toString().replace(LOG_NAME_INVALID_CHAR, "_")
        return "${invocation.tool.displayName}-$stage-$outputName"
    }

    companion object {
        val OPTIMIZER_PASSES = listOf(
            "--eliminate-dead-branches",
            "--merge-return",
            "--inline-entry-points-exhaustive",
            "--scalar-replacement=0",
            "--ssa-rewrite",
            "--simplify-instructions",
            "--eliminate-dead-inserts",
            "--eliminate-dead-functions",
            "--eliminate-dead-code-aggressive",
            "--merge-blocks",
        )

        private val LOG_NAME_INVALID_CHAR = "[^A-Za-z0-9._-]".toRegex()
    }
}

private fun String.asDiagnosticArgument(): String {
    if (none { it.isWhitespace() || it == '"' }) return this
    return buildString(length + 2) {
        append('"')
        this@asDiagnosticArgument.forEach {
            if (it == '"') append('\\')
            append(it)
        }
        append('"')
    }
}
