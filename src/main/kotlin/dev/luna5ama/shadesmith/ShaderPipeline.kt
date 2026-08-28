package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
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
    val specializationSettings: List<String> = emptyList(),
    val structuralSignatures: List<ShaderStructuralSignature> = emptyList(),
    val processCount: Int = 0,
    val finalValidationProcessCount: Int = 0,
    val cacheHits: Int = 0,
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
    private val cacheIdentityProvider: () -> String? = { ShaderPipelineCache.runtimeIdentity() },
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
    private val cache by lazy {
        cacheIdentityProvider()?.let { identity ->
            ShaderPipelineCache(artifactDirectory.resolve("cache"), identity)
        }
    }
    private var pendingCachePublications = emptyList<CachePublication>()

    init {
        require(parallelism in 1..MAX_PARALLELISM) {
            "Shader pipeline parallelism must be between 1 and $MAX_PARALLELISM"
        }
        this.artifactDirectory.createDirectories()
        Files.deleteIfExists(this.artifactDirectory.resolve("failures.tsv"))
        Files.deleteIfExists(this.artifactDirectory.resolve("boundaries.tsv"))
        Files.deleteIfExists(this.artifactDirectory.resolve("outputs.tsv"))
        Files.deleteIfExists(this.artifactDirectory.resolve("performance.tsv"))
    }

    context(ioContext: IOContext)
    fun optimize(inputFiles: List<ShaderFile>): List<OptimizedShaderFile> {
        val startedAt = System.nanoTime()
        val orderedFiles = inputFiles.sortedBy(::sourceName)
        val duplicateNames = orderedFiles.groupingBy(::sourceName).eachCount().filterValues { it > 1 }.keys
        require(duplicateNames.isEmpty()) { "Duplicate shader source names: ${duplicateNames.sorted()}" }
        if (orderedFiles.isEmpty()) return emptyList()

        val activeCache = cache
        val executor = Executors.newFixedThreadPool(minOf(parallelism, orderedFiles.size))
        val moduleExecutor = Executors.newFixedThreadPool(parallelism)
        val optimizer = SpirvOptimizer(
            artifactDirectory.resolve("round-trip"),
            moduleExecutor = moduleExecutor,
            processGate = processGate,
            metrics = metrics,
        )
        val work = try {
            orderedFiles.chunked(ROOT_BATCH_SIZE).flatMap { files ->
                processFileChunk(files, activeCache, executor, optimizer)
            }
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
            writePerformanceManifest(
                orderedFiles.size,
                work.filterIsInstance<ShaderWork.Success>().map { it.execution.result },
                cachePublications = 0,
                System.nanoTime() - startedAt,
            )
            if (failures.size == 1) throw failures.single().exception
            throw ShaderPipelineException(diagnostics, failures.first().exception)
        }

        val executions = work.filterIsInstance<ShaderWork.Success>().map { it.execution }
        val optimized = executions.map { it.result }
        val publications = executions.mapNotNull { it.publication }
        writeBoundaryManifest(optimized)
        writeOutputManifest(optimized)
        writePerformanceManifest(
            orderedFiles.size,
            optimized,
            cachePublications = 0,
            System.nanoTime() - startedAt,
        )
        pendingCachePublications = publications
        return optimized
    }

    context(ioContext: IOContext)
    private fun processFileChunk(
        files: List<ShaderFile>,
        activeCache: ShaderPipelineCache?,
        executor: java.util.concurrent.ExecutorService,
        optimizer: SpirvOptimizer,
    ): List<ShaderWork> {
        val preparations = executor.invokeAll(
            files.map { file ->
                Callable {
                    val worker = Thread.currentThread()
                    val previousName = worker.name
                    worker.name = "shadesmith-plan-${sourceName(file).substringAfterLast('/').take(64)}"
                    try {
                        ShaderPreparationWork.Success(prepareFile(file, activeCache))
                    } catch (e: Exception) {
                        ShaderPreparationWork.Failure(file, e)
                    } finally {
                        worker.name = previousName
                    }
                }
            },
        ).map { it.get() }
        val successfulPreparations = preparations.filterIsInstance<ShaderPreparationWork.Success>()
            .map { it.preparation }
        val slots = successfulPreparations.flatMapIndexed { preparationIndex, preparation ->
            preparation.materializationRequests().map { request ->
                MaterializationSlot(preparationIndex, request)
            }
        }
        val materializedByPreparation = if (slots.isEmpty()) {
            emptyMap()
        } else {
            slots.zip(materializer.materializeBatch(slots.map { it.request }))
                .groupBy({ it.first.preparationIndex }, { it.second })
        }
        val executions = executor.invokeAll(
            successfulPreparations.mapIndexed { preparationIndex, preparation ->
                Callable {
                    try {
                        ShaderWork.Success(
                            executePreparation(
                                preparation,
                                materializedByPreparation[preparationIndex].orEmpty(),
                                optimizer,
                            ),
                        )
                    } catch (e: Exception) {
                        ShaderWork.Failure(preparation.file, e)
                    }
                }
            },
        ).map { it.get() }.iterator()
        return preparations.map { preparation ->
            when (preparation) {
                is ShaderPreparationWork.Success -> executions.next()
                is ShaderPreparationWork.Failure -> ShaderWork.Failure(preparation.file, preparation.exception)
            }
        }
    }

    fun publishCache() {
        val publications = pendingCachePublications
        if (publications.isEmpty()) return
        val activeCache = requireNotNull(cache)
        publications.forEach { publication ->
            activeCache.store(publication.key, publication.shader)
        }
        pendingCachePublications = emptyList()
        val performance = artifactDirectory.resolve("performance.tsv")
        val content = performance.readText()
        performance.writeText(
            content.replace(
                "cache_publications\t0\n",
                "cache_publications\t${publications.size}\n",
            ),
        )
    }

    context(ioContext: IOContext)
    private fun prepareFile(
        file: ShaderFile,
        activeCache: ShaderPipelineCache?,
    ): ShaderPreparation {
        val sourceName = sourceName(file)
        val entryPoint = ShaderEntryPoint.from(file.path, file.code)
        if (entryPoint.kind == ShaderEntryPointKind.HOST_INTEGRATION_FRAGMENT) {
            return ShaderPreparation.Completed(
                file,
                entryPoint.stage,
                OptimizedShaderFile(
                    file = file,
                    stage = entryPoint.stage,
                    processingMode = ShaderProcessingMode.PRESERVED_HOST_INTEGRATION,
                    textureAccess = TextureAccessAnalyzer.fromOptimizedSource(file.code),
                    moduleCount = 0,
                    fallbackReason = HOST_INTEGRATION_REASON,
                ),
            )
        }
        val decision = lookupCache(activeCache, file, entryPoint.stage, ROOT_DERIVED_PLAN_CONTRACT)
        decision.hit?.let { return ShaderPreparation.Completed(file, entryPoint.stage, it, decision.binding) }

        val protection = PreprocessorProtection.protect(file.code, sourceName)
        if (protection.compilerBlockers.isEmpty()) {
            return ShaderPreparation.Direct(file, entryPoint.stage, decision.binding)
        }
        val probe = TextureAccessAnalyzer.createProbe(file.code, ioContext.config)
        val toolCapabilities = capabilities
        val plan = ShaderCompilerCopyPlanner.plan(
            probe.source,
            sourceName,
            toolCapabilities.localSizeId,
            toolCapabilities.diagnostic,
        )
        if (!ShaderStructuralPlanner.requiresPlanning(plan)) {
            return ShaderPreparation.CompilerCopy(
                file,
                entryPoint.stage,
                plan,
                probe,
                decision.binding,
            )
        }
        return when (val structural = ShaderStructuralPlanner.plan(plan, entryPoint.stage)) {
            is ShaderStructuralPlanningResult.Preserved -> {
                ShaderPreparation.Completed(
                    file,
                    entryPoint.stage,
                    preservedStructural(file, entryPoint.stage, structural.reason),
                    decision.binding,
                )
            }
            is ShaderStructuralPlanningResult.Planned -> {
                ShaderPreparation.Structural(
                    file,
                    entryPoint.stage,
                    structural.plan,
                    probe,
                    decision.binding,
                )
            }
        }
    }

    private fun executePreparation(
        preparation: ShaderPreparation,
        materializations: List<ShaderCompilerCopyMaterialization>,
        optimizer: SpirvOptimizer,
    ): ShaderExecution {
        val result = when (preparation) {
            is ShaderPreparation.Completed -> preparation.result
            is ShaderPreparation.Direct -> optimizedFile(
                preparation.file,
                preparation.stage,
                optimizer.optimize(
                    SpirvOptimizationRequest(
                        sourceName = sourceName(preparation.file),
                        stage = preparation.stage,
                        source = preparation.file.code,
                    ),
                ),
            )
            is ShaderPreparation.CompilerCopy -> {
                require(materializations.size == 1) {
                    "${sourceName(preparation.file)} compiler-copy materialization count changed"
                }
                val module = materializations.single().moduleOrThrow()
                optimizedFile(
                    preparation.file,
                    preparation.stage,
                    optimizer.optimize(
                        SpirvOptimizationRequest(
                            sourceName = sourceName(preparation.file),
                            stage = preparation.stage,
                            source = preparation.file.code,
                            compilerModules = listOf(module),
                        ),
                    ),
                )
            }
            is ShaderPreparation.Structural -> {
                val candidates = materializations.map { it.moduleOrThrow() }
                when (val finalized = preparation.plan.deduplicate(candidates)) {
                    is ShaderStructuralMaterializationResult.Preserved -> {
                        preservedStructural(preparation.file, preparation.stage, finalized.reason)
                    }
                    is ShaderStructuralMaterializationResult.Materialized -> {
                        try {
                            optimizedFile(
                                preparation.file,
                                preparation.stage,
                                optimizer.optimize(
                                    SpirvOptimizationRequest(
                                        sourceName = sourceName(preparation.file),
                                        stage = preparation.stage,
                                        source = preparation.file.code,
                                        compilerModules = finalized.modules.map { it.module },
                                        structuralPlan = preparation.plan,
                                    ),
                                ),
                            )
                        } catch (e: SpirvRoundTripException) {
                            if (!recoverableStructuralRoundTrip(e)) throw e
                            preservedStructural(
                                preparation.file,
                                preparation.stage,
                                structuralRoundTripFallbackReason(preparation.file, e),
                            )
                        }
                    }
                }
            }
        }
        val publication = preparation.binding?.takeIf { result.cacheHits == 0 }?.let { binding ->
            CachePublication(binding.key, cachedShader(preparation.file, binding, result))
        }
        return ShaderExecution(result, publication)
    }

    context(ioContext: IOContext)
    private fun lookupCache(
        activeCache: ShaderPipelineCache?,
        file: ShaderFile,
        stage: ShaderStage,
        planContract: String,
    ): CacheDecision {
        if (activeCache == null) return CacheDecision()
        val key = activeCache.key(
            sourceName(file),
            stage,
            file.code,
            ioContext.config,
            planContract,
        )
        val binding = CacheBinding(key, ShaderPipelineCache.contentHash(planContract))
        return when (val lookup = activeCache.load(key)) {
            ShaderPipelineCacheLookup.Miss -> {
                metrics.recordCacheMiss()
                CacheDecision(binding)
            }
            ShaderPipelineCacheLookup.Invalid -> {
                metrics.recordCacheInvalidEntry()
                metrics.recordCacheMiss()
                CacheDecision(binding)
            }
            is ShaderPipelineCacheLookup.Hit -> {
                val cached = restoreCached(file, stage, binding, lookup.shader)
                if (cached == null) {
                    metrics.recordCacheInvalidEntry()
                    metrics.recordCacheMiss()
                    CacheDecision(binding)
                } else {
                    metrics.recordCacheHit()
                    CacheDecision(binding, cached)
                }
            }
        }
    }

    private fun restoreCached(
        file: ShaderFile,
        stage: ShaderStage,
        binding: CacheBinding,
        cached: CachedOptimizedShader,
    ): OptimizedShaderFile? {
        if (cached.sourceName != sourceName(file)) return null
        if (cached.stage != stage.name) return null
        if (cached.inputSourceSha256 != ShaderPipelineCache.contentHash(file.code)) return null
        if (cached.planSha256 != binding.planSha256) return null
        return runCatching {
            OptimizedShaderFile(
                file = file.copy(code = cached.source),
                stage = stage,
                processingMode = ShaderProcessingMode.valueOf(cached.processingMode),
                textureAccess = TextureAccess(cached.reads.toSet(), cached.writes.toSet()),
                moduleCount = cached.moduleCount,
                fallbackReason = cached.fallbackReason,
                specializationSettings = cached.specializationSettings,
                structuralSignatures = cached.structuralSignatures.map(CachedStructuralSignature::restore),
                processCount = 0,
                finalValidationProcessCount = 0,
                cacheHits = 1,
            )
        }.getOrNull()
    }

    private fun cachedShader(
        input: ShaderFile,
        binding: CacheBinding,
        result: OptimizedShaderFile,
    ): CachedOptimizedShader {
        return CachedOptimizedShader(
            cacheKey = binding.key,
            sourceName = sourceName(input),
            inputSourceSha256 = ShaderPipelineCache.contentHash(input.code),
            planSha256 = binding.planSha256,
            source = result.file.code,
            stage = result.stage.name,
            processingMode = result.processingMode.name,
            reads = result.textureAccess.reads.sorted(),
            writes = result.textureAccess.writes.sorted(),
            moduleCount = result.moduleCount,
            fallbackReason = result.fallbackReason,
            specializationSettings = result.specializationSettings.distinct().sorted(),
            structuralSignatures = result.structuralSignatures.map(CachedStructuralSignature::from),
        )
    }

    private fun ShaderPreparation.materializationRequests(): List<ShaderCompilerCopyMaterializationRequest> {
        return when (this) {
            is ShaderPreparation.Completed,
            is ShaderPreparation.Direct,
            -> emptyList()
            is ShaderPreparation.CompilerCopy -> listOf(
                ShaderCompilerCopyMaterializationRequest(
                    sourceName(file),
                    stage,
                    plan,
                    probe,
                ),
            )
            is ShaderPreparation.Structural -> plan.materializationRows().map { row ->
                ShaderCompilerCopyMaterializationRequest(
                    sourceName(file),
                    stage,
                    row.compilerPlan,
                    probe,
                    row.name,
                )
            }
        }
    }

    private fun ShaderCompilerCopyMaterialization.moduleOrThrow(): SpirvCompilerModule {
        return when (this) {
            is ShaderCompilerCopyMaterialization.Success -> module
            is ShaderCompilerCopyMaterialization.Failure -> throw exception
        }
    }

    private fun optimizedFile(
        file: ShaderFile,
        stage: ShaderStage,
        result: SpirvOptimizationResult,
    ): OptimizedShaderFile {
        return OptimizedShaderFile(
            file = file.copy(code = result.source),
            stage = stage,
            processingMode = if (result.emissionMode == SpirvEmissionMode.OPTIMIZED) {
                ShaderProcessingMode.SPIRV_ROUND_TRIP
            } else {
                ShaderProcessingMode.PRESERVED_STRUCTURAL
            },
            textureAccess = result.modules
                .map { it.textureAccess }
                .fold(TextureAccess(), TextureAccess::plus),
            moduleCount = result.modules.size,
            fallbackReason = result.fallbackReason,
            specializationSettings = result.specializationSettings,
            structuralSignatures = result.structuralSignatures,
            processCount = result.processCount,
            finalValidationProcessCount = result.finalValidationInvocations.size,
            cacheHits = result.cacheHits,
        )
    }

    private fun preservedStructural(
        file: ShaderFile,
        stage: ShaderStage,
        reason: String,
    ): OptimizedShaderFile {
        return OptimizedShaderFile(
            file = file,
            stage = stage,
            processingMode = ShaderProcessingMode.PRESERVED_STRUCTURAL,
            textureAccess = TextureAccessAnalyzer.fromOptimizedSource(file.code),
            moduleCount = 0,
            fallbackReason = reason,
        )
    }

    private fun recoverableStructuralRoundTrip(exception: SpirvRoundTripException): Boolean {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        if (causes.any { it is InterruptedException }) return false
        val tool = causes.filterIsInstance<SpirvToolException>().firstOrNull()
        return tool?.exitCode != null || tool == null
    }

    private fun structuralRoundTripFallbackReason(
        file: ShaderFile,
        exception: SpirvRoundTripException,
    ): String {
        val detail = generateSequence<Throwable>(exception) { it.cause }
            .last()
            .message
            .orEmpty()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
        return "${sourceName(file)}: structural module round-trip failed closed during " +
            "${exception.phase.displayName}: $detail"
    }

    private fun describeFailure(file: ShaderFile, exception: Exception): ShaderPipelineFailure {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        val roundTrip = causes.filterIsInstance<SpirvRoundTripException>().firstOrNull()
        val materialization = causes.filterIsInstance<ShaderCompilerCopyException>().firstOrNull()
        val tool = causes.filterIsInstance<SpirvToolException>().firstOrNull()
        val entryPoint = runCatching { ShaderEntryPoint.from(file.path, file.code) }.getOrNull()
        val pipelineArtifacts = if (roundTrip == null && materialization == null) {
            writePipelineFailureArtifacts(file, exception)
        } else {
            null
        }
        return ShaderPipelineFailure(
            sourceName = roundTrip?.sourceName ?: materialization?.sourceName ?: sourceName(file),
            stage = roundTrip?.stage?.glslangName ?: materialization?.stage?.glslangName ?: entryPoint?.stage?.glslangName.orEmpty(),
            phase = roundTrip?.phase?.displayName ?: if (materialization != null) {
                "compiler-copy materialization"
            } else {
                "pipeline"
            },
            artifactDirectory = (
                roundTrip?.artifactDirectory ?: materialization?.artifactDirectory ?: pipelineArtifacts ?: artifactDirectory
                ).toAbsolutePath().normalize().toString(),
            command = (tool?.invocation?.command ?: materialization?.command.orEmpty())
                .joinToString(" ") { it.asPipelineDiagnosticArgument() },
            detail = exception.message.orEmpty().lineSequence().firstOrNull().orEmpty(),
        )
    }

    private fun writePipelineFailureArtifacts(file: ShaderFile, exception: Exception): Path {
        val directory = artifactDirectory.resolve("pipeline-failures").resolve(
            sourceName(file).replace("[^A-Za-z0-9._-]".toRegex(), "_").take(80) + "-" + sha256(file.code).take(16),
        )
        directory.createDirectories()
        directory.resolve("input.glsl").writeText(file.code)
        directory.resolve("error.txt").writeText(exception.stackTraceToString())
        return directory
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

    private fun writeOutputManifest(files: List<OptimizedShaderFile>) {
        val content = buildString {
            appendLine(
                "source\tstage\tdisposition\tsource_sha256\tsettings\tstructural_signatures\tfallback\t" +
                    "modules\tlifecycle_reads\tlifecycle_writes",
            )
            files.forEach { file ->
                append(sourceName(file.file).asTsvField())
                append('\t')
                append(file.stage.glslangName)
                append('\t')
                append(file.processingMode.name)
                append('\t')
                append(sha256(file.file.code))
                append('\t')
                append(file.specializationSettings.joinToString(",").asTsvField())
                append('\t')
                append(file.structuralSignatures.joinToString(" || ") { it.canonical.replace('\n', ' ') }.asTsvField())
                append('\t')
                append(file.fallbackReason.orEmpty().asTsvField())
                append('\t')
                append(file.moduleCount)
                append('\t')
                append(file.textureAccess.reads.sorted().joinToString(",").asTsvField())
                append('\t')
                appendLine(file.textureAccess.writes.sorted().joinToString(",").asTsvField())
            }
        }
        artifactDirectory.resolve("outputs.tsv").writeText(content)
    }

    private fun writePerformanceManifest(
        requestedRoots: Int,
        files: List<OptimizedShaderFile>,
        cachePublications: Int,
        durationNanos: Long,
    ) {
        val snapshot = metrics.snapshot()
        val content = buildString {
            appendLine("metric\tvalue")
            appendLine("parallelism\t$parallelism")
            appendLine("requested_roots\t$requestedRoots")
            appendLine("completed_roots\t${files.size}")
            appendLine("validated_modules\t${files.sumOf { it.moduleCount }}")
            appendLine("ordinary_setting_variants\t0")
            appendLine("shader_processes\t${files.sumOf { it.processCount }}")
            appendLine("final_validation_processes\t${files.sumOf { it.finalValidationProcessCount }}")
            appendLine("cache_enabled\t${if (cache == null) 0 else 1}")
            appendLine("cache_hits\t${snapshot.cacheHits}")
            appendLine("cache_misses\t${snapshot.cacheMisses}")
            appendLine("cache_invalid_entries\t${snapshot.cacheInvalidEntries}")
            appendLine("cache_publications\t$cachePublications")
            appendLine("materialized_compiler_modules\t${snapshot.compilerModules}")
            appendLine("root_batch_size\t$ROOT_BATCH_SIZE")
            appendLine("clang_batch_size\t${ShaderCompilerCopyMaterializer.CLANG_BATCH_SIZE}")
            appendLine("clang_processes\t${snapshot.clangProcesses}")
            appendLine("glslang_processes\t${snapshot.glslangProcesses}")
            appendLine("spirv_opt_processes\t${snapshot.spirvOptProcesses}")
            appendLine("spirv_cross_processes\t${snapshot.spirvCrossProcesses}")
            appendLine("tool_cache_hits\t${snapshot.toolCacheHits}")
            appendLine("external_processes\t${snapshot.externalProcesses}")
            appendLine("peak_external_process_concurrency\t${processGate.peakConcurrency}")
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

    private sealed interface ShaderPreparation {
        val file: ShaderFile
        val stage: ShaderStage
        val binding: CacheBinding?

        data class Completed(
            override val file: ShaderFile,
            override val stage: ShaderStage,
            val result: OptimizedShaderFile,
            override val binding: CacheBinding? = null,
        ) : ShaderPreparation

        data class Direct(
            override val file: ShaderFile,
            override val stage: ShaderStage,
            override val binding: CacheBinding?,
        ) : ShaderPreparation

        data class CompilerCopy(
            override val file: ShaderFile,
            override val stage: ShaderStage,
            val plan: ShaderCompilerCopyPlan,
            val probe: TextureAccessProbe,
            override val binding: CacheBinding?,
        ) : ShaderPreparation

        data class Structural(
            override val file: ShaderFile,
            override val stage: ShaderStage,
            val plan: ShaderStructuralCoveragePlan,
            val probe: TextureAccessProbe,
            override val binding: CacheBinding?,
        ) : ShaderPreparation
    }

    private sealed interface ShaderPreparationWork {
        data class Success(val preparation: ShaderPreparation) : ShaderPreparationWork
        data class Failure(val file: ShaderFile, val exception: Exception) : ShaderPreparationWork
    }

    private sealed interface ShaderWork {
        data class Success(val execution: ShaderExecution) : ShaderWork
        data class Failure(val file: ShaderFile, val exception: Exception) : ShaderWork
    }

    private data class ShaderExecution(
        val result: OptimizedShaderFile,
        val publication: CachePublication?,
    )

    private data class CacheDecision(
        val binding: CacheBinding? = null,
        val hit: OptimizedShaderFile? = null,
    )

    private data class CacheBinding(
        val key: String,
        val planSha256: String,
    )

    private data class CachePublication(
        val key: String,
        val shader: CachedOptimizedShader,
    )

    private data class MaterializationSlot(
        val preparationIndex: Int,
        val request: ShaderCompilerCopyMaterializationRequest,
    )

    companion object {
        private const val DEFAULT_PARALLELISM = 10
        private const val MAX_PARALLELISM = 10
        private const val ROOT_BATCH_SIZE = 2
        private const val PIPELINE_CACHE_CONTRACT = "shadesmith-compiler-copy-structural-round-trip-v1"
        private const val ROOT_DERIVED_PLAN_CONTRACT = "$PIPELINE_CACHE_CONTRACT\nroot-derived-plan-v1"
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
