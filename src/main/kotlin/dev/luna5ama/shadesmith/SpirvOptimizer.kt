package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class PreprocessorBranchSelection(
    val conditionalId: Int,
    val branchIndex: Int,
)

internal data class SpirvShaderVariant(
    val name: String,
    val source: String,
    val coveredBranches: Set<PreprocessorBranchSelection> = emptySet(),
    val resourceMarkers: List<TextureResourceMarker> = emptyList(),
    val conservativeAccess: TextureAccess = TextureAccess(),
)

internal data class SpirvOptimizationRequest(
    val sourceName: String,
    val stage: ShaderStage,
    val source: String,
    val variants: List<SpirvShaderVariant> = emptyList(),
)

internal enum class SpirvEmissionMode {
    OPTIMIZED,
    PRESERVED_PREPROCESSOR,
}

internal data class SpirvVariantResult(
    val name: String,
    val source: String,
    val semanticSource: String,
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
    val artifactDirectory: Path,
    val variants: List<SpirvVariantResult>,
    val requiredBranches: Set<PreprocessorBranchSelection>,
)

internal enum class SpirvRoundTripPhase(val displayName: String) {
    PROTECT("preprocessor protection"),
    MATERIALIZE("variant materialization"),
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
        if (protection.compilerBlockers.isEmpty()) {
            if (request.variants.isNotEmpty()) {
                fail(
                    request,
                    SpirvRoundTripPhase.MATERIALIZE,
                    requestDirectory,
                    "explicit variants were supplied for a directly compilable shader",
                )
            }
            val result = optimizeVariant(request, SpirvShaderVariant("main", request.source), requestDirectory)
            return SpirvOptimizationResult(
                source = result.source,
                emissionMode = SpirvEmissionMode.OPTIMIZED,
                artifactDirectory = requestDirectory,
                variants = listOf(result),
                requiredBranches = emptySet(),
            )
        }

        val requiredBranches = requiredPreprocessorBranches(protection)
        validateVariants(request, protection, requiredBranches, requestDirectory)
        val results = request.variants.map { variant ->
            optimizeVariant(request, variant, requestDirectory)
        }
        requestDirectory.resolve("preserved.glsl").writeText(request.source)

