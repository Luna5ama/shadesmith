package dev.luna5ama.shadesmith

internal enum class IrisSourceContractKind {
    OPTION_DEFINITION,
    HOST_DECLARATION,
    COMMENT_DIRECTIVE,
    EXTENSION,
    PRAGMA,
    LOCAL_SIZE,
    CONDITIONAL_CONTRACT,
}

internal enum class IrisAnchorKind {
    VERSION,
    DECLARATION,
    FUNCTION,
}

internal enum class IrisAnchorPlacement {
    AFTER_BEFORE,
    BEFORE_AFTER,
}

internal data class IrisSourceAnchor(val kind: IrisAnchorKind, val name: String)

internal data class IrisSourceContractSlice(
    val ordinal: Int,
    val kind: IrisSourceContractKind,
    val exactText: String,
    val sourceLine: Int,
    val beforeAnchor: IrisSourceAnchor?,
    val afterAnchor: IrisSourceAnchor?,
    val placement: IrisAnchorPlacement,
)

internal data class LocalSizeAbiSignature(val x: Int, val y: Int, val z: Int)

internal data class LocalSizeAbiAssignment(
    val settings: Map<String, String>,
    val signature: LocalSizeAbiSignature,
)

internal data class LocalSizeSpecializationContract(
    val defaultSignature: LocalSizeAbiSignature,
    val signatures: List<LocalSizeAbiSignature>,
    val settingDependencies: Set<String>,
    val assignments: List<LocalSizeAbiAssignment>,
    val specializationIds: Map<Char, Int>,
    val compilerLayout: String,
    val fallbackRequired: Boolean,
) {
    fun signatureFor(values: Map<String, String>): LocalSizeAbiSignature? {
        return assignments.firstOrNull { row ->
            row.settings.all { (name, value) -> values[name] == value }
        }?.signature
    }
}

internal enum class IrisStructuralIssueKind {
    LOCAL_SIZE_FALLBACK,
    UNSUPPORTED,
}

internal data class IrisStructuralIssue(
    val kind: IrisStructuralIssueKind,
    val reason: String,
)

internal data class IrisDerivedMacroContract(
    val sourceName: String,
    val compilerName: String,
    val compilerExpression: String,
)

internal sealed interface IrisContractRestoration {
    data class Restored(val source: String) : IrisContractRestoration
    data class StructuralPreservation(val reason: String) : IrisContractRestoration
}

internal data class IrisShaderContractPlan(
    val sourceName: String,
    val originalVersion: String,
    val compilerSource: String,
    val contracts: List<IrisSourceContractSlice>,
    val derivedMacros: List<IrisDerivedMacroContract>,
    val localSize: LocalSizeSpecializationContract?,
    val structuralIssues: List<IrisStructuralIssue>,
    private val compilerPrelude: String,
    private val compilerSettings: List<ShaderSetting>,
) {
    val structuralReason: String?
        get() = structuralIssues.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.reason }

    fun forStructuralModule(
        source: String,
        fallbackSignature: LocalSizeAbiSignature?,
    ): IrisShaderContractPlan {
        if (fallbackSignature == null) return copy(compilerSource = source)
        val local = requireNotNull(localSize) { "$sourceName has no local-size contract to specialize" }
        require(local.fallbackRequired) { "$sourceName local-size contract does not require a structural fallback" }
        val fixedLayout = renderFixedLocalSize(fallbackSignature)
        require(local.compilerLayout in source) {
            "$sourceName compiler copy is missing the local-size fallback slot"
        }
        require(local.compilerLayout in compilerPrelude) {
            "$sourceName compiler prelude is missing the local-size fallback slot"
        }
        return copy(
            compilerSource = source.replace(local.compilerLayout, fixedLayout),
            localSize = local.copy(
                defaultSignature = fallbackSignature,
                signatures = listOf(fallbackSignature),
                specializationIds = emptyMap(),
                compilerLayout = fixedLayout,
                fallbackRequired = false,
            ),
            structuralIssues = structuralIssues.filterNot {
                it.kind == IrisStructuralIssueKind.LOCAL_SIZE_FALLBACK
            },
            compilerPrelude = compilerPrelude.replace(local.compilerLayout, fixedLayout),
        )
    }

    fun restore(decompiledSource: String): IrisContractRestoration {
        var result = normalizeCompilerText(decompiledSource)
        if (localSize != null) result = LOCAL_SIZE_LAYOUT.replace(result, "")
        val version = VERSION_LINE.find(result)
            ?: return IrisContractRestoration.StructuralPreservation(
                "$sourceName: optimized GLSL has no #version anchor for Iris contract restoration",
            )
        result = result.replaceRange(version.range, originalVersion)

        val anchors = findStableAnchors(result)
        data class PendingInsertion(val offset: Int, val contract: IrisSourceContractSlice)
        val pending = mutableListOf<PendingInsertion>()
        contracts.forEach { contract ->
            val before = contract.beforeAnchor?.let { anchor ->
                val matches = anchors.filter { it.anchor == anchor }
                if (matches.size != 1) {
                    return IrisContractRestoration.StructuralPreservation(
                        anchorFailure(contract, anchor, matches.size),
                    )
                }
                matches.single()
            }
            val after = contract.afterAnchor?.let { anchor ->
                val matches = anchors.filter { it.anchor == anchor }
                if (matches.size != 1) {
                    return IrisContractRestoration.StructuralPreservation(
                        anchorFailure(contract, anchor, matches.size),
                    )
                }
                matches.single()
            }
            if (before == null && after == null) {
                return IrisContractRestoration.StructuralPreservation(
                    "$sourceName:${contract.sourceLine}: Iris ${contract.kind} contract has no stable restoration anchor",
                )
            }
            if (before != null && after != null && before.range.last >= after.range.first) {
                return IrisContractRestoration.StructuralPreservation(
                    "$sourceName:${contract.sourceLine}: Iris ${contract.kind} anchors changed relative order",
                )
            }
            val offset = when (contract.placement) {
                IrisAnchorPlacement.AFTER_BEFORE -> requireNotNull(before).range.last + 1
                IrisAnchorPlacement.BEFORE_AFTER -> requireNotNull(after).range.first
            }
            pending += PendingInsertion(offset, contract)
        }

        pending.groupBy { it.offset }.entries.sortedByDescending { it.key }.forEach { (offset, insertions) ->
            val exact = insertions.sortedBy { it.contract.ordinal }.joinToString("") { it.contract.exactText }
            val prefix = if (offset > 0 && result[offset - 1] !in "\r\n" && exact.firstOrNull() !in listOf('\r', '\n')) "\n" else ""
            val suffix = if (offset < result.length && result[offset] !in "\r\n" && exact.lastOrNull() !in listOf('\r', '\n')) "\n" else ""
            result = result.substring(0, offset) + prefix + exact + suffix + result.substring(offset)
        }
        return IrisContractRestoration.Restored(result.trimEnd() + "\n")
    }

    fun prepareCompilerSource(restoredSource: String): String {
        var result = restoredSource
        val alreadyCompilerSource = COMPILER_MARKER in result
        if (!alreadyCompilerSource) {
            contracts.sortedByDescending { it.exactText.length }.forEach { contract ->
                val first = result.indexOf(contract.exactText)
                if (first >= 0) result = result.removeRange(first, first + contract.exactText.length)
            }
            if (localSize != null) result = LOCAL_SIZE_LAYOUT.replace(result, "")
        }
        val version = VERSION_LINE.find(result)
            ?: throw IllegalArgumentException("$sourceName: restored source has no #version directive")
        result = result.replaceRange(version.range, "#version 460 core")
        if (!alreadyCompilerSource) {
            val missingSettings = compilerSettings.filterNot { setting ->
                identifierRegex(setting.compilerName).containsMatchIn(result)
            }
            val settingDeclarations = renderSettingDeclarations(missingSettings)
            val validationPrelude = if (settingDeclarations.isEmpty()) {
                compilerPrelude
            } else {
                compilerPrelude.replace(
                    "$COMPILER_MARKER\n",
                    "$COMPILER_MARKER\n$settingDeclarations",
                )
            }
            result = insertAfterVersion(result, validationPrelude)
        }
        return normalizeCompilerText(result)
    }

    private fun anchorFailure(contract: IrisSourceContractSlice, anchor: IrisSourceAnchor, matches: Int): String {
        val state = if (matches == 0) "missing" else "ambiguous ($matches matches)"
        return "$sourceName:${contract.sourceLine}: Iris ${contract.kind} ${anchor.kind}:${anchor.name} anchor is $state"
    }
}

