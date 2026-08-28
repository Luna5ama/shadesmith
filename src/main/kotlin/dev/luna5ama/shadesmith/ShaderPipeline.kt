package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal enum class ShaderProcessingMode {
    SPIRV_ROUND_TRIP,
    PRESERVED_HOST_INTEGRATION,
    PRESERVED_STRUCTURAL,
}

internal data class OptimizedShaderFile(
    val file: ShaderFile,
    val stage: ShaderStage,
    val processingMode: ShaderProcessingMode,
    val textureAccess: TextureAccess,
    val moduleCount: Int,
    val fallbackReason: String? = null,
)

internal data class ShaderPipelineFailure(
    val sourceName: String,
    val stage: String,
    val phase: String,
    val artifactDirectory: String,
    val command: String,
    val detail: String,
)

internal class ShaderPipelineException(
    val failures: List<ShaderPipelineFailure>,
    cause: Throwable,
) : IllegalStateException(
    buildString {
        append("Multiple shader pipeline failures:")
        failures.forEach {
            appendLine()
            append("- ${it.sourceName} [${it.stage}] ${it.phase}: ${it.detail}")
            appendLine()
            append("  Artifacts: ${it.artifactDirectory}")
            if (it.command.isNotEmpty()) {
                appendLine()
                append("  Command: ${it.command}")
            }
        }
    },
    cause,
)

