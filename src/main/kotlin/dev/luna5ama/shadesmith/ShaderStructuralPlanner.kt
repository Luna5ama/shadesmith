package dev.luna5ama.shadesmith

import java.security.MessageDigest

internal enum class ShaderStructuralNodeKind {
    CONDITIONAL,
    RESOURCE,
    STAGE_INTERFACE,
    FUNCTION_ABI,
    TOKEN_PASTE,
    CAPABILITY,
    LOCAL_SIZE,
}

internal data class ShaderStructuralNode(
    val id: String,
    val kind: ShaderStructuralNodeKind,
    val sourceLine: Int,
    val settings: Set<String>,
    val symbols: Set<String>,
    val detail: String,
)

internal data class ShaderStructuralDependencyComponent(
    val id: Int,
    val settings: List<String>,
    val nodes: List<String>,
    val domains: Map<String, List<String>>,
)

internal data class ShaderStructuralDependencyGraph(
    val nodes: List<ShaderStructuralNode>,
    val components: List<ShaderStructuralDependencyComponent>,
) {
    val structuralSettings: Set<String> = components.flatMapTo(sortedSetOf()) { it.settings }

    fun diagnostic(): String = buildString {
        appendLine("components:")
        components.forEach { component ->
            append("  component-")
            append(component.id)
            append(" settings=")
            append(component.settings)
            append(" domains=")
            append(component.domains.entries.joinToString(prefix = "{", postfix = "}") { (name, values) ->
                val domain = values.joinToString(prefix = "[", postfix = "]")
                "$name=$domain"
            })
            append(" nodes=")
            appendLine(component.nodes.toString())
        }
        appendLine("nodes:")
        nodes.forEach { node ->
            append("  ")
            append(node.id)
            append(" kind=")
            append(node.kind)
            append(" line=")
            append(node.sourceLine)
            append(" settings=")
            append(node.settings)
            append(" symbols=")
            append(node.symbols)
            append(" detail=")
            appendLine(node.detail)
        }
    }.trimEnd()
}

internal data class ShaderStructuralSignature(
    val stage: ShaderStage,
    val requiredCapabilities: List<String>,
    val localSizeFallback: LocalSizeAbiSignature?,
    val resources: List<String>,
    val stageInterfaces: List<String>,
    val functionAbi: List<String>,
) {
    val canonical: String = buildString {
        appendLine("stage=${stage.glslangName}")
        appendLine("capabilities=${requiredCapabilities.joinToString(",")}")
        appendLine("local_size=${localSizeFallback ?: "none"}")
        appendLine("resources=${resources.joinToString("|")}")
        appendLine("interfaces=${stageInterfaces.joinToString("|")}")
        append("function_abi=${functionAbi.joinToString("|")}")
    }
}

internal data class ShaderVaryingStructuralSlots(
    val resources: Set<String>,
    val interfaces: Set<String>,
    val functionAbi: Set<String>,
) {
    companion object {
        fun from(signatures: List<ShaderStructuralSignature>): ShaderVaryingStructuralSlots {
            fun varying(values: List<List<String>>): Set<String> {
                val union = values.flatten().toSet()
                val common = values.drop(1).fold(values.first().toSet()) { result, value ->
                    result intersect value.toSet()
                }
                return union - common
            }
            return ShaderVaryingStructuralSlots(
                varying(signatures.map { it.resources }),
                varying(signatures.map { it.stageInterfaces }),
                varying(signatures.map { it.functionAbi }),
            )
        }
    }
}

internal enum class ShaderStructuralEntitySlotKind {
    TOP_LEVEL_REGION,
    FUNCTION,
}

internal data class ShaderStructuralEntitySlot(
    val ordinal: Int,
    val kind: ShaderStructuralEntitySlotKind,
    val canonicalEntity: String?,
    val exactText: String,
    val sourceLine: Int,
    val beforeAnchor: IrisSourceAnchor?,
    val afterAnchor: IrisSourceAnchor?,
    val placement: IrisAnchorPlacement,
)

internal sealed interface ShaderStructuralRestoration {
    data class Restored(val source: String) : ShaderStructuralRestoration
    data class Preserved(val reason: String) : ShaderStructuralRestoration
}

internal data class ShaderStructuralRestorationPlan(
    val sourceName: String,
    val settings: List<ShaderSetting>,
    val structuralSettings: Set<String>,
    val islands: List<ShaderStructuralEntitySlot>,
    val restorationContracts: List<IrisSourceContractSlice>,
    val issue: String?,
) {
    fun restore(source: String): ShaderStructuralRestoration {
        issue?.let { return ShaderStructuralRestoration.Preserved(it) }
        val anchors = findStableAnchors(source)
        data class PendingInsertion(val offset: Int, val island: ShaderStructuralEntitySlot)
        val pending = mutableListOf<PendingInsertion>()
        islands.forEach { island ->
            val before = island.beforeAnchor?.let { anchor ->
                val matches = anchors.filter { it.anchor == anchor }
                if (matches.size == 1) {
                    matches.single()
                } else if (matches.isNotEmpty() && anchor == MAIN_ANCHOR) {
                    LocatedAnchor(anchor, matches.minOf { it.range.first }..matches.maxOf { it.range.last })
                } else {
                    return ShaderStructuralRestoration.Preserved(
                        anchorFailure(island, anchor, matches.size),
                    )
                }
            }
            val after = island.afterAnchor?.let { anchor ->
                val matches = anchors.filter { it.anchor == anchor }
                if (matches.size == 1) {
                    matches.single()
                } else if (matches.isNotEmpty() && anchor == MAIN_ANCHOR) {
                    LocatedAnchor(anchor, matches.minOf { it.range.first }..matches.maxOf { it.range.last })
                } else {
                    return ShaderStructuralRestoration.Preserved(
                        anchorFailure(island, anchor, matches.size),
                    )
                }
            }
            if (before == null && after == null) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${island.sourceLine}: structural island has no stable restoration anchor",
                )
            }
            if (before != null && after != null && before.range.last >= after.range.first) {
                return ShaderStructuralRestoration.Preserved(
                    "$sourceName:${island.sourceLine}: structural island anchors changed relative order",
                )
            }
            val offset = when (island.placement) {
                IrisAnchorPlacement.AFTER_BEFORE -> requireNotNull(before).range.last + 1
                IrisAnchorPlacement.BEFORE_AFTER -> requireNotNull(after).range.first
            }
            pending += PendingInsertion(offset, island)
        }

        var result = source
        pending.groupBy { it.offset }.entries.sortedByDescending { it.key }.forEach { (offset, insertions) ->
            val exact = buildString {
                insertions.sortedBy { it.island.ordinal }.forEach { insertion ->
                    val text = insertion.island.exactText
                    if (isNotEmpty() && last() !in "\r\n" && text.firstOrNull() !in listOf('\r', '\n')) {
                        append('\n')
                    }
                    append(text)
                }
            }
            val prefix = if (offset > 0 && result[offset - 1] !in "\r\n" && exact.firstOrNull() !in listOf('\r', '\n')) "\n" else ""
            val suffix = if (offset < result.length && result[offset] !in "\r\n" && exact.lastOrNull() !in listOf('\r', '\n')) "\n" else ""
            result = result.substring(0, offset) + prefix + exact + suffix + result.substring(offset)
        }
        return ShaderStructuralRestoration.Restored(result.trimEnd() + "\n")
    }

    fun materializeFinalSource(source: String, assignment: Map<String, String>): String {
        val model = StructuralSourceModel(source, sourceName, settings)
        val rendered = model.renderStructuralSource(assignment, structuralSettings)
        return model.freezeSettingsForCompiler(rendered, assignment, structuralSettings)
    }

    fun structuralOwnerRange(source: String, range: IntRange): IntRange? {
        return structuralOwnerRanges(source, listOf(range)).single()
    }

    fun structuralOwnerRanges(source: String, ranges: List<IntRange>): List<IntRange?> {
        val model = StructuralSourceModel(source, sourceName, settings)
        return ranges.map(model::conditionalOwnerRange)
    }

    private fun anchorFailure(island: ShaderStructuralEntitySlot, anchor: IrisSourceAnchor, matches: Int): String {
        val state = if (matches == 0) "missing" else "ambiguous ($matches matches)"
        return "$sourceName:${island.sourceLine}: structural island ${anchor.kind}:${anchor.name} anchor is $state"
    }

    companion object {
        private val MAIN_ANCHOR = IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")

        fun create(
            basePlan: ShaderCompilerCopyPlan,
            graph: ShaderStructuralDependencyGraph,
        ): ShaderStructuralRestorationPlan {
            val source = basePlan.originalSource
            val model = StructuralSourceModel(source, basePlan.sourceName, basePlan.settings)
            val contractLocations = basePlan.irisContracts.contracts.map { contract ->
                contract to contract.sourceRange
            }
            val contractRanges = contractLocations.map { it.second }
            val structuralConditionalIds = basePlan.conditionals.filter {
                it.disposition == ShaderConditionalDisposition.STRUCTURAL
            }.mapTo(hashSetOf()) { it.id }
            val candidates = model.structuralEntitySlots(graph.structuralSettings, structuralConditionalIds)
            val partialOverlap = candidates.firstOrNull { candidate ->
                contractRanges.any { contract ->
                    candidate.range.overlaps(contract) &&
                        !contract.containsRange(candidate.range) && !candidate.range.containsRange(contract)
                }
            }
            if (partialOverlap != null) {
                return ShaderStructuralRestorationPlan(
                    basePlan.sourceName,
                    basePlan.settings,
                    graph.structuralSettings,
                    emptyList(),
                    basePlan.irisContracts.contracts,
                    "${basePlan.sourceName}:${model.lineAt(partialOverlap.range.first)}: structural entity slot partially overlaps an Iris contract",
                )
            }
            val uncovered = candidates.filterNot { candidate ->
                contractRanges.any { it.containsRange(candidate.range) }
            }
            val mergedTopLevel = mergeStructuralRanges(
                source,
                uncovered.filter { it.kind == ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION }.map { it.range },
            ).map { range ->
                StructuralEntitySlotCandidate(range, ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION, null)
            }
            val functions = uncovered.filter { candidate ->
                candidate.kind == ShaderStructuralEntitySlotKind.FUNCTION &&
                    mergedTopLevel.none { it.range.containsRange(candidate.range) }
            }.distinctBy { it.range }
            val merged = (functions + mergedTopLevel).sortedBy { it.range.first }
            val nested = merged.firstOrNull {
                it.kind == ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION && model.braceDepthAt(it.range.first) != 0
            }
            if (nested != null) {
                return ShaderStructuralRestorationPlan(
                    basePlan.sourceName,
                    basePlan.settings,
                    graph.structuralSettings,
                    emptyList(),
                    basePlan.irisContracts.contracts,
                    "${basePlan.sourceName}:${model.lineAt(nested.range.first)}: structural entity slot is nested inside executable code",
                )
            }
            val mergedRanges = merged.map { it.range }
            val excluded = mergedRanges + contractRanges
            val anchors = findStableAnchors(source).filter { anchor ->
                anchor.anchor.kind != IrisAnchorKind.DECLARATION && excluded.none { it.overlaps(anchor.range) }
            }
            var issue: String? = null
            val islands = merged.mapIndexed { ordinal, slot ->
                val range = slot.range
                val before = anchors.filter { it.range.last < range.first }.maxByOrNull { it.range.last }
                val after = anchors.filter { it.range.first > range.last }.minByOrNull { it.range.first }
                if (before == null && after == null) {
                    issue = "${basePlan.sourceName}:${model.lineAt(range.first)}: structural island has no stable source anchor"
                }
                val placement = when {
                    after == null -> IrisAnchorPlacement.AFTER_BEFORE
                    before == null -> IrisAnchorPlacement.BEFORE_AFTER
                    range.first - before.range.last <= after.range.first - range.last -> IrisAnchorPlacement.AFTER_BEFORE
                    else -> IrisAnchorPlacement.BEFORE_AFTER
                }
                ShaderStructuralEntitySlot(
                    ordinal,
                    slot.kind,
                    slot.canonicalEntity,
                    source.substring(range),
                    model.lineAt(range.first),
                    before?.anchor,
                    after?.anchor,
                    placement,
                )
            }
            val reanchored = reanchorContracts(basePlan, source, mergedRanges, contractLocations)
            if (issue == null) issue = reanchored.issue
            return ShaderStructuralRestorationPlan(
                basePlan.sourceName,
                basePlan.settings,
                graph.structuralSettings,
                islands,
                reanchored.contracts,
                issue,
            )
        }
    }
}

internal data class ShaderStructuralCoverageRow(
    val name: String,
    val assignment: Map<String, String>,
    val changedComponents: List<Int>,
    val requiredCapabilities: List<String>,
    val localSizeFallback: LocalSizeAbiSignature?,
    val compilerPlan: ShaderCompilerCopyPlan,
)

