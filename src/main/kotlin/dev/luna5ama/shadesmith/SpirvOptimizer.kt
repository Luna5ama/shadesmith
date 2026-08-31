package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class SpirvCompilerModule(
    val name: String,
    val source: String,
    val resourceMarkers: List<TextureResourceMarker> = emptyList(),
    val conservativeAccess: TextureAccess = TextureAccess(),
    val irisContracts: IrisShaderContractPlan? = null,
    val settings: List<ShaderSetting> = emptyList(),
    val structuralSignature: ShaderStructuralSignature? = null,
    val structuralAssignment: Map<String, String> = emptyMap(),
    val structuralAssignments: List<Map<String, String>> = emptyList(),
    val tokenPasteLowerings: List<ShaderTokenPasteLowering> = emptyList(),
    val derivedScalarExpressions: Map<String, String> = emptyMap(),
)

internal data class SpirvOptimizationRequest(
    val sourceName: String,
    val stage: ShaderStage,
    val source: String,
    val compilerModules: List<SpirvCompilerModule> = emptyList(),
    val structuralPlan: ShaderStructuralCoveragePlan? = null,
    val finalSourcePolicy: IrisFinalSourcePolicy? = null,
)

internal enum class SpirvEmissionMode {
    OPTIMIZED,
    PRESERVED_SOURCE,
}

internal fun restoreMissingCompilerMacros(source: String, contractSource: String): String {
    val values = resolveIntegerObjectMacros(contractSource)
    val aliases = resolveCompilerObjectAliases(contractSource)
    if (values.isEmpty() && aliases.isEmpty()) return source
    val defined = COMPILER_MACRO_DEFINITION.findAll(source).mapTo(hashSetOf()) { it.groupValues[1] }
    val referenced = source.lineSequence().filterNot { it.trimStart().startsWith('#') }
        .flatMap { line -> COMPILER_IDENTIFIER.findAll(line.substringBefore("//")).map(MatchResult::value) }
        .filterTo(sortedSetOf()) { (it in values || it in aliases) && it !in defined && !it.startsWith("SM_") }
    if (referenced.isEmpty()) return source
    val version = COMPILER_VERSION_LINE.find(source)
        ?: throw IllegalArgumentException("final compiler copy has no #version directive")
    var offset = version.range.last + 1
    if (source.getOrNull(offset) == '\r') offset++
    if (source.getOrNull(offset) == '\n') offset++
    val bridges = referenced.joinToString("") { name ->
        val replacement = values[name]?.toString() ?: aliases.getValue(name)
        "#ifndef $name\n#define $name $replacement\n#endif\n"
    }
    return source.substring(0, offset) + bridges + source.substring(offset)
}

internal fun restoreMissingCompilerScalarMacros(source: String, contractSource: String): String {
    val integers = resolveIntegerObjectMacros(contractSource).mapValues { it.value.toString() }
    val literals = COMPILER_OBJECT_MACRO.findAll(contractSource).groupBy(
        { it.groupValues[1] },
        { it.groupValues[2].substringBefore("//").trim() },
    ).mapNotNull { (name, bodies) ->
        bodies.distinct().singleOrNull()?.takeIf(COMPILER_SCALAR_LITERAL::matches)?.let { name to it }
    }.toMap()
    val replacements = literals + integers
    if (replacements.isEmpty()) return source
    val defined = COMPILER_MACRO_DEFINITION.findAll(source).mapTo(hashSetOf()) { it.groupValues[1] }
    val referenced = source.lineSequence().filterNot { it.trimStart().startsWith('#') }
        .flatMap { line -> COMPILER_IDENTIFIER.findAll(line.substringBefore("//")).map(MatchResult::value) }
        .filterTo(sortedSetOf()) { it in replacements && it !in defined && !it.startsWith("SM_") }
    if (referenced.isEmpty()) return source
    val version = COMPILER_VERSION_LINE.find(source)
        ?: throw IllegalArgumentException("final compiler copy has no #version directive")
    var offset = version.range.last + 1
    if (source.getOrNull(offset) == '\r') offset++
    if (source.getOrNull(offset) == '\n') offset++
    val bridges = referenced.joinToString("") { name ->
        "#ifndef $name\n#define $name ${replacements.getValue(name)}\n#endif\n"
    }
    return source.substring(0, offset) + bridges + source.substring(offset)
}

internal fun restoreMissingCompilerDerivedMacros(source: String, replacements: Map<String, String>): String {
    if (replacements.isEmpty()) return source
    val defined = COMPILER_MACRO_DEFINITION.findAll(source).mapTo(hashSetOf()) { it.groupValues[1] }
    val referenced = source.lineSequence().filterNot { it.trimStart().startsWith('#') }
        .flatMap { line -> COMPILER_IDENTIFIER.findAll(line.substringBefore("//")).map(MatchResult::value) }
        .filterTo(sortedSetOf()) { it in replacements && it !in defined }
    if (referenced.isEmpty()) return source
    val version = COMPILER_VERSION_LINE.find(source)
        ?: throw IllegalArgumentException("final compiler copy has no #version directive")
    var offset = version.range.last + 1
    if (source.getOrNull(offset) == '\r') offset++
    if (source.getOrNull(offset) == '\n') offset++
    val bridges = referenced.joinToString("") { name ->
        "#ifndef $name\n#define $name (${replacements.getValue(name)})\n#endif\n"
    }
    return source.substring(0, offset) + bridges + source.substring(offset)
}

internal fun compilerDerivedScalarExpressions(contractPlan: ShaderCompilerCopyPlan): Map<String, String> {
    val controls = contractPlan.derivedControls
        .filter { it.kind == ShaderDerivedControlKind.SCALAR && "defined" !in it.compilerExpression }
        .associateBy { it.name }
    if (controls.isEmpty()) return emptyMap()
    val settingNames = contractPlan.settings.associate { setting ->
        setting.name to if (setting.controlKind == ShaderControlKind.HOST_PRESENCE) {
            "SM_HOST_${setting.name}"
        } else {
            "SM_${setting.name}"
        }
    }
    fun render(name: String, visiting: Set<String>): String? {
        if (name in visiting) return null
        var expression = controls[name]?.compilerExpression ?: return null
        val replacements = linkedMapOf<String, String>()
        replacements.putAll(settingNames)
        controls.keys.filter { it != name && COMPILER_IDENTIFIER.findAll(expression).any { match -> match.value == it } }
            .forEach { dependency ->
                render(dependency, visiting + name)?.let { replacements[dependency] = "($it)" }
                    ?: return null
            }
        replacements.entries.sortedByDescending { it.key.length }.forEach { (token, replacement) ->
            expression = compilerIdentifier(token).replace(expression, replacement)
        }
        return expression
    }
    return controls.keys.sorted().mapNotNull { name -> render(name, emptySet())?.let { name to it } }.toMap()
}