internal class ShaderPipeline(
    artifactDirectory: Path,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val capabilityProvider: (() -> OpenGlSpirvCapabilities)? = null,
) {
    val artifactDirectory: Path = artifactDirectory.toAbsolutePath().normalize()
    private val metrics = PipelineMetrics()
    private val processGate = ExternalProcessGate(parallelism)
    private val capabilities by lazy {
        capabilityProvider?.invoke() ?: OpenGlSpirvCapabilityProbe(
            this.artifactDirectory.resolve("capabilities"),
            processGate = processGate,
            metrics = metrics,
        ).probe()
    }
    private val materializer by lazy {
        ShaderCompilerCopyMaterializer(
            this.artifactDirectory.resolve("compiler-copies"),
            processGate = processGate,
            metrics = metrics,
        )
    }

    init {
        require(parallelism > 0) { "Shader pipeline parallelism must be positive" }
        this.artifactDirectory.createDirectories()
        Files.deleteIfExists(this.artifactDirectory.resolve("failures.tsv"))
        Files.deleteIfExists(this.artifactDirectory.resolve("boundaries.tsv"))
        Files.deleteIfExists(this.artifactDirectory.resolve("performance.tsv"))
    }

    context(ioContext: IOContext)
    fun optimize(inputFiles: List<ShaderFile>): List<OptimizedShaderFile> {
        val startedAt = System.nanoTime()
        val orderedFiles = inputFiles.sortedBy(::sourceName)
        val duplicateNames = orderedFiles.groupingBy(::sourceName).eachCount().filterValues { it > 1 }.keys
        require(duplicateNames.isEmpty()) { "Duplicate shader source names: ${duplicateNames.sorted()}" }
        if (orderedFiles.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(minOf(parallelism, orderedFiles.size))
        val moduleExecutor = Executors.newFixedThreadPool(parallelism)
        val optimizer = SpirvOptimizer(
            artifactDirectory.resolve("round-trip"),
            moduleExecutor = moduleExecutor,
            processGate = processGate,
            metrics = metrics,
        )
        val work = try {
            executor.invokeAll(
                orderedFiles.map { file ->
                    Callable {
                        try {
                            ShaderWork.Success(optimizeFile(file, optimizer))
                        } catch (e: Exception) {
                            ShaderWork.Failure(file, e)
                        }
                    }
                },
            ).map { it.get() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Shader pipeline interrupted", e)
        } finally {
            executor.shutdown()
            moduleExecutor.shutdown()
        }

        val failures = work.filterIsInstance<ShaderWork.Failure>()
        if (failures.isNotEmpty()) {
            val diagnostics = failures.map { describeFailure(it.file, it.exception) }
            writeFailureManifest(diagnostics)
            writePerformanceManifest(orderedFiles.size, emptyList(), System.nanoTime() - startedAt)
            if (failures.size == 1) throw failures.single().exception
            throw ShaderPipelineException(diagnostics, failures.first().exception)
        }

        val optimized = work.filterIsInstance<ShaderWork.Success>().map { it.result }
        writeBoundaryManifest(optimized)
        writePerformanceManifest(orderedFiles.size, optimized, System.nanoTime() - startedAt)
        return optimized
    }

    context(ioContext: IOContext)
    private fun optimizeFile(
        file: ShaderFile,
        optimizer: SpirvOptimizer,
    ): OptimizedShaderFile {
        val sourceName = sourceName(file)
        val entryPoint = ShaderEntryPoint.from(file.path, file.code)
        if (entryPoint.kind == ShaderEntryPointKind.HOST_INTEGRATION_FRAGMENT) {
            return OptimizedShaderFile(
                file = file,
                stage = entryPoint.stage,
                processingMode = ShaderProcessingMode.PRESERVED_HOST_INTEGRATION,
                textureAccess = TextureAccessAnalyzer.fromOptimizedSource(file.code),
                moduleCount = 0,
                fallbackReason = HOST_INTEGRATION_REASON,
            )
        }

        val protection = PreprocessorProtection.protect(file.code, sourceName)
        if (protection.compilerBlockers.isEmpty()) {
            val result = optimizer.optimize(
                SpirvOptimizationRequest(
                    sourceName = sourceName,
                    stage = entryPoint.stage,
                    source = file.code,
                ),
            )
            return OptimizedShaderFile(
                file = file.copy(code = result.source),
                stage = entryPoint.stage,
                processingMode = ShaderProcessingMode.SPIRV_ROUND_TRIP,
                textureAccess = result.modules
                    .map { it.textureAccess }
                    .fold(TextureAccess(), TextureAccess::plus),
                moduleCount = result.modules.size,
            )
        }
        val probe = TextureAccessAnalyzer.createProbe(file.code, ioContext.config)
        val toolCapabilities = capabilities
        val plan = ShaderCompilerCopyPlanner.plan(
            probe.source,
            sourceName,
            toolCapabilities.localSizeId,
            toolCapabilities.diagnostic,
        )
        if (plan.structuralBlockers.isNotEmpty()) {
            val reason = plan.structuralBlockers.joinToString("; ") { "line ${it.sourceLine}: ${it.reason}" }
            return OptimizedShaderFile(
                file = file,
                stage = entryPoint.stage,
                processingMode = ShaderProcessingMode.PRESERVED_STRUCTURAL,
                textureAccess = TextureAccessAnalyzer.fromOptimizedSource(file.code),
                moduleCount = 0,
                fallbackReason = reason,
            )
        }
        val module = materializer.materialize(sourceName, entryPoint.stage, plan, probe)
        val result = optimizer.optimize(
            SpirvOptimizationRequest(
                sourceName = sourceName,
                stage = entryPoint.stage,
                source = file.code,
                compilerModules = listOf(module),
            ),
        )
        return OptimizedShaderFile(
            file = file.copy(code = result.source),
            stage = entryPoint.stage,
            processingMode = ShaderProcessingMode.SPIRV_ROUND_TRIP,
            textureAccess = result.modules
                .map { it.textureAccess }
                .fold(TextureAccess(), TextureAccess::plus),
            moduleCount = result.modules.size,
        )
    }

    private fun describeFailure(file: ShaderFile, exception: Exception): ShaderPipelineFailure {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        val roundTrip = causes.filterIsInstance<SpirvRoundTripException>().firstOrNull()
        val materialization = causes.filterIsInstance<ShaderCompilerCopyException>().firstOrNull()
        val tool = causes.filterIsInstance<SpirvToolException>().firstOrNull()
        val entryPoint = runCatching { ShaderEntryPoint.from(file.path, file.code) }.getOrNull()
        return ShaderPipelineFailure(
            sourceName = roundTrip?.sourceName ?: materialization?.sourceName ?: sourceName(file),
            stage = roundTrip?.stage?.glslangName ?: materialization?.stage?.glslangName ?: entryPoint?.stage?.glslangName.orEmpty(),
            phase = roundTrip?.phase?.displayName ?: if (materialization != null) {
                "compiler-copy materialization"
            } else {
                "pipeline"
            },
            artifactDirectory = (
                roundTrip?.artifactDirectory ?: materialization?.artifactDirectory ?: artifactDirectory
                ).toAbsolutePath().normalize().toString(),
            command = (tool?.invocation?.command ?: materialization?.command.orEmpty())
                .joinToString(" ") { it.asPipelineDiagnosticArgument() },
            detail = exception.message.orEmpty().lineSequence().firstOrNull().orEmpty(),
        )
    }

    private fun writeFailureManifest(failures: List<ShaderPipelineFailure>) {
        val content = buildString {
            appendLine("source\tstage\tphase\tartifacts\tcommand\tdetail")
            failures.forEach {
                append(it.sourceName.asTsvField())
                append('\t')
                append(it.stage.asTsvField())
                append('\t')
                append(it.phase.asTsvField())
                append('\t')
                append(it.artifactDirectory.asTsvField())
                append('\t')
                append(it.command.asTsvField())
                append('\t')
                appendLine(it.detail.asTsvField())
            }
        }
        artifactDirectory.resolve("failures.tsv").writeText(content)
    }

    private fun writeBoundaryManifest(files: List<OptimizedShaderFile>) {
        val boundaries = files.filter { it.processingMode != ShaderProcessingMode.SPIRV_ROUND_TRIP }
        if (boundaries.isEmpty()) return
        val content = buildString {
            appendLine("source\tstage\tsource_sha256\treason")
            boundaries.forEach {
                append(sourceName(it.file).asTsvField())
                append('\t')
                append(it.stage.glslangName)
                append('\t')
                append(sha256(it.file.code))
                append('\t')
                appendLine(requireNotNull(it.fallbackReason).asTsvField())
            }
        }
        artifactDirectory.resolve("boundaries.tsv").writeText(content)
    }

    private fun writePerformanceManifest(
        requestedRoots: Int,
        files: List<OptimizedShaderFile>,
        durationNanos: Long,
    ) {
        val snapshot = metrics.snapshot()
        val content = buildString {
            appendLine("metric\tvalue")
            appendLine("parallelism\t$parallelism")
            appendLine("requested_roots\t$requestedRoots")
            appendLine("completed_roots\t${files.size}")
            appendLine("validated_modules\t${files.sumOf { it.moduleCount }}")
            appendLine("materialized_compiler_modules\t${snapshot.compilerModules}")
            appendLine("clang_processes\t${snapshot.clangProcesses}")
            appendLine("glslang_processes\t${snapshot.glslangProcesses}")
            appendLine("spirv_opt_processes\t${snapshot.spirvOptProcesses}")
            appendLine("spirv_cross_processes\t${snapshot.spirvCrossProcesses}")
            appendLine("external_processes\t${snapshot.externalProcesses}")
            appendLine("duration_ms\t${durationNanos / 1_000_000}")
        }
        artifactDirectory.resolve("performance.tsv").writeText(content)
    }

    private fun sourceName(file: ShaderFile): String = file.path.toString().replace('\\', '/')

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    private sealed interface ShaderWork {
        data class Success(val result: OptimizedShaderFile) : ShaderWork
        data class Failure(val file: ShaderFile, val exception: Exception) : ShaderWork
    }

    companion object {
        private const val DEFAULT_PARALLELISM = 10
        private const val HOST_INTEGRATION_REASON =
            "host integration fragment has no standalone #version/main contract; source preserved and lifecycle access is conservative"
    }
}

private fun String.asTsvField(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

private fun String.asPipelineDiagnosticArgument(): String {
    if (none { it.isWhitespace() || it == '"' }) return this
    return buildString(length + 2) {
        append('"')
        this@asPipelineDiagnosticArgument.forEach {
            if (it == '"') append('\\')
            append(it)
        }
        append('"')
    }
}
