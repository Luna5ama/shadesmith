package dev.luna5ama.shadesmith

import java.security.MessageDigest

internal sealed interface SpirvSettingBridgeRestoration {
    data class Restored(
        val source: String,
        val settings: List<ShaderSetting>,
    ) : SpirvSettingBridgeRestoration

    data class Preserved(val reason: String) : SpirvSettingBridgeRestoration
}

internal object SpirvSettingBridge {
    fun restoreCrossOutput(
        source: String,
        settings: List<ShaderSetting>,
        localSizeIds: Set<Int>,
    ): SpirvSettingBridgeRestoration {
        val settingById = settings.associateBy { it.specializationId }
        val blocks = CROSS_BLOCK.findAll(source).toList()
        val duplicateIds = blocks.groupingBy { it.groupValues[1].toInt() }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            return SpirvSettingBridgeRestoration.Preserved(
                "SPIRV-Cross emitted duplicate specialization blocks ${duplicateIds.sorted()}",
            )
        }
        blocks.forEach { block ->
            val opening = block.groupValues[1].toInt()
            val definition = block.groupValues[2].toInt()
            if (opening != definition) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross specialization block ID changed from $opening to $definition",
                )
            }
            if (opening !in settingById && opening !in localSizeIds) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross emitted unowned specialization ID $opening",
                )
            }
        }

        var result = removeRanges(source, blocks.map { it.range })
        val settingByCompilerName = settings.associateBy { it.compilerName }
        val declarationRanges = mutableMapOf<Int, MutableList<IntRange>>()
        val emittedAliases = mutableMapOf<String, ShaderSetting>()
        physicalLineRanges(result).forEach { range ->
            val line = result.substring(range).trimEnd('\r', '\n')
            val macroDeclaration = CROSS_SETTING_DECLARATION.matchEntire(line)
            if (macroDeclaration != null) {
                val id = macroDeclaration.groupValues[3].toInt()
                val setting = settingById[id] ?: return@forEach
                if (macroDeclaration.groupValues[1] != setting.type.glslName) {
                    return SpirvSettingBridgeRestoration.Preserved(
                        "SPIRV-Cross specialization declaration changed type for ID $id",
                    )
                }
                val emittedName = macroDeclaration.groupValues[2]
                emittedAliases[emittedName]?.takeIf { it != setting }?.let {
                    return SpirvSettingBridgeRestoration.Preserved(
                        "SPIRV-Cross reused specialization alias $emittedName for multiple IDs",
                    )
                }
                emittedAliases[emittedName] = setting
                declarationRanges.getOrPut(id, ::mutableListOf) += range
                return@forEach
            }
            val layoutDeclaration = CROSS_LAYOUT_SETTING_DECLARATION.matchEntire(line) ?: return@forEach
            val id = layoutDeclaration.groupValues[1].toInt()
            val setting = settingById[id]
                ?: return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross emitted unowned layout specialization ID $id",
                )
            if (layoutDeclaration.groupValues[2] != setting.type.glslName) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross layout specialization declaration changed for ${setting.compilerName}",
                )
            }
            val emittedName = layoutDeclaration.groupValues[3]
            emittedAliases[emittedName]?.takeIf { it != setting }?.let {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross reused specialization alias $emittedName for multiple IDs",
                )
            }
            emittedAliases[emittedName] = setting
            declarationRanges.getOrPut(id, ::mutableListOf) += range
        }
        declarationRanges.entries.firstOrNull { it.value.size > 1 }?.let { (id, _) ->
            val setting = settingById.getValue(id)
            return SpirvSettingBridgeRestoration.Preserved(
                "SPIRV-Cross emitted duplicate declarations for ${setting.compilerName}",
            )
        }
        val tokenIds = CROSS_TOKEN.findAll(result).map { it.groupValues[1].toInt() }.toSet()
        val blockIds = blocks.mapTo(mutableSetOf()) { it.groupValues[1].toInt() }
        val restoredSettings = settings.sortedBy { it.name }.filter { setting ->
            setting.specializationId in blockIds ||
                setting.specializationId in declarationRanges ||
                setting.specializationId in tokenIds
        }
        result = removeRanges(result, declarationRanges.values.flatten())
        emittedAliases.forEach { (emittedName, setting) ->
            if (emittedName != setting.compilerName) {
                result = identifierRegex(emittedName).replace(result, setting.compilerName)
            }
        }
        result = CROSS_TOKEN.replace(result) { match ->
            settingById[match.groupValues[1].toInt()]?.compilerName ?: match.value
        }
        physicalLineRanges(result).forEach { range ->
            val line = result.substring(range).trimEnd('\r', '\n')
            val match = CROSS_RESTORED_SETTING_DECLARATION.matchEntire(line) ?: return@forEach
            val setting = settingByCompilerName[match.groupValues[2]] ?: return@forEach
            if (match.groupValues[1] == setting.type.glslName && match.groupValues[3] == setting.compilerName) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross specialization declaration for ${setting.compilerName} has an unsupported shape",
                )
            }
        }
        localSizeIds.sorted().forEach { id ->
            val token = "SPIRV_CROSS_CONSTANT_ID_$id"
            if (identifierRegex(token).containsMatchIn(result)) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "local-size specialization ID $id escaped its execution-mode slot",
                )
            }
        }
        if (CROSS_TOKEN.containsMatchIn(result)) {
            return SpirvSettingBridgeRestoration.Preserved(
                "SPIRV-Cross specialization artifacts remain after deterministic restoration",
            )
        }
        val bridges = restoredSettings.joinToString("") { renderBridge(it) }
        return SpirvSettingBridgeRestoration.Restored(
            insertAfterVersion(result, bridges).trimEnd() + "\n",
            restoredSettings,
        )
    }

    fun restoreCompilerDeclarations(
        source: String,
        settings: List<ShaderSetting>,
        fixedAssignments: Map<String, String>,
        fixedSettings: Set<String>,
    ): String {
        val bridgeRanges = locateBridges(source, settings)
        val requiredSettings = settings.filter { setting ->
            bridgeRanges.getValue(setting).isNotEmpty() || identifierRegex(setting.compilerName).containsMatchIn(source)
        }
        requiredSettings.forEach { setting ->
            val ranges = bridgeRanges.getValue(setting)
            require(ranges.isNotEmpty()) {
                "setting bridge ${setting.compilerName} is missing from final GLSL"
            }
            require(ranges.size == 1) {
                "setting bridge ${setting.compilerName} is ambiguous in final GLSL"
            }
        }
        val result = removeRanges(source, bridgeRanges.values.flatten())
        val declarations = buildString {
            requiredSettings.sortedBy { it.name }.forEach { setting ->
                if (setting.name in fixedSettings) {
                    append("const ")
                    append(setting.type.glslName)
                    append(' ')
                    append(setting.compilerName)
                    append(" = ")
                    append(fixedAssignments[setting.name] ?: setting.defaultValue)
                    appendLine(';')
                } else {
                    append("layout(constant_id = ")
                    append(setting.specializationId)
                    append(") const ")
                    append(setting.type.glslName)
                    append(' ')
                    append(setting.compilerName)
                    append(" = ")
                    append(setting.defaultValue)
                    appendLine(';')
                }
            }
        }
        return insertAfterVersion(result, declarations).trimEnd() + "\n"
    }

    fun completeRestoredSettings(
        source: String,
        restoredSettings: List<ShaderSetting>,
        candidates: List<ShaderSetting>,
    ): SpirvSettingBridgeRestoration {
        val candidatesByCompilerName = candidates.associateBy { it.compilerName }
        val referencedCandidates = DECLARATION_IDENTIFIER.findAll(source)
            .mapNotNullTo(linkedSetOf()) { candidatesByCompilerName[it.value] }
        val required = (restoredSettings + candidates.filter { setting ->
            setting in referencedCandidates
        }).distinctBy { it.name }.sortedBy { it.name }
        val bridgeRanges = locateBridges(source, required)
        val missing = mutableListOf<ShaderSetting>()
        required.forEach { setting ->
            when (bridgeRanges.getValue(setting).size) {
                0 -> missing += setting
                1 -> Unit
                else -> return SpirvSettingBridgeRestoration.Preserved(
                    "setting bridge ${setting.compilerName} is ambiguous after contract restoration",
                )
            }
        }
        val completed = insertAfterVersion(source, missing.joinToString("") { renderBridge(it) })
        return SpirvSettingBridgeRestoration.Restored(completed, required)
    }

    fun placeAfterDefinitions(
        source: String,
        settings: List<ShaderSetting>,
        contracts: List<IrisSourceContractSlice>,
    ): SpirvSettingBridgeRestoration {
        if (settings.isEmpty()) return SpirvSettingBridgeRestoration.Restored(source, emptyList())
        val bridges = settings.sortedBy { it.name }.associateWith(::renderBridge)
        val bridgeRanges = mutableListOf<IntRange>()
        locateBridges(source, settings).forEach { (setting, ranges) ->
            if (ranges.size != 1) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "setting bridge ${setting.compilerName} is ${if (ranges.isEmpty()) "missing" else "ambiguous"}",
                )
            }
            bridgeRanges += ranges.single()
        }
        var result = removeRanges(source, bridgeRanges)
        val definitionSettings = settings.filter { it.controlKind != ShaderControlKind.HOST_PRESENCE }
        val hostSettings = settings - definitionSettings.toSet()
        val relevantContracts = definitionSettings.associateWith { setting ->
            contracts.filter { contract -> setting.sourceSlices.any(contract.exactText::contains) }
        }
        relevantContracts.entries.firstOrNull { it.value.isEmpty() }?.let { (setting) ->
            return SpirvSettingBridgeRestoration.Preserved(
                "setting definition contract ${setting.name} is missing from final GLSL",
            )
        }
        val definitionEnds = relevantContracts.entries.flatMap { (setting, contractsForSetting) ->
            val contractEnds = contractsForSetting.distinct().flatMap { contract ->
                occurrences(result, contract.exactText).map { it.last + 1 }
            }
            if (contractEnds.isNotEmpty()) {
                contractEnds
            } else {
                setting.sourceSlices.distinct().flatMap { slice ->
                    occurrences(result, slice).map { it.last + 1 }
                }
            }
        }
        if (definitionSettings.isNotEmpty() && definitionEnds.isEmpty()) {
            return SpirvSettingBridgeRestoration.Preserved("setting definition contracts cannot be located in final GLSL")
        }
        if (definitionEnds.isNotEmpty()) {
            var offset = definitionEnds.max()
            while (offset < result.length && result[offset] in "\r\n") offset++
            val insertion = definitionSettings.joinToString("") { bridges.getValue(it) }
            val prefix = if (offset > 0 && result[offset - 1] !in "\r\n") "\n" else ""
            val suffix = if (offset < result.length && result[offset] !in "\r\n") "\n" else ""
            result = result.substring(0, offset) + prefix + insertion + suffix + result.substring(offset)
        }
        if (hostSettings.isNotEmpty()) {
            result = insertAfterVersion(result, hostSettings.joinToString("") { bridges.getValue(it) })
        }
        return SpirvSettingBridgeRestoration.Restored(result.trimEnd() + "\n", settings.sortedBy { it.name })
    }

    private fun renderBridge(setting: ShaderSetting): String {
        return if (setting.presenceToggle) {
            buildString {
                append("#ifdef ")
                appendLine(setting.name)
                append("#define ")
                append(setting.compilerName)
                appendLine(" true")
                appendLine("#else")
                append("#define ")
                append(setting.compilerName)
                appendLine(" false")
                appendLine("#endif")
            }
        } else {
            "#define ${setting.compilerName} ${setting.name}\n"
        }
    }

    private fun locateBridges(
        source: String,
        settings: Collection<ShaderSetting>,
    ): Map<ShaderSetting, List<IntRange>> {
        data class BridgePattern(val setting: ShaderSetting, val text: String)
        val patterns = settings.map { setting -> BridgePattern(setting, renderBridge(setting)) }
        val byFirstLine = patterns.groupBy { it.text.substringBefore('\n') }
        val ranges = patterns.associate { it.setting to mutableListOf<IntRange>() }
        var lineStart = 0
        while (lineStart < source.length) {
            val newline = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val contentEnd = if (newline > lineStart && source[newline - 1] == '\r') newline - 1 else newline
            byFirstLine[source.substring(lineStart, contentEnd)].orEmpty().forEach { pattern ->
                if (source.regionMatches(lineStart, pattern.text, 0, pattern.text.length)) {
                    ranges.getValue(pattern.setting) += lineStart until lineStart + pattern.text.length
                }
            }
            lineStart = if (newline < source.length) newline + 1 else source.length
        }
        return ranges
    }

    private fun physicalLineRanges(source: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        while (start < source.length) {
            var end = start
            while (end < source.length && source[end] != '\r' && source[end] != '\n') end++
            if (end < source.length && source[end] == '\r') end++
            if (end < source.length && source[end] == '\n') end++
            result += start until end
            start = end
        }
        return result
    }

    private fun insertAfterVersion(source: String, insertion: String): String {
        if (insertion.isEmpty()) return source
        val version = VERSION_LINE.find(source) ?: throw IllegalArgumentException("optimized GLSL has no #version directive")
        var offset = version.range.last + 1
        if (source.getOrNull(offset) == '\r') offset++
        if (source.getOrNull(offset) == '\n') offset++
        val prefix = if (offset == version.range.last + 1) "\n" else ""
        return source.substring(0, offset) + prefix + insertion + source.substring(offset)
    }

    private fun occurrences(source: String, value: String): List<IntRange> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var offset = source.indexOf(value)
        while (offset >= 0) {
            result += offset until offset + value.length
            offset = source.indexOf(value, offset + value.length)
        }
        return result
    }

    private fun removeRanges(source: String, ranges: List<IntRange>): String {
        val ordered = ranges.distinct().sortedBy { it.first }
        if (ordered.isEmpty()) return source
        return buildString(source.length - ordered.sumOf(IntRange::count)) {
            var cursor = 0
            ordered.forEach { range ->
                require(range.first >= cursor) { "overlapping source removal ranges" }
                append(source, cursor, range.first)
                cursor = range.last + 1
            }
            append(source, cursor, source.length)
        }
    }

    private fun identifierRegex(name: String): Regex {
        return "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
    }

    private val CROSS_BLOCK = Regex(
        "(?m)^[\\t ]*#ifndef[\\t ]+SPIRV_CROSS_CONSTANT_ID_([0-9]+)[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#define[\\t ]+SPIRV_CROSS_CONSTANT_ID_([0-9]+)[\\t ]+[^\\r\\n]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#endif[\\t ]*(?:\\r\\n|\\n|\\r|$)",
    )
    private val CROSS_SETTING_DECLARATION = Regex(
        "[\\t ]*const[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)" +
            "[\\t ]*=[\\t ]*SPIRV_CROSS_CONSTANT_ID_([0-9]+)[\\t ]*;[^\\r\\n]*",
    )
    private val CROSS_LAYOUT_SETTING_DECLARATION = Regex(
        "[\\t ]*layout[\\t ]*\\([\\t ]*constant_id[\\t ]*=[\\t ]*([0-9]+)[\\t ]*\\)[\\t ]*" +
            "const[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)" +
            "[\\t ]*=[^;\\r\\n]+;[^\\r\\n]*",
    )
    private val CROSS_RESTORED_SETTING_DECLARATION = Regex(
        "[\\t ]*const[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)" +
            "[\\t ]*=[\\t ]*([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;[^\\r\\n]*",
    )
    private val CROSS_TOKEN = "\\bSPIRV_CROSS_CONSTANT_ID_([0-9]+)\\b".toRegex()
    private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
}

internal data class SpirvFinalEmission(
    val source: String,
    val mode: SpirvEmissionMode,
    val fallbackReason: String?,
    val optimizedEntities: Int,
    val restoredEntities: Int,
    val restoredBytes: Int,
    val restorationDiagnostics: List<String> = emptyList(),
)

internal sealed interface ConditionalNativePrimitiveRestoration {
    data class Restored(
        val source: String,
        val restoredFunctions: Int,
        val restoredBytes: Int,
        val diagnostics: List<String>,
    ) : ConditionalNativePrimitiveRestoration

    data class Preserved(val reason: String) : ConditionalNativePrimitiveRestoration
}

internal sealed interface TokenPasteSourceRestoration {
    data class Restored(
        val source: String,
        val dependencyIdentifiers: Set<String>,
    ) : TokenPasteSourceRestoration

    data class Preserved(val reason: String) : TokenPasteSourceRestoration
}

