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
        val restoredSettings = mutableListOf<ShaderSetting>()
        settings.sortedBy { it.name }.forEach { setting ->
            val token = "SPIRV_CROSS_CONSTANT_ID_${setting.specializationId}"
            val declaration = crossSettingDeclaration(setting, token).findAll(result).toList()
            if (declaration.size > 1) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross emitted duplicate declarations for ${setting.compilerName}",
                )
            }
            val used = blocks.any { it.groupValues[1].toInt() == setting.specializationId } ||
                declaration.isNotEmpty() || identifierRegex(token).containsMatchIn(result)
            if (!used) return@forEach
            result = removeRanges(result, declaration.map { it.range })
            result = identifierRegex(token).replace(result, setting.compilerName)
            if (crossSettingDeclaration(setting, setting.compilerName).containsMatchIn(result)) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "SPIRV-Cross specialization declaration for ${setting.compilerName} has an unsupported shape",
                )
            }
            restoredSettings += setting
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
        var result = source
        settings.sortedByDescending { it.name.length }.forEach { setting ->
            val bridge = renderBridge(setting)
            val first = result.indexOf(bridge)
            require(first >= 0) { "setting bridge ${setting.compilerName} is missing from final GLSL" }
            require(result.indexOf(bridge, first + bridge.length) < 0) {
                "setting bridge ${setting.compilerName} is ambiguous in final GLSL"
            }
            result = result.removeRange(first, first + bridge.length)
        }
        val declarations = buildString {
            settings.sortedBy { it.name }.forEach { setting ->
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

    fun placeAfterDefinitions(
        source: String,
        settings: List<ShaderSetting>,
        contracts: List<IrisSourceContractSlice>,
    ): SpirvSettingBridgeRestoration {
        if (settings.isEmpty()) return SpirvSettingBridgeRestoration.Restored(source, emptyList())
        val bridges = settings.sortedBy { it.name }.associateWith(::renderBridge)
        val bridgeRanges = mutableListOf<IntRange>()
        bridges.forEach { (setting, bridge) ->
            val occurrences = occurrences(source, bridge)
            if (occurrences.size != 1) {
                return SpirvSettingBridgeRestoration.Preserved(
                    "setting bridge ${setting.compilerName} is ${if (occurrences.isEmpty()) "missing" else "ambiguous"}",
                )
            }
            bridgeRanges += occurrences.single()
        }
        var result = removeRanges(source, bridgeRanges)
        val relevantContracts = settings.associateWith { setting ->
            contracts.filter { contract -> setting.sourceSlices.any(contract.exactText::contains) }
        }
        relevantContracts.entries.firstOrNull { it.value.isEmpty() }?.let { (setting) ->
            return SpirvSettingBridgeRestoration.Preserved(
                "setting definition contract ${setting.name} is missing from final GLSL",
            )
        }
        val definitionEnds = relevantContracts.values.flatten().distinct().flatMap { contract ->
            occurrences(result, contract.exactText).map { it.last + 1 }
        }
        if (definitionEnds.isEmpty()) {
            return SpirvSettingBridgeRestoration.Preserved("setting definition contracts cannot be located in final GLSL")
        }
        var offset = definitionEnds.max()
        while (offset < result.length && result[offset] in "\r\n") offset++
        val insertion = bridges.values.joinToString("")
        val prefix = if (offset > 0 && result[offset - 1] !in "\r\n") "\n" else ""
        val suffix = if (offset < result.length && result[offset] !in "\r\n") "\n" else ""
        result = result.substring(0, offset) + prefix + insertion + suffix + result.substring(offset)
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

    private fun crossSettingDeclaration(setting: ShaderSetting, value: String): Regex {
        return Regex(
            "(?m)^[\\t ]*const[\\t ]+${Regex.escape(setting.type.glslName)}[\\t ]+" +
                "${Regex.escape(setting.compilerName)}[\\t ]*=[\\t ]*${Regex.escape(value)}[\\t ]*;" +
                "[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)",
        )
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

    private fun removeRanges(source: String, ranges: List<IntRange>): String {
        return ranges.distinct().sortedByDescending { it.first }.fold(source) { value, range ->
            value.removeRange(range.first, range.last + 1)
        }
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

    private fun identifierRegex(name: String): Regex {
        return "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
    }

    private val CROSS_BLOCK = Regex(
        "(?m)^[\\t ]*#ifndef[\\t ]+SPIRV_CROSS_CONSTANT_ID_([0-9]+)[\\t ]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#define[\\t ]+SPIRV_CROSS_CONSTANT_ID_([0-9]+)[\\t ]+[^\\r\\n]*(?:\\r\\n|\\n|\\r)" +
            "^[\\t ]*#endif[\\t ]*(?:\\r\\n|\\n|\\r|$)",
    )
    private val CROSS_TOKEN = "\\bSPIRV_CROSS_CONSTANT_ID_[0-9]+\\b".toRegex()
    private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
}

internal data class SpirvFinalEmission(
    val source: String,
    val mode: SpirvEmissionMode,
    val fallbackReason: String?,
)

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
            return optimizedOrPreserved(request, modules.single().source)
        }
        structuralPlan.restorationPlan.issue?.let { return preserved(request, it) }
        if (modules.any { it.structuralSignature == null }) {
            return preserved(request, "structural compiler module metadata is incomplete")
        }

        val signatures = modules.map { requireNotNull(it.structuralSignature) }
        val varying = VaryingStructuralSlots.from(signatures)
        val stripped = modules.map { module -> stripStructuralSlots(module, varying) }
        stripped.filterIsInstance<StructuralStripResult.Preserved>().firstOrNull()?.let {
            return preserved(request, it.reason)
        }
        val restoredCores = stripped.filterIsInstance<StructuralStripResult.Restored>()
        val bodies = restoredCores.map { normalizeSemanticBody(it.source) }
        if (bodies.distinct().size != 1) {
            val diagnostics = modules.zip(bodies).joinToString(", ") { (module, body) ->
                "${module.name}=${shortHash(body)}"
            }
            return preserved(request, "optimized structural semantic bodies diverged: $diagnostics")
        }
        val structural = when (
            val restoration = structuralPlan.restorationPlan.restore(restoredCores.first().source)
        ) {
            is ShaderStructuralRestoration.Restored -> restoration.source
            is ShaderStructuralRestoration.Preserved -> return preserved(request, restoration.reason)
        }
        val restorationContracts = modules.first().irisContracts.withRestorationContracts(
            structuralPlan.restorationPlan.restorationContracts,
        )
        val contractSource = when (val contracts = restorationContracts.restore(structural)) {
            is IrisContractRestoration.Restored -> contracts.source
            is IrisContractRestoration.StructuralPreservation -> return preserved(request, contracts.reason)
        }
        val restored = when (
            val bridges = SpirvSettingBridge.placeAfterDefinitions(
                contractSource,
                modules.first().bridgeSettings,
                restorationContracts.contracts,
            )
        ) {
            is SpirvSettingBridgeRestoration.Restored -> bridges.source
            is SpirvSettingBridgeRestoration.Preserved -> return preserved(request, bridges.reason)
        }
        return optimizedOrPreserved(request, restored)
    }

    private fun optimizedOrPreserved(
        request: SpirvOptimizationRequest,
        source: String,
    ): SpirvFinalEmission {
        val artifact = FINAL_SPECIALIZATION_ARTIFACT.find(source)?.value
        return if (artifact == null) {
            SpirvFinalEmission(source.trimEnd() + "\n", SpirvEmissionMode.OPTIMIZED, null)
        } else {
            preserved(request, "final optimized GLSL still contains specialization artifact '$artifact'")
        }
    }

    private fun preserved(request: SpirvOptimizationRequest, reason: String): SpirvFinalEmission {
        return SpirvFinalEmission(
            request.source,
            SpirvEmissionMode.PRESERVED_SOURCE,
            "${request.sourceName}: $reason",
        )
    }

    private fun stripStructuralSlots(
        module: SpirvModuleResult,
        varying: VaryingStructuralSlots,
    ): StructuralStripResult {
        val signature = requireNotNull(module.structuralSignature)
        val expectedResources = signature.resources.filterTo(linkedSetOf()) { it in varying.resources }
        val expectedInterfaces = signature.stageInterfaces.filterTo(linkedSetOf()) { it in varying.interfaces }
        val expectedFunctions = signature.functionAbi.filterTo(linkedSetOf()) { it in varying.functionAbi }
        val matchedResources = linkedSetOf<String>()
        val matchedInterfaces = linkedSetOf<String>()
        val matchedFunctions = linkedSetOf<String>()
        val removals = mutableListOf<IntRange>()
        structuralEntities(module.coreSource).forEach { entity ->
            when {
                entity.canonical in expectedResources -> {
                    matchedResources += entity.canonical
                    removals += entity.range
                }
                entity.canonical in expectedInterfaces -> {
                    matchedInterfaces += entity.canonical
                    removals += entity.range
                }
                entity.canonical in expectedFunctions -> {
                    matchedFunctions += entity.canonical
                    removals += entity.range
                }
            }
        }
        val missing = (expectedResources - matchedResources) +
            (expectedInterfaces - matchedInterfaces) +
            (expectedFunctions - matchedFunctions)
        if (missing.isNotEmpty()) {
            return StructuralStripResult.Preserved(
                "${module.name}: optimized structural slots cannot be located: ${missing.sorted()}",
            )
        }
        var source = module.coreSource
        removals.distinct().sortedByDescending { it.first }.forEach { range ->
            source = source.removeRange(range.first, range.last + 1)
        }
        return StructuralStripResult.Restored(source.trimEnd() + "\n")
    }

    private fun normalizeSemanticBody(source: String): String {
        return source.replace(VERSION_LINE, "#version").replace(SEMANTIC_WHITESPACE, " ").trim()
    }

    private sealed interface StructuralStripResult {
        data class Restored(val source: String) : StructuralStripResult
        data class Preserved(val reason: String) : StructuralStripResult
    }

    private data class VaryingStructuralSlots(
        val resources: Set<String>,
        val interfaces: Set<String>,
        val functionAbi: Set<String>,
    ) {
        companion object {
            fun from(signatures: List<ShaderStructuralSignature>): VaryingStructuralSlots {
                fun varying(values: List<List<String>>): Set<String> {
                    val union = values.flatten().toSet()
                    val common = values.drop(1).fold(values.first().toSet()) { result, value -> result intersect value.toSet() }
                    return union - common
                }
                return VaryingStructuralSlots(
                    varying(signatures.map { it.resources }),
                    varying(signatures.map { it.stageInterfaces }),
                    varying(signatures.map { it.functionAbi }),
                )
            }
        }
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val FINAL_SPECIALIZATION_ARTIFACT =
        "\\b(?:constant_id|local_size_[xyz]_id|SPIRV_CROSS_CONSTANT_ID_[0-9]+)\\b".toRegex()
    private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
    private val SEMANTIC_WHITESPACE = "\\s+".toRegex()
}

private data class StructuralEntity(
    val range: IntRange,
    val canonical: String,
)

private fun structuralEntities(source: String): List<StructuralEntity> {
    val masked = maskStructuralSource(source)
    val result = mutableListOf<StructuralEntity>()
    var boundary = 0
    var depth = 0
    var functionStart = -1
    var functionHeader = ""
    var cursor = 0
    while (cursor < masked.length) {
        when (masked[cursor]) {
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
                    result += StructuralEntity(functionStart..cursor, functionHeader)
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
                    if (text.isNotEmpty()) {
                        result += StructuralEntity(start..cursor, normalizeStructuralEntity(text))
                    }
                }
                boundary = cursor + 1
            }
            '\r', '\n' -> if (depth == 0) {
                val lineStart = source.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
                if (source.substring(lineStart, cursor).trimStart().startsWith('#')) boundary = cursor + 1
            }
        }
        cursor++
    }
    return result
}

private fun firstNonWhitespace(source: String, start: Int, end: Int): Int {
    var cursor = start
    while (cursor < end && source[cursor].isWhitespace()) cursor++
    return cursor
}

private fun normalizeStructuralEntity(value: String): String = value.replace("\\s+".toRegex(), " ").trim()

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