internal fun mergeDerivedScalarExpressions(
    sourceName: String,
    modules: List<SpirvModuleResult>,
): Map<String, String> {
    return modules.flatMap { module -> module.derivedScalarExpressions.entries }
        .groupBy(Map.Entry<String, String>::key, Map.Entry<String, String>::value)
        .toSortedMap()
        .mapValues { (name, expressions) ->
            val distinct = expressions.distinct()
            require(distinct.size == 1) {
                "$sourceName: derived macro $name differs across structural modules"
            }
            distinct.single()
        }
}

private fun resolveCompilerObjectAliases(source: String): Map<String, String> {
    val abiSymbols = COMPILER_ABI_DECLARATION.findAll(source).mapTo(hashSetOf()) { it.groupValues[1] }
    val definitions = COMPILER_OBJECT_MACRO.findAll(source).groupBy(
        { it.groupValues[1] },
        { it.groupValues[2].substringBefore("//").trim() },
    ).mapNotNull { (name, bodies) ->
        val body = bodies.distinct().singleOrNull() ?: return@mapNotNull null
        body.takeIf(COMPILER_IDENTIFIER::matches)?.let { name to it }
    }.toMap()
    return definitions.mapNotNull { (name, initial) ->
        var target = initial
        val visited = linkedSetOf(name)
        while (target in definitions) {
            if (!visited.add(target)) return@mapNotNull null
            target = definitions.getValue(target)
        }
        (name to target).takeIf { COMPILER_IDENTIFIER.matches(target) && target in abiSymbols }
    }.toMap()
}

private val COMPILER_MACRO_DEFINITION =
    "(?m)^[\\t ]*#define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)\\b".toRegex()
private val COMPILER_OBJECT_MACRO =
    "(?m)^[\\t ]*#define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([^\\r\\n]+)$".toRegex()
private val COMPILER_ABI_DECLARATION = Regex(
    "(?m)^[\\t ]*(?:layout[\\t ]*\\([^\\r\\n)]*\\)[\\t ]*)?" +
        "(?:(?:readonly|writeonly|coherent|volatile|restrict|flat|smooth|highp|mediump|lowp)[\\t ]+)*" +
        "(?:uniform|buffer|in|out)[\\t ]+(?:[A-Za-z_][A-Za-z0-9_]*[\\t ]+)+" +
        "([A-Za-z_][A-Za-z0-9_]*)[\\t ]*(?:[;\\[{])",
)
private val COMPILER_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
private val COMPILER_SCALAR_LITERAL =
    "[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[fFuU]?".toRegex()
private val COMPILER_VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
private fun compilerIdentifier(name: String): Regex =
    "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()

internal data class SpirvModuleResult(
    val name: String,
    val source: String,
    val coreSource: String,
    val liveSource: String,
    val bridgeSettings: List<ShaderSetting>,
    val derivedScalarExpressions: Map<String, String>,
    val structuralSignature: ShaderStructuralSignature?,
    val structuralAssignment: Map<String, String>,
    val structuralAssignments: List<Map<String, String>>,
    val irisContracts: IrisShaderContractPlan,
    val originalContract: ShaderAbiContract,
    val generatedLayouts: List<GeneratedShaderLayout>,
    val restorationFailure: String?,
    val tokenPasteLowerings: List<ShaderTokenPasteLowering>,
    val resourceMarkers: List<TextureResourceMarker> = emptyList(),
    val textureAccess: TextureAccess,
    val artifactDirectory: Path,
    val originalSpirv: Path,
    val optimizedSpirv: Path,
    val validationSpirv: Path,
    val invocations: List<SpirvInvocation>,
) {
    val originalSpirvSize: Long
        get() = Files.size(originalSpirv)

    val optimizedSpirvSize: Long
        get() = Files.size(optimizedSpirv)
}

internal data class SpirvOptimizationResult(
    val source: String,
    val emissionMode: SpirvEmissionMode,
    val fallbackReason: String?,
    val specializationSettings: List<String>,
    val structuralSignatures: List<ShaderStructuralSignature>,
    val finalValidationInvocations: List<SpirvInvocation>,
    val cacheHits: Int,
    val artifactDirectory: Path,
    val modules: List<SpirvModuleResult>,
    val optimizedEntities: Int,
    val restoredEntities: Int,
    val restoredBytes: Int,
) {
    val compilerModuleCount: Int
        get() = modules.map { module ->
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(module.originalSpirv)).toList()
        }.distinct().size

    val processCount: Int
        get() = modules.sumOf { it.invocations.size } + finalValidationInvocations.size
}

internal enum class SpirvRoundTripPhase(val displayName: String) {
    PROTECT("preprocessor protection"),
    COMPILER_COPY("compiler-copy materialization"),
    PATCH_INPUT("OpenGL input patching"),
    COMPILE("OpenGL SPIR-V compilation"),
    OPTIMIZE("SPIR-V optimization"),
    DECOMPILE("SPIR-V decompilation"),
    RESTORE("GLSL restoration"),
    VALIDATE("restored-source validation"),
    RECOMPILE("restored OpenGL recompilation"),
}

internal class SpirvRoundTripException(
    val sourceName: String,
    val stage: ShaderStage,
    val phase: SpirvRoundTripPhase,
    val artifactDirectory: Path,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append(sourceName)
        append(" [")
        append(stage.glslangName)
        append("] failed during ")
        append(phase.displayName)
        append(": ")
        append(detail)
        appendLine()
        append("Artifacts: ")
        append(artifactDirectory.toAbsolutePath().normalize())
    },
    cause,
)

