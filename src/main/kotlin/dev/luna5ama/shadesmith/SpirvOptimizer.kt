package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
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
)

internal data class SpirvOptimizationRequest(
    val sourceName: String,
    val stage: ShaderStage,
    val source: String,
    val compilerModules: List<SpirvCompilerModule> = emptyList(),
    val structuralPlan: ShaderStructuralCoveragePlan? = null,
)

internal enum class SpirvEmissionMode {
    OPTIMIZED,
    PRESERVED_SOURCE,
}

internal data class SpirvModuleResult(
    val name: String,
    val source: String,
    val coreSource: String,
    val bridgeSettings: List<ShaderSetting>,
    val structuralSignature: ShaderStructuralSignature?,
    val structuralAssignment: Map<String, String>,
    val irisContracts: IrisShaderContractPlan,
    val originalContract: ShaderAbiContract,
    val generatedLayouts: List<GeneratedShaderLayout>,
    val restorationFailure: String?,
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
) {
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
        val finalValidationInvocations = if (
            emission.mode == SpirvEmissionMode.OPTIMIZED && request.structuralPlan != null
        ) {
            validateFinalStructuralSource(
                request,
                emission.source,
                results,
                request.structuralPlan,
                requestDirectory,
            )
        } else {
            emptyList()
        }
        requestDirectory.resolve(
            if (emission.mode == SpirvEmissionMode.OPTIMIZED) "optimized.glsl" else "preserved.glsl",
        ).writeText(emission.source)
        return SpirvOptimizationResult(
            source = emission.source,
            emissionMode = emission.mode,
            fallbackReason = emission.fallbackReason,
            specializationSettings = modules.flatMap { it.settings }.map { it.name }.distinct().sorted(),
            structuralSignatures = results.mapNotNull { it.structuralSignature }.distinct(),
            finalValidationInvocations = finalValidationInvocations,
            cacheHits = 0,
            artifactDirectory = requestDirectory,
            modules = results,
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
        return try {
            executor.invokeAll(
                modules.map { module ->
                    Callable { optimizeModule(request, module, requestDirectory, generatedCompilerSource) }
                },
            ).map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    when (cause) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> throw IllegalStateException("Shader compiler-module task failed", cause)
                    }
                }
            }
        } catch (e: InterruptedException) {
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
        val compilerPath = moduleDirectory.resolve("compiler.glsl")
        compilerPath.writeText(patch.compilerSource)

        val toolchain = if (processRunner == null) {
            SpirvToolchain(moduleDirectory, executables, processGate = processGate, metrics = metrics)
        } else {
            SpirvToolchain(moduleDirectory, executables, processRunner, processGate, metrics)
        }
        val originalSpirv = moduleDirectory.resolve("input.spv")
        val compileInvocation = toolchain.compileInvocation(request.stage, compilerPath, originalSpirv)
        phase(request, SpirvRoundTripPhase.COMPILE, moduleDirectory, moduleSourceName) {
            toolchain.execute(compileInvocation)
        }

        val optimizedSpirv = moduleDirectory.resolve("optimized.spv")
        val optimizeInvocation = toolchain.optimizeInvocation(request.stage, originalSpirv, optimizedSpirv)
        phase(request, SpirvRoundTripPhase.OPTIMIZE, moduleDirectory, moduleSourceName) {
            toolchain.execute(optimizeInvocation)
        }

        val decompiledPath = moduleDirectory.resolve("decompiled.glsl")
        val decompileInvocation = toolchain.decompileInvocation(request.stage, optimizedSpirv, decompiledPath)
        phase(request, SpirvRoundTripPhase.DECOMPILE, moduleDirectory, moduleSourceName) {
            toolchain.execute(decompileInvocation)
        }

        val semanticSource = decompiledPath.readText()
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
        val bridgeSettings: List<ShaderSetting>
        var restorationFailure: String? = null
        when (bridge) {
            is SpirvSettingBridgeRestoration.Restored -> {
                internalCoreSource = bridge.source
                bridgeSettings = bridge.settings
            }
            is SpirvSettingBridgeRestoration.Preserved -> {
                internalCoreSource = compilerCore
                bridgeSettings = emptyList()
                restorationFailure = bridge.reason
            }
        }
        val contractSource = if (restorationFailure == null) {
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
        val restored = if (restorationFailure == null) {
            when (
                val bridges = SpirvSettingBridge.placeAfterDefinitions(
                    contractSource,
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
            contractSource
        }
        val restoredPath = moduleDirectory.resolve("restored.glsl")
        restoredPath.writeText(restored)

        val validationCompilerSource = if (restorationFailure == null) {
            val compilerRestored = SpirvSettingBridge.restoreCompilerDeclarations(
                restored,
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
            validationPatch.compilerSource
        } else {
            patch.compilerSource
        }
        val validationSource = moduleDirectory.resolve("validation.glsl")
        validationSource.writeText(validationCompilerSource)
        val validationSpirv = moduleDirectory.resolve("validation.spv")
        val validationInvocation = toolchain.compileInvocation(request.stage, validationSource, validationSpirv)
        phase(request, SpirvRoundTripPhase.RECOMPILE, moduleDirectory, moduleSourceName) {
            toolchain.execute(validationInvocation)
        }

        val emissionCore = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            TextureAccessAnalyzer.restoreProbeResources(internalCoreSource, module.resourceMarkers)
        }
        val emissionSource = phase(request, SpirvRoundTripPhase.RESTORE, moduleDirectory, moduleSourceName) {
            TextureAccessAnalyzer.restoreProbeResources(restored, module.resourceMarkers)
        }
        val emissionPatch = phase(request, SpirvRoundTripPhase.VALIDATE, moduleDirectory, moduleSourceName) {
            val sourceWithoutMarkers = TextureAccessAnalyzer.restoreProbeResources(module.source, module.resourceMarkers)
            patcher.patch(
                PreprocessorProtection.protectGeneratedCompilerSource(sourceWithoutMarkers, moduleSourceName),
                request.stage,
                sourceContracts = module.irisContracts,
            )
        }
        if (restorationFailure == null) {
            phase(request, SpirvRoundTripPhase.VALIDATE, moduleDirectory, moduleSourceName) {
                patcher.validateContract(emissionSource, emissionPatch)
            }
        }

        return SpirvModuleResult(
            name = module.name,
            source = emissionSource,
            coreSource = emissionCore,
            bridgeSettings = bridgeSettings,
            structuralSignature = module.structuralSignature,
            structuralAssignment = module.structuralAssignment,
            irisContracts = patch.irisContracts,
            originalContract = emissionPatch.originalContract,
            generatedLayouts = emissionPatch.generatedLayouts,
            restorationFailure = restorationFailure,
            textureAccess = TextureAccessAnalyzer.fromOptimizedSource(
                semanticSource,
                module.resourceMarkers,
            ) + module.conservativeAccess,
            artifactDirectory = moduleDirectory,
            originalSpirv = originalSpirv,
            optimizedSpirv = optimizedSpirv,
            validationSpirv = validationSpirv,
            invocations = listOf(
                compileInvocation,
                optimizeInvocation,
                decompileInvocation,
                validationInvocation,
            ),
        )
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
        val materializer = ShaderCompilerCopyMaterializer(
            finalDirectory.resolve("cc"),
            processGate = processGate,
            metrics = metrics,
        )
        return modules.mapIndexed { index, module ->
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
            val selectedSource = phase(
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
            val compilerSource = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                module.irisContracts.prepareCompilerSource(selectedSource)
            }
            val materialized = phase(
                request,
                SpirvRoundTripPhase.VALIDATE,
                finalDirectory,
                "${request.sourceName}#${module.name}",
            ) {
                materializer.materializeSource(
                    request.sourceName,
                    request.stage,
                    compilerSource,
                    "v${index.toString().padStart(3, '0')}",
                )
            }
            val actualSignature = ShaderStructuralSignatureExtractor.extract(
                request.stage,
                materialized,
                signature.requiredCapabilities,
                signature.localSizeFallback,
            )
            if (actualSignature != signature) {
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
                )
            }
            if (
                finalPatch.originalContract != module.originalContract ||
                finalPatch.generatedLayouts.toSet() != module.generatedLayouts.toSet()
            ) {
                fail(
                    request,
                    SpirvRoundTripPhase.VALIDATE,
                    finalDirectory,
                    "final structural ABI or generated layout mapping changed for ${module.name}",
                    "${request.sourceName}#${module.name}",
                )
            }
            val moduleDirectory = finalDirectory.resolve(safeName(module.name))
            moduleDirectory.createDirectories()
            val compilerPath = moduleDirectory.resolve("final.glsl")
            val spirvPath = moduleDirectory.resolve("final.spv")
            compilerPath.writeText(finalPatch.compilerSource)
            val toolchain = if (processRunner == null) {
                SpirvToolchain(moduleDirectory, executables, processGate = processGate, metrics = metrics)
            } else {
                SpirvToolchain(moduleDirectory, executables, processRunner, processGate, metrics)
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
            invocation
        }
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
        private val INVALID_PATH_CHAR = """[^A-Za-z0-9._-]""".toRegex()
        private val MODULE_ARTIFACT_NAMES = listOf(
            "input.glsl",
            "compiler.glsl",
            "input.spv",
            "optimized.spv",
            "decompiled.glsl",
            "restored.glsl",
            "validation.glsl",
            "validation.spv",
        )
    }
}