internal object SpirvFinalEmitter {
    fun emit(
        request: SpirvOptimizationRequest,
        modules: List<SpirvModuleResult>,
    ): SpirvFinalEmission {
        modules.firstOrNull { it.restorationFailure != null }?.let { module ->
            return preserved(
                request,
                "${module.name}: ${requireNotNull(module.restorationFailure)}",
            )
        }
        val structuralPlan = request.structuralPlan
        if (structuralPlan == null) {
            if (modules.size != 1) {
                return preserved(request, "multiple compiler modules have no structural restoration plan")
            }
            val tokenPasteComplete = when (
                val tokenPaste = restoreTokenPasteSourceMacros(
                    request.sourceName,
                    modules.single().source,
                    modules.single().tokenPasteLowerings,
                )
            ) {
                is TokenPasteSourceRestoration.Restored -> tokenPaste
                is TokenPasteSourceRestoration.Preserved -> return preserved(request, tokenPaste.reason)
            }
            if (tokenPasteComplete.dependencyIdentifiers.isNotEmpty()) {
                return preserved(
                    request,
                    "${request.sourceName}: token-paste source dependencies require structural restoration: " +
                        tokenPasteComplete.dependencyIdentifiers.sorted(),
                )
            }
            val sourceFacing = when (
                val references = modules.single().irisContracts.restoreSourceReferences(tokenPasteComplete.source)
            ) {
                is IrisContractRestoration.Restored -> references.source
                is IrisContractRestoration.StructuralPreservation -> return preserved(request, references.reason)
            }
            return optimizedOrPreserved(request, restoreProbeResources(sourceFacing, modules), modules)
        }
        structuralPlan.restorationPlan.issue?.let { return preserved(request, it) }
        if (modules.any { it.structuralSignature == null }) {
            return preserved(request, "structural compiler module metadata is incomplete")
        }

        val signatures = modules.map { requireNotNull(it.structuralSignature) }
        val varying = ShaderVaryingStructuralSlots.from(signatures)
        val stripped = modules.map { module -> stripStructuralSlots(module, varying) }
        stripped.filterIsInstance<StructuralStripResult.Preserved>().firstOrNull()?.let {
            return preserved(request, it.reason)
        }
        val restoredCores = stripped.filterIsInstance<StructuralStripResult.Restored>()
        val convergence = when (
            val result = convergeStructuralEntities(
                request,
                modules.map(SpirvModuleResult::name),
                restoredCores.map(StructuralStripResult.Restored::source),
                modules.map { module ->
                    module.structuralAssignments.ifEmpty { listOf(module.structuralAssignment) }
                },
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
            )
        ) {
            is StructuralConvergence.Converged -> result
            is StructuralConvergence.Preserved -> return preserved(request, result.reason)
        }
        val structural = when (
            val restoration = convergence.restorationPlan.restore(convergence.source)
        ) {
            is ShaderStructuralRestoration.Restored -> restoration.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, restoration.reason)
        }
        val restorationContracts = modules.first().irisContracts.withRestorationContracts(
            structuralPlan.restorationPlan.restorationContracts,
        )
        val contractSource = when (val contracts = restorationContracts.restore(structural)) {
            is IrisContractRestoration.Restored -> deduplicateUnconditionalDeclarations(contracts.source)
            is IrisContractRestoration.StructuralPreservation -> return preserved(request, contracts.reason)
        }
        val bridgeSettings = modules.flatMap(SpirvModuleResult::bridgeSettings)
            .distinctBy(ShaderSetting::name)
            .sortedBy(ShaderSetting::name)
        val completedBridges = when (
            val completion = SpirvSettingBridge.completeRestoredSettings(
                contractSource,
                modules.first().bridgeSettings,
                bridgeSettings,
            )
        ) {
            is SpirvSettingBridgeRestoration.Restored -> completion
            is SpirvSettingBridgeRestoration.Preserved -> return preserved(request, completion.reason)
        }
        val restored = when (
            val bridges = SpirvSettingBridge.placeAfterDefinitions(
                completedBridges.source,
                completedBridges.settings,
                restorationContracts.contracts,
            )
        ) {
            is SpirvSettingBridgeRestoration.Restored -> bridges.source
            is SpirvSettingBridgeRestoration.Preserved -> return preserved(request, bridges.reason)
        }
        val nativeComplete = when (
            val native = restoreConditionalNativePrimitiveFunctions(
                request.source,
                restored,
                modules.first().irisContracts,
            )
        ) {
            is ConditionalNativePrimitiveRestoration.Restored -> native
            is ConditionalNativePrimitiveRestoration.Preserved -> return preserved(request, native.reason)
        }
        val dependencyComplete = when (
            val dependencies = restoreMissingSourceConstants(
                request,
                nativeComplete.source,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> dependencies.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, dependencies.reason)
        }
        val abiComplete = when (
            val abi = restoreMissingSourceAbiDeclarations(
                request,
                dependencyComplete,
                structuralPlan.restorationPlan,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> abi.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, abi.reason)
        }
        val symbolicAbiComplete = restoreSymbolicSourceAbiDeclarations(request.source, abiComplete)
        val functionComplete = when (
            val functions = restoreMissingSourceFunctions(
                request,
                symbolicAbiComplete,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> functions.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, functions.reason)
        }
        val macroComplete = when (
            val macros = restoreMissingSourceMacros(
                request,
                functionComplete,
                structuralPlan.restorationPlan,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> macros.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, macros.reason)
        }
        val finalConstants = when (
            val constants = restoreMissingSourceConstants(
                request,
                macroComplete,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> constants.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, constants.reason)
        }
        var finalSource = restoreContractHelperFunctions(request, finalConstants)
        finalSource = splitLateBranchOwnedDeclarations(finalSource)
        finalSource = relocateBranchOwnedPrologue(finalSource)
        finalSource = hoistLateDeclarationDependencies(finalSource)
        finalSource = hoistLateReferencedConstants(finalSource)
        finalSource = relocateSourceAbiDeclarations(request, finalSource)
        finalSource = relocateUnconditionalLateSourceAbiDeclarations(request, finalSource)
        finalSource = hoistLateAbiDeclarations(finalSource)
        finalSource = hoistLateAbiQualifierMacros(finalSource)
        finalSource = resolveAbiModifierTokens(finalSource, signatures)
        finalSource = restoreMissingSourceTypeDeclarations(request, finalSource)
        finalSource = when (
            val tokenPaste = restoreTokenPasteSourceDependencies(
                request,
                finalSource,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
                modules.flatMap(SpirvModuleResult::tokenPasteLowerings),
            )
        ) {
            is ShaderStructuralRestoration.Restored -> tokenPaste.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, tokenPaste.reason)
        }
        finalSource = when (
            val dependencies = restoreMissingSourceGlobalDependencies(
                request,
                finalSource,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> dependencies.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, dependencies.reason)
        }
        finalSource = hoistLateDeclarationDependencies(finalSource)
        finalSource = hoistLateReferencedConstants(finalSource)
        finalSource = relocateUnconditionalLateSourceAbiDeclarations(request, finalSource)
        finalSource = hoistLateAbiDeclarations(finalSource)
        finalSource = restoreSourceFunctionConditionalOwners(request.source, finalSource)
        finalSource = deduplicateRelaxedFunctionDefinitions(request.source, finalSource)
        finalSource = ensureForwardFunctionDeclarations(finalSource)
        finalSource = relocateUnconditionalAbiFromFunctionOwners(request, finalSource)
        finalSource = deduplicateUnconditionalDeclarations(finalSource)
        finalSource = hoistLateAbiQualifierMacros(finalSource)
        finalSource = deduplicateDominatedAbiLines(finalSource)
        finalSource = restoreMissingBranchOwnedMain(finalSource, modules, structuralPlan.restorationPlan)
        finalSource = hoistLateDeclarationDependencies(finalSource)
        finalSource = hoistLateAbiDeclarations(finalSource)
        finalSource = removeNonBranchOwnedMainFunctions(finalSource)
        finalSource = when (
            val tokenPaste = restoreTokenPasteSourceDependencies(
                request,
                finalSource,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
                modules.flatMap(SpirvModuleResult::tokenPasteLowerings),
            )
        ) {
            is ShaderStructuralRestoration.Restored -> tokenPaste.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, tokenPaste.reason)
        }
        finalSource = when (
            val dependencies = restoreMissingSourceGlobalDependencies(
                request,
                finalSource,
                structuralPlan.restorationPlan,
                modules.first().irisContracts,
                reachableCodeAndMacroIdentifiers(finalSource),
            )
        ) {
            is ShaderStructuralRestoration.Restored -> dependencies.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, dependencies.reason)
        }
        finalSource = when (val references = restorationContracts.restoreSourceReferences(finalSource)) {
            is IrisContractRestoration.Restored -> references.source
            is IrisContractRestoration.StructuralPreservation -> return preserved(request, references.reason)
        }
        finalSource = when (
            val ordered = restoreDirectiveMacroDependencies(
                request.sourceName,
                request.source,
                finalSource,
                restorationContracts.sourceFacingContracts,
                structuralPlan.restorationPlan,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> ordered.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, ordered.reason)
        }
        finalSource = restoreProbeResources(finalSource, modules)
        return optimizedOrPreserved(
            request,
            finalSource,
            modules,
            convergence.optimizedEntities,
            convergence.restoredEntities + nativeComplete.restoredFunctions,
            convergence.restoredBytes + nativeComplete.restoredBytes,
            nativeComplete.diagnostics,
        )
    }

    private fun optimizedOrPreserved(
        request: SpirvOptimizationRequest,
        source: String,
        modules: List<SpirvModuleResult>,
        optimizedEntities: Int = structuralEntities(source).size,
        restoredEntities: Int = 0,
        restoredBytes: Int = 0,
        restorationDiagnostics: List<String> = emptyList(),
    ): SpirvFinalEmission {
        val processed = IrisFinalSourceProcessor.process(request, source, modules)
        if (processed is IrisFinalSourceProcessing.Preserved) {
            return preserved(request, processed.reason)
        }
        val processedSource = (processed as IrisFinalSourceProcessing.Processed).source
        val finalSource = if (request.stage in RASTER_PIPELINE_STAGES) {
            restoreSourceStageInterfaceOrder(request.source, processedSource)
        } else {
            processedSource
        }
        finalLiveIncludeGuardDependencyIssue(request.sourceName, request.source, finalSource)?.let { issue ->
            return preserved(request, issue)
        }
        val artifact = FINAL_SPECIALIZATION_ARTIFACT.find(finalSource)?.value
        return if (artifact == null) {
            SpirvFinalEmission(
                finalSource.trimEnd() + "\n",
                SpirvEmissionMode.OPTIMIZED,
                null,
                optimizedEntities,
                restoredEntities,
                restoredBytes,
                restorationDiagnostics,
            )
        } else {
            preserved(request, "final optimized GLSL still contains specialization artifact '$artifact'")
        }
    }

    internal fun restoreSourceStageInterfaceOrder(originalSource: String, source: String): String {
        val sourceDeclarations = sourceStructuralEntities(originalSource)
            .filter(::isReorderableStageInterface)
            .sortedBy { it.range.first }
        val uniqueSourceDeclarations = sourceDeclarations.groupBy(StructuralEntity::symbol)
            .values
            .filter { declarations -> declarations.size == 1 && declarations.single().symbol != null }
            .map(List<StructuralEntity>::single)
        val sourceOrder = uniqueSourceDeclarations.withIndex().associate { (index, declaration) ->
            requireNotNull(declaration.symbol) to index
        }
        if (sourceOrder.size < 2) return source

        val declarations = structuralEntities(source)
            .filter(::isReorderableStageInterface)
            .filter { it.symbol in sourceOrder }
            .sortedBy { it.range.first }
        if (declarations.size < 2) return source
        val declarationsBySymbol = declarations.groupBy(StructuralEntity::symbol)
        if (declarationsBySymbol.values.any { it.size != 1 }) return source
        val outputOwnership = conditionalOwnership(source, declarations.map { it.range.first }) ?: return source

        val replacements = declarations.groupBy { declaration ->
            outputOwnership.getValue(declaration.range.first)
        }.values.flatMap { ownedDeclarations ->
            val ordered = ownedDeclarations.sortedBy { sourceOrder.getValue(requireNotNull(it.symbol)) }
            ownedDeclarations.zip(ordered).filter { (slot, declaration) -> slot !== declaration }
        }
        if (replacements.isEmpty()) return source

        return replacements.sortedByDescending { (slot, _) -> slot.range.first }
            .fold(source) { result, (slot, declaration) ->
                result.replaceRange(slot.range, source.substring(declaration.range))
            }
    }

    private fun conditionalOwnership(source: String, offsets: List<Int>): Map<Int, List<Pair<Int, Int>>>? {
        data class LocatedDirective(val directive: PreprocessorDirective, val offset: Int)
        data class ConditionalFrame(val id: Int, var branch: Int, val includeGuard: Boolean)

        val directives = runCatching { PreprocessorProtection.protect(source, "<stage-interface-order>").directives }
            .getOrNull() ?: return null
        val includeGuards = directives.mapIndexedNotNull { index, opener ->
            val next = directives.getOrNull(index + 1)
            opener.conditionalId?.takeIf {
                opener.kind == PreprocessorDirectiveKind.IFNDEF &&
                    opener.macroName != null &&
                    next?.kind == PreprocessorDirectiveKind.DEFINE &&
                    next.macroName == opener.macroName &&
                    next.conditionalDepth == opener.conditionalDepth + 1
            }
        }.toSet()
        var searchOffset = 0
        val located = directives.map { directive ->
            val offset = source.indexOf(directive.exactText, searchOffset)
            if (offset < 0) return null
            searchOffset = offset + directive.exactText.length
            LocatedDirective(directive, offset)
        }
        val frames = mutableListOf<ConditionalFrame>()
        val ownership = mutableMapOf<Int, List<Pair<Int, Int>>>()
        var directiveIndex = 0
        offsets.sorted().forEach { offset ->
            while (directiveIndex < located.size && located[directiveIndex].offset < offset) {
                val directive = located[directiveIndex].directive
                when (directive.kind) {
                    PreprocessorDirectiveKind.IF,
                    PreprocessorDirectiveKind.IFDEF,
                    PreprocessorDirectiveKind.IFNDEF,
                    -> {
                        val id = requireNotNull(directive.conditionalId)
                        frames += ConditionalFrame(id, directive.index, id in includeGuards)
                    }

                    PreprocessorDirectiveKind.ELIF,
                    PreprocessorDirectiveKind.ELSE,
                    -> {
                        if (frames.lastOrNull()?.id != directive.conditionalId) return null
                        frames.last().branch = directive.index
                    }

                    PreprocessorDirectiveKind.ENDIF -> {
                        if (frames.lastOrNull()?.id != directive.conditionalId) return null
                        frames.removeLast()
                    }

                    else -> Unit
                }
                directiveIndex++
            }
            ownership[offset] = frames.filterNot(ConditionalFrame::includeGuard).map { it.id to it.branch }
        }
        return ownership
    }

    private fun isReorderableStageInterface(entity: StructuralEntity): Boolean =
        entity.kind == StructuralEntityKind.DECLARATION &&
            entity.symbol !in STAGE_INTERFACE_QUALIFIER_SYMBOLS &&
            STAGE_INTERFACE_DECLARATION.containsMatchIn(entity.canonical) &&
            !EXPLICIT_INTERFACE_LOCATION.containsMatchIn(entity.canonical)

    private fun preserved(request: SpirvOptimizationRequest, reason: String): SpirvFinalEmission {
        return SpirvFinalEmission(
            request.source,
            SpirvEmissionMode.PRESERVED_SOURCE,
            "${request.sourceName}: $reason",
            0,
            0,
            0,
        )
    }

    internal fun restoreConditionalNativePrimitiveFunctions(
        originalSource: String,
        source: String,
        irisContracts: IrisShaderContractPlan? = null,
    ): ConditionalNativePrimitiveRestoration {
        val originalFunctions = (scanSourceFunctions(originalSource) + scanNamedSourceFunctions(originalSource))
            .distinctBy(StructuralEntity::identity)
        val outputFunctions = (scanSourceFunctions(source) + scanNamedSourceFunctions(source))
            .distinctBy(StructuralEntity::identity)
            .groupBy(StructuralEntity::identity)
        val calls = CONDITIONAL_PARTITIONED_PRIMITIVE_CALL.findAll(maskStructuralCode(originalSource)).toList()
        val owners = nearestConditionalOwnerRanges(originalSource, calls.map { it.range })
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val diagnostics = mutableListOf<String>()
        var restoredBytes = 0

        originalFunctions.forEach { function ->
            val expected = calls.indices.filter { index ->
                owners[index] != null && function.range.containsRange(calls[index].range)
            }.mapTo(sortedSetOf()) { index -> calls[index].groupValues[1] }
            if (expected.isEmpty()) return@forEach
            val candidates = outputFunctions[function.identity].orEmpty()
            if (candidates.isEmpty()) return@forEach
            if (candidates.size > 1) {
                return ConditionalNativePrimitiveRestoration.Preserved(
                    "conditional native primitive owner ${function.identity} has ${candidates.size} optimized matches",
                )
            }
            val output = candidates.single()
            val actual = CONDITIONAL_PARTITIONED_PRIMITIVE_CALL
                .findAll(maskStructuralCode(source.substring(output.range)))
                .mapTo(sortedSetOf()) { it.groupValues[1] }
            val missing = expected - actual
            if (missing.isEmpty()) return@forEach
            val exact = originalSource.substring(function.range)
            val restored = irisContracts?.replaceHostReferences(exact) ?: exact
            replacements += output.range to restored
            restoredBytes += restored.encodeToByteArray().size
            diagnostics += buildString {
                append(function.identity)
                append(": restored exact source owner after optimized GLSL lost conditional native primitives ")
                append(missing.sorted())
            }
        }
        val result = replacements.sortedByDescending { it.first.first }.fold(source) { current, (range, replacement) ->
            current.replaceRange(range, replacement)
        }
        return ConditionalNativePrimitiveRestoration.Restored(
            result,
            replacements.size,
            restoredBytes,
            diagnostics,
        )
    }

    private fun stripStructuralSlots(
        module: SpirvModuleResult,
        varying: ShaderVaryingStructuralSlots,
    ): StructuralStripResult {
        val signature = requireNotNull(module.structuralSignature)
        val expectedResources = signature.resources.filter { it in varying.resources }
            .mapTo(linkedSetOf(), ::normalizeStructuralEntity)
        val expectedInterfaces = signature.stageInterfaces.filter { it in varying.interfaces }
            .mapTo(linkedSetOf(), ::normalizeStructuralEntity)
        val expectedFunctions = signature.functionAbi.filter { it in varying.functionAbi }
            .mapTo(linkedSetOf(), ::normalizeStructuralEntity)
        val removals = mutableListOf<IntRange>()
        val strippedSymbols = linkedSetOf<String>()
        structuralEntities(module.coreSource).forEach { entity ->
            when {
                entity.canonical in expectedResources -> {
                    removals += entity.range
                    entity.symbol?.let(strippedSymbols::add)
                }
                entity.canonical in expectedInterfaces -> {
                    removals += entity.range
                    entity.symbol?.let(strippedSymbols::add)
                }
                entity.canonical in expectedFunctions -> {
                    removals += entity.range
                    entity.symbol?.let(strippedSymbols::add)
                }
            }
        }
        var source = module.coreSource
        removals.distinct().sortedByDescending { it.first }.forEach { range ->
            source = source.removeRange(range.first, range.last + 1)
        }
        return StructuralStripResult.Restored(source.trimEnd() + "\n", strippedSymbols)
    }

    private fun convergeStructuralEntities(
        request: SpirvOptimizationRequest,
        moduleNames: List<String>,
        sources: List<String>,
        assignmentGroups: List<List<Map<String, String>>>,
        restorationPlan: ShaderStructuralRestorationPlan,
        irisContracts: IrisShaderContractPlan,
    ): StructuralConvergence {
        val existingSlots = restorationPlan.islands
        val optimizedEntrySlots = existingSlots.filter { slot ->
            slot.kind == ShaderStructuralEntitySlotKind.FUNCTION &&
                structuralEntities(slot.exactText).any { it.identity == "function:main()" }
        }.toSet()
        val retainedExistingSlots = existingSlots.filterNot(optimizedEntrySlots::contains)
        val existingIdentities = retainedExistingSlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        val withoutKnownSlots = sources.map { source -> removeStructuralEntities(source, existingIdentities) }
        val parsedModules = withoutKnownSlots.map { source ->
            structuralEntities(source).groupBy(StructuralEntity::identity)
        }
        val residues = withoutKnownSlots.map(::normalizedStructuralResidue)
        if (residues.distinct().size != 1) {
            val diagnostics = moduleNames.zip(residues).joinToString(", ") { (name, residue) ->
                "$name=${shortHash(residue)}"
            }
            return StructuralConvergence.Preserved(
                "${request.sourceName}: optimized top-level residue diverged outside source-mappable entities: $diagnostics",
            )
        }

        val identities = parsedModules.flatMapTo(sortedSetOf()) { it.keys }
        val sourceEntities = (
            sourceStructuralEntities(request.source) +
                scanSourceFunctions(request.source) +
                scanNamedSourceFunctions(request.source)
            ).distinctBy(StructuralEntity::range)
        val sourceByIdentity = sourceEntities.groupBy(StructuralEntity::identity).toMutableMap()
        val sourceFunctionsByShape = sourceEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
            .groupBy { relaxedFunctionIdentity(it.identity) }
        identities.forEach { identity ->
            if (identity !in sourceByIdentity && identity.startsWith("function:")) {
                sourceFunctionsByShape[relaxedFunctionIdentity(identity)]?.let { matches ->
                    sourceByIdentity[identity] = matches
                }
            }
        }
        val retainedSlotReferences = retainedExistingSlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).flatMap(StructuralEntity::references)
        }
        val optimizedBySymbol = parsedModules.flatMap { module -> module.values.flatten() }
            .filter { it.symbol != null }
            .groupBy { requireNotNull(it.symbol) }
        val reachableIdentities = linkedSetOf<String>()
        val pendingSymbols = ArrayDeque(retainedSlotReferences)
        parsedModules.flatMap { it["function:main()"].orEmpty() }.forEach { main ->
            reachableIdentities += main.identity
            main.references.forEach(pendingSymbols::addLast)
        }
        while (pendingSymbols.isNotEmpty()) {
            val symbol = pendingSymbols.removeFirst()
            optimizedBySymbol[symbol].orEmpty().forEach { entity ->
                if (reachableIdentities.add(entity.identity)) {
                    entity.references.forEach(pendingSymbols::addLast)
                }
            }
        }
        val divergent = identities.filterTo(linkedSetOf()) { identity ->
            parsedModules.map { entities -> entities[identity]?.map(StructuralEntity::semantic) }.distinct().size != 1 &&
                (
                    identity in reachableIdentities ||
                    sourceByIdentity[identity].orEmpty().any { it.symbol in retainedSlotReferences }
                )
        }
        val unmappable = divergent.filter { sourceByIdentity[it].isNullOrEmpty() }
        val unsupportedUnmappable = unmappable.filter { identity ->
            identity != "function:main()" && parsedModules.any { module ->
                module[identity].orEmpty().any { it.kind != StructuralEntityKind.DECLARATION }
            }
        }
        if (unsupportedUnmappable.isNotEmpty()) {
            val diagnostics = unsupportedUnmappable.joinToString(", ") { identity ->
                val variants = parsedModules.mapIndexed { index, entities ->
                    entities[identity]?.joinToString("\u0000") { it.semantic }?.let(::shortHash)
                        ?.let { "${moduleNames[index]}=$it" }
                        ?: "${moduleNames[index]}=missing"
                }
                "$identity[${variants.joinToString()}] source_matches=${sourceByIdentity[identity]?.size ?: 0}"
            }
            return StructuralConvergence.Preserved(
                "${request.sourceName}: optimized entity divergence is not uniquely source-mappable: $diagnostics",
            )
        }

        val promoted = divergent.toMutableSet()
        val optimizedIdentities = parsedModules.flatMapTo(hashSetOf()) { it.keys }
        val optimizedSymbols = parsedModules.flatMapTo(hashSetOf()) { module ->
            module.values.flatten().mapNotNull(StructuralEntity::symbol)
        }
        val missingModuleReferences = withoutKnownSlots.flatMapTo(linkedSetOf()) { source ->
            structuralReferences(source, null)
        }.filterTo(linkedSetOf()) { it !in optimizedSymbols }
        val objectAliases = objectAliases(request.source)
        sourceEntities.filterTo(mutableListOf()) { entity ->
            val symbol = entity.symbol
            symbol in retainedSlotReferences + missingModuleReferences &&
                (
                    entity.kind != StructuralEntityKind.DECLARATION ||
                        resolveObjectAlias(requireNotNull(symbol), objectAliases) !in optimizedSymbols
                ) &&
                entity.identity !in existingIdentities
        }.mapTo(promoted, StructuralEntity::identity)
        var changed: Boolean
        do {
            changed = false
            val promotedSymbols = promoted.flatMapTo(hashSetOf()) { identity ->
                sourceByIdentity[identity].orEmpty().mapNotNull(StructuralEntity::symbol)
            }
            val promotedReferences = promoted.flatMapTo(hashSetOf()) { identity ->
                sourceByIdentity[identity].orEmpty().flatMap(StructuralEntity::references)
            }
            sourceEntities.forEach { entity ->
                if (
                    entity.identity !in promoted &&
                    entity.kind != StructuralEntityKind.DECLARATION &&
                    (
                        entity.identity in optimizedIdentities && entity.references.any(promotedSymbols::contains) ||
                            entity.symbol in promotedReferences
                    )
                ) {
                    promoted += entity.identity
                    changed = true
                }
            }
        } while (changed)

        val expandedBranches = sources.indices.flatMap { index ->
            assignmentGroups[index].map { assignment ->
                sources[index] to assignment
            }
        }
        val branchOwnedMain = if ("function:main()" in promoted) {
            renderBranchOwnedMain(
                expandedBranches.map { it.first },
                expandedBranches.map { it.second },
                restorationPlan,
                (unmappable - "function:main()").toSet(),
            )
        } else {
            null
        }
        val sourcePromoted = promoted.filterTo(linkedSetOf()) { identity ->
            identity != "function:main()" && sourceByIdentity[identity].orEmpty().isNotEmpty()
        }
        val removalIdentities = promoted + branchOwnedMain?.ownedIdentities.orEmpty()
        val convergedSources = withoutKnownSlots.map { source -> removeStructuralEntities(source, removalIdentities) }
        val commonCore = branchOwnedMain?.let { entry ->
            val core = convergedSources.first().trimEnd()
            val firstFunction = structuralEntities(core)
                .firstOrNull { it.kind == StructuralEntityKind.FUNCTION }
            val withPrologue = if (entry.prologue.isBlank()) {
                core
            } else if (firstFunction == null) {
                "$core\n\n${entry.prologue.trim()}"
            } else {
                core.substring(0, firstFunction.range.first) + entry.prologue.trim() + "\n\n" +
                    core.substring(firstFunction.range.first).trimEnd()
            }
            "$withPrologue\n\n${entry.source.trim()}\n"
        } ?: convergedSources.first().trimEnd() + "\n"
        data class DynamicSourceSlot(
            val range: IntRange,
            val kind: ShaderStructuralEntitySlotKind,
            val canonicalEntity: String?,
        )
        val dynamicEntities = sourcePromoted.flatMap { sourceByIdentity.getValue(it) }
            .distinctBy { it.identity to it.semantic }
        val dynamicOwners = restorationPlan.structuralOwnerRanges(
            request.source,
            dynamicEntities.map(StructuralEntity::range),
        )
        val dynamicConditionalOwners = nearestConditionalOwnerRanges(
            request.source,
            dynamicEntities.map(StructuralEntity::range),
        )
        val dynamicCandidates = dynamicEntities.indices.map { index ->
            val entity = dynamicEntities[index]
            val owner = dynamicOwners[index] ?: dynamicConditionalOwners[index]
            owner?.let {
                DynamicSourceSlot(owner, ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION, null)
            } ?: DynamicSourceSlot(
                entity.range,
                if (entity.kind == StructuralEntityKind.FUNCTION) {
                    ShaderStructuralEntitySlotKind.FUNCTION
                } else {
                    ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION
                },
                entity.canonical,
            )
        }.distinctBy(DynamicSourceSlot::range)
        val dynamicSlots = dynamicCandidates.filterNot { candidate ->
            dynamicCandidates.any { other ->
                other !== candidate && other.range != candidate.range && other.range.containsRange(candidate.range)
            }
        }.sortedBy { it.range.first }
        val retainedSlots = retainedExistingSlots.filterNot { slot ->
            occurrences(request.source, slot.exactText).singleOrNull()?.let { range ->
                dynamicSlots.any { it.range.containsRange(range) }
            } == true
        }
        val restoredOwnedIdentities = retainedSlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        dynamicSlots.flatMapTo(restoredOwnedIdentities) { slot ->
            structuralEntities(request.source.substring(slot.range)).map(StructuralEntity::identity)
        }
        val restorationCore = removeStructuralEntities(
            commonCore,
            restoredOwnedIdentities - optimizedRetainedEntityIdentities(
                commonCore,
                retainedSlots + dynamicSlots.mapIndexed { index, slot ->
                    ShaderStructuralEntitySlot(
                        ordinal = retainedSlots.size + index,
                        kind = slot.kind,
                        canonicalEntity = slot.canonicalEntity,
                        exactText = request.source.substring(slot.range),
                        sourceLine = sourceLine(request.source, slot.range.first),
                        beforeAnchor = null,
                        afterAnchor = null,
                        placement = IrisAnchorPlacement.AFTER_BEFORE,
                    )
                },
                commonCore + branchOwnedMain?.prologue.orEmpty() + branchOwnedMain?.source.orEmpty(),
            ),
        )
        val dependencySlots = sourceMacroDependencySlots(
            request.source,
            restorationCore,
            retainedSlots.map(ShaderStructuralEntitySlot::exactText) +
                dynamicSlots.map { request.source.substring(it.range) } +
                listOfNotNull(branchOwnedMain?.prologue, branchOwnedMain?.source),
            restorationPlan,
        )
        val dependencyOwnedIdentities = dependencySlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        val dependencyCore = removeStructuralEntities(
            restorationCore,
            dependencyOwnedIdentities - optimizedRetainedEntityIdentities(
                restorationCore,
                dependencySlots,
                restorationCore + branchOwnedMain?.prologue.orEmpty() + branchOwnedMain?.source.orEmpty(),
            ),
        )
        val optimized = structuralEntities(dependencyCore)
        if (optimized.none { it.kind == StructuralEntityKind.FUNCTION } && branchOwnedMain == null) {
            return StructuralConvergence.Preserved(
                buildString {
                    append("${request.sourceName}: whole-entity restoration would leave no optimized executable entity")
                    append("; promoted=")
                    append(promoted.sorted())
                    append("; slots=")
                    append(existingSlots.map { slot ->
                        "${slot.kind}:${slot.canonicalEntity}:${structuralEntities(slot.exactText).map(StructuralEntity::identity)}"
                    })
                    append("; structural_settings=")
                    append(restorationPlan.structuralSettings.sorted())
                    append("; assignments=")
                    append(assignmentGroups.flatten().map(Map<String, String>::toSortedMap))
                    append("; main_counts=")
                    append(parsedModules.map { it["function:main()"]?.size ?: 0 })
                },
            )
        }
        val restorationCandidates = retainedSlots + dependencySlots + dynamicSlots.mapIndexed { index, slot ->
                ShaderStructuralEntitySlot(
                    ordinal = retainedSlots.size + dependencySlots.size + index,
                    kind = slot.kind,
                    canonicalEntity = slot.canonicalEntity,
                    exactText = request.source.substring(slot.range),
                    sourceLine = sourceLine(request.source, slot.range.first),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            }
        val uniqueCandidates = restorationCandidates.distinctBy(ShaderStructuralEntitySlot::exactText)
        val positionedCandidates = uniqueCandidates.associateWith { slot ->
            occurrences(request.source, slot.exactText).singleOrNull()
        }
        val collapsedCandidates = uniqueCandidates.filterNot { candidate ->
            val candidateRange = positionedCandidates[candidate] ?: return@filterNot false
            uniqueCandidates.any { owner ->
                owner !== candidate && positionedCandidates[owner]?.let { ownerRange ->
                    ownerRange != candidateRange && ownerRange.containsRange(candidateRange)
                } == true
            }
        }.mapIndexed { index, slot -> slot.copy(ordinal = index) }
        val anchoredSlots = reanchorRestorationSlots(
            request,
            collapsedCandidates,
        ) ?: return StructuralConvergence.Preserved(
            "${request.sourceName}: promoted entities have no stable restoration anchor: " +
                sourcePromoted.sorted().joinToString() + "; occurrences=" +
                dynamicSlots.joinToString { slot ->
                    "${sourceLine(request.source, slot.range.first)}:" +
                        occurrences(request.source, request.source.substring(slot.range)).size
                } + "; existing_slots=" + retainedExistingSlots.joinToString { slot ->
                    "${slot.kind}@${slot.sourceLine}:${slot.exactText.length}:${slot.beforeAnchor}:${slot.afterAnchor}"
                },
        )
        val allSlots = anchoredSlots.map { slot ->
            slot.copy(exactText = irisContracts.replaceHostReferences(slot.exactText))
        }
        val restoredIdentities = allSlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        return StructuralConvergence.Converged(
            source = dependencyCore,
            restorationPlan = restorationPlan.copy(islands = allSlots, issue = null),
            optimizedEntities = optimized.size + if (branchOwnedMain == null) 0 else 1,
            restoredEntities = restoredIdentities.size + allSlots.count {
                structuralEntities(it.exactText).isEmpty()
            },
            restoredBytes = allSlots.distinctBy(ShaderStructuralEntitySlot::exactText)
                .sumOf { it.exactText.encodeToByteArray().size },
        )
    }

    private fun renderBranchOwnedMain(
        sources: List<String>,
        assignments: List<Map<String, String>>,
        restorationPlan: ShaderStructuralRestorationPlan,
        branchOwnedDeclarationIdentities: Set<String>,
    ): BranchOwnedMain? {
        val entities = sources.map { source ->
            scanNamedSourceFunctions(source).filter { entity -> entity.symbol == "main" }.singleOrNull() ?: return null
        }
        val payloads = sources.indices.map { index ->
            branchOwnedPayload(sources[index], entities[index], branchOwnedDeclarationIdentities)
        }
        val semantics = payloads.map(BranchOwnedPayload::semantic)
        if (semantics.distinct().size < 2) {
            return BranchOwnedMain(
                payloads.first().declarations,
                payloads.first().entry,
                payloads.flatMapTo(linkedSetOf()) { it.ownedIdentities },
            )
        }
        val settings = restorationPlan.settings.filter { it.name in restorationPlan.structuralSettings }
            .associateBy { it.name }
        val defaults = settings.mapValues { it.value.defaultValue }
        val defaultIndex = assignments.indexOfFirst { assignment ->
            defaults.all { (name, value) -> (assignment[name] ?: value) == value }
        }.takeIf { it >= 0 } ?: assignments.indices.minWithOrNull(
            compareBy<Int> { index ->
                settings.count { (name, setting) ->
                    (assignments[index][name] ?: setting.defaultValue) != setting.defaultValue
                }
            }.thenBy { index -> assignments[index].toSortedMap().toString() },
        ) ?: return null
        val defaultSemantic = semantics[defaultIndex]
        val relevant = assignments.indices.filter { semantics[it] != defaultSemantic }
            .flatMapTo(sortedSetOf()) { index ->
                settings.keys.filter { name ->
                    (assignments[index][name] ?: defaults.getValue(name)) != defaults.getValue(name)
                }
            }
        if (relevant.isEmpty()) return null
        val grouped = semantics.indices.groupBy { semantics[it] }
        val branches = grouped.entries.filter { it.key != defaultSemantic }.map { (_, indexes) ->
            val predicates = indexes.map { index ->
                renderStructuralAssignmentPredicate(assignments[index], relevant, settings)
            }.distinct().sorted()
            predicates.joinToString(" || ", "(", ")") { "($it)" } to indexes.first()
        }.sortedBy { it.first }
        if (branches.isEmpty()) return null
        fun renderBlock(begin: String, end: String, payload: (BranchOwnedPayload) -> String): String = buildString {
            appendLine(begin)
            branches.forEachIndexed { index, (predicate, payloadIndex) ->
                append(if (index == 0) "#if " else "#elif ")
                appendLine(predicate)
                appendLine(payload(payloads[payloadIndex]).trim())
            }
            appendLine("#else")
            appendLine(payload(payloads[defaultIndex]).trim())
            appendLine("#endif")
            append(end)
        }
        val prologue = if (payloads.all { it.declarations.isBlank() }) "" else {
            renderBlock(BRANCH_OWNED_PROLOGUE_BEGIN, BRANCH_OWNED_PROLOGUE_END, BranchOwnedPayload::declarations)
        }
        return BranchOwnedMain(
            prologue,
            renderBlock(BRANCH_OWNED_MAIN_BEGIN, BRANCH_OWNED_MAIN_END, BranchOwnedPayload::entry),
            payloads.flatMapTo(linkedSetOf()) { it.ownedIdentities },
        )
    }

    private fun branchOwnedPayload(
        source: String,
        main: StructuralEntity,
        branchOwnedDeclarationIdentities: Set<String>,
    ): BranchOwnedPayload {
        val declarations = structuralEntities(source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                (entity.identity in branchOwnedDeclarationIdentities ||
                    entity.symbol?.let(GENERATED_IDENTIFIER::matches) == true)
        }
        val required = main.references.toMutableSet()
        val selected = linkedSetOf<StructuralEntity>()
        var changed: Boolean
        do {
            changed = false
            declarations.forEach { declaration ->
                if (declaration !in selected && declaration.symbol in required) {
                    selected += declaration
                    required += declaration.references
                    changed = true
                }
            }
        } while (changed)
        val ordered = selected.sortedBy { it.range.first }
        val declarationSource = ordered.joinToString("\n") { source.substring(it.range) }
        val entry = source.substring(main.range)
        val payload = listOf(declarationSource, entry).filter(String::isNotBlank).joinToString("\n")
        return BranchOwnedPayload(
            declarations = declarationSource,
            entry = entry,
            semantic = normalizeStructuralEntity(payload),
            ownedIdentities = ordered.mapTo(linkedSetOf()) { it.identity },
        )
    }

    private fun renderStructuralAssignmentPredicate(
        assignment: Map<String, String>,
        relevant: Set<String>,
        settings: Map<String, ShaderSetting>,
    ): String {
        return relevant.sorted().joinToString(" && ") { name ->
            val setting = settings.getValue(name)
            val value = assignment[name] ?: setting.defaultValue
            if (setting.presenceToggle) {
                if (value == "true" || value == "1") "defined($name)" else "!defined($name)"
            } else {
                "$name == $value"
            }
        }
    }

    private fun sourceMacroDependencySlots(
        source: String,
        commonCore: String,
        dependencyTexts: List<String>,
        restorationPlan: ShaderStructuralRestorationPlan,
        includeSettingMacros: Boolean = false,
        definitionOffsetLimit: Int? = null,
    ): List<ShaderStructuralEntitySlot> {
        fun restorable(name: String): Boolean =
            restorableSourceMacro(name) || includeSettingMacros && name.startsWith("SETTING_")
        val definitions = sourceMacroDefinitions(source)
        val dependencyIdentifiers = dependencyTexts.flatMapTo(linkedSetOf()) { text ->
            DECLARATION_IDENTIFIER.findAll(text).map(MatchResult::value)
        }
        val required = dependencyIdentifiers.filterTo(linkedSetOf()) {
            it in definitions && restorable(it)
        }
        val selected = linkedMapOf<String, List<SourceMacroDefinition>>()
        val pending = ArrayDeque(required)
        var ownedRanges = emptyList<IntRange>()
        while (true) {
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name in selected) continue
                val candidates = definitions[name].orEmpty().filter { definition ->
                    definitionOffsetLimit == null || definition.offset < definitionOffsetLimit
                }
                val minimumDepth = candidates.minOfOrNull(SourceMacroDefinition::depth) ?: continue
                val shallowest = candidates.filter { it.depth == minimumDepth }
                selected[name] = shallowest
                shallowest.asSequence().flatMap { definition ->
                    DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                }
                    .filter { it in definitions && it !in selected && restorable(it) }
                    .forEach(pending::addLast)
            }
            val definitionRanges = selected.values.flatten().map { definition ->
                definition.offset until definition.offset + definition.exactText.length
            }
            val conditionalOwners = nearestConditionalOwnerRanges(source, definitionRanges)
            ownedRanges = restorationPlan.structuralOwnerRanges(source, definitionRanges)
                .zip(conditionalOwners).zip(definitionRanges) { (owner, conditional), definition ->
                    val boundedOwner = owner?.takeIf { range ->
                        definitionOffsetLimit == null || range.last < definitionOffsetLimit
                    }
                    val boundedConditional = conditional?.takeIf { range ->
                        definitionOffsetLimit == null || range.last < definitionOffsetLimit
                    }
                    val enclosing = boundedOwner ?: boundedConditional
                    if (
                        boundedConditional != null && isIncludeGuardOwner(source, boundedConditional) ||
                        enclosing != null && isIncludeGuardOwner(source, enclosing)
                    ) definition else enclosing ?: definition
                }
            val ownerDependencies = ownedRanges.asSequence().flatMap { range ->
                source.substring(range).lineSequence()
                    .map(String::trimStart)
                    .filter { line ->
                        line.startsWith("#if ") || line.startsWith("#if\t") ||
                            line.startsWith("#ifdef") || line.startsWith("#ifndef") ||
                            line.startsWith("#elif ") || line.startsWith("#elif\t")
                    }
                    .flatMap { line -> DECLARATION_IDENTIFIER.findAll(line).map(MatchResult::value) }
            }
                .filter { it in definitions && it !in selected && restorable(it) }
                .distinct()
                .toList()
            if (ownerDependencies.isEmpty()) break
            ownerDependencies.forEach(pending::addLast)
        }
        val macroRanges = ownedRanges.distinct().filterNot { candidate ->
            ownedRanges.any { owner ->
                owner != candidate && owner.containsRange(candidate)
            }
        }.filterNot { commonCore.contains(source.substring(it)) }.sortedBy(IntRange::first)
        val macroSlots = macroRanges.mapIndexed { index, range ->
                ShaderStructuralEntitySlot(
                    ordinal = index,
                    kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    canonicalEntity = null,
                    exactText = source.substring(range),
                    sourceLine = sourceLine(source, range.first),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            }
        val declaredSymbols = structuralEntities(commonCore).mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val sourceMask = maskStructuralCode(source)
        val sourceBraceDepths = structuralBraceDepths(StructuralBranchMaskResolver(source, sourceMask).defaultMask())
        val constantSlots = dependencyIdentifiers.filter { it !in declaredSymbols }.mapNotNull { name ->
            val declaration = sourceConstDefinition(name).findAll(source).singleOrNull() ?: return@mapNotNull null
            if (sourceBraceDepths[declaration.range.first] != 0) return@mapNotNull null
            ShaderStructuralEntitySlot(
                ordinal = 0,
                kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                canonicalEntity = null,
                exactText = declaration.value,
                sourceLine = sourceLine(source, declaration.range.first),
                beforeAnchor = null,
                afterAnchor = null,
                placement = IrisAnchorPlacement.AFTER_BEFORE,
            )
        }
        val sourceEntities = sourceStructuralEntities(source)
        val coreEntities = structuralEntities(commonCore)
        val sourceFunctions = sourceEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
        val sourceFunctionsByName = sourceFunctions.groupBy { it.symbol }
        val coreFunctionIdentities = coreEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
            .mapTo(hashSetOf(), StructuralEntity::identity)
        val coreFunctionNames = coreEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
            .mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val coreReferences = coreEntities.flatMapTo(linkedSetOf(), StructuralEntity::references)
        val macroReferences = selected.values.flatten().flatMapTo(linkedSetOf()) { definition ->
            DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
        }
        val requiredFunctions = (dependencyIdentifiers + macroReferences + coreReferences.filter { it !in coreFunctionNames })
            .filterTo(linkedSetOf()) { it in sourceFunctionsByName }
        val selectedFunctions = linkedMapOf<String, StructuralEntity>()
        val pendingFunctions = ArrayDeque(requiredFunctions)
        while (pendingFunctions.isNotEmpty()) {
            val name = pendingFunctions.removeFirst()
            sourceFunctionsByName[name].orEmpty().forEach { function ->
                if (function.identity in coreFunctionIdentities || function.identity in selectedFunctions) return@forEach
                selectedFunctions[function.identity] = function
                function.references.filter { it in sourceFunctionsByName }
                    .forEach(pendingFunctions::addLast)
            }
        }
        val functionRanges = selectedFunctions.values.map(StructuralEntity::range)
        val functionConditionalOwners = nearestConditionalOwnerRanges(source, functionRanges)
        val functionOwnedRanges = restorationPlan.structuralOwnerRanges(source, functionRanges)
            .zip(functionConditionalOwners).zip(functionRanges) { (owner, conditional), function ->
                val enclosing = owner ?: conditional
                if (
                    conditional != null && isIncludeGuardOwner(source, conditional) ||
                    enclosing != null && isIncludeGuardOwner(source, enclosing)
                ) function else enclosing ?: function
            }
            .distinct()
        val sourceDeclarationsByName = sourceEntities.filter { it.kind == StructuralEntityKind.DECLARATION }
            .groupBy { it.symbol }
        val coreDeclarationIdentities = coreEntities.filter { it.kind == StructuralEntityKind.DECLARATION }
            .mapTo(hashSetOf(), StructuralEntity::identity)
        val coreDeclarationNames = coreEntities.filter { it.kind == StructuralEntityKind.DECLARATION }
            .mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val selectedDeclarations = linkedMapOf<String, StructuralEntity>()
        val pendingDeclarations = ArrayDeque(
            (
                dependencyIdentifiers + macroReferences +
                    selectedFunctions.values.flatMapTo(linkedSetOf(), StructuralEntity::references) +
                    coreReferences.filter { it !in coreDeclarationNames }
                )
                .filter { it in sourceDeclarationsByName },
        )
        while (pendingDeclarations.isNotEmpty()) {
            val name = pendingDeclarations.removeFirst()
            sourceDeclarationsByName[name].orEmpty().forEach { declaration ->
                if (
                    declaration.identity in coreDeclarationIdentities ||
                    declaration.identity in selectedDeclarations
                ) {
                    return@forEach
                }
                selectedDeclarations[declaration.identity] = declaration
                declaration.references.filter { it in sourceDeclarationsByName }
                    .forEach(pendingDeclarations::addLast)
            }
        }
        val declarationRanges = selectedDeclarations.values.map(StructuralEntity::range)
        val declarationConditionalOwners = nearestConditionalOwnerRanges(source, declarationRanges)
        val declarationOwnedRanges = restorationPlan.structuralOwnerRanges(source, declarationRanges)
            .zip(declarationConditionalOwners).zip(declarationRanges) { (owner, conditional), declaration ->
                val enclosing = owner ?: conditional
                if (
                    conditional != null && isIncludeGuardOwner(source, conditional) ||
                    enclosing != null && isIncludeGuardOwner(source, enclosing)
                ) declaration else enclosing ?: declaration
            }
            .distinct()
        val dependencyEntityRanges = (functionOwnedRanges + declarationOwnedRanges).distinct().filterNot { candidate ->
            (functionOwnedRanges + declarationOwnedRanges).any { owner ->
                owner != candidate && owner.containsRange(candidate)
            }
        }
        val dependencyEntitySlots = dependencyEntityRanges.map { range ->
            ShaderStructuralEntitySlot(
                ordinal = 0,
                kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                canonicalEntity = null,
                exactText = source.substring(range),
                sourceLine = sourceLine(source, range.first),
                beforeAnchor = null,
                afterAnchor = null,
                placement = IrisAnchorPlacement.AFTER_BEFORE,
            )
        }
        return (macroSlots + constantSlots + dependencyEntitySlots)
            .distinctBy(ShaderStructuralEntitySlot::exactText)
            .sortedBy(ShaderStructuralEntitySlot::sourceLine)
            .mapIndexed { index, slot -> slot.copy(ordinal = index) }
    }

    private fun sourceConstDefinition(name: String): Regex = Regex(
        "(?m)^[\\t ]*const[\\t ]+[A-Za-z_][A-Za-z0-9_]*(?:[\\t ]*\\[[^\\r\\n;]*])?[\\t ]+" +
            Regex.escape(name) + "[\\t ]*(?:\\[[^\\r\\n;]*])?[\\t ]*=[^;\\r\\n]*;[^\\r\\n]*" +
            "(?:\\r\\n|\\n|\\r|$)",
    )

    private fun restoreMissingSourceConstants(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
        irisContracts: IrisShaderContractPlan,
    ): ShaderStructuralRestoration {
        val declared = structuralEntities(source).mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val referenced = DECLARATION_IDENTIFIER.findAll(maskStructuralSource(source))
            .mapTo(linkedSetOf(), MatchResult::value)
        val sourceConstants = sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION && entity.symbol != null &&
                sourceConstDefinition(requireNotNull(entity.symbol))
                    .matches(request.source.substring(entity.range))
        }.groupBy { requireNotNull(it.symbol) }
        val selected = linkedMapOf<String, StructuralEntity>()
        val pending = ArrayDeque(referenced.filter { it !in declared })
        while (pending.isNotEmpty()) {
            val referencedName = pending.removeFirst()
            val sourceName = irisContracts.sourceDynamicName(referencedName) ?: referencedName
            if (sourceName in selected || sourceName in declared) continue
            val declaration = sourceConstants[sourceName]?.singleOrNull() ?: continue
            selected[sourceName] = declaration
            declaration.references.filter { dependency ->
                val dependencyName = irisContracts.sourceDynamicName(dependency) ?: dependency
                dependencyName !in declared && dependencyName !in selected && sourceConstants[dependencyName]?.size == 1
            }.forEach(pending::addLast)
        }
        val slots = selected.values.sortedBy { it.range.first }.map { declaration ->
            ShaderStructuralEntitySlot(
                ordinal = 0,
                kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                canonicalEntity = null,
                exactText = request.source.substring(declaration.range),
                sourceLine = sourceLine(request.source, declaration.range.first),
                beforeAnchor = null,
                afterAnchor = null,
                placement = IrisAnchorPlacement.AFTER_BEFORE,
            )
        }.distinctBy(ShaderStructuralEntitySlot::exactText)
        if (slots.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val anchored = reanchorRestorationSlots(request, slots)
            ?: return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: missing source constants have no stable restoration anchor",
            )
        return restorationPlan.copy(
            islands = anchored.map { slot ->
                slot.copy(exactText = renderRestoredSourceConstant(slot.exactText, irisContracts))
            },
            restorationContracts = emptyList(),
            issue = null,
        ).restore(source)
    }

    private fun restoreMissingSourceGlobalDependencies(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
        irisContracts: IrisShaderContractPlan,
        additionalReferences: Set<String> = emptySet(),
    ): ShaderStructuralRestoration {
        val declared = sourceStructuralEntities(source).mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val sourceDeclarations = sourceStructuralEntities(request.source).filter { entity ->
            entity.symbol != null &&
                (
                    entity.kind == StructuralEntityKind.FUNCTION ||
                        entity.kind == StructuralEntityKind.DECLARATION &&
                        !structuralDeclarationPrototype(request.source.substring(entity.range))
                    )
        }.groupBy { requireNotNull(it.symbol) }
        additionalReferences.sorted().firstOrNull { name ->
            val candidates = sourceDeclarations[name].orEmpty()
            name !in declared && candidates.size > 1 && candidates.any { it.kind != StructuralEntityKind.FUNCTION }
        }?.let { name ->
            return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: token-paste declaration dependency $name is ambiguous " +
                    "(${sourceDeclarations.getValue(name).size} source declarations)",
            )
        }
        fun candidates(name: String): List<StructuralEntity> {
            val items = sourceDeclarations[name].orEmpty()
            return if (items.size <= 1 || items.all { it.kind == StructuralEntityKind.FUNCTION }) items else emptyList()
        }
        val selected = linkedMapOf<IntRange, StructuralEntity>()
        val directReferences = DECLARATION_IDENTIFIER.findAll(maskStructuralCode(source)).map(MatchResult::value)
            .toCollection(linkedSetOf())
        val pending = ArrayDeque(
            (directReferences + additionalReferences).asSequence()
                .filter { it !in declared && candidates(it).isNotEmpty() }
                .toCollection(linkedSetOf()),
        )
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in declared || selected.values.any { it.symbol == name }) continue
            val dependencies = candidates(name)
            if (dependencies.isEmpty()) continue
            dependencies.forEach { dependency -> selected[dependency.range] = dependency }
            dependencies.flatMap(StructuralEntity::references).filter { dependency ->
                dependency !in declared && selected.values.none { it.symbol == dependency } &&
                    candidates(dependency).isNotEmpty()
            }.forEach(pending::addLast)
        }
        if (selected.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val declarationRanges = selected.values.map(StructuralEntity::range)
        val structuralOwners = restorationPlan.structuralOwnerRanges(request.source, declarationRanges)
        val conditionalOwners = nearestConditionalOwnerRanges(request.source, declarationRanges)
        val resolvedRanges = declarationRanges.indices.map { index ->
            val declaration = declarationRanges[index]
            val structuralOwner = structuralOwners[index]
            val conditionalOwner = conditionalOwners[index]
            val enclosing = structuralOwner ?: conditionalOwner
            if (
                conditionalOwner != null && isIncludeGuardOwner(request.source, conditionalOwner) ||
                enclosing != null && isIncludeGuardOwner(request.source, enclosing)
            ) declaration else enclosing ?: declaration
        }.distinct()
        val ownedRanges = resolvedRanges.filterNot { candidate ->
            resolvedRanges.any { owner -> owner != candidate && owner.containsRange(candidate) }
        }
        val slots = ownedRanges.filterNot { range -> source.contains(request.source.substring(range)) }
            .map { range ->
                ShaderStructuralEntitySlot(
                    ordinal = 0,
                    kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    canonicalEntity = null,
                    exactText = request.source.substring(range),
                    sourceLine = sourceLine(request.source, range.first),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            }.distinctBy(ShaderStructuralEntitySlot::exactText)
        if (slots.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val anchored = reanchorRestorationSlots(request, slots)
            ?: return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: missing source declaration dependencies have no stable restoration anchor",
            )
        return restorationPlan.copy(
            islands = anchored.map { slot ->
                slot.copy(exactText = irisContracts.replaceHostReferences(slot.exactText))
            },
            restorationContracts = emptyList(),
            issue = null,
        ).restore(source)
    }

    private fun restoreMissingSourceAbiDeclarations(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
    ): ShaderStructuralRestoration {
        val emittedDeclarations = structuralEntities(source)
            .filter { entity -> entity.kind == StructuralEntityKind.DECLARATION }
            .mapTo(hashSetOf(), StructuralEntity::identity)
        val sourceDeclarations = sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val missing = sourceDeclarations.filter { declaration -> declaration.identity !in emittedDeclarations }
        if (missing.isEmpty()) return ShaderStructuralRestoration.Restored(source)

        val declarationRanges = missing.map(StructuralEntity::range)
        val resolvedOwners = abiRestorationOwners(request.source, declarationRanges)
        val ownedRanges = resolvedOwners.distinct().filterNot { candidate ->
            resolvedOwners.any { owner ->
                owner != candidate && owner.containsRange(candidate)
            }
        }
        val slots = ownedRanges.filterNot { range -> source.contains(request.source.substring(range)) }
            .map { range ->
                ShaderStructuralEntitySlot(
                    ordinal = 0,
                    kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    canonicalEntity = null,
                    exactText = request.source.substring(range),
                    sourceLine = sourceLine(request.source, range.first),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            }
        if (slots.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val anchored = reanchorRestorationSlots(request, slots)
            ?: return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: missing source ABI declarations have no stable restoration anchor",
            )
        return restorationPlan.copy(
            islands = anchored,
            restorationContracts = emptyList(),
            issue = null,
        ).restore(source)
    }

    internal fun restoreSymbolicSourceAbiDeclarations(
        originalSource: String,
        source: String,
    ): String {
        val sourceDeclarations = sourceStructuralEntities(originalSource).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical) &&
                SYMBOLIC_ARRAY_EXTENT.containsMatchIn(originalSource.substring(entity.range))
        }
        if (sourceDeclarations.isEmpty()) return source
        val sourceOwners = nearestConditionalOwnerRanges(
            originalSource,
            sourceDeclarations.map(StructuralEntity::range),
        )
        val uniqueSource = sourceDeclarations.indices.mapNotNull { index ->
            val declaration = sourceDeclarations[index]
            val owner = sourceOwners[index]
            if (owner != null && !isIncludeGuardOwner(originalSource, owner)) return@mapNotNull null
            declaration
        }.groupBy(StructuralEntity::identity).mapNotNull { (_, declarations) ->
            declarations.singleOrNull()
        }
        var result = source
        uniqueSource.forEach { sourceDeclaration ->
            val outputDeclarations = structuralEntities(result).filter { entity ->
                entity.kind == StructuralEntityKind.DECLARATION && entity.identity == sourceDeclaration.identity
            }
            if (outputDeclarations.isEmpty()) return@forEach
            val outputOwners = nearestConditionalOwnerRanges(
                result,
                outputDeclarations.map(StructuralEntity::range),
            )
            val firstIndex = outputDeclarations.indices.minBy { outputDeclarations[it].range.first }
            val first = outputDeclarations[firstIndex]
            val owner = outputOwners[firstIndex]
            val insertionOffset = if (owner != null && !isIncludeGuardOwner(result, owner)) {
                owner.first
            } else {
                first.range.first
            }
            val ranges = outputDeclarations.map(StructuralEntity::range)
            val adjustedOffset = insertionOffset - ranges.filter { it.last < insertionOffset }.sumOf(IntRange::count)
            val stripped = removeRanges(result, ranges)
            val declared = structuralEntities(stripped).mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
            val pending = ArrayDeque(sourceDeclaration.references.filter { it !in declared })
            val constants = linkedMapOf<String, MatchResult>()
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name in constants) continue
                val declaration = sourceConstDefinition(name).findAll(originalSource).toList().singleOrNull() ?: continue
                constants[name] = declaration
                structuralReferences(declaration.value, name).filter { it !in declared }.forEach(pending::addLast)
            }
            val exact = buildString {
                constants.values.sortedBy { it.range.first }.forEach { declaration ->
                    append(declaration.value.trimEnd())
                    append('\n')
                }
                append(originalSource.substring(sourceDeclaration.range).trimEnd())
                append('\n')
            }
            result = stripped.substring(0, adjustedOffset) + exact + stripped.substring(adjustedOffset)
        }
        return result
    }

    private fun renderRestoredSourceConstant(
        declaration: String,
        irisContracts: IrisShaderContractPlan,
    ): String {
        val replaced = irisContracts.replaceHostReferences(declaration)
        if (replaced == declaration) return declaration
        val assignment = replaced.indexOf('=')
        val terminator = replaced.indexOf(';', assignment + 1)
        if (assignment < 0 || terminator < 0) return replaced
        val name = DECLARATION_IDENTIFIER.findAll(replaced.substring(0, assignment)).lastOrNull()?.value
            ?: return replaced
        val indent = replaced.takeWhile { it == ' ' || it == '\t' }
        val expression = replaced.substring(assignment + 1, terminator).trim()
        return "$indent#define $name ($expression)${replaced.substring(terminator + 1)}"
    }

    internal fun restoreTokenPasteSourceMacros(
        sourceName: String,
        source: String,
        lowerings: List<ShaderTokenPasteLowering>,
    ): TokenPasteSourceRestoration {
        if (lowerings.isEmpty()) return TokenPasteSourceRestoration.Restored(source, emptySet())
        val reachable = reachableCodeAndMacroIdentifiers(source)
        val grouped = lowerings.filter { lowering -> lowering.macroName in reachable }
            .groupBy(ShaderTokenPasteLowering::macroName)
        if (grouped.isEmpty()) return TokenPasteSourceRestoration.Restored(source, emptySet())

        var result = source
        val dependencies = linkedSetOf<String>()
        grouped.toSortedMap().forEach { (macroName, items) ->
            val mappings = linkedMapOf<String, ShaderTokenPasteLowering>()
            items.sortedWith(compareBy(ShaderTokenPasteLowering::sourceLine).thenBy(ShaderTokenPasteLowering::sourceDirective))
                .forEach { lowering ->
                    val key = lowering.sourceDirective.trimEnd()
                    val previous = mappings.putIfAbsent(key, lowering)
                    if (
                        previous != null &&
                        (
                            previous.loweredDirective.trimEnd() != lowering.loweredDirective.trimEnd() ||
                                previous.helperPrototypes.trim() != lowering.helperPrototypes.trim() ||
                                previous.helperNames != lowering.helperNames ||
                                previous.candidateIdentifiers != lowering.candidateIdentifiers ||
                                previous.candidateCoverageComplete != lowering.candidateCoverageComplete ||
                                previous.settingDependencies != lowering.settingDependencies
                            )
                    ) {
                        return TokenPasteSourceRestoration.Preserved(
                            "$sourceName:${lowering.sourceLine}: conflicting token-paste lowerings for $macroName",
                        )
                    }
                }

            val definitions = sourceMacroDefinitions(result)[macroName].orEmpty()
            if (definitions.isEmpty()) {
                return TokenPasteSourceRestoration.Preserved(
                    "$sourceName:${items.minOf(ShaderTokenPasteLowering::sourceLine)}: reachable token-paste macro " +
                        "$macroName is missing after restoration",
                )
            }
            val functions = (scanSourceFunctions(result) + scanNamedSourceFunctions(result))
                .mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
            val replacements = mutableListOf<Pair<SourceMacroDefinition, String>>()
            val prototypeLines = linkedSetOf<String>()
            definitions.forEach { definition ->
                val exact = definition.exactText.trimEnd()
                val sourceLowering = mappings[exact]
                val lowered = mappings.values.firstOrNull { lowering ->
                    lowering.loweredDirective.trimEnd() == exact
                }
                if (lowered != null) return@forEach
                if (sourceLowering == null) {
                    return TokenPasteSourceRestoration.Preserved(
                        "$sourceName:${sourceLine(result, definition.offset)}: restored token-paste macro $macroName " +
                            "does not match its proven compiler-copy lowering",
                    )
                }
                val helperAvailable = sourceLowering.helperNames.all(functions::contains)
                if (sourceLowering.helperNames.isNotEmpty() && !helperAvailable) {
                    if (
                        !sourceLowering.candidateCoverageComplete ||
                        sourceLowering.candidateIdentifiers.isEmpty()
                    ) {
                        return TokenPasteSourceRestoration.Preserved(
                            "$sourceName:${sourceLowering.sourceLine}: token-paste helper " +
                                "${sourceLowering.helperNames.sorted()} for $macroName is missing and candidate " +
                                "coverage is incomplete; settings=${sourceLowering.settingDependencies.sorted()}",
                        )
                    }
                    dependencies += sourceLowering.candidateIdentifiers
                    return@forEach
                }
                if (sourceLowering.helperNames.isEmpty()) {
                    if (!sourceLowering.candidateCoverageComplete) {
                        return TokenPasteSourceRestoration.Preserved(
                            "$sourceName:${sourceLowering.sourceLine}: direct token-paste lowering for $macroName " +
                                "has incomplete candidate coverage; settings=${sourceLowering.settingDependencies.sorted()}",
                        )
                    }
                    dependencies += sourceLowering.candidateIdentifiers
                }
                sourceLowering.helperPrototypes.lineSequence().map(String::trim).filter(String::isNotEmpty)
                    .filterNot { prototype -> result.contains(prototype) }
                    .forEach(prototypeLines::add)
                replacements += definition to sourceLowering.loweredDirective
            }
            if (replacements.isEmpty()) return@forEach
            val firstOffset = replacements.minOf { (definition, _) -> definition.offset }
            val prototypes = prototypeLines.joinToString(separator = "\n", postfix = "\n")
            result = replacements.sortedByDescending { (definition, _) -> definition.offset }
                .fold(result) { current, (definition, lowered) ->
                    val end = definition.offset + definition.exactText.length
                    val prefix = prototypes.takeIf { definition.offset == firstOffset }.orEmpty()
                    current.replaceRange(definition.offset, end, prefix + lowered)
                }
        }
        return TokenPasteSourceRestoration.Restored(result, dependencies)
    }

    private fun expandTokenPasteDependencyIdentifiers(
        sourceName: String,
        source: String,
        seeds: Set<String>,
    ): TokenPasteSourceRestoration {
        if (seeds.isEmpty()) return TokenPasteSourceRestoration.Restored(source, emptySet())
        val macros = sourceMacroDefinitions(source)
        val expanded = linkedSetOf<String>()
        val visited = hashSetOf<String>()
        val active = linkedSetOf<String>()
        var cycle: List<String>? = null
        fun visit(name: String) {
            if (cycle != null) return
            expanded += name
            if (name !in macros) return
            if (name in active) {
                val path = active.toList()
                cycle = path.drop(path.indexOf(name)) + name
                return
            }
            if (!visited.add(name)) return
            active += name
            macros[name].orEmpty().forEach { definition ->
                DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value).forEach(::visit)
            }
            active.remove(name)
        }
        seeds.sorted().forEach(::visit)
        cycle?.let { path ->
            return TokenPasteSourceRestoration.Preserved(
                "$sourceName: cyclic token-paste data dependency: ${path.joinToString(" -> ")}",
            )
        }
        return TokenPasteSourceRestoration.Restored(source, expanded)
    }

    internal fun restoreTokenPasteSourceDependencies(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
        irisContracts: IrisShaderContractPlan,
        lowerings: List<ShaderTokenPasteLowering>,
    ): ShaderStructuralRestoration {
        val restoredMacros = when (
            val restoration = restoreTokenPasteSourceMacros(request.sourceName, source, lowerings)
        ) {
            is TokenPasteSourceRestoration.Restored -> restoration
            is TokenPasteSourceRestoration.Preserved -> {
                return ShaderStructuralRestoration.Preserved(restoration.reason)
            }
        }
        if (restoredMacros.dependencyIdentifiers.isEmpty()) {
            return ShaderStructuralRestoration.Restored(restoredMacros.source)
        }
        val expanded = when (
            val expansion = expandTokenPasteDependencyIdentifiers(
                request.sourceName,
                request.source,
                restoredMacros.dependencyIdentifiers,
            )
        ) {
            is TokenPasteSourceRestoration.Restored -> expansion.dependencyIdentifiers
            is TokenPasteSourceRestoration.Preserved -> {
                return ShaderStructuralRestoration.Preserved(expansion.reason)
            }
        }
        val functions = when (
            val restoration = restoreMissingSourceFunctions(
                request,
                restoredMacros.source,
                restorationPlan,
                irisContracts,
                expanded,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> restoration.source
            is ShaderStructuralRestoration.Preserved -> return restoration
        }
        val macros = when (
            val restoration = restoreMissingSourceMacros(request, functions, restorationPlan)
        ) {
            is ShaderStructuralRestoration.Restored -> restoration.source
            is ShaderStructuralRestoration.Preserved -> return restoration
        }
        val declarations = when (
            val restoration = restoreMissingSourceGlobalDependencies(
                request,
                macros,
                restorationPlan,
                irisContracts,
                expanded,
            )
        ) {
            is ShaderStructuralRestoration.Restored -> restoration.source
            is ShaderStructuralRestoration.Preserved -> return restoration
        }
        return relocateTokenPasteDeclarationDependencies(request.sourceName, declarations, expanded)
    }

    private fun restoreMissingSourceFunctions(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
        irisContracts: IrisShaderContractPlan,
        additionalReferences: Set<String> = emptySet(),
    ): ShaderStructuralRestoration {
        val reachableIdentifiers = reachableCodeAndMacroIdentifiers(source) + additionalReferences
        val sourceFunctions = (sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.FUNCTION && entity.symbol != null
        } + scanSourceFunctions(request.source) + scanNamedSourceFunctions(request.source))
            .distinctBy(StructuralEntity::identity)
            .groupBy { requireNotNull(it.symbol) }
        val outputNames = (scanSourceFunctions(source) + scanNamedSourceFunctions(source))
            .mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val pending = ArrayDeque(
            reachableIdentifiers.asSequence()
                .filter { it !in outputNames && it in sourceFunctions }
                .toCollection(linkedSetOf()),
        )
        val selected = linkedMapOf<String, StructuralEntity>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            sourceFunctions[name].orEmpty().forEach { function ->
                if (function.identity in selected) return@forEach
                selected[function.identity] = function
                function.references.filter { it !in outputNames && it in sourceFunctions }
                    .forEach(pending::addLast)
            }
        }
        if (selected.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val functions = selected.values.toList()
        val conditionalOwners = nearestConditionalOwnerRanges(
            request.source,
            functions.map(StructuralEntity::range),
        )
        val functionRanges = functions.indices.map { index ->
            val owner = conditionalOwners[index]
            if (owner != null && !isIncludeGuardOwner(request.source, owner)) owner else functions[index].range
        }
        val ranges = functionRanges
            .distinct()
            .filterNot { candidate ->
                rangesContainmentOwner(candidate, functionRanges)
            }
            .sortedBy(IntRange::first)
        val slots = ranges.mapIndexed { index, range ->
            ShaderStructuralEntitySlot(
                ordinal = index,
                kind = ShaderStructuralEntitySlotKind.FUNCTION,
                canonicalEntity = null,
                exactText = request.source.substring(range),
                sourceLine = sourceLine(request.source, range.first),
                beforeAnchor = null,
                afterAnchor = null,
                placement = IrisAnchorPlacement.AFTER_BEFORE,
            )
        }
        val anchored = reanchorRestorationSlots(request, slots)
            ?: return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: missing source functions have no stable restoration anchor",
            )
        return restorationPlan.copy(
            islands = anchored.map { slot ->
                slot.copy(exactText = irisContracts.replaceHostReferences(slot.exactText))
            },
            restorationContracts = emptyList(),
            issue = null,
        ).restore(source)
    }

    private fun reachableCodeAndMacroIdentifiers(source: String): Set<String> {
        val seeds = DECLARATION_IDENTIFIER.findAll(maskStructuralCode(source)).map(MatchResult::value)
            .toCollection(linkedSetOf())
        return reachableMacroIdentifiers(source, seeds)
    }

    private fun reachableMacroIdentifiers(source: String, seeds: Set<String>): Set<String> {
        val result = seeds.toCollection(linkedSetOf())
        val macros = sourceMacroDefinitions(source)
        val pending = ArrayDeque(result.filter(macros::containsKey))
        val visited = hashSetOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            macros[name].orEmpty().forEach { definition ->
                DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value).forEach { identifier ->
                    result += identifier
                    if (identifier in macros) pending += identifier
                }
            }
        }
        return result
    }

    private fun relocateTokenPasteDeclarationDependencies(
        sourceName: String,
        source: String,
        dependencies: Set<String>,
    ): ShaderStructuralRestoration {
        if (dependencies.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val entities = sourceStructuralEntities(source)
        val declarations = entities.filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION && entity.symbol in dependencies
        }.groupBy { requireNotNull(it.symbol) }
        val selected = linkedSetOf<StructuralEntity>()
        var insertionOffset = source.length
        val depths = preprocessorDepths(source)
        entities.forEach { consumer ->
            val exact = source.substring(consumer.range)
            val seeds = DECLARATION_IDENTIFIER.findAll(maskStructuralCode(exact)).map(MatchResult::value)
                .toCollection(linkedSetOf())
            val reachable = reachableMacroIdentifiers(source, seeds).intersect(dependencies)
            reachable.forEach { dependency ->
                val declaration = declarations[dependency]?.singleOrNull() ?: return@forEach
                if (declaration.range.first <= consumer.range.first) return@forEach
                if (depths[declaration.range.first] != 0) {
                    return ShaderStructuralRestoration.Preserved(
                        "$sourceName: token-paste declaration dependency $dependency is conditional and follows " +
                            "its first restored use at line ${sourceLine(source, consumer.range.first)}",
                    )
                }
                selected += declaration
                insertionOffset = minOf(insertionOffset, unconditionalInsertionOffset(source, consumer.range.first))
            }
        }
        if (selected.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val ordered = selected.sortedBy { entity -> entity.range.first }
        val declarationText = ordered.joinToString(separator = "\n", postfix = "\n") { declaration ->
            source.substring(declaration.range)
        }
        val ranges = ordered.map(StructuralEntity::range)
        val adjusted = insertionOffset - ranges.filter { range -> range.first < insertionOffset }.sumOf(IntRange::count)
        val stripped = removeRanges(source, ranges)
        return ShaderStructuralRestoration.Restored(
            stripped.substring(0, adjusted) + declarationText + stripped.substring(adjusted),
        )
    }

    private fun scanSourceFunctions(source: String): List<StructuralEntity> {
        val masked = maskStructuralCode(source)
        return scanTopLevelGlslBlocks(source, masked)
            .filter { it.kind == TopLevelGlslBlockKind.FUNCTION }
            .mapNotNull { block ->
                val start = firstNonWhitespace(masked, block.fullRange.first, block.prefixRange.last + 1)
                if (start > block.prefixRange.last) return@mapNotNull null
                val range = start..block.fullRange.last
                val header = normalizeStructuralEntity(source.substring(start, block.prefixRange.last + 1))
                val function = structuralFunctionIdentity(header) ?: return@mapNotNull null
                val exact = source.substring(range)
                StructuralEntity(
                    range = range,
                    canonical = header,
                    identity = function.first,
                    symbol = function.second,
                    references = structuralReferences(exact, function.second),
                    semantic = normalizeStructuralEntity(exact),
                    kind = StructuralEntityKind.FUNCTION,
                )
            }
    }

    private fun scanNamedSourceFunctions(source: String): List<StructuralEntity> {
        val masked = maskStructuralCode(source)
        val branchMasks = StructuralBranchMaskResolver(source, masked)
        val headerStart = Regex(
            "(?m)^[\\t ]*(?:[A-Za-z_][A-Za-z0-9_]*[\\t ]+)+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*\\(",
        )
        return headerStart.findAll(masked).mapNotNull { match ->
            val selectedMask = branchMasks.maskFor(match.range.first)
            val open = selectedMask.indexOf('{', match.range.last + 1)
            if (open < 0) return@mapNotNull null
            val semicolon = selectedMask.indexOf(';', match.range.last + 1)
            if (semicolon in 0 until open) return@mapNotNull null
            var depth = 0
            var close = -1
            for (offset in open until selectedMask.length) {
                when (selectedMask[offset]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            close = offset
                            break
                        }
                    }
                }
            }
            if (close < 0) return@mapNotNull null
            val range = match.range.first..close
            val header = normalizeStructuralEntity(source.substring(match.range.first, open))
            val function = structuralFunctionIdentity(header) ?: return@mapNotNull null
            val exact = source.substring(range)
            StructuralEntity(
                range = range,
                canonical = header,
                identity = function.first,
                symbol = function.second,
                references = structuralReferences(exact, function.second),
                semantic = normalizeStructuralEntity(exact),
                kind = StructuralEntityKind.FUNCTION,
            )
        }.toList()
    }

    internal fun restoreSourceFunctionConditionalOwners(originalSource: String, source: String): String {
        val originalFunctions = (scanSourceFunctions(originalSource) + scanNamedSourceFunctions(originalSource))
            .distinctBy(StructuralEntity::identity)
        val originalOwners = nearestConditionalOwnerRanges(
            originalSource,
            originalFunctions.map(StructuralEntity::range),
        )
        val owned = originalFunctions.indices.mapNotNull { index ->
            if (originalFunctions[index].symbol == "main") return@mapNotNull null
            val owner = originalOwners[index] ?: return@mapNotNull null
            val opening = simpleConditionalOpening(originalSource.substring(owner)) ?: return@mapNotNull null
            if ("SETTING_" in opening || "SM_SETTING_" in opening) return@mapNotNull null
            originalFunctions[index].identity to opening
        }.toMap()
        if (owned.isEmpty()) return source
        val outputFunctions = (scanSourceFunctions(source) + scanNamedSourceFunctions(source))
            .distinctBy(StructuralEntity::identity)
        val outputOwners = nearestConditionalOwnerRanges(source, outputFunctions.map(StructuralEntity::range))
        val replacements = outputFunctions.indices.mapNotNull { index ->
            val function = outputFunctions[index]
            val opening = owned[function.identity] ?: return@mapNotNull null
            val currentOpening = outputOwners[index]?.let { simpleConditionalOpening(source.substring(it)) }
            if (currentOpening == opening) return@mapNotNull null
            val exact = source.substring(function.range).trimEnd()
            function.range to "$opening\n$exact\n#endif"
        }
        return replacements.sortedByDescending { it.first.first }.fold(source) { result, (range, replacement) ->
            result.replaceRange(range, replacement)
        }
    }

    internal fun deduplicateRelaxedFunctionDefinitions(originalSource: String, source: String): String {
        val originalIdentities = (scanSourceFunctions(originalSource) + scanNamedSourceFunctions(originalSource))
            .mapTo(hashSetOf(), StructuralEntity::identity)
        val functions = (scanSourceFunctions(source) + scanNamedSourceFunctions(source))
            .distinctBy(StructuralEntity::range)
        val removals = functions.groupBy { relaxedFunctionIdentity(it.identity) }.values.flatMap { variants ->
            if (variants.size < 2) return@flatMap emptyList()
            val sourceDefinitions = variants.filter { it.identity in originalIdentities }
            if (sourceDefinitions.size != 1) return@flatMap emptyList()
            variants.filterNot { it === sourceDefinitions.single() }.map(StructuralEntity::range)
        }
        return if (removals.isEmpty()) source else removeRanges(source, removals)
    }

    private fun relocateUnconditionalAbiFromFunctionOwners(
        request: SpirvOptimizationRequest,
        source: String,
    ): String {
        val sourceDeclarations = sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val sourceOwners = nearestConditionalOwnerRanges(
            request.source,
            sourceDeclarations.map(StructuralEntity::range),
        )
        val unconditionalIdentities = sourceDeclarations.indices.mapNotNullTo(hashSetOf()) { index ->
            val owner = sourceOwners[index]
            sourceDeclarations[index].identity.takeIf {
                owner == null || isIncludeGuardOwner(request.source, owner)
            }
        }
        if (unconditionalIdentities.isEmpty()) return source
        val declarations = structuralEntities(source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                entity.identity in unconditionalIdentities &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val owners = nearestConditionalOwnerRanges(source, declarations.map(StructuralEntity::range))
        val relocations = declarations.indices.mapNotNull { index ->
            val owner = owners[index] ?: return@mapNotNull null
            if (isIncludeGuardOwner(source, owner)) return@mapNotNull null
            if (structuralEntities(source.substring(owner)).none { it.kind == StructuralEntityKind.FUNCTION }) {
                return@mapNotNull null
            }
            owner to declarations[index]
        }.groupBy({ it.first }, { it.second })
        if (relocations.isEmpty()) return source
        var result = source
        relocations.entries.sortedByDescending { it.key.first }.forEach { (owner, ownedDeclarations) ->
            val insertion = ownedDeclarations.sortedBy { it.range.first }
                .joinToString("\n", postfix = "\n") { result.substring(it.range).trim() }
            result = removeRanges(result, ownedDeclarations.map(StructuralEntity::range).distinct())
            result = result.substring(0, owner.first) + insertion + result.substring(owner.first)
        }
        return result
    }

    private fun simpleConditionalOpening(ownerSource: String): String? {
        var depth = 0
        var opening: String? = null
        ownerSource.lineSequence().forEach { line ->
            val directive = line.trimStart().substringBefore("//").trim()
            when {
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                    directive.startsWith("#ifdef") || directive.startsWith("#ifndef") -> {
                    if (depth == 0) opening = directive
                    depth++
                }
                directive.startsWith("#elif ") || directive.startsWith("#elif\t") ||
                    directive.startsWith("#else") -> if (depth == 1) return null
                directive.startsWith("#endif") -> depth--
            }
        }
        return opening?.takeIf { depth == 0 }
    }

    private fun rangesContainmentOwner(candidate: IntRange, ranges: List<IntRange>): Boolean {
        return ranges.any { owner -> owner != candidate && owner.containsRange(candidate) }
    }

    private fun restoreMissingSourceMacros(
        request: SpirvOptimizationRequest,
        source: String,
        restorationPlan: ShaderStructuralRestorationPlan,
    ): ShaderStructuralRestoration {
        val outputMacros = sourceMacroDefinitions(source).keys
        val sourceMacros = sourceMacroDefinitions(request.source)
        val includeGuardNames = sourceIncludeGuards(request.source, request.sourceName).mapTo(hashSetOf()) { it.name }
        val pending = ArrayDeque(
            DECLARATION_IDENTIFIER.findAll(maskStructuralSource(source)).map(MatchResult::value)
                .filter {
                    it !in outputMacros && it in sourceMacros && it !in includeGuardNames && restorableSourceMacro(it)
                }
                .toCollection(linkedSetOf()),
        )
        val selected = linkedSetOf<SourceMacroDefinition>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            sourceMacros[name].orEmpty().forEach { definition ->
                if (!selected.add(definition)) return@forEach
                DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                    .filter {
                        it !in outputMacros && it in sourceMacros && it !in includeGuardNames && restorableSourceMacro(it)
                    }
                    .forEach(pending::addLast)
            }
        }
        if (selected.isEmpty()) return ShaderStructuralRestoration.Restored(source)
        val definitions = selected.sortedBy(SourceMacroDefinition::offset)
        val definitionRanges = definitions.map { definition ->
            definition.offset until definition.offset + definition.exactText.length
        }
        val conditionalOwners = nearestConditionalOwnerRanges(request.source, definitionRanges)
        val allRanges = definitions.indices.map { index -> conditionalOwners[index] ?: definitionRanges[index] }
            .distinct()
        val ranges = allRanges.filterNot { candidate -> rangesContainmentOwner(candidate, allRanges) }
            .sortedBy(IntRange::first)
        val slots = ranges.mapIndexed { index, range ->
            ShaderStructuralEntitySlot(
                ordinal = index,
                kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                canonicalEntity = null,
                exactText = request.source.substring(range),
                sourceLine = sourceLine(request.source, range.first),
                beforeAnchor = null,
                afterAnchor = null,
                placement = IrisAnchorPlacement.AFTER_BEFORE,
            )
        }
        val anchored = reanchorRestorationSlots(request, slots)
            ?: return ShaderStructuralRestoration.Preserved(
                "${request.sourceName}: missing source macros have no stable restoration anchor",
            )
        return restorationPlan.copy(
            islands = anchored,
            restorationContracts = emptyList(),
            issue = null,
        ).restore(source)
    }

    internal fun restoreDirectiveMacroDependencies(
        sourceName: String,
        originalSource: String,
        restoredSource: String,
        contracts: List<IrisSourceContractSlice>,
        restorationPlan: ShaderStructuralRestorationPlan,
    ): ShaderStructuralRestoration {
        val sourceDefinitions = sourceMacroDefinitions(originalSource)
        if (sourceDefinitions.isEmpty()) return ShaderStructuralRestoration.Restored(restoredSource)

        data class DependencyTarget(
            val label: String,
            val exactText: String,
            val matchText: String,
            val sourceLine: Int,
            val sourceRange: IntRange,
        )
        data class DependencyBlock(
            val exactText: String,
            val sourceLine: Int,
            val sourceRange: IntRange,
            val macroNames: Set<String>,
        )
        data class TargetDependencies(
            val target: DependencyTarget,
            val blocks: List<DependencyBlock>,
        )

        val presentContracts = contracts.filter { contract ->
            occurrences(restoredSource, contract.exactText.trim()).isNotEmpty()
        }
        val contractTargets = presentContracts.map { contract ->
            DependencyTarget(
                "${contract.kind} contract",
                contract.exactText,
                contract.exactText.trim(),
                contract.sourceLine,
                contract.sourceRange,
            )
        }
        val sourceDeclarations = sourceStructuralEntities(originalSource).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                presentContracts.none { contract ->
                    contract.sourceRange.overlaps(entity.range) ||
                        contract.exactText.contains(originalSource.substring(entity.range).trim())
                }
        }
        fun declarationKey(entity: StructuralEntity): Pair<String, Set<String>> =
            entity.identity to entity.declaredSymbols()
        val sourceDeclarationsByKey = sourceDeclarations.groupBy(::declarationKey)
        val restoredDeclarationsByKey = structuralEntities(restoredSource)
            .filter { entity -> entity.kind == StructuralEntityKind.DECLARATION }
            .groupBy(::declarationKey)
        val declarationTargets = mutableListOf<DependencyTarget>()
        for (entity in sourceDeclarations) {
            val exactText = originalSource.substring(entity.range)
            if (
                DECLARATION_IDENTIFIER.findAll(exactText).none { match ->
                    match.value in sourceDefinitions && (
                        restorableSourceMacro(match.value) || match.value.startsWith("SETTING_")
                        )
                }
            ) {
                continue
            }
            val exactMatchText = exactText.trim()
            if (occurrences(restoredSource, exactMatchText).isNotEmpty()) {
                declarationTargets += DependencyTarget(
                    "declaration ${entity.symbol ?: entity.identity}",
                    exactText,
                    exactMatchText,
                    sourceLine(originalSource, entity.range.first),
                    entity.range,
                )
                continue
            }
            val key = declarationKey(entity)
            val restoredCandidates = restoredDeclarationsByKey[key].orEmpty()
            if (restoredCandidates.isEmpty()) continue
            val sourceCandidates = sourceDeclarationsByKey.getValue(key)
            if (sourceCandidates.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${sourceLine(originalSource, entity.range.first)}: expanded declaration " +
                        "${entity.symbol ?: entity.identity} source mapping is ambiguous " +
                        "(${sourceCandidates.size} candidates)",
                )
            }
            if (restoredCandidates.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${sourceLine(originalSource, entity.range.first)}: expanded declaration " +
                        "${entity.symbol ?: entity.identity} final mapping is ambiguous " +
                        "(${restoredCandidates.size} candidates)",
                )
            }
            val restoredEntity = restoredCandidates.single()
            declarationTargets += DependencyTarget(
                "expanded declaration ${entity.symbol ?: entity.identity}",
                exactText,
                restoredSource.substring(restoredEntity.range).trim(),
                sourceLine(originalSource, entity.range.first),
                entity.range,
            )
        }
        val rawTargets = (contractTargets + declarationTargets).distinctBy(DependencyTarget::sourceRange)
        val targets = rawTargets.filterNot { candidate ->
            rawTargets.any { owner ->
                owner.sourceRange != candidate.sourceRange && owner.sourceRange.containsRange(candidate.sourceRange)
            }
        }.sortedBy { target -> target.sourceRange.first }
        val dependencies = mutableListOf<TargetDependencies>()
        val selectedMacroNames = linkedSetOf<String>()

        for (target in targets) {
            val targetMacroNames = sourceMacroDefinitions(target.exactText).keys
            val requiredMacroNames = linkedSetOf<String>()
            val pendingMacroNames = ArrayDeque(
                DECLARATION_IDENTIFIER.findAll(target.exactText).map(MatchResult::value)
                    .filter { name -> name !in targetMacroNames && name in sourceDefinitions }
                    .toCollection(linkedSetOf()),
            )
            fun expandRequiredMacroNames() {
                while (pendingMacroNames.isNotEmpty()) {
                    val name = pendingMacroNames.removeFirst()
                    if (!requiredMacroNames.add(name)) continue
                    sourceDefinitions[name].orEmpty().asSequence().flatMap { definition ->
                        DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                    }.filter { dependency ->
                        dependency !in targetMacroNames && dependency in sourceDefinitions
                    }.forEach(pendingMacroNames::addLast)
                }
            }
            expandRequiredMacroNames()
            val slots = sourceMacroDependencySlots(
                originalSource,
                "",
                listOf(target.exactText),
                restorationPlan,
                includeSettingMacros = true,
                definitionOffsetLimit = target.sourceRange.first,
            )
            do {
                var addedOwnerDependency = false
                slots.asSequence().filter { slot ->
                    sourceMacroDefinitions(slot.exactText).keys.any(requiredMacroNames::contains)
                }.flatMap { slot ->
                    val siblingDefinitions = sourceMacroDefinitions(slot.exactText).keys
                    DECLARATION_IDENTIFIER.findAll(slot.exactText).map(MatchResult::value)
                        .filterNot(siblingDefinitions::contains)
                }.filter { name ->
                    name !in targetMacroNames && name in sourceDefinitions && name !in requiredMacroNames
                }.forEach { name ->
                    pendingMacroNames.addLast(name)
                    addedOwnerDependency = true
                }
                expandRequiredMacroNames()
            } while (addedOwnerDependency)
            val blocks = mutableListOf<DependencyBlock>()
            for (slot in slots) {
                val slotDefinitions = sourceMacroDefinitions(slot.exactText)
                    .filterKeys { name -> restorableSourceMacro(name) || name.startsWith("SETTING_") }
                if (slotDefinitions.isEmpty()) continue
                val requiredSlotMacros = slotDefinitions.keys.intersect(requiredMacroNames)
                if (requiredSlotMacros.isEmpty()) continue
                val occurrences = occurrences(originalSource, slot.exactText)
                val lineMatches = occurrences.filter { range ->
                    sourceLine(originalSource, range.first) == slot.sourceLine
                }
                val range = when {
                    occurrences.size == 1 -> occurrences.single()
                    lineMatches.size == 1 -> lineMatches.single()
                    else -> return ShaderStructuralRestoration.Preserved(
                        "$sourceName:${target.sourceLine}: ${target.label} macro dependency at line " +
                            "${slot.sourceLine} is ambiguous (${occurrences.size} source matches)",
                    )
                }
                if (range.overlaps(target.sourceRange)) continue
                if (range.first >= target.sourceRange.first) {
                    val coveredMacroNames = blocks.flatMapTo(linkedSetOf(), DependencyBlock::macroNames)
                    val missingMacroNames = requiredSlotMacros - coveredMacroNames
                    if (missingMacroNames.isEmpty()) continue
                    return ShaderStructuralRestoration.Preserved(
                        "$sourceName:${target.sourceLine}: ${target.label} uses macro dependency " +
                            "${missingMacroNames.sorted()} before its source definition at line ${slot.sourceLine}",
                    )
                }
                selectedMacroNames += requiredSlotMacros
                blocks += DependencyBlock(
                    slot.exactText,
                    slot.sourceLine,
                    range,
                    requiredSlotMacros,
                )
            }
            if (blocks.isNotEmpty()) {
                dependencies += TargetDependencies(target, blocks.sortedBy { block -> block.sourceRange.first })
            }
        }
        if (dependencies.isEmpty()) return ShaderStructuralRestoration.Restored(restoredSource)

        val selectedBlocks = dependencies.flatMap(TargetDependencies::blocks)
            .distinctBy(DependencyBlock::sourceRange)
        val selectedDefinitions = selectedBlocks.flatMap { block ->
            sourceMacroDefinitions(block.exactText).values.flatten()
        }
        selectedMacroNames.forEach { name ->
            val variants = selectedDefinitions.filter { definition -> definition.name == name }
            val values = variants.map { definition -> definition.value.trim() }.distinct()
            if (values.size <= 1) return@forEach
            val blocks = selectedBlocks.filter { block -> name in block.macroNames }
            if (blocks.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: conflicting source definitions for directive macro $name: $values",
                )
            }
            val block = blocks.single()
            val blockDefinitions = sourceMacroDefinitions(block.exactText)[name].orEmpty()
            val ranges = blockDefinitions.map { definition ->
                definition.offset until definition.offset + definition.exactText.length
            }
            val owners = nearestConditionalOwnerRanges(
                block.exactText,
                ranges,
            ).filterNotNull().distinct()
            if (owners.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: conflicting source definitions for directive macro $name: $values",
                )
            }
        }

        val graph = selectedMacroNames.associateWith { name ->
            selectedDefinitions.asSequence().filter { definition -> definition.name == name }
                .flatMap { definition ->
                    DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                }
                .filter { dependency -> dependency in selectedMacroNames }
                .toCollection(linkedSetOf())
        }
        val visited = hashSetOf<String>()
        val active = linkedSetOf<String>()
        fun cycle(name: String): List<String>? {
            if (name in active) {
                val path = active.toList()
                return path.drop(path.indexOf(name)) + name
            }
            if (!visited.add(name)) return null
            active += name
            graph[name].orEmpty().forEach { dependency ->
                cycle(dependency)?.let { return it }
            }
            active.remove(name)
            return null
        }
        selectedMacroNames.forEach { name ->
            cycle(name)?.let { names ->
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: cyclic directive macro dependency: ${names.joinToString(" -> ")}",
                )
            }
        }

        fun dependencyBlockOccurrences(source: String, exactText: String): List<IntRange> {
            val firstToken = exactText.indexOfFirst { char -> !char.isWhitespace() }
            if (firstToken < 0) return emptyList()
            val masked = maskStructuralSource(source)
            return occurrences(source, exactText).filter { range ->
                masked.getOrNull(range.first + firstToken) == exactText[firstToken]
            }
        }

        var result = restoredSource
        dependencies.forEach dependencyLoop@{ dependency ->
            val targetMatches = occurrences(result, dependency.target.matchText)
            if (targetMatches.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${dependency.target.sourceLine}: ${dependency.target.label} is " +
                        if (targetMatches.isEmpty()) "missing after restoration" else
                            "ambiguous (${targetMatches.size} restored matches)",
                )
            }
            var targetOffset = targetMatches.single().first
            val requiredNames = dependency.blocks.flatMapTo(linkedSetOf(), DependencyBlock::macroNames)
            val currentDefinitions = sourceMacroDefinitions(result)
            if (requiredNames.all { name ->
                    currentDefinitions[name].orEmpty().any { definition -> definition.offset < targetOffset }
                }
            ) {
                return@dependencyLoop
            }
            val insertions = mutableListOf<DependencyBlock>()
            dependency.blocks.forEach blockLoop@{ block ->
                val blockMatches = dependencyBlockOccurrences(result, block.exactText)
                when {
                    blockMatches.any { match -> match.first < targetOffset } -> {
                        val keep = blockMatches.first { match -> match.first < targetOffset }
                        val duplicates = blockMatches.filterNot { match -> match == keep }
                        if (duplicates.isNotEmpty()) {
                            result = removeRanges(result, duplicates)
                            targetOffset = occurrences(result, dependency.target.matchText).single().first
                        }
                    }
                    blockMatches.size > 1 -> {
                        result = removeRanges(result, blockMatches)
                        insertions += block
                        targetOffset = occurrences(result, dependency.target.matchText).single().first
                    }
                    blockMatches.size == 1 -> {
                        result = result.removeRange(blockMatches.single())
                        insertions += block
                    }
                    else -> {
                        val restoredDefinitions = sourceMacroDefinitions(result)
                        val lateNames = block.macroNames.filter { name ->
                            restoredDefinitions[name].orEmpty().none { definition ->
                                definition.offset < targetOffset
                            }
                        }
                        if (lateNames.isEmpty()) return@blockLoop
                        val lateDefinitions = lateNames.associateWith { name ->
                            restoredDefinitions[name].orEmpty()
                        }
                        if (lateDefinitions.values.any { definitions -> definitions.isEmpty() }) {
                            val existing = lateDefinitions.values.flatten()
                            if (existing.isEmpty()) {
                                insertions += block
                                return@blockLoop
                            }
                            return ShaderStructuralRestoration.Preserved(
                                "$sourceName:${block.sourceLine}: directive macro dependency " +
                                    "${block.macroNames.sorted()} was rewritten and cannot be safely relocated",
                            )
                        }
                        val definitionRanges = lateDefinitions.values.flatten().map { definition ->
                            definition.offset until definition.offset + definition.exactText.length
                        }
                        val owners = nearestConditionalOwnerRanges(result, definitionRanges)
                        val relocationRanges = definitionRanges.indices.map { index ->
                            owners[index] ?: definitionRanges[index]
                        }.distinct().filterNot { candidate ->
                            definitionRanges.indices.any { index ->
                                val owner = owners[index] ?: definitionRanges[index]
                                owner != candidate && owner.containsRange(candidate)
                            }
                        }.sortedBy(IntRange::first)
                        val currentTarget = occurrences(result, dependency.target.matchText).single()
                        if (relocationRanges.any { range -> range.overlaps(currentTarget) }) return@blockLoop
                        val relocatedText = relocationRanges.joinToString(separator = "") { range ->
                            result.substring(range)
                        }
                        result = removeRanges(result, relocationRanges)
                        insertions += block.copy(exactText = relocatedText)
                    }
                }
            }
            if (insertions.isEmpty()) return@dependencyLoop
            val relocatedTarget = occurrences(result, dependency.target.matchText)
            if (relocatedTarget.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${dependency.target.sourceLine}: ${dependency.target.label} anchor changed " +
                        "while relocating macro dependencies",
                )
            }
            targetOffset = relocatedTarget.single().first
            val insertion = insertions.sortedBy { block -> block.sourceRange.first }
                .joinToString(separator = "") { block -> block.exactText }
                .let { text -> if (text.endsWith('\n') || text.endsWith('\r')) text else "$text\n" }
            result = result.substring(0, targetOffset) + insertion + result.substring(targetOffset)
        }
        return restoreLiveIncludeGuardDependencies(
            sourceName,
            originalSource,
            result,
            selectedMacroNames,
        )
    }

    private fun restoreLiveIncludeGuardDependencies(
        sourceName: String,
        originalSource: String,
        restoredSource: String,
        requiredMacroNames: Set<String>,
    ): ShaderStructuralRestoration {
        if (requiredMacroNames.isEmpty()) return ShaderStructuralRestoration.Restored(restoredSource)
        val owners = sourceIncludeGuards(originalSource, sourceName).filter { guard ->
            (guard.definedNames - guard.name).any(requiredMacroNames::contains)
        }
        owners.firstOrNull { guard -> guard.definitionCount != 1 }?.let { guard ->
            return ShaderStructuralRestoration.Preserved(
                "$sourceName: live include guard ${guard.name} has ambiguous source definitions",
            )
        }
        val ownership = owners.flatMap { guard ->
            (guard.definedNames - guard.name).filter(requiredMacroNames::contains).map { name -> name to guard }
        }.groupBy({ it.first }, { it.second }).filterValues { guards ->
            guards.map(SourceIncludeGuard::range).distinct().size > 1
        }
        if (ownership.isNotEmpty()) {
            return ShaderStructuralRestoration.Preserved(
                "$sourceName: live include-guard dependency ownership is ambiguous: " +
                    ownership.mapValues { (_, guards) -> guards.map(SourceIncludeGuard::name).distinct().sorted() },
            )
        }
        val conflictingGuards = owners.groupBy(SourceIncludeGuard::name).filterValues { guards ->
            guards.map(SourceIncludeGuard::definitionText).distinct().size > 1
        }
        if (conflictingGuards.isNotEmpty()) {
            return ShaderStructuralRestoration.Preserved(
                "$sourceName: conflicting live include-guard definitions: ${conflictingGuards.keys.sorted()}",
            )
        }

        var result = restoredSource
        owners.distinctBy(SourceIncludeGuard::name).forEach { owner ->
            val parsed = parsedIncludeGuardSource(result, "$sourceName<restored-include-guards>")
            val candidates = parsed.groups.filter { group ->
                group.name == owner.name && group.definedNames.any(requiredMacroNames::contains)
            }
            if (candidates.isEmpty()) return@forEach
            if (candidates.size != 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: live include guard ${owner.name} is ambiguous (${candidates.size} candidates)",
                )
            }
            val candidate = candidates.single()
            if (candidate.definitionIsFirstChild) return@forEach
            if (candidate.guardDefinitions.isNotEmpty()) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: live include guard ${owner.name} has a misplaced internal definition",
                )
            }
            val external = parsed.definitions[owner.name].orEmpty().filterNot(candidate.range::containsRange)
            if (external.size > 1) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName: live include guard ${owner.name} has ambiguous external definitions",
                )
            }
            external.singleOrNull()?.let { range ->
                val text = result.substring(range)
                if (normalizeStructuralEntity(text) != normalizeStructuralEntity(owner.definitionText)) {
                    return ShaderStructuralRestoration.Preserved(
                        "$sourceName: live include guard ${owner.name} has a conflicting external definition",
                    )
                }
            }
            val removal = external.singleOrNull()
            val stripped = removal?.let(result::removeRange) ?: result
            val adjustedInsertion = candidate.openerRange.last + 1 -
                if (removal != null && removal.first < candidate.openerRange.first) removal.last - removal.first + 1 else 0
            result = stripped.substring(0, adjustedInsertion) + owner.definitionText +
                stripped.substring(adjustedInsertion)
        }
        finalLiveIncludeGuardDependencyIssue(sourceName, originalSource, result, requiredMacroNames)?.let { issue ->
            return ShaderStructuralRestoration.Preserved(issue)
        }
        return ShaderStructuralRestoration.Restored(result)
    }

    internal fun finalLiveIncludeGuardDependencyIssue(
        sourceName: String,
        originalSource: String,
        finalSource: String,
        requiredMacroNames: Set<String>? = null,
    ): String? {
        val sourceGuards = sourceIncludeGuards(originalSource, sourceName)
        if (sourceGuards.isEmpty()) return null
        val parsed = parsedIncludeGuardSource(finalSource, "$sourceName<final-include-guards>")
        val finalDefinitions = sourceMacroDefinitions(finalSource).values.flatten().map { definition ->
            definition.offset until definition.offset + definition.exactText.length
        }
        val liveNamesByGuard = sourceGuards.associateWith { guard ->
            val finalGroups = parsed.groups.filter { group -> group.name == guard.name }
            val finalGroupNames = finalGroups.flatMapTo(linkedSetOf()) { group ->
                group.definedNames - group.name
            }
            if (requiredMacroNames != null) {
                (guard.definedNames - guard.name).intersect(requiredMacroNames).intersect(finalGroupNames)
            } else {
                (guard.definedNames - guard.name).intersect(finalGroupNames).filterTo(linkedSetOf()) { name ->
                    DECLARATION_IDENTIFIER.findAll(maskStructuralSource(finalSource)).any { match ->
                        finalDefinitions.none { definition -> match.range.first in definition } &&
                            finalGroups.none { group -> match.range.first in group.range }
                    }
                }
            }
        }
        val owners = sourceGuards.filter { guard -> liveNamesByGuard.getValue(guard).isNotEmpty() }
        owners.firstOrNull { guard -> guard.definitionCount != 1 }?.let { guard ->
            return "$sourceName: live include guard ${guard.name} has ambiguous source definitions"
        }
        val ambiguous = owners.flatMap { guard ->
            liveNamesByGuard.getValue(guard).map { name -> name to guard.range }
        }.groupBy({ it.first }, { it.second }).filterValues { ranges -> ranges.distinct().size > 1 }
        if (ambiguous.isNotEmpty()) {
            return "$sourceName: live include-guard dependency ownership is ambiguous: ${ambiguous.keys.sorted()}"
        }
        owners.distinctBy(SourceIncludeGuard::name).forEach { owner ->
            val liveNames = liveNamesByGuard.getValue(owner)
            val candidates = parsed.groups.filter { group ->
                group.name == owner.name && group.definedNames.any(liveNames::contains)
            }
            if (candidates.size != 1) {
                return "$sourceName: live include guard ${owner.name} is " +
                    if (candidates.isEmpty()) "missing" else "ambiguous (${candidates.size} candidates)"
            }
            val candidate = candidates.single()
            if (!candidate.definitionIsFirstChild) {
                return "$sourceName: live include guard ${owner.name} is inactive because its definition " +
                    "does not immediately follow the guard opener"
            }
            if (parsed.definitions[owner.name].orEmpty().any { definition ->
                    !candidate.range.containsRange(definition)
                }
            ) {
                return "$sourceName: live include guard ${owner.name} has an external definition that disables it"
            }
        }
        return null
    }

    private fun sourceIncludeGuards(source: String, sourceName: String): List<SourceIncludeGuard> {
        val parsed = parsedIncludeGuardSource(source, "$sourceName<source-include-guards>")
        return parsed.groups.filter(ParsedIncludeGuardGroup::definitionIsFirstChild).map { group ->
            val definition = group.guardDefinitions.first()
            SourceIncludeGuard(
                group.name,
                source.substring(definition),
                group.range,
                group.definedNames,
                group.guardDefinitions.size,
            )
        }
    }

    private fun parsedIncludeGuardSource(source: String, sourceName: String): ParsedIncludeGuardSource {
        val directives = PreprocessorProtection.protect(source, sourceName).directives
        val lineStarts = mutableListOf(0)
        source.forEachIndexed { index, char ->
            if (char == '\n' || char == '\r' && source.getOrNull(index + 1) != '\n') lineStarts += index + 1
        }
        fun directiveRange(directive: PreprocessorDirective): IntRange {
            val start = lineStarts.getOrElse(directive.sourceLine - 1) { source.length }
            val end = lineStarts.getOrElse(directive.endLine) { source.length }
            return start until end
        }
        val ranges = directives.associate { directive -> directive.index to directiveRange(directive) }
        val definitions = directives.filter { directive ->
            directive.kind == PreprocessorDirectiveKind.DEFINE && directive.macroName != null
        }.groupBy({ requireNotNull(it.macroName) }, { ranges.getValue(it.index) })
        val groups = directives.mapNotNull { opener ->
            if (opener.kind != PreprocessorDirectiveKind.IFNDEF || opener.macroName == null) return@mapNotNull null
            val closing = directives.firstOrNull { directive ->
                directive.index > opener.index && directive.kind == PreprocessorDirectiveKind.ENDIF &&
                    directive.conditionalId == opener.conditionalId
            } ?: return@mapNotNull null
            val children = directives.filter { directive ->
                directive.index in opener.index + 1 until closing.index &&
                    directive.conditionalId == opener.conditionalId &&
                    directive.conditionalDepth == opener.conditionalDepth + 1
            }
            val enclosed = directives.filter { directive ->
                directive.index in opener.index + 1 until closing.index
            }
            val guardDefinitions = enclosed.filter { directive ->
                directive.kind == PreprocessorDirectiveKind.DEFINE && directive.macroName == opener.macroName
            }.map { directive -> ranges.getValue(directive.index) }
            ParsedIncludeGuardGroup(
                requireNotNull(opener.macroName),
                ranges.getValue(opener.index).first..ranges.getValue(closing.index).last,
                ranges.getValue(opener.index),
                guardDefinitions,
                emptySet(),
                children.firstOrNull()?.let { directive ->
                    directive.kind == PreprocessorDirectiveKind.DEFINE && directive.macroName == opener.macroName
                } == true,
            )
        }
        val ownedNames = groups.associateWith { linkedSetOf<String>() }
        directives.filter { directive ->
            directive.kind == PreprocessorDirectiveKind.DEFINE && directive.macroName != null
        }.forEach { directive ->
            val range = ranges.getValue(directive.index)
            groups.filter { group -> group.range.containsRange(range) }
                .minByOrNull { group -> group.range.last - group.range.first }
                ?.let { owner -> ownedNames.getValue(owner) += requireNotNull(directive.macroName) }
        }
        return ParsedIncludeGuardSource(
            groups.map { group -> group.copy(definedNames = ownedNames.getValue(group)) },
            definitions,
        )
    }

    internal fun finalDirectiveMacroDependencyIssue(
        sourceName: String,
        originalSource: String,
        finalSource: String,
        contracts: List<IrisSourceContractSlice>,
        restorationPlan: ShaderStructuralRestorationPlan,
    ): String? {
        return when (
            val ordered = restoreDirectiveMacroDependencies(
                sourceName,
                originalSource,
                finalSource,
                contracts,
                restorationPlan,
            )
        ) {
            is ShaderStructuralRestoration.Preserved -> ordered.reason
            is ShaderStructuralRestoration.Restored -> if (ordered.source == finalSource) {
                null
            } else {
                "$sourceName: final structural declaration macro dependencies are not ordered before validation"
            }
        }
    }

    private fun ensureForwardFunctionDeclarations(source: String): String {
        val entities = sourceStructuralEntities(source)
        val functions = entities.filter { it.kind == StructuralEntityKind.FUNCTION }
        if (functions.isEmpty()) return source
        val functionsByName = functions.groupBy { it.symbol }
        val defined = hashSetOf<String>()
        val forwardNames = linkedSetOf<String>()
        var insertionOffset = source.length
        entities.forEach { entity ->
            entity.references.forEach { reference ->
                if (reference !in defined && reference != entity.symbol && reference in functionsByName) {
                    forwardNames += reference
                    insertionOffset = minOf(insertionOffset, entity.range.first)
                }
            }
            if (entity.kind == StructuralEntityKind.FUNCTION) entity.symbol?.let(defined::add)
        }
        if (forwardNames.isEmpty()) return source
        val preprocessorDepth = preprocessorDepths(source)
        val prototypes = forwardNames.flatMap { name ->
            functionsByName[name].orEmpty().filter { function ->
                preprocessorDepth[function.range.first] == 0
            }
        }.map { function -> function.canonical.trimEnd() + ";" }.distinct()
        if (prototypes.isEmpty()) return source
        insertionOffset = unconditionalInsertionOffset(source, insertionOffset)
        val insertion = prototypes.joinToString(separator = "\n", postfix = "\n")
        return source.substring(0, insertionOffset) + insertion + source.substring(insertionOffset)
    }

    internal fun hoistLateDeclarationDependencies(source: String): String {
        val entities = sourceStructuralEntities(source)
        val depths = preprocessorDepths(source)
        val declarations = entities.filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION && entity.symbol != null &&
                depths[entity.range.first] == 0
        }
        val declarationsByName = declarations.flatMap { declaration ->
            declaration.declaredSymbols().map { symbol -> symbol to declaration }
        }.groupBy({ it.first }, { it.second })
        val selected = linkedSetOf<StructuralEntity>()
        var insertionOffset = source.length
        val pending = ArrayDeque<StructuralEntity>()
        val namedFunctions = scanNamedSourceFunctions(source)
        val firstFunction = (entities.filter { it.kind == StructuralEntityKind.FUNCTION } + namedFunctions)
            .minByOrNull { it.range.first }
        if (firstFunction != null) {
            val firstIdentifierOffsets = DECLARATION_IDENTIFIER.findAll(maskStructuralCode(source))
                .groupingBy(MatchResult::value)
                .fold(Int.MAX_VALUE) { offset, match -> minOf(offset, match.range.first) }
            declarations.filter { declaration ->
                declaration.range.first > firstFunction.range.first &&
                    (
                        ABI_PROLOGUE_DECLARATION.containsMatchIn(declaration.canonical) ||
                            requireNotNull(declaration.symbol).let { symbol ->
                                firstIdentifierOffsets.getOrDefault(symbol, declaration.range.first) <
                                    declaration.range.first
                            }
                    )
            }.forEach { declaration ->
                insertionOffset = minOf(
                    insertionOffset,
                    unconditionalInsertionOffset(source, firstFunction.range.first),
                )
                pending += declaration
            }
        }
        (entities + namedFunctions).distinctBy(StructuralEntity::range).forEach { consumer ->
            consumer.references.forEach { reference ->
                val dependency = declarationsByName[reference]?.singleOrNull() ?: return@forEach
                if (dependency.range.first > consumer.range.first && dependency.isMovableDeclarationDependency()) {
                    insertionOffset = minOf(
                        insertionOffset,
                        unconditionalInsertionOffset(source, consumer.range.first),
                    )
                    pending += dependency
                }
            }
        }
        while (pending.isNotEmpty()) {
            val dependency = pending.removeFirst()
            if (!selected.add(dependency)) continue
            dependency.references.forEach { reference ->
                declarationsByName[reference].orEmpty().filter { candidate ->
                    candidate.range.first > insertionOffset && candidate.isMovableDeclarationDependency()
                }.forEach(pending::addLast)
            }
        }
        if (selected.isEmpty()) return source
        val ordered = selected.sortedBy { it.range.first }
        val declarationsText = ordered.joinToString(separator = "\n", postfix = "\n") { entity ->
            source.substring(entity.range)
        }
        val ranges = ordered.map(StructuralEntity::range)
        val adjustedInsertionOffset = insertionOffset - ranges.filter { it.first < insertionOffset }.sumOf(IntRange::count)
        val stripped = removeRanges(source, ranges)
        return stripped.substring(0, adjustedInsertionOffset) + declarationsText +
            stripped.substring(adjustedInsertionOffset)
    }

    private fun StructuralEntity.isMovableDeclarationDependency(): Boolean {
        val declaration = canonical.trimStart()
        return '=' !in declaration || declaration.startsWith("struct ") ||
            ABI_PROLOGUE_DECLARATION.containsMatchIn(declaration)
    }

    private fun StructuralEntity.declaredSymbols(): Set<String> {
        val symbols = linkedSetOf<String>()
        symbol?.let(symbols::add)
        val open = canonical.indexOf('{')
        val close = canonical.lastIndexOf('}')
        if (open < 0 || close <= open) return symbols
        canonical.substring(open + 1, close).split(';').forEach { member ->
            val head = member.substringBefore('=').trim()
            if (head.isEmpty()) return@forEach
            head.split(',').forEach { declarator ->
                DECLARATION_IDENTIFIER.findAll(declarator.substringBefore('['))
                    .lastOrNull()?.value?.let(symbols::add)
            }
        }
        return symbols
    }

    internal fun hoistLateReferencedConstants(source: String): String {
        val entities = sourceStructuralEntities(source)
        val depths = preprocessorDepths(source)
        val constantsByName = entities.filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION && entity.symbol != null &&
                depths[entity.range.first] == 0 &&
                entity.canonical.startsWith("const ")
        }.associateBy { requireNotNull(it.symbol) }
        if (constantsByName.isEmpty()) return source
        val firstReferences = linkedMapOf<String, Int>()
        entities.forEach { entity ->
            entity.references.filter(constantsByName::containsKey).forEach { reference ->
                firstReferences[reference] = minOf(firstReferences[reference] ?: Int.MAX_VALUE, entity.range.first)
            }
        }
        val pending = ArrayDeque(
            constantsByName.filter { (name, declaration) ->
                firstReferences.getOrDefault(name, Int.MAX_VALUE) < declaration.range.first
            }.keys,
        )
        val selected = linkedSetOf<StructuralEntity>()
        while (pending.isNotEmpty()) {
            val constant = constantsByName[pending.removeFirst()] ?: continue
            if (!selected.add(constant)) continue
            constant.references.filter(constantsByName::containsKey).forEach(pending::addLast)
        }
        if (selected.isEmpty()) return source
        val ordered = selected.sortedBy { it.range.first }
        val selectedSymbols = ordered.mapNotNullTo(hashSetOf(), StructuralEntity::symbol)
        val insertionOffset = entities.filterNot(selected::contains)
            .filter { entity -> entity.references.any(selectedSymbols::contains) }
            .minOfOrNull { it.range.first }
            ?: return source
        val unconditionalOffset = unconditionalInsertionOffset(source, insertionOffset)
        val declarations = ordered.joinToString(separator = "\n") { source.substring(it.range) } + "\n"
        val ranges = ordered.map(StructuralEntity::range)
        val adjustedInsertionOffset = unconditionalOffset -
            ranges.filter { it.first < unconditionalOffset }.sumOf(IntRange::count)
        val stripped = removeRanges(source, ranges)
        return stripped.substring(0, adjustedInsertionOffset) + declarations + stripped.substring(adjustedInsertionOffset)
    }

    private fun restoreContractHelperFunctions(request: SpirvOptimizationRequest, source: String): String {
        val referenced = reachableCodeAndMacroIdentifiers(source).asSequence()
            .filter { name -> CONTRACT_HELPER_PREFIXES.any(name::startsWith) }
            .toCollection(linkedSetOf())
        if (referenced.isEmpty()) return source
        val outputFunctions = scanNamedSourceFunctions(source).groupBy(StructuralEntity::symbol)
        val sourceFunctions = scanNamedSourceFunctions(request.source).groupBy(StructuralEntity::symbol)
        val missing = referenced.filter { name -> outputFunctions[name].isNullOrEmpty() }
        val functions = missing.flatMap { name -> sourceFunctions[name].orEmpty() }
            .distinctBy(StructuralEntity::identity)
            .sortedBy { it.range.first }
        if (functions.isEmpty()) return source
        val insertionOffset = structuralEntities(source).minOfOrNull { it.range.first } ?: return source
        val insertion = functions.joinToString(separator = "\n", postfix = "\n") { function ->
            request.source.substring(function.range)
        }
        return source.substring(0, insertionOffset) + insertion + source.substring(insertionOffset)
    }

    private fun splitLateBranchOwnedDeclarations(source: String): String {
        val begin = source.indexOf(BRANCH_OWNED_MAIN_BEGIN)
        val end = source.indexOf(BRANCH_OWNED_MAIN_END, begin.coerceAtLeast(0))
        if (begin < 0 || end < 0) return source
        val range = source.lineRangeAt(begin).first..source.lineRangeAt(end).last
        val lines = source.substring(range).lineSequence().toList()
        data class Branch(val header: String, val content: String)
        val branches = mutableListOf<Branch>()
        var header: String? = null
        val content = mutableListOf<String>()
        var depth = 0
        lines.drop(1).dropLast(1).forEach { line ->
            val directive = line.trimStart()
            when {
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                    directive.startsWith("#ifdef") || directive.startsWith("#ifndef") -> {
                    if (depth == 0) {
                        header = line
                    } else {
                        content += line
                    }
                    depth++
                }
                (directive.startsWith("#elif ") || directive.startsWith("#elif\t") ||
                    directive == "#else") && depth == 1 -> {
                    header?.let { branches += Branch(it, content.joinToString("\n")) }
                    header = line
                    content.clear()
                }
                directive.startsWith("#endif") -> {
                    depth--
                    if (depth == 0) {
                        header?.let { branches += Branch(it, content.joinToString("\n")) }
                        header = null
                        content.clear()
                    } else {
                        content += line
                    }
                }
                else -> content += line
            }
        }
        if (branches.isEmpty()) return source
        data class SplitBranch(val branch: Branch, val prologue: String, val entry: String)
        val split = branches.map { branch ->
            val main = BRANCH_MAIN_HEADER.find(branch.content) ?: return source
            SplitBranch(
                branch,
                branch.content.substring(0, main.range.first).trim(),
                branch.content.substring(main.range.first).trim(),
            )
        }
        if (split.all { it.prologue.isEmpty() }) return source
        fun render(selectDeclarations: Boolean): String = buildString {
            split.forEach { splitBranch ->
                val branch = splitBranch.branch
                appendLine(branch.header)
                val text = if (selectDeclarations) splitBranch.prologue else splitBranch.entry
                if (text.isNotBlank()) appendLine(text)
            }
            append("#endif")
        }
        val mainBlock = buildString {
            appendLine(BRANCH_OWNED_MAIN_BEGIN)
            appendLine(render(false))
            append(BRANCH_OWNED_MAIN_END)
        }
        var result = source.substring(0, range.first) + mainBlock + source.substring(range.last + 1)
        val prologueEnd = result.indexOf(BRANCH_OWNED_PROLOGUE_END)
        val declarations = render(true)
        result = if (prologueEnd >= 0) {
            result.substring(0, prologueEnd) + declarations + "\n" + result.substring(prologueEnd)
        } else {
            result.substring(0, range.first) + BRANCH_OWNED_PROLOGUE_BEGIN + "\n" + declarations + "\n" +
                BRANCH_OWNED_PROLOGUE_END + "\n" + result.substring(range.first)
        }
        return result
    }

    private fun relocateBranchOwnedPrologue(source: String): String {
        val begin = source.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        val end = source.indexOf(BRANCH_OWNED_PROLOGUE_END, begin.coerceAtLeast(0))
        if (begin < 0 || end < 0) return source
        val range = source.lineRangeAt(begin).first..source.lineRangeAt(end).last
        val entry = source.substring(range).trim()
        val stripped = source.removeRange(range.first, range.last + 1)
        val firstFunction = (structuralEntities(stripped).filter { it.kind == StructuralEntityKind.FUNCTION } +
            scanNamedSourceFunctions(stripped)).minByOrNull { it.range.first }
            ?: return stripped.trimEnd() + "\n\n$entry\n"
        val owner = nearestConditionalOwnerRanges(stripped, listOf(firstFunction.range)).single()
        val insertionOffset = owner?.takeUnless { isIncludeGuardOwner(stripped, it) }?.first
            ?: firstFunction.range.first
        return stripped.substring(0, insertionOffset) + entry + "\n\n" + stripped.substring(insertionOffset)
    }

    private fun relocateSourceAbiDeclarations(
        request: SpirvOptimizationRequest,
        source: String,
    ): String {
        val prologueBegin = source.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        val prologueEnd = source.indexOf(BRANCH_OWNED_PROLOGUE_END, prologueBegin.coerceAtLeast(0))
        if (prologueBegin < 0 || prologueEnd < 0) return source
        val sourceDeclarations = sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val declarationRanges = sourceDeclarations.map(StructuralEntity::range)
        val resolvedOwners = abiRestorationOwners(request.source, declarationRanges)
        val ownerRanges = resolvedOwners.distinct().filterNot { candidate ->
            resolvedOwners.any { owner ->
                owner != candidate && owner.containsRange(candidate)
            }
        }
        val relocations = ownerRanges.flatMap { sourceRange ->
            val exact = request.source.substring(sourceRange)
            occurrences(source, exact).filter { occurrence ->
                occurrence.first > prologueBegin && occurrence.last < prologueEnd
            }.map { occurrence -> sourceRange to occurrence }
        }.sortedBy { it.first.first }
        var result = source
        if (relocations.isNotEmpty()) {
            val outputRanges = relocations.map { it.second }.distinct().filterNot { candidate ->
                relocations.any { (_, owner) -> owner != candidate && owner.containsRange(candidate) }
            }
            val declarations = relocations.filter { (_, outputRange) -> outputRange in outputRanges }
                .joinToString("\n") { (sourceRange) -> request.source.substring(sourceRange).trim() }
            val stripped = removeRanges(result, outputRanges)
            val insertionOffset = stripped.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
            result = stripped.substring(0, insertionOffset) + declarations + "\n" +
                stripped.substring(insertionOffset)
        }

        val remainingBegin = result.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        val remainingEnd = result.indexOf(BRANCH_OWNED_PROLOGUE_END, remainingBegin.coerceAtLeast(0))
        if (remainingBegin < 0 || remainingEnd < 0) return result
        val conditionalOwners = nearestConditionalOwnerRanges(request.source, declarationRanges)
        data class DeclarationRelocation(
            val sourceRange: IntRange,
            val outputRanges: List<IntRange>,
            val insertion: String?,
        )
        val declarationRelocations = declarationRanges.indices.mapNotNull { index ->
            val sourceRange = declarationRanges[index]
            val exact = request.source.substring(sourceRange)
            val found = occurrences(result, exact)
            val inside = found.filter { occurrence ->
                occurrence.first > remainingBegin && occurrence.last < remainingEnd
            }
            if (inside.isEmpty()) return@mapNotNull null
            val hasOutside = found.any { occurrence -> occurrence !in inside }
            val insertion = if (hasOutside) {
                null
            } else {
                renderRelocatedAbiDeclaration(request.source, sourceRange, conditionalOwners[index])
                    ?: return@mapNotNull null
            }
            DeclarationRelocation(sourceRange, inside, insertion)
        }
        val individualRanges = declarationRelocations.flatMap(DeclarationRelocation::outputRanges).distinct()
        val individualInsertions = declarationRelocations.filter { it.insertion != null }
            .distinctBy(DeclarationRelocation::sourceRange)
            .sortedBy { it.sourceRange.first }
            .joinToString("\n") { requireNotNull(it.insertion) }
        var stripped = removeRanges(result, individualRanges)
        var insertionOffset = stripped.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        result = stripped.substring(0, insertionOffset) +
            individualInsertions.takeIf(String::isNotEmpty)?.plus("\n").orEmpty() +
            stripped.substring(insertionOffset)

        val identityBegin = result.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        val identityEnd = result.indexOf(BRANCH_OWNED_PROLOGUE_END, identityBegin.coerceAtLeast(0))
        if (identityBegin < 0 || identityEnd < 0) return result
        val sourceByIdentity = sourceDeclarations.groupBy(StructuralEntity::identity)
        val sourceRangeIndexes = declarationRanges.withIndex().associate { it.value to it.index }
        val outputDeclarations = structuralEntities(result).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val identityRelocations = outputDeclarations.filter { entity ->
            entity.range.first > identityBegin && entity.range.last < identityEnd
        }.mapNotNull { entity ->
            val candidates = sourceByIdentity[entity.identity].orEmpty()
            val sourceDeclaration = when (candidates.size) {
                0 -> return@mapNotNull null
                1 -> candidates.single()
                else -> {
                    val outputIdentifiers = DECLARATION_IDENTIFIER.findAll(result.substring(entity.range))
                        .mapTo(hashSetOf(), MatchResult::value)
                    val ranked = candidates.map { candidate ->
                        val candidateIdentifiers = DECLARATION_IDENTIFIER.findAll(
                            request.source.substring(candidate.range),
                        ).mapTo(hashSetOf(), MatchResult::value)
                        candidate to candidateIdentifiers.intersect(outputIdentifiers).size
                    }.sortedByDescending { it.second }
                    ranked.firstOrNull()?.takeIf { best ->
                        best.second > 0 && ranked.getOrNull(1)?.second != best.second
                    }?.first ?: return@mapNotNull null
                }
            }
            val hasOutside = outputDeclarations.any { candidate ->
                candidate.identity == entity.identity &&
                    (candidate.range.first < identityBegin || candidate.range.last > identityEnd)
            }
            val insertion = if (hasOutside) {
                null
            } else {
                val sourceIndex = sourceRangeIndexes[sourceDeclaration.range] ?: return@mapNotNull null
                renderRelocatedAbiDeclaration(
                    request.source,
                    sourceDeclaration.range,
                    conditionalOwners[sourceIndex],
                ) ?: return@mapNotNull null
            }
            Triple(sourceDeclaration.range, entity.range, insertion)
        }
        if (identityRelocations.isEmpty()) return result
        val identityInsertions = identityRelocations.filter { it.third != null }.distinctBy { it.first }
            .sortedBy { it.first.first }.joinToString("\n") { requireNotNull(it.third) }
        stripped = removeRanges(result, identityRelocations.map { it.second }.distinct())
        insertionOffset = stripped.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN)
        return stripped.substring(0, insertionOffset) +
            identityInsertions.takeIf(String::isNotEmpty)?.plus("\n").orEmpty() +
            stripped.substring(insertionOffset)
    }

    private fun renderRelocatedAbiDeclaration(
        source: String,
        declaration: IntRange,
        conditionalOwner: IntRange?,
    ): String? {
        val exact = source.substring(declaration).trim()
        if (conditionalOwner == null || isIncludeGuardOwner(source, conditionalOwner)) return exact
        val ownerSource = source.substring(conditionalOwner)
        val header = ownerSource.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?: return null
        if (!header.matches(Regex("^#if(?:def|ndef)?\\b.*$"))) return null
        if (ownerSource.lineSequence().map(String::trim).any { line -> line.startsWith("#elif") }) return null
        val elseDirective = Regex("(?m)^[\\t ]*#else[\\t ]*(?://[^\\r\\n]*)?$").find(ownerSource)
        val selectedHeader = if (
            elseDirective != null && declaration.first - requireNotNull(conditionalOwner).first > elseDirective.range.first
        ) {
            when {
                header.startsWith("#ifdef ") -> "#ifndef ${header.removePrefix("#ifdef ").trim()}"
                header.startsWith("#ifndef ") -> "#ifdef ${header.removePrefix("#ifndef ").trim()}"
                header.startsWith("#if ") -> "#if !(${header.removePrefix("#if ").trim()})"
                else -> return null
            }
        } else {
            header
        }
        return "$selectedHeader\n$exact\n#endif"
    }

    private fun abiRestorationOwners(
        source: String,
        declarations: List<IntRange>,
    ): List<IntRange> {
        val conditionalOwners = nearestConditionalOwnerRanges(source, declarations)
        val distinctOwners = conditionalOwners.filterNotNull().distinct()
        val includeGuards = distinctOwners.filterTo(hashSetOf()) { owner -> isIncludeGuardOwner(source, owner) }
        val safeOwners = distinctOwners.filterTo(hashSetOf()) { owner ->
            owner !in includeGuards && structuralEntities(source.substring(owner)).none { entity ->
                entity.kind == StructuralEntityKind.FUNCTION
            }
        }
        return declarations.indices.mapNotNull { index ->
            val owner = conditionalOwners[index] ?: return@mapNotNull declarations[index]
            when (owner) {
                in includeGuards -> declarations[index]
                in safeOwners -> owner
                else -> null
            }
        }
    }

    private fun isIncludeGuardOwner(source: String, owner: IntRange): Boolean {
        val directives = source.substring(owner).lineSequence().map(String::trim)
            .filter(String::isNotEmpty).take(2).toList()
        val guard = directives.firstOrNull()?.let { line ->
            Regex("^#ifndef[\\t ]+([A-Za-z_][A-Za-z0-9_]*)$").matchEntire(line)?.groupValues?.get(1)
        }
        return guard != null && directives.getOrNull(1)?.matches(
            Regex("^#define[\\t ]+${Regex.escape(guard)}(?:[\\t ].*)?$"),
        ) == true
    }

    private fun resolveAbiModifierTokens(
        source: String,
        signatures: List<ShaderStructuralSignature>,
    ): String {
        val qualifierSets = signatures.flatMap(ShaderStructuralSignature::resources).mapNotNull { resource ->
            val identity = structuralDeclarationIdentity(resource)?.first ?: return@mapNotNull null
            val qualifiers = ABI_MEMORY_QUALIFIER.findAll(resource).mapTo(linkedSetOf(), MatchResult::value)
            identity to qualifiers
        }.groupBy({ it.first }, { it.second })
        val stableQualifiers = qualifierSets.mapNotNull { (identity, variants) ->
            variants.distinct().singleOrNull()?.let { identity to it }
        }.toMap()
        val replacements = structuralEntities(source).mapNotNull { entity ->
            if (entity.kind != StructuralEntityKind.DECLARATION) return@mapNotNull null
            val exact = source.substring(entity.range)
            if (!ABI_MODIFIER_TOKEN.containsMatchIn(exact)) return@mapNotNull null
            val qualifiers = stableQualifiers[entity.identity] ?: return@mapNotNull null
            val replacement = qualifiers.joinToString(" ")
            entity.range to ABI_MODIFIER_TOKEN.replace(exact, replacement)
        }
        if (replacements.isEmpty()) return source
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range.first, range.last + 1, replacement)
        }
        return result
    }

    internal fun restoreMissingSourceTypeDeclarations(
        request: SpirvOptimizationRequest,
        source: String,
    ): String {
        val outputEntities = structuralEntities(source)
        val masked = maskStructuralCode(source)
        val firstFunctionOffset = outputEntities.firstOrNull { it.kind == StructuralEntityKind.FUNCTION }
            ?.range?.first ?: masked.length
        val prologueOffset = masked.indexOf(BRANCH_OWNED_PROLOGUE_BEGIN).takeIf { it >= 0 } ?: firstFunctionOffset
        val abiOffset = ABI_PROLOGUE_DECLARATION.find(masked.substring(0, prologueOffset))?.range?.first
            ?: prologueOffset
        val abiLineOffset = source.lineRangeAt(abiOffset).first
        val insertionOffset = nearestConditionalOwnerRanges(source, listOf(abiLineOffset..abiLineOffset))
            .singleOrNull()?.first ?: abiLineOffset
        val sourceTypes = sourceStructuralEntities(request.source).mapNotNull { entity ->
            if (entity.kind != StructuralEntityKind.DECLARATION) return@mapNotNull null
            val typeName = STRUCT_DECLARATION_NAME.find(entity.canonical)?.groupValues?.get(1)
                ?: return@mapNotNull null
            typeName to entity
        }.groupBy({ it.first }, { it.second })
        val outputTypes = outputEntities.mapNotNull { entity ->
            if (entity.kind != StructuralEntityKind.DECLARATION) return@mapNotNull null
            val typeName = STRUCT_DECLARATION_NAME.find(entity.canonical)?.groupValues?.get(1)
                ?: return@mapNotNull null
            typeName to entity
        }.groupBy({ it.first }, { it.second })
        fun requiresRestoration(name: String): Boolean =
            outputTypes[name].isNullOrEmpty() || outputTypes.getValue(name).any { it.range.first > insertionOffset }
        val referenced = DECLARATION_IDENTIFIER.findAll(masked).mapTo(linkedSetOf(), MatchResult::value)
        val pending = ArrayDeque(
            referenced.filter { name -> requiresRestoration(name) && sourceTypes[name]?.size == 1 },
        )
        val selected = linkedMapOf<String, StructuralEntity>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in selected || !requiresRestoration(name)) continue
            val declaration = sourceTypes[name]?.singleOrNull() ?: continue
            selected[name] = declaration
            declaration.references.filter { dependency ->
                dependency !in selected && requiresRestoration(dependency) && sourceTypes[dependency]?.size == 1
            }.forEach(pending::addLast)
        }
        if (selected.isEmpty()) return source
        val restoredInstanceIdentities = selected.values.filter { entity ->
            val typeName = STRUCT_DECLARATION_NAME.find(entity.canonical)?.groupValues?.get(1)
            typeName != null && entity.symbol != typeName
        }.mapTo(hashSetOf(), StructuralEntity::identity)
        val removals = (
            selected.keys.flatMap { name -> outputTypes[name].orEmpty() } +
                outputEntities.filter { entity -> entity.identity in restoredInstanceIdentities }
            ).map(StructuralEntity::range).distinct()
        val adjustedInsertionOffset = insertionOffset - removals.filter { it.first < insertionOffset }.sumOf(IntRange::count)
        val stripped = removeRanges(source, removals)
        val declarations = selected.values.sortedBy { it.range.first }.joinToString("\n", postfix = "\n") { entity ->
            request.source.substring(entity.range)
        }
        return stripped.substring(0, adjustedInsertionOffset) + declarations + stripped.substring(adjustedInsertionOffset)
    }

    private fun removeNonBranchOwnedMainFunctions(source: String): String {
        val begin = source.indexOf(BRANCH_OWNED_MAIN_BEGIN)
        val end = source.indexOf(BRANCH_OWNED_MAIN_END, begin.coerceAtLeast(0))
        if (begin < 0 || end < 0) return source
        val removals = scanNamedSourceFunctions(source).filter { entity ->
            entity.symbol == "main" && (entity.range.first < begin || entity.range.last > end)
        }.map(StructuralEntity::range)
        return if (removals.isEmpty()) source else removeRanges(source, removals)
    }

    private fun restoreMissingBranchOwnedMain(
        source: String,
        modules: List<SpirvModuleResult>,
        restorationPlan: ShaderStructuralRestorationPlan,
    ): String {
        val begin = source.indexOf(BRANCH_OWNED_MAIN_BEGIN)
        val end = source.indexOf(BRANCH_OWNED_MAIN_END, begin.coerceAtLeast(0))
        if (begin < 0 || end < 0) return source
        val range = source.lineRangeAt(begin).first..source.lineRangeAt(end).last
        if (scanNamedSourceFunctions(source.substring(range)).any { it.symbol == "main" }) return source
        val branches = modules.flatMap { module ->
            module.structuralAssignments.ifEmpty { listOf(module.structuralAssignment) }.map { assignment ->
                module.source to assignment
            }
        }
        val restored = renderBranchOwnedMain(
            branches.map { it.first },
            branches.map { it.second },
            restorationPlan,
            emptySet(),
        ) ?: return source
        return source.substring(0, range.first) + restored.source + source.substring(range.last + 1)
    }

    private fun hoistLateAbiDeclarations(source: String): String {
        val functions = sourceStructuralEntities(source).filter { it.kind == StructuralEntityKind.FUNCTION }
        val firstFunction = functions.minOfOrNull { it.range.first } ?: return source
        val candidates = sourceStructuralEntities(source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                entity.range.first > firstFunction &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val ownedRanges = abiRestorationOwners(source, candidates.map(StructuralEntity::range))
        val ranges = ownedRanges.distinct()
            .filter { it.first > firstFunction }
            .filterNot { candidate ->
                ownedRanges.any { owner ->
                    owner != candidate && owner.containsRange(candidate)
                }
            }
        if (ranges.isEmpty()) return source
        val insertion = ranges.sortedBy(IntRange::first).joinToString("\n", postfix = "\n") { range ->
            source.substring(range)
        }
        val stripped = removeRanges(source, ranges)
        val insertionOffset = sourceStructuralEntities(stripped).filter { it.kind == StructuralEntityKind.FUNCTION }
            .minOf { it.range.first }.let { unconditionalInsertionOffset(stripped, it) }
        return stripped.substring(0, insertionOffset) + insertion + stripped.substring(insertionOffset)
    }

    private fun relocateUnconditionalLateSourceAbiDeclarations(
        request: SpirvOptimizationRequest,
        source: String,
    ): String {
        val firstFunction = sourceStructuralEntities(source).filter { it.kind == StructuralEntityKind.FUNCTION }
            .minOfOrNull { it.range.first } ?: return source
        val sourceDeclarations = sourceStructuralEntities(request.source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val conditionalOwners = nearestConditionalOwnerRanges(
            request.source,
            sourceDeclarations.map(StructuralEntity::range),
        )
        val relocations = sourceDeclarations.indices.mapNotNull { index ->
            if (conditionalOwners[index] != null) return@mapNotNull null
            val exact = request.source.substring(sourceDeclarations[index].range)
            val occurrences = occurrences(source, exact)
            val occurrenceOwners = nearestConditionalOwnerRanges(source, occurrences)
            if (occurrences.indices.any { occurrenceIndex ->
                    occurrences[occurrenceIndex].first < firstFunction && occurrenceOwners[occurrenceIndex] == null
                }
            ) return@mapNotNull null
            val misplaced = occurrences.indices.mapNotNull { occurrenceIndex ->
                occurrences[occurrenceIndex].takeIf { occurrence ->
                    occurrence.first > firstFunction || occurrenceOwners[occurrenceIndex] != null
                }
            }
            if (misplaced.isEmpty()) return@mapNotNull null
            exact to misplaced
        }.distinctBy { it.first }
        if (relocations.isEmpty()) return source
        val stripped = removeRanges(source, relocations.flatMap { it.second }.distinct())
        val insertionOffset = sourceStructuralEntities(stripped).filter { it.kind == StructuralEntityKind.FUNCTION }
            .minOf { it.range.first }.let { unconditionalInsertionOffset(stripped, it) }
        val insertion = relocations.joinToString("\n", postfix = "\n") { it.first.trim() }
        return stripped.substring(0, insertionOffset) + insertion + stripped.substring(insertionOffset)
    }

    private fun restoreProbeResources(source: String, modules: List<SpirvModuleResult>): String {
        val markers = modules.flatMap(SpirvModuleResult::resourceMarkers)
            .distinctBy(TextureResourceMarker::identifier)
        return if (markers.isEmpty()) source else TextureAccessAnalyzer.restoreProbeResources(source, markers)
    }

    private fun hoistLateAbiQualifierMacros(source: String): String {
        val abiDeclarations = structuralEntities(source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
        }
        if (abiDeclarations.isEmpty()) return source
        val definitions = sourceMacroDefinitions(source)
        val nestedDefinitions = definitions.values.flatten().filter { it.depth != 0 }
        val nestedOwners = nearestConditionalOwnerRanges(source, nestedDefinitions.map { definition ->
            definition.offset until definition.offset + definition.exactText.length
        })
        val includeGuardDefinitions = nestedDefinitions.filterIndexedTo(hashSetOf()) { index, _ ->
            nestedOwners[index]?.let { owner -> isIncludeGuardOwner(source, owner) } == true
        }
        val insertionOffset = abiDeclarations.minOf { it.range.first }
        val abiLineIdentifiers = source.lineSequence().filter(ABI_PROLOGUE_DECLARATION::containsMatchIn)
            .flatMap { line -> DECLARATION_IDENTIFIER.findAll(line).map(MatchResult::value) }
            .toCollection(linkedSetOf())
        val stableAbiAliases = objectAliases(source).keys.intersect(abiLineIdentifiers)
        val pending = ArrayDeque(
            (abiDeclarations.flatMapTo(linkedSetOf(), StructuralEntity::references) +
                abiDeclarations.mapNotNull(StructuralEntity::symbol) + abiLineIdentifiers)
                .filter(definitions::containsKey),
        )
        val selectedDefinitions = linkedSetOf<SourceMacroDefinition>()
        while (pending.isNotEmpty()) {
            definitions[pending.removeFirst()].orEmpty().forEach { definition ->
                if (
                    definition.offset < insertionOffset ||
                    (definition.depth != 0 && definition !in includeGuardDefinitions &&
                        definition.name !in stableAbiAliases)
                ) return@forEach
                if (!selectedDefinitions.add(definition)) return@forEach
                DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                    .filter(definitions::containsKey)
                    .forEach(pending::addLast)
            }
        }
        val selected = selectedDefinitions.sortedBy(SourceMacroDefinition::offset)
        if (selected.isEmpty()) return source
        val ranges = selected.map { definition ->
            definition.offset until definition.offset + definition.exactText.length
        }
        val insertion = selected.joinToString(separator = "") { it.exactText }.let { value ->
            if (value.endsWith('\n') || value.endsWith('\r')) value else "$value\n"
        }
        val stripped = removeRanges(source, ranges)
        return stripped.substring(0, insertionOffset) + insertion + stripped.substring(insertionOffset)
    }

    private fun sourceMacroDefinitions(source: String): Map<String, List<SourceMacroDefinition>> {
        val result = linkedMapOf<String, MutableList<SourceMacroDefinition>>()
        var depth = 0
        var offset = 0
        while (offset < source.length) {
            val newline = source.indexOfAny(charArrayOf('\r', '\n'), offset)
            val contentEnd = if (newline < 0) source.length else newline
            val lineEnd = when {
                newline < 0 -> source.length
                source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
                else -> newline + 1
            }
            val line = source.substring(offset, contentEnd)
            val directive = line.trimStart()
            if (directive.startsWith("#endif")) depth = (depth - 1).coerceAtLeast(0)
            MACRO_DEFINITION.matchEntire(line)?.destructured?.let { (name, value) ->
                var definitionEnd = lineEnd
                var physicalLine = line
                while (physicalLine.trimEnd().endsWith('\\') && definitionEnd < source.length) {
                    val continuationStart = definitionEnd
                    val continuationNewline = source.indexOfAny(charArrayOf('\r', '\n'), continuationStart)
                    val continuationContentEnd = if (continuationNewline < 0) source.length else continuationNewline
                    definitionEnd = when {
                        continuationNewline < 0 -> source.length
                        source[continuationNewline] == '\r' &&
                            source.getOrNull(continuationNewline + 1) == '\n' -> continuationNewline + 2
                        else -> continuationNewline + 1
                    }
                    physicalLine = source.substring(continuationStart, continuationContentEnd)
                }
                val exactText = source.substring(offset, definitionEnd)
                result.getOrPut(name) { mutableListOf() } += SourceMacroDefinition(
                    name,
                    value + exactText.removePrefix(source.substring(offset, lineEnd)),
                    exactText,
                    offset,
                    depth,
                )
                offset = definitionEnd
            }
            if (
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                directive.startsWith("#ifdef") || directive.startsWith("#ifndef")
            ) {
                depth++
            }
            if (offset < lineEnd) offset = lineEnd
        }
        return result
    }

    private fun objectAliases(source: String): Map<String, String> = sourceMacroDefinitions(source).mapNotNull {
            (name, definitions) ->
        val targets = definitions.mapNotNull { definition ->
            DECLARATION_IDENTIFIER.matchEntire(definition.value.trim())?.value
        }.distinct()
        name to targets.singleOrNull().orEmpty()
    }.filter { it.second.isNotEmpty() }.toMap()

    private fun resolveObjectAlias(name: String, aliases: Map<String, String>): String {
        var result = name
        val visited = linkedSetOf<String>()
        while (visited.add(result)) result = aliases[result] ?: break
        return result
    }

    internal fun deduplicateUnconditionalDeclarations(source: String): String {
        val aliases = objectAliases(source)
        val depths = preprocessorDepths(source)
        val assignedSymbols = optimizedAssignedSymbols(source)
        val declarationEntities = structuralEntities(source).filter { it.kind == StructuralEntityKind.DECLARATION }
        val conditionalOwners = nearestConditionalOwnerRanges(source, declarationEntities.map(StructuralEntity::range))
        val ownerByRange = declarationEntities.indices.associate { index ->
            declarationEntities[index].range to conditionalOwners[index]
        }
        val declarations = declarationEntities
            .mapNotNull { entity ->
                val symbol = entity.symbol ?: return@mapNotNull null
                val typeName = STRUCT_DECLARATION_NAME.find(entity.canonical)?.groupValues?.get(1)
                val owner = typeName?.let { "struct:$it" } ?: resolveObjectAlias(symbol, aliases)
                Triple(owner, entity, depths[entity.range.first])
            }
        val duplicates = declarations.groupBy { it.first }.values.flatMap { variants ->
            val typeName = variants.first().first.removePrefix("struct:")
                .takeIf { variants.first().first.startsWith("struct:") }
            val instanceDeclarations = typeName?.let { name ->
                variants.filter { it.second.symbol != name }
            }.orEmpty()
            val candidates = instanceDeclarations.ifEmpty { variants }
            val discarded = variants - candidates.toSet()
            val physicalDeclarations = candidates.filter { it.second.symbol == it.first }
            fun resolvedCanonical(candidate: Triple<String, StructuralEntity, Int>): String {
                val symbol = candidate.second.symbol ?: return candidate.second.canonical
                return Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])")
                    .replace(candidate.second.canonical, candidate.first)
            }
            val physicalCanonical = physicalDeclarations.mapTo(hashSetOf(), ::resolvedCanonical)
            val conditionalCanonicals = candidates.filter { it.third != 0 }
                .mapTo(hashSetOf(), ::resolvedCanonical)
            val aliasDuplicates = candidates.filter { candidate ->
                candidate.second.symbol != candidate.first && resolvedCanonical(candidate) in physicalCanonical
            }
            val dominated = candidates.filter { candidate ->
                val candidateOwner = ownerByRange[candidate.second.range] ?: return@filter false
                candidates.any { other ->
                    if (other === candidate || resolvedCanonical(other) != resolvedCanonical(candidate)) {
                        return@any false
                    }
                    val otherOwner = ownerByRange[other.second.range]
                    other.third == 0 && conditionalCanonicals.size <= 1 ||
                        (otherOwner != null && isIncludeGuardOwner(source, otherOwner)) ||
                        (otherOwner != null && otherOwner != candidateOwner && otherOwner.containsRange(candidateOwner))
                }
            }
            val removedDuplicates = (aliasDuplicates + dominated).toSet()
            val retainedCandidates = candidates - removedDuplicates
            val conditional = retainedCandidates.filter { it.third != 0 }
            val unconditional = retainedCandidates.filter { it.third == 0 }
            (discarded + removedDuplicates).map { it.second.range } + when {
                conditional.isNotEmpty() -> unconditional.map { it.second.range }
                retainedCandidates.first().second.symbol in assignedSymbols -> {
                    val writable = unconditional.filterNot { it.second.canonical.trimStart().startsWith("const ") }
                    if (writable.size == 1) {
                        unconditional.filterNot { it === writable.single() }.map { it.second.range }
                    } else {
                        unconditional.drop(1).map { it.second.range }
                    }
                }
                else -> unconditional.drop(1).map { it.second.range }
            }
        }
        return removeRanges(source, duplicates)
    }

    internal fun deduplicateDominatedAbiLines(source: String): String {
        data class ConditionalFrame(val opening: String, var branch: String)
        data class AbiLine(val range: IntRange, val text: String, val path: List<Pair<String, String>>)
        fun normalizeDirective(value: String): String =
            value.substringBefore("//").replace(SEMANTIC_WHITESPACE, " ").trim()
        val frames = mutableListOf<ConditionalFrame>()
        val declarations = mutableListOf<AbiLine>()
        var offset = 0
        while (offset < source.length) {
            val range = source.lineRangeAt(offset)
            val line = source.substring(range)
            val directive = line.trimStart()
            when {
                directive.startsWith("#endif") -> if (frames.isNotEmpty()) frames.removeLast()
                directive.startsWith("#elif ") || directive.startsWith("#elif\t") ||
                    directive.startsWith("#else") -> if (frames.isNotEmpty()) {
                    frames.last().branch = normalizeDirective(directive)
                }
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                    directive.startsWith("#ifdef") || directive.startsWith("#ifndef") ->
                    frames += ConditionalFrame(normalizeDirective(directive), "")
                line.trimEnd().endsWith(';') && ABI_PROLOGUE_DECLARATION.containsMatchIn(line) ->
                    declarations += AbiLine(
                        range,
                        normalizeStructuralEntity(line),
                        frames.map { it.opening to it.branch },
                    )
            }
            offset = range.last + 1
        }
        val duplicates = declarations.groupBy(AbiLine::text).values.flatMap { variants ->
            variants.filter { candidate ->
                variants.any { other ->
                    other !== candidate && (
                        other.path.size < candidate.path.size &&
                            candidate.path.take(other.path.size) == other.path ||
                            other.range.first < candidate.range.first && other.path == candidate.path
                    )
                }
            }.map(AbiLine::range)
        }
        return removeRanges(source, duplicates)
    }

    private fun optimizedRetainedEntityIdentities(
        optimizedSource: String,
        restoredSlots: List<ShaderStructuralEntitySlot>,
        assignmentSource: String = optimizedSource,
    ): Set<String> {
        val optimizedEntities = structuralEntities(optimizedSource)
        val restoredIdentities = restoredSlots.flatMapTo(hashSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        val assignedSymbols = optimizedAssignedSymbols(assignmentSource)
        val restoredConstIdentities = restoredSlots.flatMapTo(hashSetOf()) { slot ->
            structuralEntities(slot.exactText).filter { entity ->
                entity.kind == StructuralEntityKind.DECLARATION &&
                    entity.canonical.trimStart().startsWith("const ")
            }.map(StructuralEntity::identity)
        }
        val retained = optimizedEntities.filterTo(linkedSetOf()) { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                entity.identity in restoredConstIdentities &&
                entity.symbol in assignedSymbols &&
                !entity.canonical.trimStart().startsWith("const ")
        }.mapTo(linkedSetOf(), StructuralEntity::identity)
        val functions = optimizedEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
        val bySymbol = functions.filter { it.symbol != null }.groupBy { requireNotNull(it.symbol) }
        val pending = ArrayDeque<String>()
        functions.filter { it.identity !in restoredIdentities || it.identity == "function:main()" }
            .flatMapTo(pending, StructuralEntity::references)
        val visitedSymbols = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val symbol = pending.removeFirst()
            if (!visitedSymbols.add(symbol)) continue
            bySymbol[symbol].orEmpty().forEach { function ->
                if (function.identity in restoredIdentities) retained += function.identity
                function.references.forEach(pending::addLast)
            }
        }
        return retained
    }

    private fun optimizedAssignedSymbols(source: String): Set<String> {
        val functions = structuralEntities(source).filter { it.kind == StructuralEntityKind.FUNCTION }
        if (functions.isEmpty()) return emptySet()
        return functions.flatMapTo(linkedSetOf()) { function ->
            ASSIGNED_SYMBOL.findAll(function.semantic).map { match -> match.groupValues[1] }
        }
    }

    private fun preprocessorDepths(source: String): IntArray {
        val result = IntArray(source.length + 1)
        var depth = 0
        var offset = 0
        while (offset < source.length) {
            val newline = source.indexOfAny(charArrayOf('\r', '\n'), offset)
            val contentEnd = if (newline < 0) source.length else newline
            val lineEnd = when {
                newline < 0 -> source.length
                source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
                else -> newline + 1
            }
            val directive = source.substring(offset, contentEnd).trimStart()
            if (directive.startsWith("#endif")) depth = (depth - 1).coerceAtLeast(0)
            for (index in offset until lineEnd) result[index] = depth
            if (
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                directive.startsWith("#ifdef") || directive.startsWith("#ifndef")
            ) {
                depth++
            }
            offset = lineEnd
        }
        result[source.length] = depth
        return result
    }

    private fun restorableSourceMacro(name: String): Boolean =
        !name.startsWith("SETTING_") && !name.startsWith("SM_SETTING_")

    private fun reanchorRestorationSlots(
        request: SpirvOptimizationRequest,
        slots: List<ShaderStructuralEntitySlot>,
    ): List<ShaderStructuralEntitySlot>? {
        if (slots.isEmpty()) return emptyList()
        val located = slots.map { slot ->
            val occurrences = occurrences(request.source, slot.exactText)
            val lineMatches = occurrences.filter { sourceLine(request.source, it.first) == slot.sourceLine }
            val selected = when {
                occurrences.size == 1 -> occurrences.single()
                lineMatches.size == 1 -> lineMatches.single()
                else -> return null
            }
            slot to selected
        }
        val ranges = located.map { it.second }
        val anchors = findStableAnchors(request.source).filter { anchor ->
            anchor.anchor.kind != IrisAnchorKind.DECLARATION && ranges.none { it.overlaps(anchor.range) }
        }
        val mainAnchor = anchors.singleOrNull {
            it.anchor == IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
        }
        return located.sortedBy { it.second.first }.mapIndexed { ordinal, (slot, range) ->
            val slotEntities = structuralEntities(slot.exactText)
            val isAbiPrologue = slotEntities.any { entity ->
                    entity.kind == StructuralEntityKind.DECLARATION &&
                        ABI_PROLOGUE_DECLARATION.containsMatchIn(entity.canonical)
                }
            if (isAbiPrologue && mainAnchor != null) {
                return@mapIndexed slot.copy(
                    ordinal = ordinal,
                    beforeAnchor = null,
                    afterAnchor = mainAnchor.anchor,
                    placement = IrisAnchorPlacement.BEFORE_AFTER,
                )
            }
            val before = anchors.filter { it.range.last < range.first }.maxByOrNull { it.range.last }
            val after = anchors.filter { it.range.first > range.last }.minByOrNull { it.range.first }
            if (before == null && after == null) return null
            val placement = when {
                before == null -> IrisAnchorPlacement.BEFORE_AFTER
                after == null -> IrisAnchorPlacement.AFTER_BEFORE
                range.first - before.range.last <= after.range.first - range.last -> IrisAnchorPlacement.AFTER_BEFORE
                else -> IrisAnchorPlacement.BEFORE_AFTER
            }
            slot.copy(
                ordinal = ordinal,
                beforeAnchor = before?.anchor,
                afterAnchor = after?.anchor,
                placement = placement,
            )
        }
    }

    private fun normalizedStructuralResidue(source: String): String {
        val entities = structuralEntities(source)
        val extensionRanges = EXTENSION_DIRECTIVE.findAll(source).map { match ->
            val owner = nearestConditionalOwnerRanges(source, listOf(match.range)).single()
            owner ?: source.lineRangeAt(match.range.first)
        }.toList()
        val residue = removeRanges(source, entities.map(StructuralEntity::range) + extensionRanges)
        return normalizeSemanticBody(residue)
    }

    private fun String.lineRangeAt(offset: Int): IntRange {
        val start = lastIndexOfAny(charArrayOf('\r', '\n'), offset - 1).let { if (it < 0) 0 else it + 1 }
        val newline = indexOfAny(charArrayOf('\r', '\n'), offset)
        val end = when {
            newline < 0 -> length
            this[newline] == '\r' && getOrNull(newline + 1) == '\n' -> newline + 2
            else -> newline + 1
        }
        return start until end
    }

    private fun removeStructuralEntities(source: String, identities: Set<String>): String {
        if (identities.isEmpty()) return source
        return removeRanges(
            source,
            structuralEntities(source).filter { it.identity in identities }.map(StructuralEntity::range),
        ).trimEnd() + "\n"
    }

    private fun occurrences(source: String, value: String): List<IntRange> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var offset = source.indexOf(value)
        while (offset >= 0) {
            result += offset until offset + value.length
            offset = source.indexOf(value, offset + value.length)
        }
        return result
    }

    private fun removeRanges(source: String, ranges: List<IntRange>): String {
        return ranges.distinct().sortedByDescending { it.first }.fold(source) { value, range ->
            value.removeRange(range.first, range.last + 1)
        }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

    private fun unconditionalInsertionOffset(source: String, offset: Int): Int {
        var insertionOffset = offset.coerceIn(0, source.length)
        var probeOffset = insertionOffset
        while (true) {
            val line = source.lineRangeAt(probeOffset)
            val owner = nearestConditionalOwnerRanges(source, listOf(line)).singleOrNull()
                ?: return insertionOffset
            insertionOffset = owner.first
            if (insertionOffset == 0) return 0
            probeOffset = insertionOffset - 1
        }
    }

    private fun nearestConditionalOwnerRanges(source: String, ranges: List<IntRange>): List<IntRange?> {
        if (ranges.isEmpty()) return emptyList()
        val lineStarts = mutableListOf(0)
        source.forEachIndexed { index, char ->
            if (char == '\n' || char == '\r' && source.getOrNull(index + 1) != '\n') lineStarts += index + 1
        }
        fun directiveRange(directive: PreprocessorDirective): IntRange {
            val start = lineStarts.getOrElse(directive.sourceLine - 1) { source.length }
            val end = lineStarts.getOrElse(directive.endLine) { source.length }
            return start until end
        }
        data class ConditionalStart(val offset: Int, val includeGuard: Boolean)
        val owners = mutableListOf<IntRange>()
        val open = ArrayDeque<ConditionalStart>()
        val directives = PreprocessorProtection.protect(source, "<conditional-owner>").directives
        directives.forEachIndexed { index, directive ->
            when (directive.kind) {
                PreprocessorDirectiveKind.IF,
                PreprocessorDirectiveKind.IFDEF,
                PreprocessorDirectiveKind.IFNDEF,
                -> {
                    val next = directives.getOrNull(index + 1)
                    val includeGuard = directive.kind == PreprocessorDirectiveKind.IFNDEF &&
                        directive.macroName != null && next?.kind == PreprocessorDirectiveKind.DEFINE &&
                        next.macroName == directive.macroName &&
                        next.conditionalDepth == directive.conditionalDepth + 1
                    open.addLast(ConditionalStart(directiveRange(directive).first, includeGuard))
                }
                PreprocessorDirectiveKind.ENDIF -> if (open.isNotEmpty()) {
                    val start = open.removeLast()
                    if (!start.includeGuard) owners += start.offset..directiveRange(directive).last
                }
                else -> Unit
            }
        }
        return ranges.map { range ->
            owners.filter { it.containsRange(range) }.minByOrNull { it.last - it.first }
        }
    }

    private fun sourceLine(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1

    private fun normalizeSemanticBody(source: String): String {
        return normalizeStructuralEntity(
            source
                .replace(SETTING_PRESENCE_BRIDGE, "")
                .replace(SETTING_VALUE_BRIDGE, "")
                .replace(VERSION_LINE, "#version"),
        )
    }

    private sealed interface StructuralStripResult {
        data class Restored(
            val source: String,
            val strippedSymbols: Set<String>,
        ) : StructuralStripResult
        data class Preserved(val reason: String) : StructuralStripResult
    }

    private sealed interface StructuralConvergence {
        data class Converged(
            val source: String,
            val restorationPlan: ShaderStructuralRestorationPlan,
            val optimizedEntities: Int,
            val restoredEntities: Int,
            val restoredBytes: Int,
        ) : StructuralConvergence

        data class Preserved(val reason: String) : StructuralConvergence
    }

    private data class BranchOwnedMain(
        val prologue: String,
        val source: String,
        val ownedIdentities: Set<String>,
    )

    private data class BranchOwnedPayload(
        val declarations: String,
        val entry: String,
        val semantic: String,
        val ownedIdentities: Set<String>,
    )

    private val SETTING_PRESENCE_BRIDGE = Regex(
        "(?m)^[\\t ]*#ifdef[\\t ]+(SETTING_[A-Za-z0-9_]+)[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#define[\\t ]+SM_\\1[\\t ]+true[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#else[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#define[\\t ]+SM_\\1[\\t ]+false[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#endif[\\t ]*(?:\\r\\n|\\n|\\r|$)",
    )
    private const val BRANCH_OWNED_PROLOGUE_BEGIN = "// SHADESMITH_BRANCH_OWNED_PROLOGUE_BEGIN"
    private const val BRANCH_OWNED_PROLOGUE_END = "// SHADESMITH_BRANCH_OWNED_PROLOGUE_END"
    private const val BRANCH_OWNED_MAIN_BEGIN = "// SHADESMITH_BRANCH_OWNED_MAIN_BEGIN"
    private const val BRANCH_OWNED_MAIN_END = "// SHADESMITH_BRANCH_OWNED_MAIN_END"
    private val BRANCH_MAIN_HEADER = "(?m)^[\\t ]*void[\\t ]+main[\\t ]*\\(".toRegex()
    private val SETTING_VALUE_BRIDGE = Regex(
        "(?m)^[\\t ]*#define[\\t ]+SM_(SETTING_[A-Za-z0-9_]+)[\\t ]+\\1[\\t ]*(?:\\r\\n|\\n|\\r|$)",
    )

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val FINAL_SPECIALIZATION_ARTIFACT =
        "\\b(?:constant_id|local_size_[xyz]_id|SPIRV_CROSS_CONSTANT_ID_[0-9]+)\\b".toRegex()
    private val CONDITIONAL_PARTITIONED_PRIMITIVE_CALL =
        "\\b(subgroupPartitionNV|subgroupPartitioned[A-Za-z0-9_]*NV)[\\t ]*\\(".toRegex()
    private val ABI_PROLOGUE_DECLARATION =
        "\\b(?:uniform|buffer|shared)\\b|^layout\\s*\\([^)]*(?:std430|std140|binding)".toRegex()
    private val SYMBOLIC_ARRAY_EXTENT =
        "\\[[^\\]\\r\\n]*[A-Za-z_][A-Za-z0-9_]*[^\\]\\r\\n]*\\]".toRegex()
    private val ABI_MODIFIER_TOKEN = "\\b[A-Z][A-Z0-9_]*_MODIFIER\\b".toRegex()
    private val ABI_MEMORY_QUALIFIER =
        "\\b(?:readonly|writeonly|coherent|volatile|restrict|uniform|buffer|shared)\\b".toRegex()
    private val EXTENSION_DIRECTIVE = "(?m)^[\\t ]*#extension\\b[^\\r\\n]*".toRegex()
    private val CONTRACT_HELPER_PREFIXES = listOf("_textile_")
    private val STRUCT_DECLARATION_NAME = "^struct\\s+([A-Za-z_][A-Za-z0-9_]*)\\b".toRegex()
    private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
    private val SEMANTIC_WHITESPACE = "\\s+".toRegex()
    private val MACRO_DEFINITION =
        "^[\\t ]*#define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*(.*)$".toRegex()
}

private data class SourceMacroDefinition(
    val name: String,
    val value: String,
    val exactText: String,
    val offset: Int,
    val depth: Int,
)

private data class SourceIncludeGuard(
    val name: String,
    val definitionText: String,
    val range: IntRange,
    val definedNames: Set<String>,
    val definitionCount: Int,
)

private data class ParsedIncludeGuardGroup(
    val name: String,
    val range: IntRange,
    val openerRange: IntRange,
    val guardDefinitions: List<IntRange>,
    val definedNames: Set<String>,
    val definitionIsFirstChild: Boolean,
)

private data class ParsedIncludeGuardSource(
    val groups: List<ParsedIncludeGuardGroup>,
    val definitions: Map<String, List<IntRange>>,
)

internal data class StructuralEntity(
    val range: IntRange,
    val canonical: String,
    val identity: String,
    val symbol: String?,
    val references: Set<String>,
    val semantic: String,
    val kind: StructuralEntityKind,
)

internal enum class StructuralEntityKind {
    DECLARATION,
    FUNCTION,
}

private class StructuralBranchMaskResolver(
    source: String,
    private val baseMask: String,
) {
    private data class Group(val branches: List<IntRange>)
    private data class LocatedDirective(val directive: PreprocessorDirective, val range: IntRange)

    private val groups = runCatching { locateGroups(source) }.getOrDefault(emptyList())
    private val cache = mutableMapOf<List<Pair<Int, Int>>, String>()

    fun defaultMask(): String = maskFor(-1)

    fun maskFor(offset: Int): String {
        if (groups.isEmpty()) return baseMask
        val overrides = groups.mapIndexedNotNull { index, group ->
            group.branches.indexOfFirst { offset in it }.takeIf { it > 0 }?.let { index to it }
        }
        return cache.getOrPut(overrides) {
            val selected = overrides.toMap()
            val result = baseMask.toCharArray()
            groups.forEachIndexed { index, group ->
                val selectedBranch = selected[index] ?: 0
                group.branches.forEachIndexed { branchIndex, range ->
                    if (branchIndex != selectedBranch) {
                        for (cursor in range) {
                            if (result[cursor] !in "\r\n") result[cursor] = ' '
                        }
                    }
                }
            }
            result.concatToString()
        }
    }

    private fun locateGroups(source: String): List<Group> {
        val directives = PreprocessorProtection.protect(source, "<structural-function-scan>").directives
        var searchOffset = 0
        val located = directives.map { directive ->
            val start = source.indexOf(directive.exactText, searchOffset)
            require(start >= 0) { "preprocessor directive location drifted during structural function scan" }
            val range = start until start + directive.exactText.length
            searchOffset = range.last + 1
            LocatedDirective(directive, range)
        }
        return located.filter { it.directive.conditionalId != null }
            .groupBy { requireNotNull(it.directive.conditionalId) }
            .toSortedMap()
            .values
            .mapNotNull { conditional ->
                val ordered = conditional.sortedBy { it.range.first }
                val opener = ordered.firstOrNull { it.directive.kind in CONDITIONAL_OPENING_KINDS }
                    ?: return@mapNotNull null
                val end = ordered.lastOrNull { it.directive.kind == PreprocessorDirectiveKind.ENDIF }
                    ?: return@mapNotNull null
                val delimiters = listOf(opener) + ordered.filter {
                    it.directive.kind == PreprocessorDirectiveKind.ELIF ||
                        it.directive.kind == PreprocessorDirectiveKind.ELSE
                }
                Group(
                    delimiters.mapIndexed { index, delimiter ->
                        val next = delimiters.getOrNull(index + 1)?.range?.first ?: end.range.first
                        (delimiter.range.last + 1) until next
                    },
                )
            }
    }

    private companion object {
        val CONDITIONAL_OPENING_KINDS = setOf(
            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
        )
    }
}

internal fun structuralEntities(source: String): List<StructuralEntity> =
    parseStructuralEntities(source, maskStructuralCode(source))

internal fun sourceStructuralEntities(source: String): List<StructuralEntity> {
    val baseMask = maskStructuralCode(source)
    val branchMask = StructuralBranchMaskResolver(source, baseMask).defaultMask()
    if (branchMask == baseMask) return parseStructuralEntities(source, baseMask)
    return (
        parseStructuralEntities(source, baseMask) +
            parseStructuralEntities(source, branchMask)
        ).distinctBy(StructuralEntity::range)
}

private fun parseStructuralEntities(source: String, masked: String): List<StructuralEntity> {
    val result = mutableListOf<StructuralEntity>()
    var boundary = 0
    var depth = 0
    var parenthesisDepth = 0
    var functionStart = -1
    var functionHeader = ""
    var cursor = 0
    while (cursor < masked.length) {
        when (masked[cursor]) {
            '(' -> if (depth == 0) parenthesisDepth++
            ')' -> if (depth == 0 && parenthesisDepth > 0) parenthesisDepth--
            '{' -> {
                if (depth == 0) {
                    val start = firstNonWhitespace(masked, boundary, cursor)
                    val prefix = if (start < cursor) masked.substring(start, cursor).trim() else ""
                    if (prefix.endsWith(')')) {
                        functionStart = start
                        functionHeader = normalizeStructuralEntity(source.substring(start, cursor))
                    }
                }
                depth++
            }
            '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && functionStart >= 0) {
                    val range = functionStart..cursor
                    val exact = source.substring(range)
                    val function = structuralFunctionIdentity(functionHeader)
                    val identity = function?.first ?: "function:$functionHeader"
                    val symbol = function?.second
                    result += StructuralEntity(
                        range,
                        functionHeader,
                        identity,
                        symbol,
                        structuralReferences(exact, symbol),
                        normalizeStructuralEntity(exact),
                        StructuralEntityKind.FUNCTION,
                    )
                    boundary = cursor + 1
                    functionStart = -1
                    functionHeader = ""
                }
            }
            ';' -> if (depth == 0) {
                val start = firstNonWhitespace(masked, boundary, cursor)
                if (start <= cursor) {
                    val text = source.substring(start, cursor + 1).lineSequence()
                        .filterNot { it.trimStart().startsWith('#') }
                        .joinToString("\n").trim()
                    if (text.isNotEmpty() && isStructuralDeclarationCandidate(text)) {
                        val range = start..cursor
                        val exact = source.substring(range)
                        val canonical = normalizeStructuralEntity(text)
                        val declaration = structuralDeclarationIdentity(text)
                        val identity = declaration?.first ?: "synthetic:$canonical"
                        val symbol = declaration?.second
                        result += StructuralEntity(
                            range,
                            canonical,
                            identity,
                            symbol,
                            structuralReferences(exact, symbol),
                            normalizeStructuralEntity(exact),
                            StructuralEntityKind.DECLARATION,
                        )
                    }
                }
                boundary = cursor + 1
            }
            '\r', '\n' -> if (depth == 0) {
                val lineStart = source.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
                if (
                    parenthesisDepth == 0 &&
                    source.substring(lineStart, cursor).trimStart().startsWith('#')
                ) {
                    boundary = cursor + 1
                }
            }
        }
        cursor++
    }
    return result
}