internal class SpirvOptimizer(
    workingDirectory: Path,
    private val executables: SpirvExecutables = SpirvExecutables(),
    private val processRunner: SpirvProcessRunner? = null,
    private val patcher: OpenGlShaderPatcher = OpenGlShaderPatcher(),
    private val moduleExecutor: ExecutorService? = null,
    private val processGate: ExternalProcessGate? = null,
    private val metrics: PipelineMetrics? = null,
) {
    val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()
    private val toolResultCache = SpirvToolResultCache()

    init {
        this.workingDirectory.createDirectories()
    }

    fun optimize(request: SpirvOptimizationRequest): SpirvOptimizationResult {
        require(request.sourceName.isNotBlank()) { "Shader source name cannot be blank" }
        val requestDirectory = artifactDirectory(request.sourceName, request.stage, "request", request.source)
        requestDirectory.createDirectories()
        requestDirectory.resolve("original.glsl").writeText(request.source)

        val protection = phase(request, SpirvRoundTripPhase.PROTECT, requestDirectory) {
            PreprocessorProtection.protect(request.source, request.sourceName)
        }
        val explicitModules = request.compilerModules.isNotEmpty()
        val modules = if (explicitModules) {
            validateModules(request, requestDirectory)
            request.compilerModules
        } else {
            if (protection.compilerBlockers.isNotEmpty()) {
                val blocker = protection.compilerBlockers.first()
                fail(
                    request,
                    SpirvRoundTripPhase.COMPILER_COPY,
                    requestDirectory,
                    "${blocker.reason} at line ${blocker.sourceLine}; no compiler-copy module was supplied",
                )
            }
            listOf(SpirvCompilerModule("main", request.source))
        }
        val results = optimizeModules(request, modules, requestDirectory, explicitModules)
        val emission = phase(request, SpirvRoundTripPhase.RESTORE, requestDirectory) {
            SpirvFinalEmitter.emit(request, results)
        }
        val restorationDiagnostic = requestDirectory.resolve("native-primitive-restorations.txt")
        if (emission.restorationDiagnostics.isEmpty()) {
            Files.deleteIfExists(restorationDiagnostic)
        } else {
            restorationDiagnostic.writeText(emission.restorationDiagnostics.joinToString("\n", postfix = "\n"))
        }
        val finalValidationInvocations = if (emission.mode == SpirvEmissionMode.OPTIMIZED) {
            if (request.structuralPlan == null) {
                listOf(validateFinalSource(request, emission.source, results.single(), requestDirectory))
            } else {
                validateFinalStructuralSource(
                    request,
                    emission.source,
                    results,
                    request.structuralPlan,
                    requestDirectory,
                )
            }
        } else {
            emptyList()
        }
        requestDirectory.resolve(
            if (emission.mode == SpirvEmissionMode.OPTIMIZED) "optimized.glsl" else "preserved.glsl",
        ).writeText(emission.source)
        emission.fallbackReason?.let { reason ->
            requestDirectory.resolve("fallback-reason.txt").writeText(reason.trimEnd() + "\n")
        }
        val emittedResults = if (results.size == 1 && request.structuralPlan == null) {
            listOf(results.single().copy(source = emission.source))
        } else {
            results
        }
        return SpirvOptimizationResult(
            source = emission.source,
            emissionMode = emission.mode,
            fallbackReason = emission.fallbackReason,
            specializationSettings = modules.flatMap { it.settings }.map { it.name }.distinct().sorted(),
            structuralSignatures = results.mapNotNull { it.structuralSignature }.distinct(),
            finalValidationInvocations = finalValidationInvocations,
            cacheHits = 0,
            artifactDirectory = requestDirectory,
            modules = emittedResults,
            optimizedEntities = emission.optimizedEntities,
            restoredEntities = emission.restoredEntities,
            restoredBytes = emission.restoredBytes,
        )
    }

    private fun optimizeModules(
        request: SpirvOptimizationRequest,
        modules: List<SpirvCompilerModule>,
        requestDirectory: Path,
        generatedCompilerSource: Boolean,
    ): List<SpirvModuleResult> {
        val executor = moduleExecutor ?: return modules.map { module ->
            optimizeModule(request, module, requestDirectory, generatedCompilerSource)
        }
        val completion = ExecutorCompletionService<IndexedValue<SpirvModuleResult>>(executor)
        val futures = modules.mapIndexed { index, module ->
            completion.submit(
                Callable {
                    IndexedValue(index, optimizeModule(request, module, requestDirectory, generatedCompilerSource))
                },
            )
        }
        val results = MutableList<SpirvModuleResult?>(modules.size) { null }
        return try {
            repeat(modules.size) {
                val future = completion.take()
                try {
                    val result = future.get()
                    results[result.index] = result.value
                } catch (e: ExecutionException) {
                    futures.filterNot { it.isDone }.forEach { it.cancel(true) }
                    val cause = e.cause
                    when (cause) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> throw IllegalStateException("Shader compiler-module task failed", cause)
                    }
                }
            }
            results.map { requireNotNull(it) }
        } catch (e: InterruptedException) {
            futures.filterNot { it.isDone }.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw IllegalStateException("Shader compiler-module processing interrupted", e)
        }
    }

    private fun optimizeModule(
        request: SpirvOptimizationRequest,
        module: SpirvCompilerModule,
        requestDirectory: Path,
        generatedCompilerSource: Boolean,
    ): SpirvModuleResult {
        val moduleDirectory = requestDirectory.resolve(
            "${safeName(module.name)}-${shortHash("${module.name}\u0000${module.source}")}",
        )
        moduleDirectory.createDirectories()
        MODULE_ARTIFACT_NAMES.forEach { Files.deleteIfExists(moduleDirectory.resolve(it)) }
        val originalPath = moduleDirectory.resolve("input.glsl")
        originalPath.writeText(module.source)

        val moduleSourceName = if (module.name == "main") request.sourceName else "${request.sourceName}#${module.name}"
        val moduleProtection = phase(request, SpirvRoundTripPhase.PROTECT, moduleDirectory, moduleSourceName) {
            if (generatedCompilerSource) {
                PreprocessorProtection.protectGeneratedCompilerSource(module.source, moduleSourceName)
            } else {
                PreprocessorProtection.protect(module.source, moduleSourceName)
            }
        }
        val patch = phase(request, SpirvRoundTripPhase.PATCH_INPUT, moduleDirectory, moduleSourceName) {
            patcher.patch(
                moduleProtection,
                request.stage,
                sourceContracts = module.irisContracts,
            )
        }
        val earlyReturnNormalization = phase(
            request,
            SpirvRoundTripPhase.PATCH_INPUT,
            moduleDirectory,
            moduleSourceName,
        ) {
            CompilerCopyEarlyReturnNormalizer.normalize(patch.compilerSource)
        }
        moduleDirectory.resolve("compiler-early-returns.txt").writeText(earlyReturnNormalization.renderReport())
        val plannedNativeContract = phase(
            request,
            SpirvRoundTripPhase.PATCH_INPUT,
            moduleDirectory,
            moduleSourceName,
        ) {
            SpirvNativePrimitiveContract.plan(earlyReturnNormalization.source)
        }
        val compilerSource = plannedNativeContract?.prepareCompilerSource(earlyReturnNormalization.source)
            ?: earlyReturnNormalization.source
        val compilerPath = moduleDirectory.resolve("compiler.glsl")
        compilerPath.writeText(compilerSource)

        val toolchain = if (processRunner == null) {
            SpirvToolchain(
                moduleDirectory,
                executables,
                processGate = processGate,
                metrics = metrics,
                resultCache = toolResultCache,
            )
        } else {
            SpirvToolchain(moduleDirectory, executables, processRunner, processGate, metrics, toolResultCache)
        }
        val originalSpirv = moduleDirectory.resolve("input.spv")
        val compileInvocation = toolchain.compileInvocation(request.stage, compilerPath, originalSpirv)
        phase(request, SpirvRoundTripPhase.COMPILE, moduleDirectory, moduleSourceName) {
            toolchain.execute(compileInvocation)
        }

        val optimizedSpirv = moduleDirectory.resolve("optimized.spv")
        val optimizeInvocation = if (plannedNativeContract?.requiresCompilerAdapter == true) {
            toolchain.optimizeCrossAdapterInvocation(request.stage, originalSpirv, optimizedSpirv)
        } else {
            toolchain.optimizeInvocation(request.stage, originalSpirv, optimizedSpirv)
        }
        phase(request, SpirvRoundTripPhase.OPTIMIZE, moduleDirectory, moduleSourceName) {
            toolchain.execute(optimizeInvocation)
        }

        val originalPrimitiveInventory = phase(
            request,
            SpirvRoundTripPhase.OPTIMIZE,
            moduleDirectory,
            moduleSourceName,
        ) {
            SpirvBinaryInventory.read(originalSpirv)
        }
        val optimizedPrimitiveInventory = phase(
            request,
            SpirvRoundTripPhase.OPTIMIZE,
            moduleDirectory,
            moduleSourceName,
        ) {
            SpirvBinaryInventory.read(optimizedSpirv)
        }
        val nativeContract = plannedNativeContract.takeIf {
            it?.requiresCompilerAdapter == true || 5297 in optimizedPrimitiveInventory.capabilities
        }
        var crossCompileInvocation: SpirvInvocation? = null
        var crossOptimizeInvocation: SpirvInvocation? = null
        val crossInput = if (
            nativeContract == null ||
            !nativeContract.requiresCrossAdapter ||
            5297 !in optimizedPrimitiveInventory.capabilities
        ) {
            optimizedSpirv
        } else {
            val crossCompilerPath = moduleDirectory.resolve("cross-compiler.glsl")
            crossCompilerPath.writeText(nativeContract.prepareCrossSource(earlyReturnNormalization.source))
            val crossOriginalSpirv = moduleDirectory.resolve("cross-input.spv")
            crossCompileInvocation = toolchain.compileInvocation(request.stage, crossCompilerPath, crossOriginalSpirv)
            phase(request, SpirvRoundTripPhase.COMPILE, moduleDirectory, moduleSourceName) {
                toolchain.execute(requireNotNull(crossCompileInvocation))
            }
            val crossOptimizedSpirv = moduleDirectory.resolve("cross-optimized.spv")
            crossOptimizeInvocation = toolchain.optimizeCrossAdapterInvocation(
                request.stage,
                crossOriginalSpirv,
                crossOptimizedSpirv,
            )
            phase(request, SpirvRoundTripPhase.OPTIMIZE, moduleDirectory, moduleSourceName) {
                toolchain.execute(requireNotNull(crossOptimizeInvocation))
            }
            crossOptimizedSpirv
        }
        val vulkanCrossSemantics = optimizedPrimitiveInventory.requiresVulkanCrossSemantics
        val decompiledPath = moduleDirectory.resolve("decompiled.glsl")
        val decompileInvocation = toolchain.decompileInvocation(
            request.stage,
            crossInput,
            decompiledPath,
            vulkanSemantics = vulkanCrossSemantics,
        )
        phase(request, SpirvRoundTripPhase.DECOMPILE, moduleDirectory, moduleSourceName) {
            toolchain.execute(decompileInvocation)
        }

        val rawSemanticSource = decompiledPath.readText()
        var nativeRestorationFailure: String? = null
        val semanticSource = if (nativeContract == null) {
            rawSemanticSource
        } else {
            when (val restoration = nativeContract.restoreCrossOutput(rawSemanticSource)) {
                is SpirvNativePrimitiveRestoration.Restored -> restoration.source
                is SpirvNativePrimitiveRestoration.Preserved -> {
                    nativeRestorationFailure = restoration.reason
                    rawSemanticSource
                }
            }
        }
        moduleDirectory.resolve("decompiled-native.glsl").writeText(semanticSource)
        val compilerCore = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            patcher.restoreCore(semanticSource, patch)
        }
        val bridge = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            SpirvSettingBridge.restoreCrossOutput(
                compilerCore,
                module.settings,
                patch.irisContracts.localSizeSpecializationIds,
            )
        }
        val internalCoreSource: String
        val crossBridgeSettings: List<ShaderSetting>
        var restorationFailure: String? = nativeRestorationFailure
        when (bridge) {
            is SpirvSettingBridgeRestoration.Restored -> {
                internalCoreSource = bridge.source
                crossBridgeSettings = bridge.settings
            }
            is SpirvSettingBridgeRestoration.Preserved -> {
                internalCoreSource = compilerCore
                crossBridgeSettings = emptyList()
                restorationFailure = bridge.reason
            }
        }
        val deferFinalRestoration = request.structuralPlan != null
        val contractSource = if (restorationFailure == null && !deferFinalRestoration) {
            when (val contracts = patcher.restoreContracts(internalCoreSource, patch)) {
                is IrisContractRestoration.Restored -> contracts.source.also {
                    patcher.validateContract(it, patch)
                }
                is IrisContractRestoration.StructuralPreservation -> {
                    restorationFailure = contracts.reason
                    internalCoreSource
                }
            }
        } else {
            internalCoreSource
        }
        val bridgeCompletion = if (restorationFailure == null) {
            SpirvSettingBridge.completeRestoredSettings(
                contractSource,
                crossBridgeSettings,
                module.settings,
            )
        } else {
            SpirvSettingBridgeRestoration.Restored(contractSource, crossBridgeSettings)
        }
        val bridgeSettings: List<ShaderSetting>
        val bridgeSource: String
        when (bridgeCompletion) {
            is SpirvSettingBridgeRestoration.Restored -> {
                bridgeSource = bridgeCompletion.source
                bridgeSettings = bridgeCompletion.settings
            }
            is SpirvSettingBridgeRestoration.Preserved -> {
                bridgeSource = contractSource
                bridgeSettings = crossBridgeSettings
                restorationFailure = bridgeCompletion.reason
            }
        }
        val restored = if (restorationFailure == null && !deferFinalRestoration) {
            when (
                val bridges = SpirvSettingBridge.placeAfterDefinitions(
                    bridgeSource,
                    bridgeSettings,
                    patch.irisContracts.contracts,
                )
            ) {
                is SpirvSettingBridgeRestoration.Restored -> bridges.source
                is SpirvSettingBridgeRestoration.Preserved -> {
                    restorationFailure = bridges.reason
                    contractSource
                }
            }
        } else {
            bridgeSource
        }
        val restoredWithTypes = if (restorationFailure == null && !deferFinalRestoration) {
            SpirvFinalEmitter.restoreMissingSourceTypeDeclarations(request, restored)
        } else {
            restored
        }
        val restoredPath = moduleDirectory.resolve("restored.glsl")
        restoredPath.writeText(restoredWithTypes)

        val validationCompilerSource = if (restorationFailure == null) {
            val compilerRestored = SpirvSettingBridge.restoreCompilerDeclarations(
                restoredWithTypes,
                bridgeSettings,
                emptyMap(),
                emptySet(),
            )
            val validationPatch = phase(request, SpirvRoundTripPhase.VALIDATE, moduleDirectory, moduleSourceName) {
                val restoredProtection = PreprocessorProtection.protectGeneratedCompilerSource(
                    compilerRestored,
                    moduleSourceName,
                )
                patcher.patch(
                    restoredProtection,
                    request.stage,
                    patch.generatedLayouts,
                    patch.irisContracts,
                )
            }
            if (validationPatch.generatedLayouts.toSet() != patch.generatedLayouts.toSet()) {
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    moduleDirectory,
                    "generated OpenGL layout mapping changed after restoration",
                    moduleSourceName,
                )
            }
            val validationNormalization = CompilerCopyEarlyReturnNormalizer.normalize(validationPatch.compilerSource)
            moduleDirectory.resolve("validation-early-returns.txt").writeText(validationNormalization.renderReport())
            nativeContract?.prepareCompilerSource(validationNormalization.source)
                ?: validationNormalization.source
        } else {
            compilerSource
        }
        val validationSource = moduleDirectory.resolve("validation.glsl")
        validationSource.writeText(validationCompilerSource)
        val validationSpirv = if (deferFinalRestoration) {
            requestDirectory.resolve("final-structural-validation").resolve(safeName(module.name)).resolve("final.spv")
        } else {
            requestDirectory.resolve("final-validation").resolve(safeName(module.name)).resolve("final.spv")
        }
        val validationInvocation: SpirvInvocation? = null
        moduleDirectory.resolve("native-primitives.txt").writeText(
            buildString {
                appendLine("cross-semantics: ${if (vulkanCrossSemantics) "vulkan-subgroup" else "opengl"}")
                appendLine("cross-adapter: ${nativeContract?.primitives?.sorted()?.joinToString(", ") ?: "none"}")
                appendLine("original")
                appendLine(originalPrimitiveInventory.render())
                appendLine("optimized")
                appendLine(optimizedPrimitiveInventory.render())
                if (Files.isRegularFile(validationSpirv)) {
                    appendLine("validation")
                    appendLine(SpirvBinaryInventory.read(validationSpirv).render())
                } else {
                    appendLine("validation: deferred final-source recompile")
                }
            },
        )

        val emissionCore = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            TextureAccessAnalyzer.restoreProbeResources(internalCoreSource, module.resourceMarkers)
        }
        val emissionSource = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            TextureAccessAnalyzer.restoreProbeResources(restoredWithTypes, module.resourceMarkers)
        }
        val emissionPatch = phase(request, SpirvRoundTripPhase.VALIDATE, moduleDirectory, moduleSourceName) {
            val sourceWithoutMarkers = TextureAccessAnalyzer.restoreProbeResources(module.source, module.resourceMarkers)
            patcher.patch(
                PreprocessorProtection.protectGeneratedCompilerSource(sourceWithoutMarkers, moduleSourceName),
                request.stage,
                sourceContracts = module.irisContracts,
            )
        }
        if (restorationFailure == null && !deferFinalRestoration) {
            phase(request, SpirvRoundTripPhase.VALIDATE, moduleDirectory, moduleSourceName) {
                patcher.validateContract(emissionSource, emissionPatch)
            }
        }

        return SpirvModuleResult(
            name = module.name,
            source = emissionSource,
            coreSource = emissionCore,
            liveSource = semanticSource,
            bridgeSettings = bridgeSettings,
            derivedScalarExpressions = module.derivedScalarExpressions,
            structuralSignature = module.structuralSignature,
            structuralAssignment = module.structuralAssignment,
            structuralAssignments = module.structuralAssignments,
            irisContracts = patch.irisContracts,
            originalContract = emissionPatch.originalContract,
            generatedLayouts = emissionPatch.generatedLayouts,
            restorationFailure = restorationFailure,
            tokenPasteLowerings = module.tokenPasteLowerings,
            resourceMarkers = module.resourceMarkers,
            textureAccess = TextureAccessAnalyzer.fromOptimizedSource(
                semanticSource,
                module.resourceMarkers,
            ) + module.conservativeAccess,
            artifactDirectory = moduleDirectory,
            originalSpirv = originalSpirv,
            optimizedSpirv = optimizedSpirv,
            validationSpirv = validationSpirv,
            invocations = listOfNotNull(
                compileInvocation,
                optimizeInvocation,
                crossCompileInvocation,
                crossOptimizeInvocation,
                decompileInvocation,
                validationInvocation,
            ),
        )
    }

    private fun validateFinalSource(
        request: SpirvOptimizationRequest,
        source: String,
        module: SpirvModuleResult,
        requestDirectory: Path,
    ): SpirvInvocation {
        val finalDirectory = requestDirectory.resolve("final-validation").resolve(safeName(module.name))
        finalDirectory.createDirectories()
        finalDirectory.resolve("emitted.glsl").writeText(source)
        module.irisContracts.finalSourceReferenceIssue(source)?.let { issue ->
            fail(request, SpirvRoundTripPhase.VALIDATE, finalDirectory, issue, request.sourceName)
        }
        val compilerRestored = phase(request, SpirvRoundTripPhase.VALIDATE, finalDirectory) {
            SpirvSettingBridge.restoreCompilerDeclarations(
                source,
                module.bridgeSettings,
                emptyMap(),
                emptySet(),
            )
        }
        val compilerReady = phase(request, SpirvRoundTripPhase.VALIDATE, finalDirectory) {
            module.irisContracts.replaceHostReferences(
                module.irisContracts.prepareCompilerSource(compilerRestored),
            )
        }
        val finalPatch = phase(request, SpirvRoundTripPhase.VALIDATE, finalDirectory) {
            patcher.patch(
                PreprocessorProtection.protectGeneratedCompilerSource(compilerReady, request.sourceName),
                request.stage,
                module.generatedLayouts,
                sourceContracts = module.irisContracts,
            )
        }
        val activeExpectedLayouts = module.generatedLayouts.filter { expected ->
            finalPatch.generatedLayouts.any { it.key == expected.key }
        }
        generatedLayoutDifference(activeExpectedLayouts, finalPatch.generatedLayouts)?.let { difference ->
            fail(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "final generated layout mapping changed for ${module.name}: $difference",
                request.sourceName,
            )
        }
        phase(request, SpirvRoundTripPhase.VALIDATE, finalDirectory) {
            patcher.validateContract(source, finalPatch, validateSourceContracts = false)
        }
        val normalized = CompilerCopyEarlyReturnNormalizer.normalize(finalPatch.compilerSource)
        finalDirectory.resolve("early-returns.txt").writeText(normalized.renderReport())
        val compilerSource = SpirvNativePrimitiveContract.plan(normalized.source)
            ?.prepareCompilerSource(normalized.source)
            ?: normalized.source
        val compilerPath = finalDirectory.resolve("final.glsl")
        val spirvPath = finalDirectory.resolve("final.spv")
        compilerPath.writeText(compilerSource)
        val toolchain = if (processRunner == null) {
            SpirvToolchain(
                finalDirectory,
                executables,
                processGate = processGate,
                metrics = metrics,
                resultCache = toolResultCache,
            )
        } else {
            SpirvToolchain(finalDirectory, executables, processRunner, processGate, metrics, toolResultCache)
        }
        val invocation = toolchain.compileInvocation(request.stage, compilerPath, spirvPath)
        phase(request, SpirvRoundTripPhase.RECOMPILE, finalDirectory) {
            toolchain.execute(invocation)
        }
        return invocation
    }

    private fun validateFinalStructuralSource(
        request: SpirvOptimizationRequest,
        source: String,
        modules: List<SpirvModuleResult>,
        structuralPlan: ShaderStructuralCoveragePlan,
        requestDirectory: Path,
    ): List<SpirvInvocation> {
        val finalDirectory = requestDirectory.resolve("final-structural-validation")
        finalDirectory.createDirectories()
        finalDirectory.resolve("emitted.glsl").writeText(source)
        modules.asSequence().mapNotNull { module ->
            module.irisContracts.finalSourceReferenceIssue(source)?.let { issue -> module.name to issue }
        }.firstOrNull()?.let { (moduleName, issue) ->
            fail(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "$moduleName: $issue",
                "${request.sourceName}#$moduleName",
            )
        }
        if (request.finalSourcePolicy == null) {
            modules.asSequence().mapNotNull { module ->
                module.irisContracts.finalSourceDependencyIssue(source)?.let { issue -> module.name to issue }
            }.firstOrNull()?.let { (moduleName, issue) ->
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    finalDirectory,
                    "$moduleName: $issue",
                    "${request.sourceName}#$moduleName",
                )
            }
            SpirvFinalEmitter.finalDirectiveMacroDependencyIssue(
                request.sourceName,
                request.source,
                source,
                modules.first().irisContracts.sourceFacingContracts,
                structuralPlan.restorationPlan,
            )?.let { issue ->
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    finalDirectory,
                    issue,
                    request.sourceName,
                )
            }
        }
        val validationModules = modules.distinctBy { module ->
            requireNotNull(module.structuralSignature).canonical
        }
        val varying = ShaderVaryingStructuralSlots.from(
            validationModules.map { requireNotNull(it.structuralSignature) },
        )
        val materializer = ShaderCompilerCopyMaterializer(
            finalDirectory.resolve("cc"),
            processGate = processGate,
            metrics = metrics,
        )
        data class FinalValidationCandidate(
            val index: Int,
            val module: SpirvModuleResult,
            val signature: ShaderStructuralSignature,
            val selectedSource: String,
            val compilerPlan: ShaderCompilerCopyPlan,
        )
        val derivedScalarExpressions = mergeDerivedScalarExpressions(request.sourceName, modules)
        val candidates = validationModules.mapIndexed { index, module ->
            val signature = requireNotNull(module.structuralSignature) {
                "${request.sourceName} final structural validation is missing ${module.name} signature metadata"
            }
            val compilerBridge = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                SpirvSettingBridge.restoreCompilerDeclarations(
                    source,
                    module.bridgeSettings,
                    module.structuralAssignment,
                    structuralPlan.graph.structuralSettings,
                )
            }
            val selectedStructuralSource = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                structuralPlan.restorationPlan.materializeFinalSource(
                    compilerBridge,
                    module.structuralAssignment,
                )
            }
            val selectedSource = SpirvFinalEmitter.restoreSourceFunctionConditionalOwners(
                request.source,
                selectedStructuralSource,
            ).let { restoredOwners ->
                structuralPlan.restorationPlan.materializeFinalSource(
                    restoredOwners,
                    module.structuralAssignment,
                )
            }
            val candidateDirectory = finalDirectory.resolve(safeName(module.name))
            candidateDirectory.createDirectories()
            candidateDirectory.resolve("selected.glsl").writeText(selectedSource)
            val compilerPlan = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                val compilerView = IrisFinalSourceProcessor.prepareCompatibilityCompilerSource(
                    request.source,
                    selectedSource,
                )
                val plan = ShaderCompilerCopyPlanner.plan(
                    compilerView,
                    "${request.sourceName}#${module.name}",
                )
                plan.copy(
                    compilerSource = module.irisContracts.restoreRequiredCompilerPrelude(plan.compilerCandidateSource),
                )
            }
            FinalValidationCandidate(index, module, signature, selectedSource, compilerPlan)
        }
        val preprocessedSources = phase(
            request,
            SpirvRoundTripPhase.VALIDATE,
            finalDirectory,
            request.sourceName,
        ) {
            materializer.materializeBatch(
                candidates.map { candidate ->
                    ShaderCompilerCopyMaterializationRequest(
                        request.sourceName,
                        request.stage,
                        candidate.compilerPlan,
                        TextureAccessProbe(candidate.selectedSource, emptyList(), TextureAccess()),
                        "v${candidate.index.toString().padStart(3, '0')}",
                    )
                },
            ).map { materialization ->
                when (materialization) {
                    is ShaderCompilerCopyMaterialization.Success -> materialization.module.source
                    is ShaderCompilerCopyMaterialization.Failure -> throw materialization.exception
                }
            }
        }
        fun validateModule(candidate: FinalValidationCandidate, preprocessedSource: String): SpirvInvocation {
            val module = candidate.module
            val signature = candidate.signature
            val selectedSource = candidate.selectedSource
            val compilerMacroSource = restoreMissingCompilerDerivedMacros(
                restoreMissingCompilerScalarMacros(
                    restoreMissingCompilerMacros(preprocessedSource, selectedSource),
                    source,
                ),
                derivedScalarExpressions,
            )
            val materialized = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                candidate.compilerPlan.irisContracts.prepareCompilerSource(compilerMacroSource)
            }
            val moduleDirectory = finalDirectory.resolve(safeName(module.name))
            moduleDirectory.createDirectories()
            val selectedDiagnostic = moduleDirectory.resolve("selected.failed.glsl")
            val preprocessedDiagnostic = moduleDirectory.resolve("preprocessed.failed.glsl")
            val materializedDiagnostic = moduleDirectory.resolve("materialized.failed.glsl")
            selectedDiagnostic.writeText(selectedSource)
            preprocessedDiagnostic.writeText(preprocessedSource)
            materializedDiagnostic.writeText(materialized)
            val actualSignature = ShaderStructuralSignatureExtractor.extract(
                request.stage,
                materialized,
                signature.requiredCapabilities,
                signature.localSizeFallback,
            )
            if (!sameStructuralProjection(actualSignature, signature, varying)) {
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    finalDirectory,
                    "final structural signature changed for ${module.name}; expected=" +
                        signature.canonical.replace('\n', ' ') + "; actual=" +
                        actualSignature.canonical.replace('\n', ' '),
                    "${request.sourceName}#${module.name}",
                )
            }
            val finalPatch = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                patcher.patch(
                    PreprocessorProtection.protectGeneratedCompilerSource(
                        materialized,
                        "${request.sourceName}#${module.name}",
                    ),
                    request.stage,
                    module.generatedLayouts,
                    candidate.compilerPlan.irisContracts,
                )
            }
            phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                patcher.validateContract(
                    finalPatch.compilerSource,
                    finalPatch,
                    validateSourceContracts = false,
                )
            }
            val activeExpectedLayouts = module.generatedLayouts.filter { expected ->
                finalPatch.generatedLayouts.any { it.key == expected.key }
            }
            generatedLayoutDifference(activeExpectedLayouts, finalPatch.generatedLayouts)?.let { difference ->
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    finalDirectory,
                    "final generated layout mapping changed for ${module.name}: $difference",
                    "${request.sourceName}#${module.name}",
                )
            }
            val compilerPath = moduleDirectory.resolve("final.glsl")
            val spirvPath = moduleDirectory.resolve("final.spv")
            val finalNormalization = CompilerCopyEarlyReturnNormalizer.normalize(finalPatch.compilerSource)
            moduleDirectory.resolve("early-returns.txt").writeText(finalNormalization.renderReport())
            val finalCompilerSource = SpirvNativePrimitiveContract.plan(finalNormalization.source)
                ?.prepareCompilerSource(finalNormalization.source)
                ?: finalNormalization.source
            compilerPath.writeText(finalCompilerSource)
            val toolchain = if (processRunner == null) {
                SpirvToolchain(
                    moduleDirectory,
                    executables,
                    processGate = processGate,
                    metrics = metrics,
                    resultCache = toolResultCache,
                )
            } else {
                SpirvToolchain(moduleDirectory, executables, processRunner, processGate, metrics, toolResultCache)
            }
            val invocation = toolchain.compileInvocation(request.stage, compilerPath, spirvPath)
            phase(
                request,
                SpirvRoundTripPhase.RECOMPILE,
                moduleDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                toolchain.execute(invocation)
            }
            Files.deleteIfExists(selectedDiagnostic)
            Files.deleteIfExists(preprocessedDiagnostic)
            Files.deleteIfExists(materializedDiagnostic)
            return invocation
        }
        if (candidates.size == 1) return listOf(validateModule(candidates.single(), preprocessedSources.single()))
        val executor = Executors.newFixedThreadPool(minOf(FINAL_VALIDATION_PARALLELISM, candidates.size))
        val completion = ExecutorCompletionService<IndexedValue<SpirvInvocation>>(executor)
        val futures = candidates.mapIndexed { index, candidate ->
            completion.submit(Callable {
                IndexedValue(index, validateModule(candidate, preprocessedSources[index]))
            })
        }
        val invocations = MutableList<SpirvInvocation?>(candidates.size) { null }
        return try {
            repeat(candidates.size) {
                val future = completion.take()
                try {
                    val invocation = future.get()
                    invocations[invocation.index] = invocation.value
                } catch (e: ExecutionException) {
                    futures.filterNot { it.isDone }.forEach { it.cancel(true) }
                    val cause = e.cause
                    when (cause) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> throw IllegalStateException("Final structural validation task failed", cause)
                    }
                }
            }
            invocations.map { requireNotNull(it) }
        } catch (e: InterruptedException) {
            futures.filterNot { it.isDone }.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw IllegalStateException("Final structural validation interrupted", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sameStructuralProjection(
        actual: ShaderStructuralSignature,
        expected: ShaderStructuralSignature,
        varying: ShaderVaryingStructuralSlots,
    ): Boolean {
        fun compatible(
            actualValues: List<String>,
            expectedValues: List<String>,
            varyingValues: Set<String>,
            include: (String) -> Boolean = { true },
        ): Boolean {
            val expectedProjection = expectedValues.filterTo(linkedSetOf()) {
                it in varyingValues && include(it)
            }
            return actualValues.filter { it in varyingValues && include(it) }
                .all(expectedProjection::contains)
        }
        return actual.stage == expected.stage &&
            actual.requiredCapabilities == expected.requiredCapabilities &&
            actual.localSizeFallback == expected.localSizeFallback &&
            compatible(actual.resources, expected.resources, varying.resources) { !it.startsWith("shared ") } &&
            compatible(actual.stageInterfaces, expected.stageInterfaces, varying.interfaces) &&
            compatible(actual.functionAbi, expected.functionAbi, varying.functionAbi) { !it.startsWith("struct ") }
    }

    private fun generatedLayoutDifference(
        expected: List<GeneratedShaderLayout>,
        actual: List<GeneratedShaderLayout>,
    ): String? {
        val duplicateExpected = expected.groupingBy(GeneratedShaderLayout::key).eachCount().filterValues { it > 1 }.keys
        val duplicateActual = actual.groupingBy(GeneratedShaderLayout::key).eachCount().filterValues { it > 1 }.keys
        if (duplicateExpected.isNotEmpty() || duplicateActual.isNotEmpty()) {
            val order = compareBy<ShaderAbiKey>({ it.kind.name }, ShaderAbiKey::name)
            return "duplicate keys expected=${duplicateExpected.sortedWith(order)} " +
                "actual=${duplicateActual.sortedWith(order)}"
        }
        val expectedByKey = expected.associateBy(GeneratedShaderLayout::key)
        val actualByKey = actual.associateBy(GeneratedShaderLayout::key)
        val differences = (expectedByKey.keys + actualByKey.keys).sortedWith(
            compareBy<ShaderAbiKey>({ it.kind.name }, ShaderAbiKey::name),
        ).mapNotNull { key ->
            val expectedLayout = expectedByKey[key]
            val actualLayout = actualByKey[key]
            if (expectedLayout == actualLayout) null else {
                "${key.kind}:${key.name} expected=${expectedLayout?.qualifier}=${expectedLayout?.value} " +
                    "actual=${actualLayout?.qualifier}=${actualLayout?.value}"
            }
        }
        return differences.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun validateModules(
        request: SpirvOptimizationRequest,
        artifactDirectory: Path,
    ) {
        val duplicateNames = request.compilerModules.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty() || request.compilerModules.any { it.name.isBlank() }) {
            fail(
                request,
                SpirvRoundTripPhase.COMPILER_COPY,
                artifactDirectory,
                "compiler-module names must be non-blank and unique; duplicates=${duplicateNames.sorted()}",
            )
        }
    }

    private fun artifactDirectory(
        sourceName: String,
        stage: ShaderStage,
        moduleName: String,
        source: String,
    ): Path {
        val hash = shortHash("$sourceName\u0000${stage.name}\u0000$moduleName\u0000$source")
        return workingDirectory.resolve("${safeName(sourceName)}-${stage.glslangName}-$hash")
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun safeName(name: String): String {
        val safe = name.replace(INVALID_PATH_CHAR, "_").trim('_').take(80)
        return safe.ifEmpty { "shader" }
    }

    private inline fun <T> phase(
        request: SpirvOptimizationRequest,
        phase: SpirvRoundTripPhase,
        artifactDirectory: Path,
        sourceName: String = request.sourceName,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (e: SpirvRoundTripException) {
            throw e
        } catch (e: Exception) {
            throw SpirvRoundTripException(
                sourceName,
                request.stage,
                phase,
                artifactDirectory,
                e.message ?: e::class.simpleName.orEmpty(),
                e,
            )
        }
    }

    private fun fail(
        request: SpirvOptimizationRequest,
        phase: SpirvRoundTripPhase,
        artifactDirectory: Path,
        detail: String,
        sourceName: String = request.sourceName,
    ): Nothing {
        throw SpirvRoundTripException(sourceName, request.stage, phase, artifactDirectory, detail)
    }

    companion object {
        private const val FINAL_VALIDATION_PARALLELISM = 8
        private val INVALID_PATH_CHAR = """[^A-Za-z0-9._-]""".toRegex()
        private val MODULE_ARTIFACT_NAMES = listOf(
            "input.glsl",
            "compiler.glsl",
            "compiler-early-returns.txt",
            "input.spv",
            "optimized.spv",
            "cross-compiler.glsl",
            "cross-input.spv",
            "cross-optimized.spv",
            "decompiled.glsl",
            "decompiled-native.glsl",
            "native-primitives.txt",
            "restored.glsl",
            "validation.glsl",
            "validation-early-returns.txt",
            "validation.spv",
        )
    }
}