internal data class ShaderStructuralCoveragePlan(
    val sourceName: String,
    val stage: ShaderStage,
    val graph: ShaderStructuralDependencyGraph,
    val rows: List<ShaderStructuralCoverageRow>,
    val restorationPlan: ShaderStructuralRestorationPlan,
) {
    fun materializationRows(): List<ShaderStructuralCoverageRow> {
        return rows.distinctBy(::materializationKey)
    }

    fun deduplicate(materialized: List<SpirvCompilerModule>): ShaderStructuralMaterializationResult {
        val compactRows = materializationRows()
        val materializedRows = when (materialized.size) {
            compactRows.size -> compactRows.zip(materialized).map { (row, module) ->
                val key = materializationKey(row)
                rows.filter { materializationKey(it) == key } to module
            }
            rows.size -> rows.zip(materialized).map { (row, module) -> listOf(row) to module }
            else -> error(
                "$sourceName structural materialization count changed: expected ${compactRows.size} compact rows " +
                    "or ${rows.size} coverage rows, got ${materialized.size}",
            )
        }
        data class DistinctModule(
            val rows: MutableList<ShaderStructuralCoverageRow>,
            val module: SpirvCompilerModule,
            var conservativeAccess: TextureAccess,
        )
        val distinct = linkedMapOf<ShaderStructuralSignature, DistinctModule>()
        materializedRows.forEach { (coveredRows, module) ->
            val row = coveredRows.first()
            require(module.name == row.name) {
                "$sourceName structural module order changed: expected ${row.name}, got ${module.name}"
            }
            val signature = ShaderStructuralSignatureExtractor.extract(
                stage,
                module.source,
                row.requiredCapabilities,
                row.localSizeFallback,
            )
            val distinctModule = distinct.getOrPut(signature) {
                DistinctModule(mutableListOf(), module, TextureAccess())
            }
            distinctModule.rows += coveredRows
            distinctModule.conservativeAccess += module.conservativeAccess
        }
        if (distinct.size > MAX_STRUCTURAL_MODULES) {
            return ShaderStructuralMaterializationResult.Preserved(
                buildString {
                    append(sourceName)
                    append(": structural module cap exceeded: ")
                    append(distinct.size)
                    append(" > ")
                    appendLine(MAX_STRUCTURAL_MODULES)
                    appendLine(graph.diagnostic())
                    appendLine("coverage assignments:")
                    rows.forEach { row ->
                        append("  ")
                        append(row.name)
                        append(" assignment=")
                        append(row.assignment.toSortedMap())
                        append(" changed_components=")
                        appendLine(row.changedComponents.toString())
                    }
                    appendLine("retained structural modules:")
                    distinct.entries.forEachIndexed { index, (signature, distinctModule) ->
                        append("  module-")
                        append(index.toString().padStart(3, '0'))
                        append(" signature=")
                        append(signature.canonical.replace('\n', ' '))
                        append(" compiler_sha256=")
                        appendLine(shortHash(normalizeStructuralText(distinctModule.module.source)))
                    }
                }.trimEnd(),
            )
        }
        val modules = distinct.entries.mapIndexed { index, (signature, distinctModule) ->
            val moduleName = "structural-${index.toString().padStart(3, '0')}-${shortHash(signature.canonical)}"
            val representative = distinctModule.rows.first()
            ShaderStructuralModule(
                name = moduleName,
                signature = signature,
                coverage = representative,
                module = distinctModule.module.copy(
                    name = moduleName,
                    structuralSignature = signature,
                    structuralAssignment = representative.assignment,
                    structuralAssignments = distinctModule.rows.map(ShaderStructuralCoverageRow::assignment).distinct(),
                    conservativeAccess = distinctModule.conservativeAccess,
                ),
            )
        }
        return ShaderStructuralMaterializationResult.Materialized(modules)
    }

    private fun materializationKey(row: ShaderStructuralCoverageRow): StructuralMaterializationKey {
        return StructuralMaterializationKey(
            compilerSource = requireNotNull(row.compilerPlan.compilerSource),
            requiredCapabilities = row.requiredCapabilities.distinct().sorted(),
            localSizeFallback = row.localSizeFallback,
            settings = row.compilerPlan.settings,
            irisContracts = row.compilerPlan.irisContracts,
        )
    }
}

private data class StructuralMaterializationKey(
    val compilerSource: String,
    val requiredCapabilities: List<String>,
    val localSizeFallback: LocalSizeAbiSignature?,
    val settings: List<ShaderSetting>,
    val irisContracts: IrisShaderContractPlan,
)

internal data class ShaderStructuralModule(
    val name: String,
    val signature: ShaderStructuralSignature,
    val coverage: ShaderStructuralCoverageRow,
    val module: SpirvCompilerModule,
)

internal sealed interface ShaderStructuralPlanningResult {
    data class Planned(val plan: ShaderStructuralCoveragePlan) : ShaderStructuralPlanningResult
    data class Preserved(val reason: String) : ShaderStructuralPlanningResult
}

internal sealed interface ShaderStructuralMaterializationResult {
    data class Materialized(val modules: List<ShaderStructuralModule>) : ShaderStructuralMaterializationResult
    data class Preserved(val reason: String) : ShaderStructuralMaterializationResult
}

internal fun interface ShaderStructuralCompileFeedback {
    fun hiddenDependency(rows: List<ShaderStructuralCoverageRow>): Set<String>?
}

internal object ShaderStructuralPlanner {
    fun requiresPlanning(basePlan: ShaderCompilerCopyPlan): Boolean {
        if (basePlan.structuralBlockers.isNotEmpty()) return true
        return StructuralSourceModel(
            basePlan.originalSource,
            basePlan.sourceName,
            basePlan.settings,
        ).capabilityNodes().isNotEmpty()
    }