private fun isStructuralDeclarationCandidate(declaration: String): Boolean {
    val trimmed = declaration.trim()
    if (STRUCTURAL_STATEMENT_PREFIX.containsMatchIn(trimmed)) return false
    return !STRUCTURAL_EXPRESSION_PREFIX.containsMatchIn(trimmed)
}

private fun structuralDeclarationPrototype(declaration: String): Boolean {
    val firstCall = declaration.indexOf('(')
    return !declaration.startsWith("layout") && firstCall >= 0 &&
        '=' !in declaration.substring(0, firstCall) && FUNCTION_PROTOTYPE.matches(declaration)
}

private fun structuralFunctionIdentity(header: String): Pair<String, String>? {
    val match = FUNCTION_HEADER.matchEntire(header.trim()) ?: return null
    val name = match.groupValues[1]
    val parameters = splitStructuralParameters(match.groupValues[2]).map(::normalizeStructuralParameter)
    return "function:$name(${parameters.joinToString(",")})" to name
}

private fun relaxedFunctionIdentity(identity: String): String {
    if (!identity.startsWith("function:")) return identity
    return FUNCTION_PARAMETER_QUALIFIER.replace(identity, "")
}

private fun structuralDeclarationIdentity(declaration: String): Pair<String, String>? {
    val trimmed = declaration.trim()
    val prototype = FUNCTION_PROTOTYPE.matchEntire(trimmed).takeIf { structuralDeclarationPrototype(trimmed) }
    if (prototype != null) {
        val name = prototype.groupValues[1]
        val parameters = splitStructuralParameters(prototype.groupValues[2]).map(::normalizeStructuralParameter)
        return "prototype:$name(${parameters.joinToString(",")})" to name
    }
    val block = declaration.lastIndexOf('}')
    if (block >= 0) {
        val instance = DECLARATION_IDENTIFIER.find(declaration.substring(block + 1))?.value
        val blockName = DECLARATION_IDENTIFIER.findAll(declaration.substringBeforeLast('{')).lastOrNull()?.value
        val name = instance ?: BLOCK_NAME.find(declaration)?.groupValues?.get(1) ?: blockName ?: return null
        return "declaration:$name" to name
    }
    val head = STRUCTURAL_ARRAY_SUFFIX.replace(
        stripLeadingLayouts(declaration).substringBefore('=').substringBefore(';'),
        "",
    )
    val name = DECLARATION_IDENTIFIER.findAll(head).lastOrNull()?.value ?: return null
    val identity = if (GENERATED_IDENTIFIER.matches(name)) {
        "generated-declaration:${normalizeStructuralEntity(declaration)}"
    } else {
        "declaration:$name"
    }
    return identity to name
}