internal object IrisShaderContractExtractor {
    fun extract(
        source: String,
        sourceName: String,
        settings: List<ShaderSetting> = emptyList(),
        usedSpecializationIds: Set<Int> = emptySet(),
        localSizeIdSupported: Boolean = true,
        localSizeProbeDiagnostic: String? = null,
    ): IrisShaderContractPlan {
        val lines = ContractLineMap(source)
        val protection = PreprocessorProtection.protect(source, sourceName)
        val directives = protection.directives.map { directive ->
            ContractDirective(directive, lines.directiveRange(directive))
        }
        val versions = directives.filter { it.directive.kind == PreprocessorDirectiveKind.VERSION }
        require(versions.size == 1) { "$sourceName: expected exactly one #version directive, found ${versions.size}" }
        val version = versions.single()
        val originalVersion = source.substring(version.range).trimEnd('\r', '\n')
        val conditionalGroups = buildConditionalGroups(directives)
        val settingsByName = settings.associateBy { it.name }
        val macroDefinitions = collectMacroDefinitions(source, directives, conditionalGroups, settingsByName)
        val lexical = ContractLexicalMap(source)
        val extractionErrors = mutableListOf<String>()

        val localLayouts = LOCAL_SIZE_LAYOUT.findAll(source)
            .filter { lexical.isTopLevelCode(it.range.first) }
            .map { match ->
                val range = lines.fullLineRange(match.range)
                LocalLayout(
                    range,
                    parseLocalSizeItems(match.groupValues[1]),
                    predicateAt(range.first, conditionalGroups, settingsByName),
                    LOCAL_SIZE_ID_ITEM.containsMatchIn(match.groupValues[1]),
                )
            }
            .toList()
        val hostDeclarations = HOST_CONST_START.findAll(source)
            .filter { lexical.isTopLevelCode(it.range.first) && isHostDeclarationName(it.groupValues[1]) }
            .map { match ->
                val semicolon = lexical.findCodeCharacter(';', match.range.last + 1)
                    ?: throw IllegalArgumentException(
                        "$sourceName:${lines.lineAt(match.range.first)}: unterminated Iris host declaration ${match.groupValues[1]}",
                    )
                val range = lines.fullLineRange(match.range.first..semicolon)
                HostDeclaration(match.groupValues[1], range, source.substring(match.range.first, semicolon + 1))
            }
            .toList()
        val hostDeclarationRanges = hostDeclarations.map { it.range }
        hostDeclarations.forEach { declaration ->
            val references = identifierOccurrences(source, declaration.name, lexical)
                .filterNot { offset -> hostDeclarationRanges.any { offset in it } }
                .filterNot { offset -> directives.any { offset in it.range } }
            require(references.isEmpty()) {
                "$sourceName:${lines.lineAt(declaration.range.first)}: Iris host declaration ${declaration.name} is referenced by shader code"
            }
        }

        val atoms = mutableListOf<ContractAtom>()
        directives.filter {
            (it.directive.kind == PreprocessorDirectiveKind.DEFINE ||
                it.directive.kind == PreprocessorDirectiveKind.DISABLED_DEFINE) &&
                it.directive.macroName?.startsWith("SETTING_") == true
        }.forEach { atoms += ContractAtom(IrisSourceContractKind.OPTION_DEFINITION, it.range, mask = false) }
        directives.filter {
            it.directive.kind == PreprocessorDirectiveKind.DISABLED_DEFINE &&
                it.directive.macroName?.startsWith("SETTING_") != true
        }.forEach { atoms += ContractAtom(IrisSourceContractKind.OPTION_DEFINITION, it.range, mask = false) }
        directives.filter { it.directive.kind == PreprocessorDirectiveKind.EXTENSION }
            .forEach { atoms += ContractAtom(IrisSourceContractKind.EXTENSION, it.range, mask = true) }
        directives.filter { it.directive.kind == PreprocessorDirectiveKind.PRAGMA }
            .forEach { atoms += ContractAtom(IrisSourceContractKind.PRAGMA, it.range, mask = false) }
        hostDeclarations.forEach { atoms += ContractAtom(IrisSourceContractKind.HOST_DECLARATION, it.range, mask = true) }
        localLayouts.forEach { atoms += ContractAtom(IrisSourceContractKind.LOCAL_SIZE, it.range, mask = true) }
        IRIS_COMMENT_DIRECTIVE.findAll(source).forEach { match ->
            if (!lexical.isTopLevel(match.range.first)) {
                extractionErrors += "$sourceName:${lines.lineAt(match.range.first)}: nested Iris comment directive has no stable top-level anchor"
            }
            atoms += ContractAtom(IrisSourceContractKind.COMMENT_DIRECTIVE, match.range, mask = false)
        }

        val localExpressionIdentifiers = localLayouts.flatMapTo(linkedSetOf()) { layout ->
            layout.items.values.flatMap(::identifiers)
        }
        val hostExpressionIdentifiers = hostDeclarations.flatMapTo(linkedSetOf()) { identifiers(it.declaration) }
        val helperMacroNames = linkedSetOf<String>()
        fun collectHelperMacros(name: String) {
            if (name in settingsByName || name !in macroDefinitions || !helperMacroNames.add(name)) return
            macroDefinitions[name].orEmpty().forEach { definition ->
                identifiers(definition.body).forEach(::collectHelperMacros)
            }
        }
        localExpressionIdentifiers.forEach(::collectHelperMacros)
        hostExpressionIdentifiers.forEach(::collectHelperMacros)
        while (true) {
            val selectedGroups = helperMacroNames.flatMap { macroDefinitions[it].orEmpty() }
                .mapNotNullTo(linkedSetOf()) { it.group }
            val before = helperMacroNames.size
            macroDefinitions.filterValues { definitions -> definitions.any { it.group in selectedGroups } }
                .keys
                .forEach(::collectHelperMacros)
            if (helperMacroNames.size == before) break
        }
        val helperDefinitions = helperMacroNames.flatMap { macroDefinitions[it].orEmpty() }
            .distinctBy { it.range }

        val localAnalysis = analyzeLocalSize(
            sourceName,
            localLayouts,
            macroDefinitions,
            settings,
            usedSpecializationIds,
            localSizeIdSupported,
        )
        val derivedAnalysis = buildDerivedMacros(
            sourceName,
            source,
            helperMacroNames,
            macroDefinitions,
            localLayouts,
            hostDeclarations,
            settingsByName,
            localAnalysis?.axisMacros.orEmpty(),
            lexical,
            directives,
        )
        val derivedMacros = derivedAnalysis.contracts

        val contractDrafts = mutableListOf<ContractDraft>()
        val consumedAtoms = linkedSetOf<ContractAtom>()
        val maskRanges = mutableListOf<IntRange>()
        helperDefinitions.forEach { definition ->
            atoms += ContractAtom(IrisSourceContractKind.CONDITIONAL_CONTRACT, definition.range, mask = true)
        }

        conditionalGroups.filter { it.parentId == null }.sortedBy { it.range.first }
            .forEach { group ->
                val groupedAtoms = atoms.filter {
                    it !in consumedAtoms && it.range.first >= group.range.first && it.range.last <= group.range.last &&
                        it.kind in CONDITIONAL_CONTRACT_KINDS
                }
                if (groupedAtoms.isEmpty()) return@forEach
                val exactText = synthesizeConditionalContract(source, group, groupedAtoms, conditionalGroups)
                contractDrafts += ContractDraft(
                    IrisSourceContractKind.CONDITIONAL_CONTRACT,
                    group.range,
                    exactText,
                )
                consumedAtoms += groupedAtoms
                if (isContractOnlyConditional(source, group, groupedAtoms, conditionalGroups)) {
                    maskRanges += group.range
                } else {
                    groupedAtoms.filter { it.mask }.forEach { maskRanges += it.range }
                }
            }

        atoms.filter { it !in consumedAtoms }.forEach { atom ->
            contractDrafts += ContractDraft(atom.kind, atom.range, source.substring(atom.range))
            if (atom.mask) maskRanges += atom.range
        }

        var compilerSource = maskRanges.distinct().fold(source) { value, range ->
            value.replaceRange(range, maskSource(value.substring(range)))
        }
        derivedMacros.forEach { derived ->
            compilerSource = replaceCodeIdentifier(compilerSource, derived.sourceName, derived.compilerName)
        }
        localAnalysis?.axisMacros.orEmpty().forEach { (name, axis) ->
            compilerSource = replaceCodeIdentifier(compilerSource, name, "int(gl_WorkGroupSize.$axis)")
        }

        val extensionLines = compilerExtensionLines(directives)
        val compilerPrelude = buildString {
            extensionLines.forEach { appendLine(it) }
            if (
                extensionLines.isNotEmpty() || settings.isNotEmpty() ||
                derivedMacros.isNotEmpty() || localAnalysis?.contract != null
            ) {
                appendLine(COMPILER_MARKER)
            }
            derivedMacros.forEach {
                append("const int ")
                append(it.compilerName)
                append(" = ")
                append(it.compilerExpression)
                appendLine(";")
            }
            localAnalysis?.contract?.let { append(it.compilerLayout) }
        }
        compilerSource = insertAfterVersion(compilerSource, compilerPrelude)

        val anchors = findStableAnchors(source)
        val orderedDrafts = contractDrafts.sortedBy { it.range.first }
        val exactDrafts = buildList<ContractDraft> {
            orderedDrafts.forEach { draft ->
                val previous = lastOrNull()
                val gap = previous?.let { source.substring(it.range.last + 1, draft.range.first) }.orEmpty()
                if (previous != null && gap.all(Char::isWhitespace)) {
                    removeAt(lastIndex)
                    add(
                        previous.copy(
                            kind = when {
                                previous.kind == IrisSourceContractKind.EXTENSION ||
                                    draft.kind == IrisSourceContractKind.EXTENSION -> IrisSourceContractKind.EXTENSION
                                previous.kind == IrisSourceContractKind.CONDITIONAL_CONTRACT ||
                                    draft.kind == IrisSourceContractKind.CONDITIONAL_CONTRACT ->
                                    IrisSourceContractKind.CONDITIONAL_CONTRACT
                                else -> previous.kind
                            },
                            range = previous.range.first..draft.range.last,
                            exactText = previous.exactText + gap + draft.exactText,
                        ),
                    )
                } else {
                    add(draft)
                }
            }
        }
        val contracts = exactDrafts.mapIndexed { ordinal, draft ->
            val nearestBefore = anchors.filter { it.range.last < draft.range.first }.maxByOrNull { it.range.last }
            val nearestAfter = anchors.filter { it.range.first > draft.range.last }.minByOrNull { it.range.first }
            val placement = when {
                nearestAfter == null -> IrisAnchorPlacement.AFTER_BEFORE
                nearestBefore == null -> IrisAnchorPlacement.BEFORE_AFTER
                draft.kind == IrisSourceContractKind.EXTENSION -> IrisAnchorPlacement.AFTER_BEFORE
                draft.range.first - nearestBefore.range.last <= nearestAfter.range.first - draft.range.last ->
                    IrisAnchorPlacement.AFTER_BEFORE
                else -> IrisAnchorPlacement.BEFORE_AFTER
            }
            IrisSourceContractSlice(
                ordinal,
                draft.kind,
                draft.exactText,
                lines.lineAt(draft.range.first),
                nearestBefore?.anchor,
                nearestAfter?.anchor,
                placement,
            )
        }
        val structuralIssues = buildList {
            extractionErrors.forEach { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            localAnalysis?.error?.let { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            derivedAnalysis.error?.let { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            if (localAnalysis?.contract?.fallbackRequired == true) add(
                IrisStructuralIssue(
                    IrisStructuralIssueKind.LOCAL_SIZE_FALLBACK,
                    buildString {
                        append("local-size ABI requires structural signatures ")
                        append(localAnalysis.contract.signatures.joinToString(prefix = "[", postfix = "]"))
                        localSizeProbeDiagnostic?.let { append("; $it") }
                    },
                ),
            )
        }
        return IrisShaderContractPlan(
            sourceName,
            originalVersion,
            compilerSource,
            contracts,
            derivedMacros,
            localAnalysis?.contract,
            structuralIssues,
            compilerPrelude,
            settings,
        )
    }

    private fun buildDerivedMacros(
        sourceName: String,
        source: String,
        helperMacroNames: Set<String>,
        macroDefinitions: Map<String, List<ContractMacroDefinition>>,
        localLayouts: List<LocalLayout>,
        hostDeclarations: List<HostDeclaration>,
        settings: Map<String, ShaderSetting>,
        axisMacros: Map<String, Char>,
        lexical: ContractLexicalMap,
        directives: List<ContractDirective>,
    ): DerivedMacroAnalysis {
        val helperRanges = helperMacroNames.flatMap { macroDefinitions[it].orEmpty() }.map { it.range }
        val excluded = helperRanges + localLayouts.map { it.range } + hostDeclarations.map { it.range }
        val directlyUsed = helperMacroNames.filter { name ->
            name !in axisMacros && identifierOccurrences(source, name, lexical).any { offset ->
                excluded.none { offset in it } && directives.none { offset in it.range }
            }
        }
        val required = linkedSetOf<String>()
        fun collect(name: String) {
            if (name in axisMacros || name !in helperMacroNames || !required.add(name)) return
            macroDefinitions[name].orEmpty().forEach { definition ->
                identifiers(definition.body).forEach(::collect)
            }
        }
        directlyUsed.forEach(::collect)
        if (required.isEmpty()) return DerivedMacroAnalysis()

        val compilerNames = required.associateWith { "SM_DERIVED_$it" }
        val dependencies = required.associateWith { name ->
            macroDefinitions[name].orEmpty().flatMapTo(linkedSetOf()) { definition ->
                identifiers(definition.body).filter { it in required }
            }
        }
        val ordered = mutableListOf<String>()
        val visiting = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        fun visit(name: String): Boolean {
            if (name in visited) return true
            if (!visiting.add(name)) return false
            if (!dependencies.getValue(name).all(::visit)) return false
            visiting.remove(name)
            visited += name
            ordered += name
            return true
        }
        if (!required.sorted().all(::visit)) {
            return DerivedMacroAnalysis(error = "$sourceName: cyclic derived Iris contract macros ${visiting.joinToString()}")
        }

        val contracts = mutableListOf<IrisDerivedMacroContract>()
        ordered.forEach { name ->
            val definitions = macroDefinitions[name].orEmpty().sortedBy { it.range.first }
            if (definitions.isEmpty() || definitions.any { !INTEGER_MACRO_BODY.matches(stripComments(it.body).trim()) }) {
                return DerivedMacroAnalysis(error = "$sourceName: derived Iris contract macro $name is not a provable integer expression")
            }
            val unsupportedPredicateNames = definitions.flatMapTo(linkedSetOf()) { it.predicate.identifierNames() }
                .filterNot { it in settings || it == "defined" || it == "true" || it == "false" }
            if (unsupportedPredicateNames.isNotEmpty()) {
                return DerivedMacroAnalysis(
                    error = "$sourceName: derived Iris contract macro $name has unsupported predicate identifiers " +
                        unsupportedPredicateNames.sorted(),
                )
            }
            val relevantSettings = settings.values.filter { setting ->
                definitions.any { setting.name in it.predicate.settingNames() }
            }
            val assignments = enumerateAssignments(relevantSettings, MAX_LOCAL_SIZE_ASSIGNMENTS)
                ?: return DerivedMacroAnalysis(
                    error = "$sourceName: derived Iris contract macro $name exceeds $MAX_LOCAL_SIZE_ASSIGNMENTS assignments",
                )
            val ambiguous = assignments.firstOrNull { assignment ->
                definitions.count { it.predicate.evaluate(assignment, settings) == true } != 1
            }
            if (ambiguous != null) {
                return DerivedMacroAnalysis(
                    error = "$sourceName: derived Iris contract macro $name is not uniquely defined for $ambiguous",
                )
            }
            val bodies = assignments.associateWith { assignment ->
                val definition = definitions.single { it.predicate.evaluate(assignment, settings) == true }
                convertMacroBody(definition.body, settings, axisMacros, compilerNames)
            }
            val expression = renderDerivedDecision(relevantSettings, assignments, bodies)
            contracts += IrisDerivedMacroContract(name, compilerNames.getValue(name), expression)
        }
        return DerivedMacroAnalysis(contracts)
    }

    private fun analyzeLocalSize(
        sourceName: String,
        layouts: List<LocalLayout>,
        macroDefinitions: Map<String, List<ContractMacroDefinition>>,
        settings: List<ShaderSetting>,
        usedSpecializationIds: Set<Int>,
        localSizeIdSupported: Boolean,
    ): LocalSizeAnalysis? {
        if (layouts.isEmpty()) return null
        if (layouts.any { it.sourceSpecializationIds }) {
            return LocalSizeAnalysis(error = "$sourceName: source local_size_*_id declarations require structural preservation")
        }
        val settingsByName = settings.associateBy { it.name }
        val dependencies = linkedSetOf<String>()
        val visitedMacros = linkedSetOf<String>()
        fun collectExpression(expression: String) {
            identifiers(expression).forEach { name ->
                if (settings.any { it.name == name }) dependencies += name
                if (visitedMacros.add(name)) {
                    macroDefinitions[name].orEmpty().forEach { definition ->
                        dependencies += definition.predicate.settingNames()
                        collectExpression(definition.body)
                    }
                }
            }
        }
        layouts.forEach { layout ->
            dependencies += layout.predicate.settingNames()
            layout.items.values.forEach(::collectExpression)
        }
        val predicates = layouts.map { it.predicate } + visitedMacros.flatMap { name ->
            macroDefinitions[name].orEmpty().map { it.predicate }
        }
        val unsupportedPredicateNames = predicates.flatMapTo(linkedSetOf()) { it.identifierNames() }
            .filterNot { it in settingsByName || it == "defined" || it == "true" || it == "false" }
        if (unsupportedPredicateNames.isNotEmpty()) {
            return LocalSizeAnalysis(
                error = "$sourceName: local-size contract has unsupported predicate identifiers " +
                    unsupportedPredicateNames.sorted(),
            )
        }
        val relevant = settings.filter { it.name in dependencies }
        if (relevant.any { it.type == ShaderSettingType.FLOAT }) {
            return LocalSizeAnalysis(error = "$sourceName: floating-point setting cannot define local-size ABI")
        }
        val assignments = enumerateAssignments(relevant, MAX_LOCAL_SIZE_ASSIGNMENTS)
            ?: return LocalSizeAnalysis(
                error = "$sourceName: local-size dependency domain exceeds $MAX_LOCAL_SIZE_ASSIGNMENTS assignments",
            )
        val signatures = linkedSetOf<LocalSizeAbiSignature>()
        val signatureAssignments = mutableListOf<LocalSizeAbiAssignment>()
        var defaultSignature: LocalSizeAbiSignature? = null
        assignments.forEach { assignment ->
            val active = layouts.filter { it.predicate.evaluate(assignment, settingsByName) == true }
            if (active.isEmpty()) {
                return LocalSizeAnalysis(error = "$sourceName: no local-size declaration is active for $assignment")
            }
            val values = mutableMapOf('x' to 1, 'y' to 1, 'z' to 1)
            active.forEach { layout ->
                layout.items.forEach { (axis, expression) ->
                    val value = evaluateInteger(
                        expression,
                        assignment,
                        settingsByName,
                        macroDefinitions,
                        mutableSetOf(),
                    )
                        ?: return LocalSizeAnalysis(
                            error = "$sourceName: cannot prove local_size_$axis expression '$expression' for $assignment",
                        )
                    values[axis] = value
                }
            }
            val signature = LocalSizeAbiSignature(values.getValue('x'), values.getValue('y'), values.getValue('z'))
            signatures += signature
            signatureAssignments += LocalSizeAbiAssignment(assignment.toSortedMap(), signature)
            if (relevant.all { assignment[it.name] == scalarDefault(it) }) defaultSignature = signature
        }
        val default = defaultSignature ?: signatures.first()
        val varyingAxes = listOf('x', 'y', 'z').filter { axis ->
            signatures.map { it.axis(axis) }.distinct().size > 1
        }
        val ids = linkedMapOf<Char, Int>()
        val occupied = usedSpecializationIds.toMutableSet()
        var nextId = 0
        varyingAxes.forEach { axis ->
            while (nextId in occupied) nextId++
            ids[axis] = nextId
            occupied += nextId
            nextId++
        }
        val compilerLayout = buildString {
            append("layout(local_size_x = ${default.x}, local_size_y = ${default.y}, local_size_z = ${default.z}) in;\n")
            if (localSizeIdSupported && ids.isNotEmpty()) {
                append("layout(")
                append(ids.entries.joinToString(", ") { (axis, id) -> "local_size_${axis}_id = $id" })
                append(") in;\n")
            }
        }
        val axisMacros = mutableMapOf<String, Char>()
        layouts.forEach { layout ->
            layout.items.forEach { (axis, expression) ->
                val name = expression.trim()
                if (IDENTIFIER.matches(name) && name in macroDefinitions) axisMacros[name] = axis
            }
        }
        return LocalSizeAnalysis(
            LocalSizeSpecializationContract(
                default,
                signatures.sortedWith(compareBy(LocalSizeAbiSignature::x, LocalSizeAbiSignature::y, LocalSizeAbiSignature::z)),
                relevant.mapTo(sortedSetOf()) { it.name },
                signatureAssignments,
                if (localSizeIdSupported) ids else emptyMap(),
                compilerLayout,
                !localSizeIdSupported && signatures.size > 1,
            ),
            axisMacros,
        )
    }
}

private data class ContractAtom(val kind: IrisSourceContractKind, val range: IntRange, val mask: Boolean)
private data class ContractDraft(val kind: IrisSourceContractKind, val range: IntRange, val exactText: String)
private data class ContractDirective(val directive: PreprocessorDirective, val range: IntRange)
private data class HostDeclaration(val name: String, val range: IntRange, val declaration: String)
private data class LocalLayout(
    val range: IntRange,
    val items: Map<Char, String>,
    val predicate: ContractPredicate,
    val sourceSpecializationIds: Boolean,
)
private data class LocalSizeAnalysis(
    val contract: LocalSizeSpecializationContract? = null,
    val axisMacros: Map<String, Char> = emptyMap(),
    val error: String? = null,
)

private data class DerivedMacroAnalysis(
    val contracts: List<IrisDerivedMacroContract> = emptyList(),
    val error: String? = null,
)

private data class ContractMacroDefinition(
    val name: String,
    val body: String,
    val range: IntRange,
    val group: ContractConditionalGroup?,
    val predicate: ContractPredicate,
)

private data class ContractPredicate(val expressions: List<String>) {
    fun evaluate(values: Map<String, String>, settings: Map<String, ShaderSetting>): Boolean? {
        return expressions.all { PreprocessorExpressionParser(it, values, settings).parse() ?: return null }
    }

    fun settingNames(): Set<String> = expressions.flatMapTo(linkedSetOf()) {
        SETTING_IDENTIFIER.findAll(it).map(MatchResult::value)
    }

    fun identifierNames(): Set<String> = expressions.flatMapTo(linkedSetOf(), ::identifiers)

}

private data class ContractConditionalGroup(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val opener: ContractDirective,
    val delimiters: MutableList<ContractDirective>,
    var endif: ContractDirective? = null,
    var range: IntRange = IntRange.EMPTY,
)

private data class LocatedAnchor(val anchor: IrisSourceAnchor, val range: IntRange)

private class ContractLineMap(private val source: String) {
    private val starts = buildList {
        add(0)
        LINE_ENDING.findAll(source).forEach { add(it.range.last + 1) }
    }.distinct()

    fun directiveRange(directive: PreprocessorDirective): IntRange {
        val start = starts[directive.sourceLine - 1]
        val end = if (directive.endLine < starts.size) starts[directive.endLine] else source.length
        return start until end
    }

    fun fullLineRange(range: IntRange): IntRange = fullLineRange(range.first, range.last)

    fun fullLineRange(startOffset: Int, endOffset: Int): IntRange {
        val start = source.lastIndexOf('\n', startOffset - 1).let { if (it < 0) 0 else it + 1 }
        if (source.getOrNull(endOffset) == '\n') return start until (endOffset + 1)
        val newline = source.indexOf('\n', endOffset + 1)
        val end = if (newline < 0) source.length else newline + 1
        return start until end
    }

    fun lineAt(offset: Int): Int = source.take(offset).count { it == '\n' } + 1
}

private class ContractLexicalMap(private val source: String) {
    private val code = BooleanArray(source.length + 1) { true }
    private val depth = IntArray(source.length + 1)

    init {
        var cursor = 0
        var braceDepth = 0
        var lineComment = false
        var blockComment = false
        var quote: Char? = null
        while (cursor < source.length) {
            val current = source[cursor]
            val next = source.getOrNull(cursor + 1)
            depth[cursor] = braceDepth
            code[cursor] = !lineComment && !blockComment && quote == null
            if (lineComment) {
                if (current == '\r' || current == '\n') lineComment = false
                cursor++
                continue
            }
            if (blockComment) {
                code[cursor] = false
                if (current == '*' && next == '/') {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    blockComment = false
                    cursor += 2
                } else {
                    cursor++
                }
                continue
            }
            val activeQuote = quote
            if (activeQuote != null) {
                code[cursor] = false
                if (current == '\\' && next != null) {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    cursor += 2
                } else {
                    if (current == activeQuote) quote = null
                    cursor++
                }
                continue
            }
            when {
                current == '/' && next == '/' -> {
                    code[cursor] = false
                    code[cursor + 1] = false
                    lineComment = true
                    cursor += 2
                }
                current == '/' && next == '*' -> {
                    code[cursor] = false
                    code[cursor + 1] = false
                    blockComment = true
                    cursor += 2
                }
                current == '"' || current == '\'' -> {
                    code[cursor] = false
                    quote = current
                    cursor++
                }
                current == '{' -> {
                    braceDepth++
                    cursor++
                }
                current == '}' -> {
                    if (braceDepth > 0) braceDepth--
                    cursor++
                }
                else -> cursor++
            }
        }
        depth[source.length] = braceDepth
    }

    fun isCode(offset: Int): Boolean = offset in code.indices && code[offset]
    fun isTopLevel(offset: Int): Boolean = offset in depth.indices && depth[offset] == 0
    fun isTopLevelCode(offset: Int): Boolean = isCode(offset) && depth[offset] == 0

    fun findCodeCharacter(character: Char, start: Int): Int? {
        for (offset in start until source.length) {
            if (source[offset] == character && isCode(offset)) return offset
        }
        return null
    }
}

private fun buildConditionalGroups(directives: List<ContractDirective>): List<ContractConditionalGroup> {
    val result = mutableListOf<ContractConditionalGroup>()
    val stack = mutableListOf<ContractConditionalGroup>()
    directives.forEach { directive ->
        when (directive.directive.kind) {
            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            -> {
                val group = ContractConditionalGroup(
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
                group.range = group.opener.range.first until (directive.range.last + 1)
            }
            else -> Unit
        }
    }
    return result
}

private fun collectMacroDefinitions(
    source: String,
    directives: List<ContractDirective>,
    groups: List<ContractConditionalGroup>,
    settings: Map<String, ShaderSetting>,
): Map<String, List<ContractMacroDefinition>> {
    return directives.filter {
        it.directive.kind == PreprocessorDirectiveKind.DEFINE &&
            it.directive.macroName != null &&
            !it.directive.macroFunctionLike
    }.groupBy { requireNotNull(it.directive.macroName) }.mapValues { (name, definitions) ->
        definitions.map { definition ->
            val group = groups.filter { definition.range.first in it.range }.maxByOrNull { it.depth }
            ContractMacroDefinition(
                name,
                definition.directive.macroBody.orEmpty(),
                definition.range,
                group,
                predicateAt(definition.range.first, groups, settings),
            )
        }
    }
}

private fun predicateAt(
    offset: Int,
    groups: List<ContractConditionalGroup>,
    settings: Map<String, ShaderSetting>,
): ContractPredicate {
    val expressions = groups.filter { offset in it.range }.sortedBy { it.depth }.mapNotNull { group ->
        val delimiters = group.delimiters
        val branch = delimiters.indexOfLast { it.range.first < offset }.coerceAtLeast(0)
        val prior = delimiters.take(branch).filter { it.directive.kind != PreprocessorDirectiveKind.ELSE }
            .map { directiveCondition(it.directive) }
        val current = delimiters[branch]
        val predicate = when (current.directive.kind) {
            PreprocessorDirectiveKind.ELSE -> prior.joinToString(" && ") { "!($it)" }
            else -> (prior.map { "!($it)" } + directiveCondition(current.directive)).joinToString(" && ")
        }
        predicate.takeIf { expression ->
            settings.keys.any { identifierRegex(it).containsMatchIn(expression) }
        }
    }
    return ContractPredicate(expressions)
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

private fun synthesizeConditionalContract(
    source: String,
    group: ContractConditionalGroup,
    atoms: List<ContractAtom>,
    groups: List<ContractConditionalGroup>,
): String {
    val relevantGroups = groups.filter { candidate ->
        candidate.range.first >= group.range.first && candidate.range.last <= group.range.last &&
            atoms.any { atom -> atom.range.first >= candidate.range.first && atom.range.last <= candidate.range.last }
    }
    val ranges = buildList {
        addAll(atoms.map { it.range })
        relevantGroups.forEach { candidate ->
            addAll(candidate.delimiters.map { it.range })
            add(requireNotNull(candidate.endif).range)
        }
    }.distinct().sortedBy { it.first }
    return buildString {
        var previousEnd: Int? = null
        ranges.forEach { range ->
            previousEnd?.let { end ->
                val gap = source.substring(end + 1, range.first)
                if (gap.all(Char::isWhitespace)) append(gap)
            }
            append(source.substring(range))
            previousEnd = range.last
        }
    }
}

private fun isContractOnlyConditional(
    source: String,
    group: ContractConditionalGroup,
    atoms: List<ContractAtom>,
    groups: List<ContractConditionalGroup>,
): Boolean {
    val covered = buildList {
        addAll(atoms.map { it.range })
        groups.filter { candidate ->
            candidate.range.first >= group.range.first && candidate.range.last <= group.range.last
        }.forEach { candidate ->
            addAll(candidate.delimiters.map { it.range })
            add(requireNotNull(candidate.endif).range)
        }
    }.distinct().sortedByDescending { it.first }
    var remaining = source.substring(group.range)
    covered.forEach { range ->
        val relative = (range.first - group.range.first)..(range.last - group.range.first)
        remaining = remaining.replaceRange(relative, maskSource(remaining.substring(relative)))
    }
    return stripComments(remaining).isBlank()
}

private fun compilerExtensionLines(directives: List<ContractDirective>): List<String> {
    return directives.filter { it.directive.kind == PreprocessorDirectiveKind.EXTENSION }
        .mapNotNull { EXTENSION_DECLARATION.find(it.directive.exactText)?.destructured }
        .groupBy { it.component1() }
        .toSortedMap()
        .map { (name, declarations) ->
            val behavior = if (declarations.any { it.component2() == "require" }) "require" else "enable"
            "#extension $name : $behavior"
        }
}

private fun renderSettingDeclarations(settings: List<ShaderSetting>): String = buildString {
    settings.forEach { setting ->
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

private fun parseLocalSizeItems(text: String): Map<Char, String> {
    return text.split(',').mapNotNull { item ->
        val match = LOCAL_SIZE_ITEM.matchEntire(item.trim()) ?: return@mapNotNull null
        match.groupValues[1].single() to match.groupValues[2].trim()
    }.toMap(linkedMapOf())
}

private fun enumerateAssignments(settings: List<ShaderSetting>, limit: Int): List<Map<String, String>>? {
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

private fun scalarDefault(setting: ShaderSetting): String = setting.defaultValue

private fun evaluateInteger(
    expression: String,
    values: Map<String, String>,
    settings: Map<String, ShaderSetting>,
    macros: Map<String, List<ContractMacroDefinition>>,
    visiting: MutableSet<String>,
): Int? {
    return IntegerExpressionParser(expression) { name ->
        values[name]?.toScalarInt() ?: run {
            if (!visiting.add(name)) return@IntegerExpressionParser null
            val active = macros[name].orEmpty().filter { it.predicate.evaluate(values, settings) == true }.singleOrNull()
            val value = active?.let { evaluateInteger(it.body, values, settings, macros, visiting) }
            visiting.remove(name)
            value
        }
    }.parse()?.toInt()
}

private fun String.toScalarInt(): Int? = when (trim()) {
    "true" -> 1
    "false" -> 0
    else -> trim().removeSuffix("u").removeSuffix("U").toIntOrNull()
}

private fun convertMacroBody(
    body: String,
    settings: Map<String, ShaderSetting>,
    axisMacros: Map<String, Char>,
    derivedMacros: Map<String, String>,
): String {
    var result = stripComments(body).trim()
    settings.forEach { (name, setting) -> result = identifierRegex(name).replace(result, setting.compilerName) }
    axisMacros.forEach { (name, axis) -> result = identifierRegex(name).replace(result, "int(gl_WorkGroupSize.$axis)") }
    derivedMacros.forEach { (name, compilerName) -> result = identifierRegex(name).replace(result, compilerName) }
    return result
}

private fun renderDerivedDecision(
    settings: List<ShaderSetting>,
    assignments: List<Map<String, String>>,
    bodies: Map<Map<String, String>, String>,
): String {
    fun render(settingIndex: Int, rows: List<Map<String, String>>): String {
        val distinctBodies = rows.map { bodies.getValue(it) }.distinct()
        if (distinctBodies.size == 1) return distinctBodies.single()
        val setting = settings.getOrNull(settingIndex)
            ?: error("derived Iris contract decision has unresolved assignments")
        val branches = setting.domain.mapNotNull { value ->
            rows.filter { it[setting.name] == value }.takeIf { it.isNotEmpty() }?.let { value to it }
        }
        var result = render(settingIndex + 1, branches.last().second)
        branches.dropLast(1).asReversed().forEach { (value, branchRows) ->
            val condition = if (setting.presenceToggle) {
                if (value.toScalarInt() == 0) "!${setting.compilerName}" else setting.compilerName
            } else {
                "${setting.compilerName} == $value"
            }
            result = "(($condition) ? (${render(settingIndex + 1, branchRows)}) : ($result))"
        }
        return result
    }
    return render(0, assignments)
}

private fun replaceCodeIdentifier(source: String, name: String, replacement: String): String {
    val lexical = ContractLexicalMap(source)
    val directiveLines = PreprocessorProtection.protect(source, "<compiler-contract>").directives
    val lines = ContractLineMap(source)
    val directiveRanges = directiveLines.map(lines::directiveRange)
    val matches = identifierRegex(name).findAll(source).filter {
        lexical.isCode(it.range.first) && directiveRanges.none { range -> it.range.first in range }
    }.toList()
    return matches.asReversed().fold(source) { value, match -> value.replaceRange(match.range, replacement) }
}

private fun identifierOccurrences(source: String, name: String, lexical: ContractLexicalMap): List<Int> {
    return identifierRegex(name).findAll(source).filter { lexical.isCode(it.range.first) }.map { it.range.first }.toList()
}

private fun findStableAnchors(source: String): List<LocatedAnchor> {
    val lexical = ContractLexicalMap(source)
    val result = mutableListOf<LocatedAnchor>()
    VERSION_LINE.find(source)?.let { result += LocatedAnchor(IrisSourceAnchor(IrisAnchorKind.VERSION, "version"), it.range) }
    STABLE_ABI_DECLARATION.findAll(source).filter { lexical.isTopLevelCode(it.range.first) }.forEach { match ->
        result += LocatedAnchor(
            IrisSourceAnchor(
                IrisAnchorKind.DECLARATION,
                "${match.groupValues[1]}:${match.groupValues[3]}",
            ),
            match.range,
        )
    }
    FUNCTION_START.findAll(source).filter {
        lexical.isTopLevelCode(it.range.first) && it.groupValues[1] == "main"
    }.forEach { match ->
        val open = source.indexOf('{', match.range.first)
        if (open < 0) return@forEach
        var depth = 0
        var end = -1
        for (offset in open until source.length) {
            if (!lexical.isCode(offset)) continue
            when (source[offset]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = offset
                        break
                    }
                }
            }
        }
        if (end >= 0) {
            result += LocatedAnchor(
                IrisSourceAnchor(IrisAnchorKind.FUNCTION, match.groupValues[1]),
                match.range.first..end,
            )
        }
    }
    return result.sortedBy { it.range.first }
}

private fun insertAfterVersion(source: String, insertion: String): String {
    if (insertion.isEmpty()) return source
    val version = VERSION_LINE.find(source) ?: throw IllegalArgumentException("compiler source has no #version directive")
    var offset = version.range.last + 1
    if (source.getOrNull(offset) == '\r') offset++
    if (source.getOrNull(offset) == '\n') offset++
    val prefix = if (offset == version.range.last + 1) "\n" else ""
    return source.substring(0, offset) + prefix + insertion + source.substring(offset)
}

private fun maskSource(source: String): String = source.map { if (it == '\r' || it == '\n') it else ' ' }.joinToString("")
private fun normalizeCompilerText(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
private fun identifiers(source: String): List<String> = IDENTIFIER.findAll(stripComments(source)).map { it.value }.toList()
private fun identifierRegex(name: String): Regex = "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
private fun stripComments(source: String): String = BLOCK_COMMENT.replace(LINE_COMMENT.replace(source, ""), "")

private fun isHostDeclarationName(name: String): Boolean {
    return name in IRIS_HOST_NAMES || IRIS_HOST_NAME_PATTERNS.any { it.matches(name) }
}

private fun LocalSizeAbiSignature.axis(axis: Char): Int = when (axis) {
    'x' -> x
    'y' -> y
    'z' -> z
    else -> error("invalid local-size axis $axis")
}

private fun renderFixedLocalSize(signature: LocalSizeAbiSignature): String {
    return "layout(local_size_x = ${signature.x}, local_size_y = ${signature.y}, local_size_z = ${signature.z}) in;\n"
}

private class IntegerExpressionParser(
    private val source: String,
    private val identifierValue: (String) -> Int?,
) {
    private var cursor = 0

    fun parse(): Long? = runCatching {
        val result = expression()
        whitespace()
        require(cursor == source.length)
        result
    }.getOrNull()

    private fun expression(): Long {
        var value = term()
        while (true) {
            value = when {
                consume('+') -> Math.addExact(value, term())
                consume('-') -> Math.subtractExact(value, term())
                else -> return value
            }
        }
    }

    private fun term(): Long {
        var value = unary()
        while (true) {
            value = when {
                consume('*') -> Math.multiplyExact(value, unary())
                consume('/') -> value / unary()
                consume('%') -> value % unary()
                else -> return value
            }
        }
    }

    private fun unary(): Long = when {
        consume('+') -> unary()
        consume('-') -> Math.negateExact(unary())
        consume('(') -> expression().also { require(consume(')')) }
        else -> primary()
    }

    private fun primary(): Long {
        whitespace()
        val identifier = IDENTIFIER.matchAt(source, cursor)
        if (identifier != null) {
            cursor = identifier.range.last + 1
            return requireNotNull(identifierValue(identifier.value)).toLong()
        }
        val number = INTEGER_LITERAL.matchAt(source, cursor) ?: error("expected integer")
        cursor = number.range.last + 1
        return number.value.removeSuffix("u").removeSuffix("U").toLong()
    }

    private fun consume(character: Char): Boolean {
        whitespace()
        if (source.getOrNull(cursor) != character) return false
        cursor++
        return true
    }

    private fun whitespace() {
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
    }
}

private class PreprocessorExpressionParser(
    private val source: String,
    private val values: Map<String, String>,
    private val settings: Map<String, ShaderSetting>,
) {
    private var cursor = 0

    fun parse(): Boolean? = runCatching {
        val result = logicalOr()
        whitespace()
        require(cursor == source.length)
        result
    }.getOrNull()

    private fun logicalOr(): Boolean {
        var value = logicalAnd()
        while (consume("||")) value = logicalAnd() || value
        return value
    }

    private fun logicalAnd(): Boolean {
        var value = equality()
        while (consume("&&")) value = equality() && value
        return value
    }

    private fun equality(): Boolean {
        val left = scalar()
        return when {
            consume("==") -> left == scalar()
            consume("!=") -> left != scalar()
            consume("<=") -> left <= scalar()
            consume(">=") -> left >= scalar()
            consume("<") -> left < scalar()
            consume(">") -> left > scalar()
            else -> left != 0
        }
    }

    private fun scalar(): Int {
        whitespace()
        if (consume("!")) return if (scalar() == 0) 1 else 0
        if (consume("(")) return if (logicalOr().also { require(consume(")")) }) 1 else 0
        if (source.startsWith("defined", cursor)) {
            cursor += "defined".length
            whitespace()
            val parenthesized = consume("(")
            val name = requireNotNull(IDENTIFIER.matchAt(source, cursor)).value
            cursor += name.length
            if (parenthesized) require(consume(")"))
            val setting = settings[name] ?: return 0
            return if (!setting.presenceToggle || values[name]?.toScalarInt()?.let { it != 0 } == true) 1 else 0
        }
        IDENTIFIER.matchAt(source, cursor)?.let { match ->
            cursor = match.range.last + 1
            return values[match.value]?.toScalarInt() ?: 0
        }
        val number = requireNotNull(INTEGER_LITERAL.matchAt(source, cursor))
        cursor = number.range.last + 1
        return number.value.removeSuffix("u").removeSuffix("U").toInt()
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

internal const val COMPILER_MARKER = "// SHADESMITH_IRIS_COMPILER_CONTRACT"
private const val MAX_LOCAL_SIZE_ASSIGNMENTS = 64
private val CONDITIONAL_CONTRACT_KINDS = setOf(
    IrisSourceContractKind.OPTION_DEFINITION,
    IrisSourceContractKind.HOST_DECLARATION,
    IrisSourceContractKind.COMMENT_DIRECTIVE,
    IrisSourceContractKind.EXTENSION,
    IrisSourceContractKind.PRAGMA,
    IrisSourceContractKind.LOCAL_SIZE,
    IrisSourceContractKind.CONDITIONAL_CONTRACT,
)
private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
private val LOCAL_SIZE_LAYOUT =
    "(?m)^[\\t ]*layout\\s*\\(([^)]*\\blocal_size_[xyz](?:_id)?\\b[^)]*)\\)\\s*in\\s*;[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)".toRegex()
private val LOCAL_SIZE_ITEM = "local_size_([xyz])\\s*=\\s*(.+)".toRegex()
private val LOCAL_SIZE_ID_ITEM = "\\blocal_size_[xyz]_id\\s*=".toRegex()
private val HOST_CONST_START =
    "(?m)^[\\t ]*const[\\t ]+(?:int|float|bool|vec[234]|ivec3)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)".toRegex()
private val IRIS_COMMENT_DIRECTIVE =
    "/\\*[\\t ]*(?:DRAWBUFFERS|RENDERTARGETS|SHADOWRES|SHADOWFOV|SHADOWHPL|GAUX4FORMAT):[\\s\\S]*?\\*/".toRegex()
private val EXTENSION_DECLARATION =
    "#\\s*extension\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(require|enable|warn|disable)".toRegex()
private val FUNCTION_START =
    "(?m)^[\\t ]*(?:[A-Za-z_][A-Za-z0-9_]*[\\t ]+)+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*\\([^;{}]*\\)\\s*\\{".toRegex()
private val STABLE_ABI_DECLARATION = (
    "(?m)^[\\t ]*(?:layout\\s*\\([^)\\r\\n]*\\)\\s*)?" +
        "(?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise|highp|mediump|lowp|" +
        "coherent|volatile|restrict|readonly|writeonly)\\s+)*(uniform|in|out)\\s+" +
        "(?:(?:highp|mediump|lowp|readonly|writeonly)\\s+)*([A-Za-z_][A-Za-z0-9_]*)\\s+" +
        "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^]\\r\\n]*])?\\s*(?:=[^;\\r\\n]+)?;"
    ).toRegex()
private val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
private val SETTING_IDENTIFIER = "\\bSETTING_[A-Za-z0-9_]+\\b".toRegex()
private val INTEGER_LITERAL = "[0-9]+[uU]?".toRegex()
private val INTEGER_MACRO_BODY = "[-+*/%() A-Za-z0-9_]+".toRegex()
private val LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
private val LINE_COMMENT = "//[^\\r\\n]*".toRegex()
private val BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/".toRegex()
private val IRIS_HOST_NAMES = setOf(
    "noiseTextureResolution",
    "sunPathRotation",
    "ambientOcclusionLevel",
    "wetnessHalflife",
    "drynessHalflife",
    "eyeBrightnessHalflife",
    "centerDepthHalflife",
    "shadowMapResolution",
    "shadowMapFov",
    "shadowDistance",
    "shadowNearPlane",
    "shadowFarPlane",
    "voxelDistance",
    "entityShadowDistanceMul",
    "shadowDistanceRenderMul",
    "shadowIntervalSize",
    "shadowHardwareFiltering",
    "generateShadowMipmap",
    "shadowtexMipmap",
    "generateShadowColorMipmap",
    "shadowtexNearest",
    "workGroups",
    "workGroupsRender",
)
private val IRIS_HOST_NAME_PATTERNS = listOf(
    "(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])(?:Format|Clear|ClearColor|MipmapEnabled)".toRegex(),
    "shadowHardwareFiltering\\d+".toRegex(),
    "shadowtex\\d+(?:Mipmap|Nearest)".toRegex(),
    "shadow\\d+MinMagNearest".toRegex(),
    "shadowcolor\\d+(?:Mipmap|Nearest|Format|Clear|ClearColor)".toRegex(),
    "shadowColor\\d+(?:Mipmap|Nearest|MinMagNearest)".toRegex(),
)