    fun plan(
        basePlan: ShaderCompilerCopyPlan,
        stage: ShaderStage,
        compileFeedback: ShaderStructuralCompileFeedback? = null,
        activation: ProgramActivationContract? = null,
        resourceMarkers: List<TextureResourceMarker> = emptyList(),
    ): ShaderStructuralPlanningResult {
        if (!requiresPlanning(basePlan)) {
            return ShaderStructuralPlanningResult.Preserved(
                "${basePlan.sourceName}: structural planning was requested for a one-module compiler copy",
            )
        }
        val unsupportedContracts = basePlan.irisContracts.structuralIssues.filter {
            it.kind == IrisStructuralIssueKind.UNSUPPORTED
        }
        if (unsupportedContracts.isNotEmpty()) {
            return ShaderStructuralPlanningResult.Preserved(
                unsupportedContracts.joinToString("; ") { it.reason },
            )
        }

        val settings = basePlan.settings.associateBy { it.name }
        val compilerModel = StructuralSourceModel(
            basePlan.compilerCandidateSource,
            basePlan.sourceName,
            settings.values.toList(),
        )
        val originalModel = StructuralSourceModel(
            basePlan.originalSource,
            basePlan.sourceName,
            settings.values.toList(),
        )
        compilerModel.settingMutations().takeIf { it.isNotEmpty() }?.let { mutations ->
            return ShaderStructuralPlanningResult.Preserved(
                mutations.joinToString("; ") { (line, name) ->
                    "${basePlan.sourceName}:$line: structural setting $name is mutated with #undef"
                },
            )
        }
        val compilerNodes = compilerModel.structuralNodes()
        val capabilityNodes = originalModel.capabilityNodes()
        val nodes = buildList {
            addAll(compilerNodes)
            addAll(capabilityNodes)
            basePlan.irisContracts.localSize?.takeIf { it.fallbackRequired }?.let { local ->
                add(
                    ShaderStructuralNode(
                        id = "local-size",
                        kind = ShaderStructuralNodeKind.LOCAL_SIZE,
                        sourceLine = 1,
                        settings = local.settingDependencies,
                        symbols = setOf("gl_WorkGroupSize"),
                        detail = "LocalSizeId capability fallback",
                    ),
                )
            }
        }.distinctBy { listOf(it.kind, it.sourceLine, it.settings, it.symbols, it.detail) }
            .sortedWith(compareBy(ShaderStructuralNode::sourceLine, ShaderStructuralNode::id))
        val preprocessorMaterializedSettings = nodes.filter {
            it.kind != ShaderStructuralNodeKind.LOCAL_SIZE
        }.flatMapTo(sortedSetOf()) { it.settings }
        val structuralSymbolsBySetting = preprocessorMaterializedSettings.associateWith { setting ->
            nodes.filter { setting in it.settings }.flatMapTo(sortedSetOf()) { it.symbols }
        }
        val macrosByName = basePlan.macros.associateBy(ShaderMacroDependency::name)
        val resourceSymbols = resourceMarkers.associate { it.identifier to it.sourceIdentifier }
        val coupledConditionals = basePlan.conditionals.filter {
            it.disposition in setOf(
                ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT,
                ShaderConditionalDisposition.CONTROL_FLOW_EXPRESSION,
                ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION,
            )
        }.mapNotNull { conditional ->
            val symbols = structuralIdentifiers(conditional.exactSlice).toMutableSet()
            val pending = ArrayDeque(symbols)
            while (pending.isNotEmpty()) {
                val symbol = pending.removeFirst()
                val dependencies = macrosByName[symbol]?.dependencies.orEmpty() +
                    listOfNotNull(resourceSymbols[symbol])
                dependencies.forEach { dependency ->
                    if (symbols.add(dependency)) pending.addLast(dependency)
                }
            }
            val dependencies = conditional.settingDependencies.filterTo(sortedSetOf()) { setting ->
                setting in preprocessorMaterializedSettings &&
                    symbols.intersect(structuralSymbolsBySetting[setting].orEmpty()).isNotEmpty()
            }
            if (dependencies.isEmpty()) null else conditional to dependencies
        }
        val coupledStructuralSettings = coupledConditionals.flatMapTo(sortedSetOf()) { it.second }
        val selectedOriginalConditionalIds = buildSet {
            addAll(
                originalModel.groupIdsMatching(
                    basePlan.conditionals.filter {
                        it.disposition == ShaderConditionalDisposition.STRUCTURAL
                    },
                ),
            )
            addAll(capabilityNodes.mapNotNull { it.id.removePrefix("capability-").toIntOrNull() })
        }
        val shapeValueSettings = compilerNodes.filter { it.kind != ShaderStructuralNodeKind.CONDITIONAL }
            .flatMapTo(sortedSetOf()) { it.settings }
            .apply { addAll(coupledStructuralSettings) }

        if (nodes.isEmpty()) {
            return ShaderStructuralPlanningResult.Preserved(
                buildString {
                    append(basePlan.sourceName)
                    append(": structural blockers have no enumerable ABI/capability node: ")
                    append(basePlan.structuralBlockers.joinToString { "line ${it.sourceLine}: ${it.reason}" })
                },
            )
        }
        val unresolved = nodes.flatMapTo(sortedSetOf()) { it.settings } - settings.keys
        if (unresolved.isNotEmpty()) {
            return ShaderStructuralPlanningResult.Preserved(
                "${basePlan.sourceName}: structural settings have no proven scalar domain: ${unresolved.sorted()}",
            )
        }
        val floating = nodes.flatMapTo(sortedSetOf()) { it.settings }.filter {
            settings.getValue(it).type == ShaderSettingType.FLOAT
        }
        if (floating.isNotEmpty()) {
            return ShaderStructuralPlanningResult.Preserved(
                "${basePlan.sourceName}: floating-point structural settings cannot be preprocessor-materialized: $floating",
            )
        }

        val hiddenDependencies = mutableListOf<Set<String>>()
        repeat(settings.size.coerceAtLeast(1)) {
            val graph = buildGraph(nodes, settings, hiddenDependencies)
            val assignments = coverageAssignments(graph, settings)?.filterNot { assignment ->
                activation?.isProvenDisabled(assignment, settings) == true
            }
                ?: return ShaderStructuralPlanningResult.Preserved(
                    "${basePlan.sourceName}: structural dependency domain exceeds $MAX_COMPONENT_ASSIGNMENTS rows\n" +
                        graph.diagnostic(),
                )
            if (assignments.isEmpty()) {
                return ShaderStructuralPlanningResult.Preserved(
                    "${basePlan.sourceName}: program activation contract proves every structural assignment disabled",
                )
            }
            val rows = mutableListOf<ShaderStructuralCoverageRow>()
            val plansByShape = linkedMapOf<StructuralCoverageShapeKey, ShaderCompilerCopyPlan>()
            val materializationKeys = linkedSetOf<StructuralMaterializationKey>()
            try {
                assignments.forEachIndexed { index, assignment ->
                    val fallback = basePlan.irisContracts.localSize?.takeIf { it.fallbackRequired }
                        ?.signatureFor(assignment)
                        ?: if (basePlan.irisContracts.localSize?.fallbackRequired == true) {
                            return ShaderStructuralPlanningResult.Preserved(
                                "${basePlan.sourceName}: local-size fallback has no signature for $assignment",
                            )
                        } else {
                            null
                    }
                    val requiredCapabilities = originalModel.activeCapabilities(assignment)
                    val selectionModel = if (coupledStructuralSettings.isEmpty()) compilerModel else originalModel
                    val selectedConditionalIds = selectedOriginalConditionalIds.takeIf {
                        coupledStructuralSettings.isNotEmpty()
                    }
                    val shape = StructuralCoverageShapeKey(
                        selectedBranches = selectionModel.structuralSelectionSignature(
                            assignment,
                            preprocessorMaterializedSettings,
                            selectedConditionalIds,
                        ),
                        valueSensitiveSettings = shapeValueSettings.map { name ->
                            name to (assignment[name] ?: settings.getValue(name).defaultValue)
                        },
                        requiredCapabilities = requiredCapabilities,
                        localSizeFallback = fallback,
                    )
                    val rowPlan = plansByShape[shape] ?: run {
                        val planned = if (coupledStructuralSettings.isEmpty()) {
                            basePlan.copy(
                                compilerCandidateSource = compilerModel.renderCompilerStructuralSource(
                                    assignment,
                                    preprocessorMaterializedSettings,
                                ),
                                structuralBlockers = emptyList(),
                            )
                        } else {
                            val rendered = originalModel.renderCompilerStructuralSource(
                                assignment,
                                preprocessorMaterializedSettings,
                                selectedConditionalIds,
                            )
                            val frozen = originalModel.freezeSettingsForCompiler(
                                rendered,
                                assignment,
                                coupledStructuralSettings,
                            )
                            ShaderCompilerCopyPlanner.plan(
                                frozen,
                                basePlan.sourceName,
                                localSizeIdSupported = basePlan.irisContracts.localSize?.fallbackRequired != true,
                                localSizeProbeDiagnostic = "inherited LocalSizeId structural fallback",
                            )
                        }
                        val localOnlyIssue = planned.irisContracts.structuralIssues.isNotEmpty() &&
                            planned.irisContracts.structuralIssues.all {
                                it.kind == IrisStructuralIssueKind.LOCAL_SIZE_FALLBACK
                            }
                        val expectedContractBlocker = planned.irisContracts.structuralReason.takeIf { localOnlyIssue }
                        val remainingBlockers = planned.structuralBlockers.filterNot { blocker ->
                            expectedContractBlocker != null && blocker.reason == expectedContractBlocker
                        }
                        if (remainingBlockers.isNotEmpty()) {
                            return ShaderStructuralPlanningResult.Preserved(
                                buildString {
                                    append(basePlan.sourceName)
                                    append(": structural row ")
                                    append(assignment.toSortedMap())
                                    append(" still has compiler-copy blockers: ")
                                    append(remainingBlockers.joinToString { "line ${it.sourceLine}: ${it.reason}" })
                                    append("; selected_settings=")
                                    append(preprocessorMaterializedSettings)
                                    append("; coupled_settings=")
                                    append(coupledStructuralSettings)
                                    append("; remaining_structural_conditionals=")
                                    append(planned.conditionals.filter {
                                        it.disposition == ShaderConditionalDisposition.STRUCTURAL
                                    }.map { "${it.id}@${it.sourceLine}:${it.settingDependencies}" })
                                    append("; remaining_slices=")
                                    append(planned.conditionals.filter {
                                        it.disposition == ShaderConditionalDisposition.STRUCTURAL
                                    }.map { it.exactSlice.lineSequence().firstOrNull().orEmpty().trim() })
                                    append("; relevant_macros=")
                                    val remainingIdentifiers = planned.conditionals.filter {
                                        it.disposition == ShaderConditionalDisposition.STRUCTURAL
                                    }.flatMapTo(linkedSetOf()) { structuralIdentifiers(it.exactSlice) }
                                    append(planned.macros.filter { it.name in remainingIdentifiers }.map {
                                        "${it.name}[defined=${it.unconditionallyDefined},deps=${it.settingDependencies},derived=${it.derivedControl?.compilerExpression}]"
                                    })
                                },
                            )
                        }
                        val contracts = try {
                            basePlan.irisContracts.forStructuralModule(
                                planned.compilerCandidateSource,
                                fallback,
                                planned.irisContracts,
                            )
                        } catch (e: IllegalArgumentException) {
                            return ShaderStructuralPlanningResult.Preserved(e.message.orEmpty())
                        }
                        planned.copy(
                            originalSource = basePlan.originalSource,
                            compilerSource = contracts.compilerSource,
                            compilerCandidateSource = contracts.compilerSource,
                            structuralBlockers = emptyList(),
                            irisContracts = contracts,
                        ).also { plansByShape[shape] = it }
                    }
                    materializationKeys += StructuralMaterializationKey(
                        compilerSource = requireNotNull(rowPlan.compilerSource),
                        requiredCapabilities = requiredCapabilities.distinct().sorted(),
                        localSizeFallback = fallback,
                        settings = rowPlan.settings,
                        irisContracts = rowPlan.irisContracts,
                    )
                    if (materializationKeys.size > MAX_STRUCTURAL_MATERIALIZATION_ROWS) {
                        return ShaderStructuralPlanningResult.Preserved(
                            structuralCompilerModuleCapDiagnostic(
                                basePlan.sourceName,
                                graph,
                                assignments.size,
                                assignment,
                                materializationKeys,
                            ),
                        )
                    }
                    val changed = graph.components.filter { component ->
                        component.settings.any { name -> assignment[name] != settings.getValue(name).defaultValue }
                    }.map { it.id }
                    val rowName = "structural-row-${index.toString().padStart(4, '0')}"
                    rows += ShaderStructuralCoverageRow(
                        name = rowName,
                        assignment = assignment,
                        changedComponents = changed,
                        requiredCapabilities = requiredCapabilities,
                        localSizeFallback = fallback,
                        compilerPlan = rowPlan,
                    )
                }
            } catch (e: StructuralEvaluationException) {
                return ShaderStructuralPlanningResult.Preserved(e.message.orEmpty())
            }
            val hidden = compileFeedback?.hiddenDependency(rows)?.toSortedSet()
            if (hidden.isNullOrEmpty()) {
                return ShaderStructuralPlanningResult.Planned(
                    ShaderStructuralCoveragePlan(
                        basePlan.sourceName,
                        stage,
                        graph,
                        rows,
                        ShaderStructuralRestorationPlan.create(basePlan, graph),
                    ),
                )
            }
            val unknown = hidden - graph.structuralSettings
            if (unknown.isNotEmpty()) {
                return ShaderStructuralPlanningResult.Preserved(
                    "${basePlan.sourceName}: compile feedback named non-structural settings ${unknown.sorted()}",
                )
            }
            val componentIds = graph.components.filter { component -> component.settings.any { it in hidden } }.map { it.id }
            if (componentIds.size < 2 || hidden in hiddenDependencies) {
                return ShaderStructuralPlanningResult.Preserved(
                    "${basePlan.sourceName}: compile feedback did not expose a new cross-component dependency: $hidden",
                )
            }
            hiddenDependencies += hidden
        }
        return ShaderStructuralPlanningResult.Preserved(
            "${basePlan.sourceName}: structural hidden-dependency replanning did not converge",
        )
    }

    private fun structuralCompilerModuleCapDiagnostic(
        sourceName: String,
        graph: ShaderStructuralDependencyGraph,
        coverageRows: Int,
        triggerAssignment: Map<String, String>,
        materializationKeys: Set<StructuralMaterializationKey>,
    ): String = buildString {
        append(sourceName)
        append(": structural materialization-row cap exceeded before materialization: ")
        append(materializationKeys.size)
        append(" > ")
        appendLine(MAX_STRUCTURAL_MATERIALIZATION_ROWS)
        appendLine(graph.diagnostic())
        append("coverage_rows=")
        appendLine(coverageRows)
        append("trigger_assignment=")
        appendLine(triggerAssignment.toSortedMap().toString())
        appendLine("predicted compiler shapes:")
        materializationKeys.forEachIndexed { index, key ->
            append("  shape-")
            append(index.toString().padStart(3, '0'))
            append(" capabilities=")
            append(key.requiredCapabilities)
            append(" local_size=")
            append(key.localSizeFallback ?: "none")
            append(" compiler_sha256=")
            appendLine(shortHash(key.compilerSource))
        }
    }.trimEnd()

    private fun buildGraph(
        nodes: List<ShaderStructuralNode>,
        settings: Map<String, ShaderSetting>,
        hiddenDependencies: List<Set<String>>,
    ): ShaderStructuralDependencyGraph {
        val names = nodes.flatMapTo(sortedSetOf()) { it.settings }
        val union = SettingUnion(names)
        nodes.forEach { node -> union.merge(node.settings) }
        hiddenDependencies.forEach(union::merge)
        val grouped = names.groupBy(union::root).values.map { it.sorted() }.sortedBy { it.first() }
        val components = grouped.mapIndexed { index, componentSettings ->
            ShaderStructuralDependencyComponent(
                id = index,
                settings = componentSettings,
                nodes = nodes.filter { it.settings.any(componentSettings::contains) }.map { it.id }.distinct().sorted(),
                domains = componentSettings.associateWith { settings.getValue(it).domain },
            )
        }
        return ShaderStructuralDependencyGraph(nodes, components)
    }

    private fun coverageAssignments(
        graph: ShaderStructuralDependencyGraph,
        settings: Map<String, ShaderSetting>,
    ): List<Map<String, String>>? {
        val defaults = graph.structuralSettings.associateWithTo(linkedMapOf()) { settings.getValue(it).defaultValue }
        val result = linkedMapOf<String, Map<String, String>>()
        fun add(values: Map<String, String>) {
            val sorted = values.toSortedMap()
            result.putIfAbsent(sorted.entries.joinToString("\u0000") { "${it.key}=${it.value}" }, sorted)
        }
        add(defaults)
        graph.components.forEach { component ->
            val componentRows = enumerate(component.settings.map(settings::getValue), MAX_COMPONENT_ASSIGNMENTS)
                ?: return null
            componentRows.forEach { values -> add(defaults + values) }
        }
        return result.values.toList()
    }

    private fun enumerate(settings: List<ShaderSetting>, limit: Int): List<Map<String, String>>? {
        val result = mutableListOf<Map<String, String>>()
        fun visit(index: Int, values: LinkedHashMap<String, String>): Boolean {
            if (result.size >= limit) return false
            if (index == settings.size) {
                result += values.toMap()
                return true
            }
            val setting = settings[index]
            setting.domain.forEach { value ->
                values[setting.name] = value
                if (!visit(index + 1, values)) return false
            }
            values.remove(setting.name)
            return true
        }
        return if (visit(0, linkedMapOf())) result else null
    }
}

private class SettingUnion(names: Set<String>) {
    private val parent = names.associateWithTo(mutableMapOf()) { it }

    fun root(name: String): String {
        val current = parent.getValue(name)
        if (current == name) return name
        val result = root(current)
        parent[name] = result
        return result
    }

    fun merge(names: Collection<String>) {
        val roots = names.filter(parent::containsKey).map(::root).distinct().sorted()
        if (roots.size < 2) return
        val first = roots.first()
        roots.drop(1).forEach { parent[it] = first }
    }
}

private class StructuralEvaluationException(message: String) : IllegalArgumentException(message)