private fun stripLeadingLayouts(declaration: String): String {
    var result = declaration.trimStart()
    while (result.startsWith("layout")) {
        val open = result.indexOf('(')
        if (open < 0) break
        var depth = 0
        var close = -1
        for (index in open until result.length) {
            when (result[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        close = index
                        break
                    }
                }
            }
        }
        if (close < 0) break
        result = result.substring(close + 1).trimStart()
    }
    return result
}

private fun splitStructuralParameters(source: String): List<String> {
    if (source.trim().isEmpty() || source.trim() == "void") return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    source.forEachIndexed { index, character ->
        when (character) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) {
                result += source.substring(start, index)
                start = index + 1
            }
        }
    }
    result += source.substring(start)
    return result
}

private fun normalizeStructuralParameter(parameter: String): String {
    val normalized = normalizeStructuralEntity(parameter)
    val identifiers = DECLARATION_IDENTIFIER.findAll(normalized).toList()
    if (identifiers.size <= 1) return normalized
    val last = identifiers.last()
    return normalized.removeRange(last.range).replace("\\s+".toRegex(), " ").trim()
}

private fun structuralReferences(source: String, symbol: String?): Set<String> {
    return DECLARATION_IDENTIFIER.findAll(maskStructuralCode(source)).map(MatchResult::value)
        .filterNot { it == symbol || it in STRUCTURAL_KEYWORDS }
        .toCollection(linkedSetOf())
}

