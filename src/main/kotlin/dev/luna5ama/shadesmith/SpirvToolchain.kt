package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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
            return ShaderEntryPoint.from(path, source).stage
        }
    }
}

internal enum class ShaderEntryPointKind {
    STANDALONE,
    HOST_INTEGRATION_FRAGMENT,
}

internal data class ShaderEntryPoint(
    val stage: ShaderStage,
    val kind: ShaderEntryPointKind,
) {
    companion object {
        fun from(path: Path, source: String): ShaderEntryPoint {
            if (!path.extension.equals("glsl", ignoreCase = true)) {
                return ShaderEntryPoint(ShaderStage.fromPath(path), ShaderEntryPointKind.STANDALONE)
            }
            if (!VOXY_FRAGMENT_HOOK.containsMatchIn(source)) {
                throw IllegalArgumentException("Cannot infer shader stage from ${path.name}")
            }
            val kind = if (VERSION_DIRECTIVE.containsMatchIn(source) && MAIN_ENTRY_POINT.containsMatchIn(source)) {
                ShaderEntryPointKind.STANDALONE
            } else {
                ShaderEntryPointKind.HOST_INTEGRATION_FRAGMENT
            }
            return ShaderEntryPoint(ShaderStage.FRAGMENT, kind)
        }

        private val VOXY_FRAGMENT_HOOK = """\bvoid\s+voxy_emitFragment\s*\(""".toRegex()
        private val VERSION_DIRECTIVE = """(?m)^[\t ]*#version\b""".toRegex()
        private val MAIN_ENTRY_POINT = """\bvoid\s+main\s*\(""".toRegex()
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

internal enum class SpirvOptimizationProfile {
    DEFAULT,
    NO_SSA_REWRITE,
    STRUCTURAL_CONVERGENCE,
    STRUCTURAL_CONVERGENCE_NO_SSA,
}

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

internal class SpirvToolResultCache(
    private val completedByteBudget: Long = DEFAULT_COMPLETED_BYTE_BUDGET,
) {
    private val entries = ConcurrentHashMap<SpirvToolCacheKey, CompletableFuture<CachedSpirvToolOutput>>()
    private val completed = ArrayDeque<CompletedSpirvToolCacheEntry>()
    private var completedBytes = 0L

    init {
        require(completedByteBudget >= 0) { "SPIR-V tool cache byte budget cannot be negative" }
    }

    fun execute(
        invocation: SpirvInvocation,
        action: () -> CachedSpirvToolOutput,
    ): CachedSpirvToolExecution {
        val key = invocation.cacheKey()
        val created = CompletableFuture<CachedSpirvToolOutput>()
        val existing = entries.putIfAbsent(key, created)
        if (existing != null) {
            return try {
                CachedSpirvToolExecution(await(existing), cacheHit = true)
            } catch (e: SpirvToolException) {
                entries.remove(key, existing)
                execute(invocation, action)
            }
        }
        return try {
            val output = action()
            created.complete(output)
            retainCompleted(key, created, output)
            CachedSpirvToolExecution(output, cacheHit = false)
        } catch (t: Throwable) {
            created.completeExceptionally(t)
            entries.remove(key, created)
            throw t
        }
    }

    private fun retainCompleted(
        key: SpirvToolCacheKey,
        future: CompletableFuture<CachedSpirvToolOutput>,
        output: CachedSpirvToolOutput,
    ) {
        val bytes = output.output.size.toLong() + output.stdout.size + output.stderr.size
        synchronized(completed) {
            completed += CompletedSpirvToolCacheEntry(key, future, bytes)
            completedBytes += bytes
            while (completedBytes > completedByteBudget && completed.isNotEmpty()) {
                val evicted = completed.removeFirst()
                if (entries.remove(evicted.key, evicted.future)) completedBytes -= evicted.bytes
            }
        }
    }

    private fun await(future: CompletableFuture<CachedSpirvToolOutput>): CachedSpirvToolOutput {
        return try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            when (val cause = e.cause ?: e) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("cached SPIR-V tool execution failed", cause)
            }
        }
    }

    companion object {
        internal const val DEFAULT_COMPLETED_BYTE_BUDGET = 512L * 1024L * 1024L
    }
}