private class StructuralSourceModel(
    private val source: String,
    private val sourceName: String,
    settings: List<ShaderSetting>,
) {
    private val settingsByCanonical = settings.associateBy { it.name }
    private val settingAliases = buildMap {
        settings.forEach { setting ->
            put(setting.name, setting.name)
            put(setting.compilerName, setting.name)
        }
    }
    private val lines = StructuralLineMap(source)
    private val directives = PreprocessorProtection.protect(source, sourceName).directives.map {
        StructuralDirective(it, lines.directiveRange(it))
    }
    private val groups = buildGroups(directives)
    private val includeGuardNames = groups.mapNotNullTo(hashSetOf()) { group ->
        val name = group.opener.directive.macroName
        if (group.opener.directive.kind != PreprocessorDirectiveKind.IFNDEF || name == null) {
            return@mapNotNullTo null
        }
        directives.firstOrNull { located ->
            located.directive.index > group.opener.directive.index && located.range.first in group.range
        }?.directive?.takeIf { directive ->
            directive.kind == PreprocessorDirectiveKind.DEFINE && directive.macroName == name
        }?.macroName
    }
    private val macros = StructuralMacroGraph(directives, groups, settingAliases, includeGuardNames)
    private val groupsByParent = groups.groupBy { it.parentId }
        .mapValues { (_, value) -> value.sortedBy { it.range.first } }
    private val groupSettingDependencies = groups.associate { group ->
        val includeGuard = group.opener.directive.kind == PreprocessorDirectiveKind.IFNDEF &&
            group.opener.directive.macroName in includeGuardNames
        group.id to if (includeGuard) {
            emptySet()
        } else {
            group.delimiters.flatMapTo(sortedSetOf()) { delimiter ->
                macros.dependencies(directiveCondition(delimiter.directive))
            }
        }
    }
    private val maskedGlsl by lazy {
        maskGlslRanges(
            maskStructuralCommentsAndStrings(source),
            0,
            directives.map(StructuralDirective::range),
        )
    }
    private val topLevelBlocks by lazy { scanTopLevelGlslBlocks(source, maskedGlsl) }
    private val sourceDirectRegions by lazy { directRegions() }

    fun structuralNodes(): List<ShaderStructuralNode> {
        val conditional = groups.mapNotNull { group ->
            val dependencies = groupSettingDependencies.getValue(group.id)
            if (dependencies.isEmpty()) return@mapNotNull null
            ShaderStructuralNode(
                id = "conditional-${group.id}",
                kind = ShaderStructuralNodeKind.CONDITIONAL,
                sourceLine = group.opener.directive.sourceLine,
                settings = dependencies,
                symbols = structuralSymbols(source.substring(group.range), settingAliases.keys, macros.names),
                detail = "setting-controlled preprocessor region remains outside ordinary GLSL control flow",
            )
        }
        val direct = sourceDirectRegions.mapIndexed { index, region ->
            ShaderStructuralNode(
                id = "${region.kind.name.lowercase()}-${region.sourceLine}-${index}",
                kind = region.kind,
                sourceLine = region.sourceLine,
                settings = region.settings,
                symbols = structuralSymbols(source.substring(region.range), settingAliases.keys, macros.names),
                detail = region.detail,
            )
        }
        return (conditional + direct).distinctBy { it.id }
    }

    fun capabilityNodes(): List<ShaderStructuralNode> {
        val extensions = directives.filter {
            it.directive.kind == PreprocessorDirectiveKind.EXTENSION
        }
        return groups.mapNotNull { group ->
            val groupExtensions = extensions.filter { it.range.first in group.range }
            if (groupExtensions.isEmpty()) return@mapNotNull null
            val dependencies = groupSettingDependencies.getValue(group.id)
            if (dependencies.isEmpty()) return@mapNotNull null
            ShaderStructuralNode(
                id = "capability-${group.id}",
                kind = ShaderStructuralNodeKind.CAPABILITY,
                sourceLine = group.opener.directive.sourceLine,
                settings = dependencies,
                symbols = groupExtensions.mapNotNullTo(sortedSetOf()) {
                    EXTENSION.find(it.directive.exactText)?.groupValues?.get(1)
                },
                detail = "setting-controlled extension capability contract",
            )
        }
    }

    fun structuralEntitySlots(
        selectedSettings: Set<String>,
        structuralConditionalIds: Set<Int>,
    ): List<StructuralEntitySlotCandidate> {
        val selectedGroups = groups.filter { group ->
            groupSettingDependencies.getValue(group.id).any(selectedSettings::contains) &&
                (group.id in structuralConditionalIds || sourceDirectRegions.any { group.range.overlaps(it.range) })
        }
        val selectedIds = selectedGroups.mapTo(hashSetOf()) { it.id }
        val rootGroups = selectedGroups.filter { it.parentId !in selectedIds }
        val groupRanges = rootGroups.map { it.range }
        val directRanges = sourceDirectRegions.filter { region ->
            region.settings.any(selectedSettings::contains) && groupRanges.none { it.containsRange(region.range) }
        }.map { it.range }
        return (groupRanges + directRanges).distinct().map { range ->
            val owner = topLevelBlocks.singleOrNull { block -> block.fullRange.containsRange(range) }
                ?.takeIf { block -> braceDepthAt(block.fullRange.first) == 0 }
                ?: enclosingTopLevelBraceBlock(range)
            if (owner == null) {
                StructuralEntitySlotCandidate(
                    range,
                    ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    null,
                )
            } else {
                StructuralEntitySlotCandidate(
                    owner.fullRange,
                    if (owner.kind == TopLevelGlslBlockKind.FUNCTION) {
                        ShaderStructuralEntitySlotKind.FUNCTION
                    } else {
                        ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION
                    },
                    if (owner.kind == TopLevelGlslBlockKind.FUNCTION) {
                        normalizeStructuralSignatureText(source.substring(owner.prefixRange))
                    } else {
                        null
                    },
                )
            }
        }.distinctBy { listOf(it.range, it.kind, it.canonicalEntity) }.sortedBy { it.range.first }
    }

    fun conditionalOwnerRange(range: IntRange): IntRange? {
        return groups.filter { group ->
            val includeGuard = group.opener.directive.kind == PreprocessorDirectiveKind.IFNDEF &&
                group.opener.directive.macroName in includeGuardNames
            group.range.containsRange(range) && !includeGuard
        }.maxByOrNull { group -> group.range.last - group.range.first }?.range
    }

    private fun enclosingTopLevelBraceBlock(range: IntRange): TopLevelGlslBlock? {
        var depth = 0
        var boundary = 0
        var outerOpen = -1
        var outerBoundary = 0
        for (cursor in 0 until range.first) {
            when (maskedGlsl[cursor]) {
                '{' -> {
                    if (depth == 0) {
                        outerOpen = cursor
                        outerBoundary = boundary
                    }
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0) boundary = cursor + 1
                }
                ';' -> if (depth == 0) boundary = cursor + 1
            }
        }
        if (depth == 0 || outerOpen < 0) return null
        var close = outerOpen + 1
        var nestedDepth = 1
        while (close < maskedGlsl.length && nestedDepth > 0) {
            when (maskedGlsl[close]) {
                '{' -> nestedDepth++
                '}' -> nestedDepth--
            }
            close++
        }
        if (nestedDepth != 0) return null
        val prefixStart = firstStructuralNonWhitespace(maskedGlsl, outerBoundary, outerOpen)
        val prefix = maskedGlsl.substring(prefixStart, outerOpen).trim()
        val kind = if (prefix.endsWith(')')) TopLevelGlslBlockKind.FUNCTION else TopLevelGlslBlockKind.ABI
        var fullEnd = close - 1
        if (kind != TopLevelGlslBlockKind.FUNCTION) {
            var cursor = close
            while (cursor < maskedGlsl.length && maskedGlsl[cursor].isWhitespace()) cursor++
            if (maskedGlsl.getOrNull(cursor) == ';') fullEnd = cursor
        }
        return TopLevelGlslBlock(kind, prefixStart until outerOpen, prefixStart..fullEnd)
    }

    fun braceDepthAt(offset: Int): Int {
        var depth = 0
        for (cursor in 0 until offset.coerceAtMost(maskedGlsl.length)) {
            when (maskedGlsl[cursor]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
        }
        return depth
    }

    fun lineAt(offset: Int): Int {
        return lines.lines.lastOrNull { it.range.first <= offset }?.number ?: 1
    }

    fun groupIdsMatching(conditionals: List<ShaderConditionalRegion>): Set<Int> {
        return conditionals.mapNotNullTo(linkedSetOf()) { conditional ->
            val exact = groups.filter { group ->
                source.substring(group.range) == conditional.exactSlice
            }
            if (exact.size == 1) return@mapNotNullTo exact.single().id
            groups.singleOrNull { group ->
                group.opener.directive.sourceLine == conditional.sourceLine &&
                    requireNotNull(group.endif).directive.endLine == conditional.endLine
            }?.id
        }
    }

    fun activeCapabilities(assignment: Map<String, String>): List<String> {
        val extensions = directives.filter {
            it.directive.kind == PreprocessorDirectiveKind.EXTENSION
        }
        if (extensions.none { extension -> groups.any { extension.range.first in it.range } }) {
            return extensions.mapNotNull(::capabilityName).distinct().sorted()
        }
        val evaluation = evaluate(assignment, extensions.maxOf { it.directive.index })
        return extensions.filter { it.directive.index in evaluation.activeDirectives }
            .mapNotNull(::capabilityName).distinct().sorted()
    }

    private fun capabilityName(directive: StructuralDirective): String? {
        return EXTENSION.find(directive.directive.exactText)?.let {
            "${it.groupValues[1]}:${it.groupValues[2]}"
        }
    }

    fun structuralSelectionSignature(
        assignment: Map<String, String>,
        selectedSettings: Set<String>,
        selectedGroupIds: Set<Int>? = null,
    ): List<Pair<Int, Int>> {
        val selectedIds = selectedGroupClosure(selectedSettings, selectedGroupIds)
        val selectedGroups = groups.filter { group ->
            group.id in selectedIds && groupSettingDependencies.getValue(group.id).isNotEmpty()
        }
        val evaluation = evaluate(assignment, selectedGroupIds = selectedIds)
        return selectedGroups.sortedBy { it.id }.map { group ->
            group.id to (evaluation.selectedBranches[group.id] ?: -1)
        }
    }

    private fun selectedGroupClosure(
        selectedSettings: Set<String>,
        selectedGroupIds: Set<Int>?,
    ): Set<Int> {
        val selected = groups.filter { group ->
            (selectedGroupIds == null || group.id in selectedGroupIds) &&
                groupSettingDependencies.getValue(group.id).let { dependencies ->
                    dependencies.isNotEmpty() && dependencies.all(selectedSettings::contains)
                }
        }.mapTo(linkedSetOf()) { it.id }
        var changed: Boolean
        do {
            changed = false
            groups.filter { it.id in selected }.forEach { group ->
                group.delimiters.flatMap { delimiter ->
                    structuralIdentifiers(directiveCondition(delimiter.directive))
                }.forEach { macroName ->
                    directives.filter { directive ->
                        directive.directive.kind == PreprocessorDirectiveKind.DEFINE &&
                            directive.directive.macroName == macroName
                    }.flatMap { definition ->
                        groups.filter { definition.range.first in it.range }
                    }.forEach { owner ->
                        val dependencies = groupSettingDependencies.getValue(owner.id)
                        if (
                            owner.id !in selected && dependencies.isNotEmpty() &&
                            dependencies.all(selectedSettings::contains)
                        ) {
                            selected += owner.id
                            changed = true
                        }
                    }
                }
            }
            groups.filter { it.id in selected }.mapNotNull(StructuralConditionalGroup::parentId).forEach { parentId ->
                if (parentId !in selected && groupSettingDependencies.getValue(parentId).isEmpty()) {
                    selected += parentId
                    changed = true
                }
            }
        } while (changed)
        return selected
    }

    fun settingMutations(): List<Pair<Int, String>> {
        return directives.mapNotNull { located ->
            if (located.directive.kind != PreprocessorDirectiveKind.UNDEF) return@mapNotNull null
            val name = located.directive.macroName ?: return@mapNotNull null
            val canonical = settingAliases[name] ?: return@mapNotNull null
            located.directive.sourceLine to canonical
        }.distinct().sortedWith(compareBy<Pair<Int, String>>({ it.first }, { it.second }))
    }

    fun renderStructuralSource(
        assignment: Map<String, String>,
        selectedSettings: Set<String> = settingsByCanonical.keys,
        renderSource: String = source,
        selectedGroupIds: Set<Int>? = null,
    ): String {
        require(renderSource.length == source.length) {
            "$sourceName structural render source changed length"
        }
        val structuralGroups = selectedGroupClosure(selectedSettings, selectedGroupIds)
        val materializedGroups = groups.filterTo(hashSetOf()) { group ->
            group.id in structuralGroups && groupSettingDependencies.getValue(group.id).isNotEmpty()
        }.mapTo(hashSetOf(), StructuralConditionalGroup::id)
        val evaluation = evaluate(assignment, selectedGroupIds = structuralGroups)

        lateinit var renderGroup: (StructuralConditionalGroup) -> String
        fun renderRange(start: Int, end: Int, parentId: Int?): String {
            val children = groupsByParent[parentId].orEmpty().filter {
                it.range.first >= start && it.range.last < end
            }
            if (children.isEmpty()) return renderSource.substring(start, end)
            return buildString {
                var cursor = start
                children.forEach { child ->
                    append(renderSource, cursor, child.range.first)
                    append(renderGroup(child))
                    cursor = child.range.last + 1
                }
                append(renderSource, cursor, end)
            }
        }

        fun renderUnrelated(group: StructuralConditionalGroup): String = buildString {
            val boundaries = group.delimiters + requireNotNull(group.endif)
            group.delimiters.forEachIndexed { index, delimiter ->
                append(renderSource.substring(delimiter.range))
                append(renderRange(delimiter.range.last + 1, boundaries[index + 1].range.first, group.id))
            }
            append(renderSource.substring(requireNotNull(group.endif).range))
        }

        fun renderSelected(group: StructuralConditionalGroup): String {
            val selected = evaluation.selectedBranches[group.id] ?: return ""
            val boundaries = group.delimiters + requireNotNull(group.endif)
            if (selected !in group.delimiters.indices) return ""
            return renderRange(
                group.delimiters[selected].range.last + 1,
                boundaries[selected + 1].range.first,
                group.id,
            )
        }

        fun renderGroupImpl(group: StructuralConditionalGroup): String {
            return if (group.id in materializedGroups) renderSelected(group) else renderUnrelated(group)
        }

        renderGroup = ::renderGroupImpl
        return renderRange(0, renderSource.length, null)
    }

    fun renderCompilerStructuralSource(
        assignment: Map<String, String>,
        selectedSettings: Set<String>,
        selectedGroupIds: Set<Int>? = null,
    ): String {
        if (selectedSettings.isEmpty()) {
            return renderStructuralSource(assignment, selectedSettings, selectedGroupIds = selectedGroupIds)
        }
        val valueOf = { canonical: String ->
            (assignment[canonical] ?: settingsByCanonical.getValue(canonical).defaultValue).asLong()
        }
        val isDefined = { canonical: String ->
            val setting = settingsByCanonical.getValue(canonical)
            !setting.presenceToggle || (assignment[canonical] ?: setting.defaultValue).asLong() != 0L
        }
        val selectedGroups = selectedGroupClosure(selectedSettings, selectedGroupIds)
        val evaluation = evaluate(assignment, selectedGroupIds = selectedGroups)
        val materializablePresence = macros.materializablePresenceNames(selectedSettings)
        val conditionalMacroReplacements = directives.filter {
            it.directive.kind in setOf(PreprocessorDirectiveKind.IF, PreprocessorDirectiveKind.ELIF)
        }.flatMap { located ->
            val text = source.substring(located.range)
            STRUCTURAL_DEFINED_MACRO.findAll(maskStructuralCommentsAndStrings(text)).mapNotNull { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (name !in materializablePresence) return@mapNotNull null
                val value = if (name in evaluation.definedMacros) "1" else "0"
                StructuralReplacement(
                    located.range.first + match.range.first,
                    located.range.first + match.range.last + 1,
                    value.padEnd(match.value.length),
                )
            }.toList()
        }
        val materializedAliases = sourceDirectRegions.filter {
            it.kind == ShaderStructuralNodeKind.TOKEN_PASTE && it.settings.any(selectedSettings::contains)
        }.flatMap { region ->
            macros.materializedAliases(
                source.substring(region.range),
                selectedSettings,
                valueOf,
                isDefined,
            ).entries
        }.associate { it.toPair() }
        val macroReplacements = directives.mapNotNull { located ->
            val name = located.directive.macroName ?: return@mapNotNull null
            val value = materializedAliases[name] ?: return@mapNotNull null
            if (located.directive.kind != PreprocessorDirectiveKind.DEFINE) return@mapNotNull null
            StructuralReplacement(
                located.range.first,
                located.range.last + 1,
                renderMaterializedMacro(source.substring(located.range), name, value),
            )
        }
        val replacements = conditionalMacroReplacements + macroReplacements + sourceDirectRegions.flatMap { region ->
            if (region.settings.none(selectedSettings::contains)) return@flatMap emptyList()
            val text = source.substring(region.range)
            val lexical = maskGlslRanges(
                maskStructuralCommentsAndStrings(text),
                region.range.first,
                directives.map(StructuralDirective::range),
            )
            STRUCTURAL_IDENTIFIER.findAll(lexical).mapNotNull { match ->
                val compilerValue = settingAliases[match.value]?.let { canonical ->
                    if (canonical !in selectedSettings) return@let null
                    val setting = settingsByCanonical.getValue(canonical)
                    (assignment[canonical] ?: setting.defaultValue).asPreprocessorValue()
                } ?: macros.materializedValue(
                    match.value,
                    selectedSettings,
                    valueOf,
                    isDefined,
                )?.toString() ?: return@mapNotNull null
                if (compilerValue.length > match.value.length) {
                    throw StructuralEvaluationException(
                        "$sourceName: structural value $compilerValue is wider than compiler token ${match.value}",
                    )
                }
                StructuralReplacement(
                    region.range.first + match.range.first,
                    region.range.first + match.range.last + 1,
                    compilerValue.padEnd(match.value.length, ' '),
                )
            }.toList()
        }
        val fixedSource = applyStructuralReplacements(source, replacements)
        val result = renderStructuralSource(assignment, selectedSettings, fixedSource, selectedGroupIds)
        val compilerNames = selectedSettings.mapTo(hashSetOf()) {
            settingsByCanonical.getValue(it).compilerName
        }
        val specializationUses = countTargetStructuralIdentifiers(result, compilerNames)
        val declarations = STRUCTURAL_SPECIALIZATION_DECLARATION.findAll(result)
            .associateBy { it.groupValues[1] }
        val removals = selectedSettings.mapNotNull { name ->
            val compilerName = settingsByCanonical.getValue(name).compilerName
            val declaration = declarations[compilerName] ?: return@mapNotNull null
            if (specializationUses[compilerName] != 1) return@mapNotNull null
            StructuralReplacement(declaration.range.first, declaration.range.last + 1, "")
        }
        return applyStructuralReplacements(result, removals)
    }

    private fun renderMaterializedMacro(exactText: String, name: String, value: Long): String {
        val endingStart = exactText.indexOfFirst { it == '\r' || it == '\n' }.let {
            if (it < 0) exactText.length else it
        }
        val ending = exactText.substring(endingStart)
        val indent = exactText.substring(0, exactText.indexOf('#').coerceAtLeast(0))
        val directive = "$indent#define $name $value"
        if (directive.length > endingStart) {
            throw StructuralEvaluationException(
                "$sourceName: materialized macro $name value $value exceeds its source contract width",
            )
        }
        return directive.padEnd(endingStart) + ending
    }

    fun freezeSettingsForCompiler(
        rendered: String,
        assignment: Map<String, String>,
        fixedSettings: Set<String>,
    ): String {
        if (fixedSettings.isEmpty()) return rendered
        val renderedLines = StructuralLineMap(rendered)
        val renderedDirectives = PreprocessorProtection.protect(rendered, sourceName).directives
        val definitionReplacements = renderedDirectives.mapNotNull { directive ->
            if (
                directive.macroName == null || directive.macroName !in fixedSettings ||
                directive.kind !in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.DISABLED_DEFINE)
            ) {
                return@mapNotNull null
            }
            val range = renderedLines.directiveRange(directive)
            StructuralReplacement(
                range.first,
                range.last + 1,
                rendered.substring(range).map { if (it in "\r\n") it else ' ' }.joinToString(""),
            )
        }
        var result = applyStructuralReplacements(rendered, definitionReplacements)
        val aliases = buildMap {
            val reserved = structuralIdentifiers(maskStructuralCommentsAndStrings(result)).toMutableSet()
            fixedSettings.sorted().forEach { name ->
                var alias = "SM_STRUCT_$name"
                while (!reserved.add(alias)) alias += '_'
                put(name, alias)
            }
        }
        val presenceDirectiveReplacements = renderedDirectives.mapNotNull { directive ->
            val name = directive.macroName ?: return@mapNotNull null
            if (
                name !in fixedSettings || settingsByCanonical[name]?.presenceToggle != true ||
                directive.kind !in setOf(PreprocessorDirectiveKind.IFDEF, PreprocessorDirectiveKind.IFNDEF)
            ) {
                return@mapNotNull null
            }
            val range = renderedLines.directiveRange(directive)
            val exactText = result.substring(range)
            val endingStart = exactText.indexOfFirst { it == '\r' || it == '\n' }.let {
                if (it < 0) exactText.length else it
            }
            val ending = exactText.substring(endingStart)
            val indent = exactText.substring(0, exactText.indexOf('#').coerceAtLeast(0))
            val condition = if (directive.kind == PreprocessorDirectiveKind.IFDEF) name else "!$name"
            StructuralReplacement(range.first, range.last + 1, "$indent#if $condition$ending")
        }
        result = applyStructuralReplacements(result, presenceDirectiveReplacements)
        val definedReplacements = STRUCTURAL_DEFINED_MACRO.findAll(maskStructuralCommentsAndStrings(result))
            .mapNotNull { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (name !in fixedSettings || settingsByCanonical[name]?.presenceToggle != true) return@mapNotNull null
                StructuralReplacement(match.range.first, match.range.last + 1, name)
            }.toList()
        result = applyStructuralReplacements(result, definedReplacements)
        val lexical = maskStructuralCommentsAndStrings(result)
        val tokenReplacements = STRUCTURAL_IDENTIFIER.findAll(lexical).mapNotNull { match ->
            aliases[match.value]?.let { alias ->
                StructuralReplacement(match.range.first, match.range.last + 1, alias)
            }
        }.toList()
        result = applyStructuralReplacements(result, tokenReplacements)

        val version = STRUCTURAL_VERSION_LINE.find(result)
            ?: throw StructuralEvaluationException("$sourceName: compiler root has no #version directive")
        var insertion = version.range.last + 1
        if (result.getOrNull(insertion) == '\r') insertion++
        if (result.getOrNull(insertion) == '\n') insertion++
        val newline = when {
            "\r\n" in result -> "\r\n"
            '\r' in result -> "\r"
            else -> "\n"
        }
        val definitions = buildString {
            aliases.toSortedMap().forEach { (name, alias) ->
                val setting = settingsByCanonical.getValue(name)
                val value = assignment[name] ?: setting.defaultValue
                append("#define ")
                append(alias)
                append(' ')
                append(value.asPreprocessorValue())
                append(newline)
            }
        }
        val prefix = if (insertion == 0 || result[insertion - 1] in "\r\n") "" else newline
        return result.substring(0, insertion) + prefix + definitions + result.substring(insertion)
    }

    private fun directRegions(): List<StructuralDirectRegion> {
        val directiveLines = directives.flatMapTo(hashSetOf()) {
            it.directive.sourceLine..it.directive.endLine
        }
        val abiBlocks = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.ABI }
        val abiBlockRanges = abiBlocks.map { it.fullRange }
        val functionSignatureRanges = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.FUNCTION }
            .map { it.prefixRange }
        val blocks = abiBlocks.mapNotNull { block ->
            val text = source.substring(block.fullRange)
            val dependencies = macros.dependencies(text)
            if (dependencies.isEmpty()) return@mapNotNull null
            val lexical = maskStructuralCommentsAndStrings(text)
            val tokenPaste = macros.usesTokenPaste(text)
            val kind = when {
                tokenPaste -> ShaderStructuralNodeKind.TOKEN_PASTE
                INTERFACE_DECLARATION.containsMatchIn(lexical) -> ShaderStructuralNodeKind.STAGE_INTERFACE
                else -> ShaderStructuralNodeKind.RESOURCE
            }
            StructuralDirectRegion(
                kind,
                lineAt(block.fullRange.first),
                block.fullRange,
                dependencies,
                when (kind) {
                    ShaderStructuralNodeKind.TOKEN_PASTE -> "setting participates in token-pasted structural code"
                    ShaderStructuralNodeKind.STAGE_INTERFACE -> "setting affects a stage interface declaration"
                    else -> "setting affects a resource/layout declaration"
                },
            )
        }
        val direct = lines.lines.mapNotNull { line ->
            if (line.number in directiveLines) return@mapNotNull null
            val analysisText = maskGlslRanges(
                line.text,
                line.range.first,
                functionSignatureRanges + abiBlockRanges,
            )
            val normalized = analysisText.trim()
            val tokenPaste = macros.usesTokenPaste(analysisText)
            val dependencies = macros.dependencies(analysisText)
            if (dependencies.isEmpty()) return@mapNotNull null
            val layout = LAYOUT.containsMatchIn(normalized) &&
                !CONSTANT_ID.containsMatchIn(normalized) &&
                !LOCAL_SIZE_ID.containsMatchIn(normalized)
            val abi = line.braceDepth == 0 && ABI_DECLARATION.containsMatchIn(normalized)
            if (!tokenPaste && !layout && !abi) return@mapNotNull null
            val kind = when {
                tokenPaste -> ShaderStructuralNodeKind.TOKEN_PASTE
                INTERFACE_DECLARATION.containsMatchIn(normalized) -> ShaderStructuralNodeKind.STAGE_INTERFACE
                else -> ShaderStructuralNodeKind.RESOURCE
            }
            StructuralDirectRegion(
                kind,
                line.number,
                line.range,
                dependencies,
                when (kind) {
                    ShaderStructuralNodeKind.TOKEN_PASTE -> "setting participates in token-pasted structural code"
                    ShaderStructuralNodeKind.FUNCTION_ABI -> "setting affects a function signature"
                    ShaderStructuralNodeKind.STAGE_INTERFACE -> "setting affects a stage interface declaration"
                    else -> "setting affects a resource/layout declaration"
                },
            )
        }
        val functions = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.FUNCTION }.mapNotNull { block ->
            val text = source.substring(block.prefixRange)
            val dependencies = macros.dependencies(text)
            if (dependencies.isEmpty()) return@mapNotNull null
            StructuralDirectRegion(
                if (macros.usesTokenPaste(text)) {
                    ShaderStructuralNodeKind.TOKEN_PASTE
                } else {
                    ShaderStructuralNodeKind.FUNCTION_ABI
                },
                lineAt(block.prefixRange.first),
                block.prefixRange,
                dependencies,
                if (macros.usesTokenPaste(text)) {
                    "setting participates in token-pasted structural code"
                } else {
                    "setting affects a function signature"
                },
            )
        }
        return (blocks + direct + functions).distinctBy { it.range }
    }

    private fun evaluate(
        assignment: Map<String, String>,
        lastDirectiveIndex: Int? = null,
        selectedGroupIds: Set<Int>? = null,
    ): StructuralEvaluation {
        val relevantGroups = selectedGroupIds?.let { selected ->
            buildSet {
                fun addWithParents(id: Int) {
                    if (!add(id)) return
                    groups.firstOrNull { it.id == id }?.parentId?.let(::addWithParents)
                }
                selected.forEach(::addWithParents)
            }
        }
        val evaluator = StructuralPreprocessorEvaluator(
            sourceName,
            if (lastDirectiveIndex == null) {
                directives
            } else {
                directives.takeWhile { it.directive.index <= lastDirectiveIndex }
            },
            settingAliases,
            settingsByCanonical,
            assignment,
            relevantGroups,
        )
        return evaluator.evaluate()
    }
}