private fun firstNonWhitespace(source: String, start: Int, end: Int): Int {
    var cursor = start
    while (cursor < end && source[cursor].isWhitespace()) cursor++
    return cursor
}

private fun structuralBraceDepths(masked: String): IntArray {
    val result = IntArray(masked.length + 1)
    var depth = 0
    for (index in masked.indices) {
        result[index] = depth
        when (masked[index]) {
            '{' -> depth++
            '}' -> if (depth > 0) depth--
        }
    }
    result[masked.length] = depth
    return result
}

private fun IntRange.containsRange(other: IntRange): Boolean = first <= other.first && last >= other.last

private fun normalizeStructuralEntity(value: String): String = alphaNormalizeGeneratedIdentifiers(
    maskStructuralSource(value).replace("\\s+".toRegex(), " ").trim(),
)

private fun alphaNormalizeGeneratedIdentifiers(value: String): String {
    val names = linkedMapOf<String, String>()
    return GENERATED_IDENTIFIER.replace(value) { match ->
        names.getOrPut(match.value) { "_smg${names.size}" }
    }
}

private fun maskStructuralSource(source: String): String {
    val result = source.toCharArray()
    var blockComment = false
    var lineComment = false
    var quote: Char? = null
    var cursor = 0
    while (cursor < result.size) {
        val char = result[cursor]
        val next = result.getOrNull(cursor + 1)
        when {
            lineComment -> {
                if (char in "\r\n") lineComment = false else result[cursor] = ' '
                cursor++
            }
            blockComment -> {
                if (char == '*' && next == '/') {
                    result[cursor] = ' '
                    result[cursor + 1] = ' '
                    blockComment = false
                    cursor += 2
                } else {
                    if (char !in "\r\n") result[cursor] = ' '
                    cursor++
                }
            }
            quote != null -> {
                if (char == '\\' && next != null) {
                    result[cursor] = ' '
                    result[cursor + 1] = ' '
                    cursor += 2
                } else {
                    if (char == quote) quote = null
                    if (char !in "\r\n") result[cursor] = ' '
                    cursor++
                }
            }
            char == '/' && next == '/' -> {
                result[cursor] = ' '
                result[cursor + 1] = ' '
                lineComment = true
                cursor += 2
            }
            char == '/' && next == '*' -> {
                result[cursor] = ' '
                result[cursor + 1] = ' '
                blockComment = true
                cursor += 2
            }
            char == '"' || char == '\'' -> {
                result[cursor] = ' '
                quote = char
                cursor++
            }
            else -> cursor++
        }
    }
    return result.concatToString()
}

