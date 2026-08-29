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

    fun completeRestoredSettings(
        source: String,
        restoredSettings: List<ShaderSetting>,
        candidates: List<ShaderSetting>,
    ): SpirvSettingBridgeRestoration {
        val required = (restoredSettings + candidates.filter { setting ->
            identifierRegex(setting.compilerName).containsMatchIn(source)
        }).distinctBy { it.name }.sortedBy { it.name }
        val missing = mutableListOf<ShaderSetting>()
        required.forEach { setting ->
            when (occurrences(source, renderBridge(setting)).size) {
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
    val optimizedEntities: Int,
    val restoredEntities: Int,
    val restoredBytes: Int,
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
                structuralPlan.restorationPlan,
                restoredCores.flatMapTo(linkedSetOf()) { it.strippedSymbols },
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
        return optimizedOrPreserved(
            request,
            restored,
            convergence.optimizedEntities,
            convergence.restoredEntities,
            convergence.restoredBytes,
        )
    }

    private fun optimizedOrPreserved(
        request: SpirvOptimizationRequest,
        source: String,
        optimizedEntities: Int = structuralEntities(source).size,
        restoredEntities: Int = 0,
        restoredBytes: Int = 0,
    ): SpirvFinalEmission {
        val artifact = FINAL_SPECIALIZATION_ARTIFACT.find(source)?.value
        return if (artifact == null) {
            SpirvFinalEmission(
                source.trimEnd() + "\n",
                SpirvEmissionMode.OPTIMIZED,
                null,
                optimizedEntities,
                restoredEntities,
                restoredBytes,
            )
        } else {
            preserved(request, "final optimized GLSL still contains specialization artifact '$artifact'")
        }
    }

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
        restorationPlan: ShaderStructuralRestorationPlan,
        requiredSourceSymbols: Set<String>,
    ): StructuralConvergence {
        val existingSlots = restorationPlan.islands
        val existingIdentities = existingSlots.flatMapTo(linkedSetOf()) { slot ->
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
        val divergent = identities.filterTo(linkedSetOf()) { identity ->
            parsedModules.map { entities -> entities[identity]?.map(StructuralEntity::semantic) }.distinct().size != 1
        }
        val sourceEntities = structuralEntities(request.source)
        val sourceByIdentity = sourceEntities.groupBy(StructuralEntity::identity)
        val unmappable = divergent.filter { sourceByIdentity[it].isNullOrEmpty() }
        if (unmappable.isNotEmpty()) {
            val diagnostics = unmappable.joinToString(", ") { identity ->
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
        sourceEntities.filterTo(mutableListOf()) { entity ->
            entity.symbol in requiredSourceSymbols && entity.identity !in existingIdentities
        }.mapTo(promoted, StructuralEntity::identity)
        val optimizedIdentities = parsedModules.flatMapTo(hashSetOf()) { it.keys }
        var changed: Boolean
        do {
            changed = false
            val promotedSymbols = promoted.flatMapTo(hashSetOf()) { identity ->
                sourceByIdentity[identity].orEmpty().mapNotNull(StructuralEntity::symbol)
            }
            sourceEntities.forEach { entity ->
                if (
                    entity.identity !in promoted && entity.identity in optimizedIdentities &&
                    entity.references.any(promotedSymbols::contains)
                ) {
                    promoted += entity.identity
                    changed = true
                }
            }
        } while (changed)

        val convergedSources = withoutKnownSlots.map { source -> removeStructuralEntities(source, promoted) }
        val bodies = convergedSources.map(::normalizeSemanticBody)
        if (bodies.distinct().size != 1) {
            val diagnostics = moduleNames.zip(bodies).joinToString(", ") { (name, body) ->
                "$name=${shortHash(body)}"
            }
            return StructuralConvergence.Preserved(
                "${request.sourceName}: optimized whole-entity convergence did not close: $diagnostics",
            )
        }
        val commonCore = convergedSources.first().trimEnd() + "\n"
        val optimized = structuralEntities(commonCore)
        if (optimized.none { it.kind == StructuralEntityKind.FUNCTION }) {
            return StructuralConvergence.Preserved(
                "${request.sourceName}: whole-entity restoration would leave no optimized executable entity",
            )
        }

        val dynamicEntities = promoted.flatMap { sourceByIdentity.getValue(it) }
        val macroSlots = sourceMacroDependencySlots(request.source, commonCore, dynamicEntities)
        val allSlots = reanchorRestorationSlots(
            request,
            existingSlots + macroSlots + dynamicEntities.mapIndexed { index, entity ->
                ShaderStructuralEntitySlot(
                    ordinal = existingSlots.size + macroSlots.size + index,
                    kind = if (entity.kind == StructuralEntityKind.FUNCTION) {
                        ShaderStructuralEntitySlotKind.FUNCTION
                    } else {
                        ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION
                    },
                    canonicalEntity = entity.canonical,
                    exactText = request.source.substring(entity.range),
                    sourceLine = sourceLine(request.source, entity.range.first),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            },
        ) ?: return StructuralConvergence.Preserved(
            "${request.sourceName}: promoted entities have no stable restoration anchor",
        )
        val restoredIdentities = allSlots.flatMapTo(linkedSetOf()) { slot ->
            structuralEntities(slot.exactText).map(StructuralEntity::identity)
        }
        return StructuralConvergence.Converged(
            source = commonCore,
            restorationPlan = restorationPlan.copy(islands = allSlots, issue = null),
            optimizedEntities = optimized.size,
            restoredEntities = restoredIdentities.size + allSlots.count {
                structuralEntities(it.exactText).isEmpty()
            },
            restoredBytes = allSlots.distinctBy(ShaderStructuralEntitySlot::exactText)
                .sumOf { it.exactText.encodeToByteArray().size },
        )
    }

    private fun sourceMacroDependencySlots(
        source: String,
        commonCore: String,
        entities: List<StructuralEntity>,
    ): List<ShaderStructuralEntitySlot> {
        val definitions = sourceMacroDefinitions(source)
        val required = entities.flatMapTo(linkedSetOf()) { entity ->
            DECLARATION_IDENTIFIER.findAll(source.substring(entity.range)).map(MatchResult::value)
                .filter { it in definitions && restorableSourceMacro(it) }
        }
        val selected = linkedMapOf<String, SourceMacroDefinition>()
        val pending = ArrayDeque(required)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in selected) continue
            val candidates = definitions[name].orEmpty()
            val minimumDepth = candidates.minOfOrNull(SourceMacroDefinition::depth) ?: continue
            val definition = candidates.filter { it.depth == minimumDepth }.singleOrNull() ?: continue
            selected[name] = definition
            DECLARATION_IDENTIFIER.findAll(definition.value).map(MatchResult::value)
                .filter { it in definitions && it !in selected && restorableSourceMacro(it) }
                .forEach(pending::addLast)
        }
        return selected.values.filterNot { commonCore.contains(it.exactText) }
            .sortedBy(SourceMacroDefinition::offset)
            .mapIndexed { index, definition ->
                ShaderStructuralEntitySlot(
                    ordinal = index,
                    kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    canonicalEntity = null,
                    exactText = definition.exactText,
                    sourceLine = sourceLine(source, definition.offset),
                    beforeAnchor = null,
                    afterAnchor = null,
                    placement = IrisAnchorPlacement.AFTER_BEFORE,
                )
            }
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
            val exactText = source.substring(offset, lineEnd)
            val directive = line.trimStart()
            if (directive.startsWith("#endif")) depth = (depth - 1).coerceAtLeast(0)
            MACRO_DEFINITION.matchEntire(line)?.destructured?.let { (name, value) ->
                result.getOrPut(name) { mutableListOf() } += SourceMacroDefinition(
                    name,
                    value,
                    exactText,
                    offset,
                    depth,
                )
            }
            if (
                directive.startsWith("#if ") || directive.startsWith("#if\t") ||
                directive.startsWith("#ifdef") || directive.startsWith("#ifndef")
            ) {
                depth++
            }
            offset = lineEnd
        }
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
            if (occurrences.size != 1) return null
            slot to occurrences.single()
        }
        val ranges = located.map { it.second }
        val anchors = findStableAnchors(request.source).filter { anchor ->
            anchor.anchor.kind != IrisAnchorKind.DECLARATION && ranges.none { it.overlaps(anchor.range) }
        }
        return located.sortedBy { it.second.first }.mapIndexed { ordinal, (slot, range) ->
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
        val residue = removeRanges(source, entities.map(StructuralEntity::range))
        return normalizeSemanticBody(residue)
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

    private fun sourceLine(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1

    private fun normalizeSemanticBody(source: String): String {
        return source.replace(VERSION_LINE, "#version").replace(SEMANTIC_WHITESPACE, " ").trim()
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

private data class StructuralEntity(
    val range: IntRange,
    val canonical: String,
    val identity: String,
    val symbol: String?,
    val references: Set<String>,
    val semantic: String,
    val kind: StructuralEntityKind,
)

private enum class StructuralEntityKind {
    DECLARATION,
    FUNCTION,
}

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
                    if (text.isNotEmpty()) {
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
                if (source.substring(lineStart, cursor).trimStart().startsWith('#')) boundary = cursor + 1
            }
        }
        cursor++
    }
    return result
}

private fun structuralFunctionIdentity(header: String): Pair<String, String>? {
    val match = FUNCTION_HEADER.matchEntire(header.trim()) ?: return null
    val name = match.groupValues[1]
    val parameters = splitStructuralParameters(match.groupValues[2]).map(::normalizeStructuralParameter)
    return "function:$name(${parameters.joinToString(",")})" to name
}

private fun structuralDeclarationIdentity(declaration: String): Pair<String, String>? {
    val trimmed = declaration.trim()
    val firstCall = trimmed.indexOf('(')
    val prototype = if (
        !trimmed.startsWith("layout") &&
        firstCall >= 0 &&
        '=' !in trimmed.substring(0, firstCall)
    ) {
        FUNCTION_PROTOTYPE.matchEntire(trimmed)
    } else {
        null
    }
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
    val head = stripLeadingLayouts(declaration).substringBefore('=').substringBefore(';')
    val name = DECLARATION_IDENTIFIER.findAll(head).lastOrNull()?.value ?: return null
    return "declaration:$name" to name
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
    return DECLARATION_IDENTIFIER.findAll(maskStructuralSource(source)).map(MatchResult::value)
        .filterNot { it == symbol || it in STRUCTURAL_KEYWORDS }
        .toCollection(linkedSetOf())
}

private fun firstNonWhitespace(source: String, start: Int, end: Int): Int {
    var cursor = start
    while (cursor < end && source[cursor].isWhitespace()) cursor++
    return cursor
}

private fun normalizeStructuralEntity(value: String): String =
    maskStructuralSource(value).replace("\\s+".toRegex(), " ").trim()

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

private val FUNCTION_HEADER =
    "(?s).*?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*".toRegex()
private val FUNCTION_PROTOTYPE =
    "(?s)^[A-Za-z_][A-Za-z0-9_\\s\\[\\]]*\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*;$".toRegex()
private val BLOCK_NAME = "\\b(?:uniform|buffer)\\s+([A-Za-z_][A-Za-z0-9_]*)".toRegex()
private val DECLARATION_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
private val STRUCTURAL_KEYWORDS = setOf(
    "const", "layout", "uniform", "buffer", "in", "out", "inout", "void", "true", "false",
    "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue",
    "struct", "shared", "readonly", "writeonly", "coherent", "volatile", "restrict", "precision",
    "highp", "mediump", "lowp", "flat", "smooth", "noperspective", "centroid", "sample", "patch",
)