private data class StructuralDirectRegion(
    val kind: ShaderStructuralNodeKind,
    val sourceLine: Int,
    val range: IntRange,
    val settings: Set<String>,
    val detail: String,
)

private data class StructuralEntitySlotCandidate(
    val range: IntRange,
    val kind: ShaderStructuralEntitySlotKind,
    val canonicalEntity: String?,
)

private data class StructuralDirective(val directive: PreprocessorDirective, val range: IntRange)

private data class StructuralConditionalGroup(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val opener: StructuralDirective,
    val delimiters: MutableList<StructuralDirective>,
    var endif: StructuralDirective? = null,
    var range: IntRange = IntRange.EMPTY,
)

private fun buildGroups(directives: List<StructuralDirective>): List<StructuralConditionalGroup> {
    val result = mutableListOf<StructuralConditionalGroup>()
    val stack = mutableListOf<StructuralConditionalGroup>()
    directives.forEach { directive ->
        when (directive.directive.kind) {
            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            -> {
                val group = StructuralConditionalGroup(
                    requireNotNull(directive.directive.conditionalId),
                    stack.lastOrNull()?.id,
                    stack.size,
                    directive,
                    mutableListOf(directive),
                )
                result += group
                stack += group
            }
            PreprocessorDirectiveKind.ELIF,
            PreprocessorDirectiveKind.ELSE,
            -> stack.last().delimiters += directive
            PreprocessorDirectiveKind.ENDIF -> {
                val group = stack.removeAt(stack.lastIndex)
                group.endif = directive
                group.range = group.opener.range.first..directive.range.last
            }
            else -> Unit
        }
    }
    return result
}