internal fun maskStructuralCode(source: String): String {
    val result = maskStructuralSource(source).toCharArray()
    var continuedDirective = false
    var offset = 0
    while (offset < result.size) {
        val newline = source.indexOfAny(charArrayOf('\r', '\n'), offset)
        val contentEnd = if (newline < 0) source.length else newline
        val lineEnd = when {
            newline < 0 -> source.length
            source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
            else -> newline + 1
        }
        val line = result.concatToString(offset, contentEnd)
        val directive = continuedDirective || line.trimStart().startsWith('#')
        if (directive) {
            continuedDirective = line.trimEnd().endsWith('\\')
            for (index in offset until contentEnd) result[index] = ' '
        } else {
            continuedDirective = false
        }
        offset = lineEnd
    }
    return result.concatToString()
}

private val FUNCTION_HEADER =
    "(?s).*?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*".toRegex()
private val FUNCTION_PROTOTYPE =
    "(?s)^[A-Za-z_][A-Za-z0-9_\\s\\[\\]]*\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*;$".toRegex()
private val FUNCTION_PARAMETER_QUALIFIER =
    "(?<=\\(|,)(?:(?:const|in|out|inout|highp|mediump|lowp)\\s+)+".toRegex()
private val STRUCTURAL_STATEMENT_PREFIX =
    "^(?:if|else|for|while|do|switch|case|default|return|break|continue|discard)\\b".toRegex()