private data class CompletedSpirvToolCacheEntry(
    val key: SpirvToolCacheKey,
    val future: CompletableFuture<CachedSpirvToolOutput>,
    val bytes: Long,
)

internal data class CachedSpirvToolExecution(
    val output: CachedSpirvToolOutput,
    val cacheHit: Boolean,
)

internal data class CachedSpirvToolOutput(
    val output: ByteArray,
    val stdout: ByteArray,
    val stderr: ByteArray,
)

private data class SpirvToolCacheKey(
    val tool: SpirvTool,
    val stage: ShaderStage?,
    val command: List<String>,
    val inputSha256: String,
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
    private val processGate: ExternalProcessGate? = null,
    private val metrics: PipelineMetrics? = null,
    private val resultCache: SpirvToolResultCache? = null,
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

    fun optimizeInvocation(
        stage: ShaderStage,
        input: Path,
        output: Path,
        profile: SpirvOptimizationProfile = SpirvOptimizationProfile.DEFAULT,
    ): SpirvInvocation {
        return optimizeInvocation(stage, input, output, optimizerPasses(profile))
    }

    fun optimizeCrossAdapterInvocation(
        stage: ShaderStage,
        input: Path,
        output: Path,
        profile: SpirvOptimizationProfile = SpirvOptimizationProfile.DEFAULT,
    ): SpirvInvocation {
        return optimizeInvocation(stage, input, output, crossAdapterPasses(profile))
    }

    private fun optimizeInvocation(
        stage: ShaderStage,
        input: Path,
        output: Path,
        passes: List<String>,
    ): SpirvInvocation {
        val normalizedInput = input.toAbsolutePath().normalize()
        val normalizedOutput = ownedOutput(output)
        return SpirvInvocation(
            tool = SpirvTool.SPIRV_OPT,
            stage = stage,
            command = buildList {
                add(executables.spirvOpt)
                addAll(passes)
                add(normalizedInput.absolutePathString())
                add("-o")
                add(normalizedOutput.absolutePathString())
            },
            input = normalizedInput,
            output = normalizedOutput,
        )
    }

    fun decompileInvocation(
        stage: ShaderStage,
        input: Path,
        output: Path,
        vulkanSemantics: Boolean = false,
    ): SpirvInvocation {
        val normalizedInput = input.toAbsolutePath().normalize()
        val normalizedOutput = ownedOutput(output)
        return SpirvInvocation(
            tool = SpirvTool.SPIRV_CROSS,
            stage = stage,
            command = buildList {
                add(executables.spirvCross)
                add("--no-es")
                add("--version")
                add("460")
                if (vulkanSemantics) add("--vulkan-semantics")
                add(normalizedInput.absolutePathString())
                add("--output")
                add(normalizedOutput.absolutePathString())
                add("--glsl-force-flattened-io-blocks")
                add("--combined-samplers-inherit-bindings")
                add("--remove-unused-variables")
            },
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

        val cacheExecution = resultCache?.execute(invocation) {
            executeUncached(invocation, stdoutPath, stderrPath)
        }
        if (cacheExecution == null) {
            executeUncached(invocation, stdoutPath, stderrPath)
        } else if (cacheExecution.cacheHit) {
            Files.write(invocation.output, cacheExecution.output.output)
            Files.write(stdoutPath, cacheExecution.output.stdout)
            Files.write(stderrPath, cacheExecution.output.stderr)
            metrics?.recordToolCacheHit()
        }
        return SpirvToolResult(invocation, 0, stdoutPath, stderrPath)
    }

    private fun executeUncached(
        invocation: SpirvInvocation,
        stdoutPath: Path,
        stderrPath: Path,
    ): CachedSpirvToolOutput {
        val exitCode = try {
            metrics?.recordToolProcess(invocation.tool)
            if (processGate == null) {
                processRunner.execute(invocation, workingDirectory, stdoutPath, stderrPath)
            } else {
                processGate.run {
                    processRunner.execute(invocation, workingDirectory, stdoutPath, stderrPath)
                }
            }
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
        return CachedSpirvToolOutput(
            Files.readAllBytes(invocation.output),
            Files.readAllBytes(stdoutPath),
            Files.readAllBytes(stderrPath),
        )
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
            "--preserve-bindings",
            "--preserve-interface",
            "--preserve-spec-constants",
            "--eliminate-dead-branches",
            "--eliminate-dead-functions",
            "--eliminate-dead-code-aggressive",
            "--private-to-local",
            "--eliminate-local-single-block",
            "--eliminate-local-single-store",
            "--eliminate-dead-code-aggressive",
            "--scalar-replacement=0",
            "--convert-local-access-chains",
            "--eliminate-local-single-block",
            "--eliminate-local-single-store",
            "--eliminate-dead-code-aggressive",
            "--ssa-rewrite",
            "--eliminate-dead-code-aggressive",
            "--ccp",
            "--eliminate-dead-code-aggressive",
            "--combine-access-chains",
            "--simplify-instructions",
            "--scalar-replacement=0",
            "--convert-local-access-chains",
            "--eliminate-local-single-block",
            "--eliminate-local-single-store",
            "--eliminate-dead-code-aggressive",
            "--ssa-rewrite",
            "--eliminate-dead-code-aggressive",
            "--vector-dce",
            "--eliminate-dead-inserts",
            "--eliminate-dead-code-aggressive",
            "--merge-blocks",
            "--cfg-cleanup",
            "--simplify-instructions",
        )
        val CROSS_ADAPTER_PASSES = OPTIMIZER_PASSES
        val NO_SSA_REWRITE_PASSES = withoutSsaRewrite(OPTIMIZER_PASSES)
        val STRUCTURAL_CONVERGENCE_PASSES = OPTIMIZER_PASSES.toMutableList().apply {
            add(indexOf("--eliminate-dead-functions"), "--inline-entry-points-exhaustive")
        }.toList()
        val STRUCTURAL_CONVERGENCE_NO_SSA_PASSES = NO_SSA_REWRITE_PASSES.toMutableList().apply {
            add(indexOf("--eliminate-dead-functions"), "--inline-entry-points-exhaustive")
        }.toList()

        private fun optimizerPasses(profile: SpirvOptimizationProfile): List<String> = when (profile) {
            SpirvOptimizationProfile.DEFAULT -> OPTIMIZER_PASSES
            SpirvOptimizationProfile.NO_SSA_REWRITE -> NO_SSA_REWRITE_PASSES
            SpirvOptimizationProfile.STRUCTURAL_CONVERGENCE -> STRUCTURAL_CONVERGENCE_PASSES
            SpirvOptimizationProfile.STRUCTURAL_CONVERGENCE_NO_SSA -> STRUCTURAL_CONVERGENCE_NO_SSA_PASSES
        }

        private fun crossAdapterPasses(profile: SpirvOptimizationProfile): List<String> = when (profile) {
            SpirvOptimizationProfile.DEFAULT -> CROSS_ADAPTER_PASSES
            SpirvOptimizationProfile.NO_SSA_REWRITE -> NO_SSA_REWRITE_PASSES
            SpirvOptimizationProfile.STRUCTURAL_CONVERGENCE -> STRUCTURAL_CONVERGENCE_PASSES
            SpirvOptimizationProfile.STRUCTURAL_CONVERGENCE_NO_SSA -> STRUCTURAL_CONVERGENCE_NO_SSA_PASSES
        }

        private fun withoutSsaRewrite(passes: List<String>): List<String> =
            passes.filterNot { it == "--ssa-rewrite" }

        private val LOG_NAME_INVALID_CHAR = "[^A-Za-z0-9._-]".toRegex()
    }
}

private fun SpirvInvocation.cacheKey(): SpirvToolCacheKey {
    val inputPath = input.absolutePathString()
    val outputPath = output.absolutePathString()
    return SpirvToolCacheKey(
        tool,
        stage,
        command.map { argument ->
            when (argument) {
                inputPath -> "<input>"
                outputPath -> "<output>"
                else -> argument
            }
        },
        sha256(input),
    )
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xff) }
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