private class StructuralMacroGraph(
    directives: List<StructuralDirective>,
    groups: List<StructuralConditionalGroup>,
    private val settingAliases: Map<String, String>,
    private val includeGuardNames: Set<String>,
) {
    private data class Macro(
        val name: String,
        val bodies: List<String>,
        val ownerConditions: List<String>,
        val functionLike: Boolean,
        val tokenPaste: Boolean,
    )

    private val macros = directives.filter {
        it.directive.kind == PreprocessorDirectiveKind.DEFINE && it.directive.macroName != null
    }.groupBy { requireNotNull(it.directive.macroName) }.mapValues { (name, definitions) ->
        Macro(
            name,
            definitions.map { it.directive.macroBody.orEmpty() },
            definitions.flatMap { definition ->
                groups.filter { definition.range.first in it.range }.flatMap { group ->
                    group.delimiters.map { directiveCondition(it.directive) }
                }
            }.distinct(),
            definitions.any { it.directive.macroFunctionLike },
            definitions.any { "##" in it.directive.macroBody.orEmpty() },
        )
    }
    val names: Set<String> = macros.keys
    private val dependencyMemo = mutableMapOf<String, Set<String>>()
    private val tokenPasteMemo = mutableMapOf<String, Boolean>()

    fun dependencies(text: String): Set<String> {
        return structuralIdentifiers(text).flatMapTo(sortedSetOf()) { identifier ->
            settingAliases[identifier]?.let(::setOf) ?: dependenciesFor(identifier, linkedSetOf())
        }
    }

    fun usesTokenPaste(text: String): Boolean {
        if ("##" in text) return true
        return structuralIdentifiers(text).any { tokenPasteFor(it, linkedSetOf()) }
    }

    fun materializablePresenceNames(selectedSettings: Set<String>): Set<String> {
        return macros.keys.filterTo(linkedSetOf()) { name ->
            dependenciesFor(name, linkedSetOf()).let { dependencies ->
                dependencies.isNotEmpty() && dependencies.all(selectedSettings::contains)
            }
        }
    }

    fun materializedValue(
        name: String,
        selectedSettings: Set<String>,
        valueOf: (String) -> Long?,
        isDefined: (String) -> Boolean,
    ): Long? {
        val dependencies = dependenciesFor(name, linkedSetOf())
        if (dependencies.isEmpty() || dependencies.any { it !in selectedSettings }) return null
        fun resolve(identifier: String, visiting: MutableSet<String>): Long? {
            settingAliases[identifier]?.let { canonical ->
                return canonical.takeIf(selectedSettings::contains)?.let(valueOf)
            }
            if (identifier == "true") return 1L
            if (identifier == "false") return 0L
            val macro = macros[identifier] ?: return null
            val bodies = macro.bodies.map(::stripStructuralComments).map(String::trim).distinct()
            if (macro.functionLike || bodies.size != 1 || !visiting.add(identifier)) return null
            val value = StructuralExpressionParser(
                bodies.single(),
                { child -> resolve(child, visiting) },
                { child ->
                    settingAliases[child]?.let { canonical ->
                        canonical in selectedSettings && isDefined(canonical)
                    } ?: (child in macros)
                },
            ).parse()
            visiting.remove(identifier)
            return value
        }
        return resolve(name, linkedSetOf())
    }

    fun materializedAliases(
        text: String,
        selectedSettings: Set<String>,
        valueOf: (String) -> Long?,
        isDefined: (String) -> Boolean,
    ): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        val visited = linkedSetOf<String>()
        fun visit(name: String) {
            val macro = macros[name] ?: return
            if (!visited.add(name)) return
            if (!macro.functionLike) {
                materializedValue(name, selectedSettings, valueOf, isDefined)?.let { result[name] = it }
            }
            macro.bodies.forEach { body -> structuralIdentifiers(body).forEach(::visit) }
        }
        structuralIdentifiers(text).forEach(::visit)
        return result
    }

    private fun dependenciesFor(name: String, visiting: MutableSet<String>): Set<String> {
        if (name in includeGuardNames) return emptySet()
        dependencyMemo[name]?.let { return it }
        val macro = macros[name] ?: return emptySet()
        if (!visiting.add(name)) return emptySet()
        val result = (macro.bodies + macro.ownerConditions).flatMapTo(sortedSetOf()) { body ->
            structuralIdentifiers(body).flatMapTo(linkedSetOf()) { identifier ->
                settingAliases[identifier]?.let(::setOf) ?: dependenciesFor(identifier, visiting)
            }
        }
        visiting.remove(name)
        dependencyMemo[name] = result
        return result
    }

    private fun tokenPasteFor(name: String, visiting: MutableSet<String>): Boolean {
        tokenPasteMemo[name]?.let { return it }
        val macro = macros[name] ?: return false
        if (!visiting.add(name)) return false
        val result = macro.tokenPaste || macro.bodies.any { body ->
            structuralIdentifiers(body).any { tokenPasteFor(it, visiting) }
        }
        visiting.remove(name)
        tokenPasteMemo[name] = result
        return result
    }
}

private data class StructuralEvaluation(
    val selectedBranches: Map<Int, Int>,
    val activeDirectives: Set<Int>,
    val definedMacros: Set<String>,
)

private data class StructuralCoverageShapeKey(
    val selectedBranches: List<Pair<Int, Int>>,
    val valueSensitiveSettings: List<Pair<String, String>>,
    val requiredCapabilities: List<String>,
    val localSizeFallback: LocalSizeAbiSignature?,
)

private class StructuralPreprocessorEvaluator(
    private val sourceName: String,
    private val directives: List<StructuralDirective>,
    private val settingAliases: Map<String, String>,
    private val settings: Map<String, ShaderSetting>,
    private val assignment: Map<String, String>,
    private val relevantGroups: Set<Int>? = null,
) {
    private data class Macro(val body: String, val functionLike: Boolean)
    private data class Frame(
        val id: Int,
        val parentActive: Boolean,
        var branchIndex: Int,
        var branchTaken: Boolean,
        var active: Boolean,
        val evaluated: Boolean,
    )

    private val macros = linkedMapOf<String, Macro>()
    private val frames = mutableListOf<Frame>()
    private val selected = linkedMapOf<Int, Int>()
    private val activeDirectives = linkedSetOf<Int>()

    fun evaluate(): StructuralEvaluation {
        directives.forEach { located ->
            val directive = located.directive
            val active = frames.lastOrNull()?.active ?: true
            when (directive.kind) {
                PreprocessorDirectiveKind.IF,
                PreprocessorDirectiveKind.IFDEF,
                PreprocessorDirectiveKind.IFNDEF,
                -> {
                    val parentActive = active
                    val id = requireNotNull(directive.conditionalId)
                    val evaluated = relevantGroups == null || id in relevantGroups
                    val condition = if (parentActive && evaluated) evaluateCondition(directive) else false
                    val frame = Frame(id, parentActive, 0, condition, parentActive && condition, evaluated)
                    frames += frame
                    if (frame.active) selected[frame.id] = 0
                }
                PreprocessorDirectiveKind.ELIF -> {
                    val frame = frames.last()
                    frame.branchIndex++
                    val condition = frame.evaluated && frame.parentActive && !frame.branchTaken && evaluateCondition(directive)
                    frame.active = condition
                    if (condition) {
                        frame.branchTaken = true
                        selected[frame.id] = frame.branchIndex
                    }
                }
                PreprocessorDirectiveKind.ELSE -> {
                    val frame = frames.last()
                    frame.branchIndex++
                    frame.active = frame.evaluated && frame.parentActive && !frame.branchTaken
                    if (frame.active) {
                        frame.branchTaken = true
                        selected[frame.id] = frame.branchIndex
                    }
                }
                PreprocessorDirectiveKind.ENDIF -> frames.removeAt(frames.lastIndex)
                PreprocessorDirectiveKind.DEFINE -> if (active && directive.macroName != null) {
                    val canonical = settingAliases[directive.macroName]
                    if (canonical == null) {
                        macros[directive.macroName] = Macro(directive.macroBody.orEmpty(), directive.macroFunctionLike)
                    }
                    activeDirectives += directive.index
                }
                PreprocessorDirectiveKind.UNDEF -> if (active && settingAliases[directive.macroName] == null) {
                    directive.macroName?.let(macros::remove)
                    activeDirectives += directive.index
                }
                else -> if (active) activeDirectives += directive.index
            }
        }
        return StructuralEvaluation(selected, activeDirectives, macros.keys.toSet())
    }

    private fun evaluateCondition(directive: PreprocessorDirective): Boolean {
        return when (directive.kind) {
            PreprocessorDirectiveKind.IFDEF -> isDefined(directive.macroName.orEmpty())
            PreprocessorDirectiveKind.IFNDEF -> !isDefined(directive.macroName.orEmpty())
            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.ELIF,
            -> {
                val expression = directive.expression.orEmpty().substringBefore("//").trim()
                val value = StructuralExpressionParser(expression, ::resolveIdentifier, ::isDefined).parse()
                    ?: throw StructuralEvaluationException(
                        "$sourceName:${directive.sourceLine}: cannot evaluate structural preprocessor expression '$expression' " +
                            "for ${assignment.toSortedMap()}",
                    )
                value != 0L
            }
            else -> false
        }
    }

    private fun isDefined(name: String): Boolean {
        val canonical = settingAliases[name]
        if (canonical != null) {
            val setting = settings.getValue(canonical)
            val value = assignment[canonical] ?: setting.defaultValue
            return !setting.presenceToggle || value.asLong() != 0L
        }
        return name in macros
    }

    private fun resolveIdentifier(name: String, visiting: MutableSet<String> = linkedSetOf()): Long? {
        val canonical = settingAliases[name]
        if (canonical != null) {
            return (assignment[canonical] ?: settings.getValue(canonical).defaultValue).asLong()
        }
        if (name == "true") return 1L
        if (name == "false") return 0L
        val macro = macros[name] ?: return 0L
        if (macro.functionLike || !visiting.add(name)) return null
        val value = StructuralExpressionParser(
            stripStructuralComments(macro.body),
            { child -> resolveIdentifier(child, visiting) },
            ::isDefined,
        ).parse()
        visiting.remove(name)
        return value
    }
}