private val STRUCTURAL_EXPRESSION_PREFIX =
    "^(?:\\+\\+|--|[A-Za-z_][A-Za-z0-9_]*\\s*(?:[<>!+\\-*/%&|^.]|\\+\\+|--))".toRegex()
private val BLOCK_NAME = "\\b(?:uniform|buffer)\\s+([A-Za-z_][A-Za-z0-9_]*)".toRegex()
private val DECLARATION_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
private val ASSIGNED_SYMBOL =
    "(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\\s*(?:[+\\-*/%&|^]?=(?!=)|\\+\\+|--)".toRegex()
private val GENERATED_IDENTIFIER = "(?<![A-Za-z0-9_])_[0-9]+(?![A-Za-z0-9_])".toRegex()
private val STRUCTURAL_ARRAY_SUFFIX = "\\[[^]]*]".toRegex()
private val RASTER_PIPELINE_STAGES = setOf(
    ShaderStage.VERTEX,
    ShaderStage.TESSELLATION_CONTROL,
    ShaderStage.TESSELLATION_EVALUATION,
    ShaderStage.GEOMETRY,
    ShaderStage.FRAGMENT,
)
private val STAGE_INTERFACE_DECLARATION = "\\b(?:in|out|attribute|varying)\\b".toRegex()
private val EXPLICIT_INTERFACE_LOCATION = "\\blayout\\s*\\([^)]*\\blocation\\s*=".toRegex()
private val STAGE_INTERFACE_QUALIFIER_SYMBOLS = setOf("in", "out", "attribute", "varying")
private val STRUCTURAL_KEYWORDS = setOf(
    "const", "layout", "uniform", "buffer", "in", "out", "inout", "void", "true", "false",
    "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue",
    "struct", "shared", "readonly", "writeonly", "coherent", "volatile", "restrict", "precision",
    "highp", "mediump", "lowp", "flat", "smooth", "noperspective", "centroid", "sample", "patch",
)