        return SpirvOptimizationResult(
            source = request.source,
            emissionMode = SpirvEmissionMode.PRESERVED_PREPROCESSOR,
            artifactDirectory = requestDirectory,
            variants = results,
            requiredBranches = requiredBranches,
        )
    }

    private fun optimizeVariant(
        request: SpirvOptimizationRequest,
        variant: SpirvShaderVariant,
        requestDirectory: Path,
    ): SpirvVariantResult {
        val variantDirectory = requestDirectory.resolve(
            "${safeName(variant.name)}-${shortHash("${variant.name}\u0000${variant.source}")}",
        )
        variantDirectory.createDirectories()
        VARIANT_ARTIFACT_NAMES.forEach { Files.deleteIfExists(variantDirectory.resolve(it)) }
        val originalPath = variantDirectory.resolve("input.glsl")
        originalPath.writeText(variant.source)

        val variantSourceName = if (variant.name == "main") request.sourceName else "${request.sourceName}#${variant.name}"
        val protection = phase(request, SpirvRoundTripPhase.PROTECT, variantDirectory, variantSourceName) {
            PreprocessorProtection.protect(variant.source, variantSourceName)
        }
        val patch = phase(request, SpirvRoundTripPhase.PATCH_INPUT, variantDirectory, variantSourceName) {
            patcher.patch(
                protection,
                request.stage,
                alwaysRestorableResources = variant.resourceMarkers.mapTo(linkedSetOf()) { it.identifier },
            )
        }
        val compilerPath = variantDirectory.resolve("compiler.glsl")
        compilerPath.writeText(patch.compilerSource)

        val toolchain = if (processRunner == null) {
            SpirvToolchain(variantDirectory, executables)
        } else {
            SpirvToolchain(variantDirectory, executables, processRunner)
        }
        val originalSpirv = variantDirectory.resolve("input.spv")
        val compileInvocation = toolchain.compileInvocation(request.stage, compilerPath, originalSpirv)
        phase(request, SpirvRoundTripPhase.COMPILE, variantDirectory, variantSourceName) {
            toolchain.execute(compileInvocation)
        }

        val optimizedSpirv = variantDirectory.resolve("optimized.spv")
        val optimizeInvocation = toolchain.optimizeInvocation(request.stage, originalSpirv, optimizedSpirv)
        phase(request, SpirvRoundTripPhase.OPTIMIZE, variantDirectory, variantSourceName) {
            toolchain.execute(optimizeInvocation)
        }

        val decompiledPath = variantDirectory.resolve("decompiled.glsl")
        val decompileInvocation = toolchain.decompileInvocation(request.stage, optimizedSpirv, decompiledPath)
        phase(request, SpirvRoundTripPhase.DECOMPILE, variantDirectory, variantSourceName) {
            toolchain.execute(decompileInvocation)
        }

        val semanticSource = decompiledPath.readText()
        val restored = phase(request, SpirvRoundTripPhase.RESTORE, variantDirectory, variantSourceName) {
            patcher.restore(semanticSource, patch)
        }
        val restoredPath = variantDirectory.resolve("restored.glsl")
        restoredPath.writeText(restored)

        val validationPatch = phase(request, SpirvRoundTripPhase.VALIDATE, variantDirectory, variantSourceName) {
            val restoredProtection = PreprocessorProtection.protect(restored, variantSourceName)
            patcher.patch(
                restoredProtection,
                request.stage,
                patch.generatedLayouts,
                variant.resourceMarkers.mapTo(linkedSetOf()) { it.identifier },
            )
        }
        if (validationPatch.generatedLayouts.toSet() != patch.generatedLayouts.toSet()) {
            fail(
                request,
                SpirvRoundTripPhase.VALIDATE,
                variantDirectory,
                "generated OpenGL layout mapping changed after restoration",
                variantSourceName,
            )
        }
        val validationSource = variantDirectory.resolve("validation.glsl")
        validationSource.writeText(validationPatch.compilerSource)
        val validationSpirv = variantDirectory.resolve("validation.spv")
        val validationInvocation = toolchain.compileInvocation(request.stage, validationSource, validationSpirv)
        phase(request, SpirvRoundTripPhase.RECOMPILE, variantDirectory, variantSourceName) {
            toolchain.execute(validationInvocation)
        }

        return SpirvVariantResult(
            name = variant.name,
            source = restored,
            semanticSource = semanticSource,
            textureAccess = TextureAccessAnalyzer.fromOptimizedSource(
                semanticSource,
                variant.resourceMarkers,
            ) + variant.conservativeAccess,
            artifactDirectory = variantDirectory,
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

    private fun validateVariants(
        request: SpirvOptimizationRequest,
        protection: ProtectedPreprocessorSource,
        requiredBranches: Set<PreprocessorBranchSelection>,
        artifactDirectory: Path,
    ) {
        if (request.variants.isEmpty()) {
            val blocker = protection.compilerBlockers.first()
            fail(
                request,
                SpirvRoundTripPhase.MATERIALIZE,
                artifactDirectory,
                "${blocker.reason} at line ${blocker.sourceLine}; no explicit variants were supplied",
            )
        }
        val duplicateNames = request.variants.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty() || request.variants.any { it.name.isBlank() }) {
            fail(
                request,
                SpirvRoundTripPhase.MATERIALIZE,
                artifactDirectory,
                "variant names must be non-blank and unique; duplicates=${duplicateNames.sorted()}",
            )
        }

        request.variants.forEach { variant ->
            val duplicates = variant.coveredBranches.groupBy { it.conditionalId }.filterValues { it.size > 1 }
            if (duplicates.isNotEmpty()) {
                fail(
                    request,
                    SpirvRoundTripPhase.MATERIALIZE,
                    artifactDirectory,
                    "variant ${variant.name} selects multiple branches for conditionals ${duplicates.keys.sorted()}",
                )
            }
            val unknown = variant.coveredBranches - requiredBranches
            if (unknown.isNotEmpty()) {
                fail(
                    request,
                    SpirvRoundTripPhase.MATERIALIZE,
                    artifactDirectory,
                    "variant ${variant.name} reports unknown branch selections ${unknown.sortedForDiagnostic()}",
                )
            }
        }

        val covered = request.variants.flatMapTo(mutableSetOf()) { it.coveredBranches }
        val missing = requiredBranches - covered
        if (missing.isNotEmpty()) {
            fail(
                request,
                SpirvRoundTripPhase.MATERIALIZE,
                artifactDirectory,
                "explicit variants do not cover configurable branches ${missing.sortedForDiagnostic()}",
            )
        }
    }

    private fun artifactDirectory(
        sourceName: String,
        stage: ShaderStage,
        variantName: String,
        source: String,
    ): Path {
        val hash = shortHash("$sourceName\u0000${stage.name}\u0000$variantName\u0000$source")
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

    private fun Set<PreprocessorBranchSelection>.sortedForDiagnostic(): List<String> {
        return sortedWith(compareBy(PreprocessorBranchSelection::conditionalId, PreprocessorBranchSelection::branchIndex))
            .map { "${it.conditionalId}:${it.branchIndex}" }
    }

    companion object {
        private val INVALID_PATH_CHAR = """[^A-Za-z0-9._-]""".toRegex()
        private val VARIANT_ARTIFACT_NAMES = listOf(
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