internal class StructuralExpressionParser(
    private val source: String,
    private val identifierValue: (String) -> Long?,
    private val isDefined: (String) -> Boolean,
) {
    private var cursor = 0

    fun parse(): Long? = runCatching {
        val value = conditional()
        whitespace()
        require(cursor == source.length)
        value
    }.getOrNull()

    private fun conditional(): Long {
        val condition = logicalOr()
        if (!consume("?")) return condition
        val whenTrue = conditional()
        require(consume(":"))
        val whenFalse = conditional()
        return if (condition != 0L) whenTrue else whenFalse
    }

    private fun logicalOr(): Long {
        var value = logicalAnd()
        while (consume("||")) value = if (logicalAnd() != 0L || value != 0L) 1L else 0L
        return value
    }

    private fun logicalAnd(): Long {
        var value = bitwiseOr()
        while (consume("&&")) value = if (bitwiseOr() != 0L && value != 0L) 1L else 0L
        return value
    }

    private fun bitwiseOr(): Long {
        var value = bitwiseXor()
        while (peek("|") && !peek("||")) {
            consume("|")
            value = value or bitwiseXor()
        }
        return value
    }

    private fun bitwiseXor(): Long {
        var value = bitwiseAnd()
        while (consume("^")) value = value xor bitwiseAnd()
        return value
    }

    private fun bitwiseAnd(): Long {
        var value = equality()
        while (peek("&") && !peek("&&")) {
            consume("&")
            value = value and equality()
        }
        return value
    }

    private fun equality(): Long {
        var value = relational()
        while (true) {
            value = when {
                consume("==") -> if (value == relational()) 1 else 0
                consume("!=") -> if (value != relational()) 1 else 0
                else -> return value
            }
        }
    }

    private fun relational(): Long {
        var value = shift()
        while (true) {
            value = when {
                consume("<=") -> if (value <= shift()) 1 else 0
                consume(">=") -> if (value >= shift()) 1 else 0
                consume("<") -> if (value < shift()) 1 else 0
                consume(">") -> if (value > shift()) 1 else 0
                else -> return value
            }
        }
    }

    private fun shift(): Long {
        var value = additive()
        while (true) {
            value = when {
                consume("<<") -> value shl additive().toInt()
                consume(">>") -> value shr additive().toInt()
                else -> return value
            }
        }
    }

    private fun additive(): Long {
        var value = multiplicative()
        while (true) {
            value = when {
                consume("+") -> Math.addExact(value, multiplicative())
                consume("-") -> Math.subtractExact(value, multiplicative())
                else -> return value
            }
        }
    }

    private fun multiplicative(): Long {
        var value = unary()
        while (true) {
            value = when {
                consume("*") -> Math.multiplyExact(value, unary())
                consume("/") -> value / unary()
                consume("%") -> value % unary()
                else -> return value
            }
        }
    }

    private fun unary(): Long = when {
        consume("!") -> if (unary() == 0L) 1L else 0L
        consume("~") -> unary().inv()
        consume("+") -> unary()
        consume("-") -> Math.negateExact(unary())
        else -> primary()
    }

    private fun primary(): Long {
        whitespace()
        if (consume("(")) return conditional().also { require(consume(")")) }
        if (source.startsWith("defined", cursor) && !source.getOrNull(cursor + 7).isIdentifierPart()) {
            cursor += 7
            whitespace()
            val parenthesized = consume("(")
            val name = requireNotNull(STRUCTURAL_IDENTIFIER.matchAt(source, cursor)).value
            cursor += name.length
            if (parenthesized) require(consume(")"))
            return if (isDefined(name)) 1L else 0L
        }
        STRUCTURAL_IDENTIFIER.matchAt(source, cursor)?.let { match ->
            cursor = match.range.last + 1
            return requireNotNull(identifierValue(match.value))
        }
        val number = requireNotNull(STRUCTURAL_INTEGER.matchAt(source, cursor))
        cursor = number.range.last + 1
        return requireNotNull(number.value.asLong())
    }

    private fun peek(token: String): Boolean {
        whitespace()
        return source.startsWith(token, cursor)
    }

    private fun consume(token: String): Boolean {
        whitespace()
        if (!source.startsWith(token, cursor)) return false
        cursor += token.length
        return true
    }

    private fun whitespace() {
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
    }
}

internal object ShaderStructuralSignatureExtractor {
    fun extract(
        stage: ShaderStage,
        source: String,
        requiredCapabilities: List<String>,
        localSizeFallback: LocalSizeAbiSignature?,
    ): ShaderStructuralSignature {
        val declarations = topLevelDeclarations(source)
        val resources = declarations.filter { declaration ->
            val lexical = stripStructuralComments(declaration)
            RESOURCE_KEYWORD.containsMatchIn(lexical) && !SHADESMITH_RESOURCE_MARKER.containsMatchIn(lexical)
        }
            .map(::normalizeStructuralSignatureText).distinct().sorted()
        val interfaces = declarations.filter { declaration ->
            val lexical = stripStructuralComments(declaration)
            INTERFACE_KEYWORD.containsMatchIn(lexical) && !RESOURCE_KEYWORD.containsMatchIn(lexical) &&
                !LOCAL_SIZE_DECLARATION.containsMatchIn(lexical)
        }.map(::normalizeStructuralSignatureText).distinct().sorted()
        val functions = FUNCTION_ABI.findAll(maskStructuralCommentsAndStrings(source))
            .map { normalizeStructuralSignatureText(it.value.substringBefore('{')) }
            .filterNot(::isMainFunctionAbi)
            .toList()
        val prototypes = declarations.map(::normalizeStructuralSignatureText)
            .filter { FUNCTION_PROTOTYPE.containsMatchIn(it) }
            .filterNot(::isMainFunctionAbi)
        val types = declarations.map(::normalizeStructuralSignatureText)
            .filter { STRUCT_DECLARATION.containsMatchIn(it) }
        return ShaderStructuralSignature(
            stage,
            requiredCapabilities.distinct().sorted(),
            localSizeFallback,
            resources,
            interfaces,
            (functions + prototypes + types).distinct().sorted(),
        )
    }
}

private class StructuralLineMap(private val source: String) {
    data class Line(val number: Int, val text: String, val range: IntRange, val braceDepth: Int)

    private val starts = buildList {
        add(0)
        STRUCTURAL_LINE_ENDING.findAll(source).forEach { add(it.range.last + 1) }
    }.distinct()
    val lines: List<Line>

    init {
        val depths = lineBraceDepths(source)
        lines = starts.mapIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { source.length }
            Line(index + 1, source.substring(start, end), start until end, depths.getOrElse(index) { 0 })
        }
    }

    fun directiveRange(directive: PreprocessorDirective): IntRange {
        val start = starts[directive.sourceLine - 1]
        val end = if (directive.endLine < starts.size) starts[directive.endLine] else source.length
        return start until end
    }
}

internal enum class TopLevelGlslBlockKind {
    FUNCTION,
    ABI,
    OTHER,
}

internal data class TopLevelGlslBlock(
    val kind: TopLevelGlslBlockKind,
    val prefixRange: IntRange,
    val fullRange: IntRange,
)

internal fun scanTopLevelGlslBlocks(source: String, masked: String): List<TopLevelGlslBlock> {
    require(source.length == masked.length)
    val result = mutableListOf<TopLevelGlslBlock>()
    var depth = 0
    var boundary = 0
    var prefixStart = 0
    var prefixEnd = 0
    var blockKind = TopLevelGlslBlockKind.OTHER
    var parenthesisDepth = 0
    var cursor = 0
    while (cursor < masked.length) {
        when (masked[cursor]) {
            '(' -> if (depth == 0) parenthesisDepth++
            ')' -> if (depth == 0 && parenthesisDepth > 0) parenthesisDepth--
            '{' -> {
                if (depth == 0) {
                    prefixStart = boundary
                    prefixEnd = cursor
                    val prefix = masked.substring(prefixStart, prefixEnd).trim()
                    blockKind = when {
                        prefix.endsWith(')') -> TopLevelGlslBlockKind.FUNCTION
                        !hasTopLevelAssignment(prefix) -> TopLevelGlslBlockKind.ABI
                        else -> TopLevelGlslBlockKind.OTHER
                    }
                }
                depth++
            }
            '}' -> {
                if (depth > 0) depth--
                if (depth == 0) {
                    var fullEnd = cursor
                    if (blockKind == TopLevelGlslBlockKind.ABI) {
                        while (fullEnd + 1 < masked.length && masked[fullEnd + 1].isWhitespace()) fullEnd++
                        while (fullEnd + 1 < masked.length && masked[fullEnd + 1] != ';') fullEnd++
                        if (masked.getOrNull(fullEnd + 1) == ';') fullEnd++
                    }
                    result += TopLevelGlslBlock(
                        blockKind,
                        prefixStart until prefixEnd,
                        prefixStart..fullEnd,
                    )
                    if (blockKind == TopLevelGlslBlockKind.FUNCTION) boundary = cursor + 1
                }
            }
            ';' -> if (depth == 0) boundary = cursor + 1
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

private fun hasTopLevelAssignment(source: String): Boolean {
    var parenDepth = 0
    var bracketDepth = 0
    source.forEachIndexed { index, char ->
        when (char) {
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '=' -> if (
                parenDepth == 0 && bracketDepth == 0 &&
                source.getOrNull(index - 1) !in listOf('!', '<', '>', '=') &&
                source.getOrNull(index + 1) != '='
            ) {
                return true
            }
        }
    }
    return false
}

private fun firstStructuralNonWhitespace(source: String, start: Int, end: Int): Int {
    var cursor = start
    while (cursor < end && source[cursor].isWhitespace()) cursor++
    return cursor
}

internal fun maskGlslRanges(text: String, sourceOffset: Int, ranges: List<IntRange>): String {
    if (ranges.none { it.first < sourceOffset + text.length && sourceOffset <= it.last }) return text
    val result = text.toCharArray()
    ranges.forEach { range ->
        val start = maxOf(range.first, sourceOffset) - sourceOffset
        val end = minOf(range.last + 1, sourceOffset + text.length) - sourceOffset
        for (index in start until end) {
            if (result[index] !in "\r\n") result[index] = ' '
        }
    }
    return result.concatToString()
}

private fun lineBraceDepths(source: String): List<Int> {
    val masked = maskStructuralCommentsAndStrings(source)
    val result = mutableListOf<Int>()
    var depth = 0
    var cursor = 0
    while (cursor < masked.length) {
        result += depth
        while (cursor < masked.length && masked[cursor] !in "\r\n") {
            when (masked[cursor]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
            cursor++
        }
        if (masked.getOrNull(cursor) == '\r' && masked.getOrNull(cursor + 1) == '\n') cursor += 2 else cursor++
    }
    if (source.endsWith('\n') || source.endsWith('\r')) result += depth
    return result
}

private fun topLevelDeclarations(source: String): List<String> {
    val masked = maskStructuralCommentsAndStrings(source)
    val result = mutableListOf<String>()
    var depth = 0
    var boundary = 0
    var functionBody = false
    var cursor = 0
    while (cursor < masked.length) {
        when (masked[cursor]) {
            '{' -> {
                if (depth == 0) {
                    val prefix = masked.substring(boundary, cursor).trimEnd()
                    functionBody = prefix.endsWith(')')
                }
                depth++
            }
            '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && functionBody) {
                    boundary = cursor + 1
                    functionBody = false
                }
            }
            ';' -> if (depth == 0) {
                val declaration = source.substring(boundary, cursor + 1).lineSequence()
                    .filterNot { it.trimStart().startsWith('#') }
                    .joinToString("\n").trim()
                if (declaration.isNotEmpty()) result += declaration
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

private fun structuralSymbols(text: String, settingNames: Set<String>, macroNames: Set<String>): Set<String> {
    val masked = maskStructuralCommentsAndStrings(text)
    return buildSet {
        structuralIdentifiers(masked).filterTo(this) {
            it !in STRUCTURAL_KEYWORDS && it !in settingNames && it !in macroNames && !it.startsWith("SM_SETTING_") &&
                !STRUCTURAL_BUILTIN_TOKEN.matches(it)
        }
        STRUCTURAL_LAYOUT.findAll(masked).forEach { layout ->
            STRUCTURAL_LAYOUT_SLOT.findAll(layout.groupValues[1]).forEach { slot ->
                add("layout:${slot.groupValues[1]}=${normalizeStructuralText(slot.groupValues[2])}")
            }
        }
    }
}

private fun structuralIdentifiers(text: String): Set<String> {
    return STRUCTURAL_IDENTIFIER.findAll(stripStructuralComments(text)).mapTo(linkedSetOf()) { it.value }
}

private fun directiveCondition(directive: PreprocessorDirective): String {
    return when (directive.kind) {
        PreprocessorDirectiveKind.IFDEF -> "defined(${directive.macroName})"
        PreprocessorDirectiveKind.IFNDEF -> "!defined(${directive.macroName})"
        PreprocessorDirectiveKind.IF,
        PreprocessorDirectiveKind.ELIF,
        -> directive.expression.orEmpty().substringBefore("//").trim()
        else -> "true"
    }
}

private fun applyStructuralReplacements(source: String, replacements: List<StructuralReplacement>): String {
    if (replacements.isEmpty()) return source
    val ordered = replacements.distinctBy { it.start to it.end }.sortedBy { it.start }
    return buildString(source.length) {
        var cursor = 0
        ordered.forEach { replacement ->
            require(replacement.start >= cursor && replacement.end in replacement.start..source.length) {
                "Structural replacements overlap or exceed the source: $replacement after offset $cursor"
            }
            append(source, cursor, replacement.start)
            append(replacement.text)
            cursor = replacement.end
        }
        append(source, cursor, source.length)
    }
}

private data class ReanchoredStructuralContracts(
    val contracts: List<IrisSourceContractSlice>,
    val issue: String?,
)

private fun reanchorContracts(
    basePlan: ShaderCompilerCopyPlan,
    source: String,
    structuralRanges: List<IntRange>,
    locations: List<Pair<IrisSourceContractSlice, IntRange>>,
): ReanchoredStructuralContracts {
    val retainedLocations = locations.filterNot { (_, range) ->
        structuralRanges.any { it.containsRange(range) }
    }
    val contractRanges = retainedLocations.map { it.second }
    val excluded = structuralRanges + contractRanges
    val anchors = findStableAnchors(source).filter { anchor ->
        anchor.anchor.kind != IrisAnchorKind.DECLARATION && excluded.none { it.overlaps(anchor.range) }
    }
    val version = anchors.singleOrNull { it.anchor.kind == IrisAnchorKind.VERSION }
    val main = anchors.singleOrNull { it.anchor == IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main") }
    var issue: String? = null
    val contracts = retainedLocations.map { (contract, range) ->
        val before = version?.takeIf { it.range.last < range.first }
        val after = main?.takeIf { it.range.first > range.last }
        if (before == null && after == null) {
            issue = "${basePlan.sourceName}:${contract.sourceLine}: Iris ${contract.kind} contract has no structural-safe anchor"
        }
        val placement = when {
            before == null -> IrisAnchorPlacement.BEFORE_AFTER
            else -> IrisAnchorPlacement.AFTER_BEFORE
        }
        contract.copy(
            beforeAnchor = before?.anchor,
            afterAnchor = after?.anchor,
            placement = placement,
        )
    }
    return ReanchoredStructuralContracts(contracts, issue)
}

private fun mergeStructuralRanges(source: String, ranges: List<IntRange>): List<IntRange> {
    if (ranges.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    ranges.sortedBy { it.first }.forEach { range ->
        val previous = result.lastOrNull()
        if (
            previous != null &&
            (range.first <= previous.last + 1 || source.substring(previous.last + 1, range.first).all(Char::isWhitespace))
        ) {
            result[result.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            result += range
        }
    }
    return result
}

internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
private fun IntRange.containsRange(other: IntRange): Boolean = first <= other.first && last >= other.last

private data class StructuralReplacement(val start: Int, val end: Int, val text: String)

private fun normalizeStructuralText(value: String): String = value.replace(STRUCTURAL_WHITESPACE, " ").trim()
private fun normalizeStructuralSignatureText(value: String): String {
    val normalized = normalizeStructuralText(stripStructuralComments(value))
    return STRUCTURAL_ARRAY_EXPRESSION.replace(normalized) { match ->
        val expression = match.groupValues[1].trim()
        StructuralExpressionParser(expression, { null }, { false }).parse()?.let { "[$it]" } ?: match.value
    }
}
private fun isMainFunctionAbi(value: String): Boolean = "\\bmain\\s*\\(".toRegex().containsMatchIn(value)

private fun stripStructuralComments(value: String): String {
    return STRUCTURAL_BLOCK_COMMENT.replace(STRUCTURAL_LINE_COMMENT.replace(value, " "), " ")
}

private fun maskStructuralCommentsAndStrings(source: String): String {
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

private fun countTargetStructuralIdentifiers(
    source: String,
    targets: Set<String>,
): Map<String, Int> {
    if (targets.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, Int>()
    var lineComment = false
    var blockComment = false
    var quote: Char? = null
    var cursor = 0
    while (cursor < source.length) {
        val char = source[cursor]
        val next = source.getOrNull(cursor + 1)
        when {
            lineComment -> {
                if (char in "\r\n") lineComment = false
                cursor++
            }
            blockComment -> {
                if (char == '*' && next == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor++
                }
            }
            quote != null -> {
                if (char == '\\') {
                    cursor += minOf(2, source.length - cursor)
                } else {
                    if (char == quote) quote = null
                    cursor++
                }
            }
            char == '/' && next == '/' -> {
                lineComment = true
                cursor += 2
            }
            char == '/' && next == '*' -> {
                blockComment = true
                cursor += 2
            }
            char == '"' || char == '\'' -> {
                quote = char
                cursor++
            }
            char.isStructuralIdentifierStart() -> {
                val start = cursor++
                while (cursor < source.length && source[cursor].isStructuralIdentifierPart()) cursor++
                if (source.regionMatches(start, STRUCTURAL_SETTING_PREFIX, 0, STRUCTURAL_SETTING_PREFIX.length)) {
                    val token = source.substring(start, cursor)
                    if (token in targets) result[token] = result.getOrDefault(token, 0) + 1
                }
            }
            else -> cursor++
        }
    }
    return result
}

private fun Char.isStructuralIdentifierStart(): Boolean =
    this == '_' || this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isStructuralIdentifierPart(): Boolean =
    isStructuralIdentifierStart() || this in '0'..'9'

private fun String.asPreprocessorValue(): String = when (trim()) {
    "true" -> "1"
    "false" -> "0"
    else -> trim()
}

private fun String.asLong(): Long? {
    val value = trim().removeSuffix("u").removeSuffix("U").removeSuffix("l").removeSuffix("L")
    return when {
        value.equals("true", ignoreCase = true) -> 1L
        value.equals("false", ignoreCase = true) -> 0L
        value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16)
        else -> value.toLongOrNull()
    }
}

private fun Char?.isIdentifierPart(): Boolean = this != null && (this == '_' || isLetterOrDigit())

private fun shortHash(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private const val MAX_STRUCTURAL_MODULES = 32
private const val MAX_STRUCTURAL_MATERIALIZATION_ROWS = 64
private const val MAX_COMPONENT_ASSIGNMENTS = 4096
private const val STRUCTURAL_SETTING_PREFIX = "SM_SETTING_"
private const val STRUCTURAL_LAYOUT_PREFIX = "layout:"
private val STRUCTURAL_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
private val STRUCTURAL_DEFINED_MACRO =
    "\\bdefined\\s*(?:\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)|([A-Za-z_][A-Za-z0-9_]*))".toRegex()
private val STRUCTURAL_SPECIALIZATION_DECLARATION =
    ("(?m)^[\\t ]*layout\\s*\\(\\s*constant_id\\s*=\\s*[0-9]+\\s*\\)\\s*" +
        "const\\s+(?:bool|int|float)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=[^;\\r\\n]*;" +
        "[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)").toRegex()
private val STRUCTURAL_VERSION_LINE = "(?m)^[ \\t]*#version\\b[^\\r\\n]*".toRegex()
private val STRUCTURAL_INTEGER = "(?:0[xX][0-9A-Fa-f]+|[0-9]+)[uUlL]*".toRegex()
private val STRUCTURAL_LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
private val STRUCTURAL_LINE_COMMENT = "//[^\\r\\n]*".toRegex()
private val STRUCTURAL_BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/".toRegex()
private val STRUCTURAL_WHITESPACE = "\\s+".toRegex()
private val STRUCTURAL_ARRAY_EXPRESSION = "\\[([^]\\r\\n]+)]".toRegex()
private val EXTENSION = "#\\s*extension\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(require|enable|warn|disable)".toRegex()
private val LAYOUT = "\\blayout\\s*\\(".toRegex()
private val CONSTANT_ID = "\\bconstant_id\\s*=".toRegex()
private val LOCAL_SIZE_ID = "\\blocal_size_[xyz]_id\\s*=".toRegex()
private val LOCAL_SIZE_DECLARATION = "\\blocal_size_[xyz](?:_id)?\\b".toRegex()
private val ABI_DECLARATION = "\\b(?:uniform|buffer|in|out|attribute|varying|shared)\\b".toRegex()
private val ABI_BLOCK_DECLARATION = "\\b(?:uniform|buffer)\\b[^{;]*\\{".toRegex()
private val INTERFACE_DECLARATION = "\\b(?:in|out|attribute|varying)\\b".toRegex()
private val FUNCTION_SIGNATURE = "(?:[A-Za-z_][A-Za-z0-9_]*\\s+)+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^;{}]*\\)\\s*\\{".toRegex()
private val FUNCTION_ABI = "(?m)^[ \\t]*(?:[A-Za-z_][A-Za-z0-9_]*[ \\t]+)+[A-Za-z_][A-Za-z0-9_]*[ \\t]*\\([^;{}]*\\)\\s*\\{".toRegex()
private val FUNCTION_PROTOTYPE = "^(?:[A-Za-z_][A-Za-z0-9_]*\\s+)+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^;{}]*\\)\\s*;$".toRegex()
private val RESOURCE_KEYWORD = "\\b(?:uniform|buffer|shared)\\b".toRegex()
private val SHADESMITH_RESOURCE_MARKER = "\\bshadesmith_resource_[A-Za-z0-9_]*\\b".toRegex()
private val INTERFACE_KEYWORD = "\\b(?:in|out|attribute|varying)\\b".toRegex()
private val STRUCT_DECLARATION = "\\bstruct\\b".toRegex()
private val STRUCTURAL_LAYOUT = "\\blayout\\s*\\(([^)]*)\\)".toRegex()
private val STRUCTURAL_LAYOUT_SLOT = "\\b(binding|location|component|index|set)\\s*=\\s*([^,)]*)".toRegex()
private val STRUCTURAL_BUILTIN_TOKEN = (
    "(?:[iub]?sampler[A-Za-z0-9_]*|[iu]?image[A-Za-z0-9_]*|[biud]?vec[234]|d?mat[234](?:x[234])?|" +
        "r(?:g|gb|gba)?(?:8|16|32)(?:f|i|ui|snorm)?|depth_component(?:16|24|32f)?)"
    ).toRegex(RegexOption.IGNORE_CASE)
private val STRUCTURAL_KEYWORDS = setOf(
    "defined", "true", "false", "layout", "constant_id", "uniform", "buffer", "shared", "in", "out",
    "attribute", "varying", "readonly", "writeonly", "coherent", "volatile", "restrict", "flat", "smooth",
    "centroid", "sample", "patch", "invariant", "precise", "highp", "mediump", "lowp", "const", "struct",
    "void", "bool", "int", "uint", "float", "double", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
    "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "sampler2D",
    "usampler2D", "isampler2D", "image2D", "uimage2D", "iimage2D", "main", "return", "if", "else",
    "ifdef", "ifndef", "elif", "endif", "define", "undef", "extension", "version", "pragma", "require",
    "enable", "warn", "disable",
    "binding", "location", "component", "index", "set", "offset", "align", "std140", "std430", "scalar",
    "packed", "row_major", "column_major", "push_constant", "early_fragment_tests", "local_size_x", "local_size_y",
    "local_size_z", "local_size_x_id", "local_size_y_id", "local_size_z_id",
)
