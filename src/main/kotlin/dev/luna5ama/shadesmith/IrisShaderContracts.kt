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
    val sourceRange: IntRange,
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
    val compilerType: ShaderSettingType,
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
    private val compilerHostNames: Map<String, String>,
    private val compilerDynamicNames: Map<String, String>,
) {
    val localSizeSpecializationIds: Set<Int>
        get() = localSize?.specializationIds?.values.orEmpty().toSet()

    val structuralReason: String?
        get() = structuralIssues.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.reason }

    fun withRestorationContracts(restorationContracts: List<IrisSourceContractSlice>): IrisShaderContractPlan {
        return copy(contracts = restorationContracts)
    }

    fun withMaterializedCompilerSource(source: String): IrisShaderContractPlan {
        val activeExtensions = PreprocessorProtection.protectGeneratedCompilerSource(source, sourceName).directives
            .filter { it.kind == PreprocessorDirectiveKind.EXTENSION }
            .mapNotNull { directive ->
                EXTENSION_DECLARATION.find(directive.exactText)?.destructured?.let { (name, behavior) ->
                    "#extension $name : $behavior"
                }
            }
            .distinct()
        val marker = compilerPrelude.indexOf(COMPILER_MARKER)
        val nonExtensionPrelude = if (marker < 0) compilerPrelude else compilerPrelude.substring(marker)
        val materializedPrelude = buildString {
            activeExtensions.forEach { appendLine(it) }
            append(nonExtensionPrelude)
        }
        return copy(compilerPrelude = materializedPrelude)
    }

    fun restoreRequiredCompilerPrelude(source: String): String {
        var result = stripTopLevelExtensionDirectives(source)
        val activeExtensions = compilerPrelude.lineSequence()
            .takeWhile { EXTENSION_DECLARATION.matches(it) }
            .joinToString("\n")
        val version = VERSION_LINE.find(result)
            ?: throw IllegalArgumentException("$sourceName: compiler source has no #version directive")
        if (activeExtensions.isNotEmpty()) {
            result = result.substring(0, version.range.last + 1) + "\n" + activeExtensions +
                result.substring(version.range.last + 1)
        }
        val missingPrelude = (compilerHostNames.values + derivedMacros.map { it.compilerName }).mapNotNull { name ->
            if (!"\\b${Regex.escape(name)}\\b".toRegex().containsMatchIn(result)) return@mapNotNull null
            val declaration = compilerHostDeclaration(name)
            if (declaration.containsMatchIn(result)) return@mapNotNull null
            declaration.find(compilerPrelude)?.let { it.range.first to it.value.trim() }
        }.toMutableList()
        localSize?.let { local ->
            result = LOCAL_SIZE_LAYOUT.replace(result, "")
            val offset = compilerPrelude.indexOf(local.compilerLayout)
            require(offset >= 0) { "$sourceName: compiler prelude is missing the authoritative local-size layout" }
            missingPrelude += offset to local.compilerLayout.trim()
        }
        if (missingPrelude.isNotEmpty()) {
            val marker = result.indexOf(COMPILER_MARKER)
            val markerInsertion = if (marker < 0) {
                version.range.last + 1
            } else {
                result.indexOf('\n', marker).let { if (it < 0) result.length else it + 1 }
            }
            val settingInsertion = compilerSettings.mapNotNull { setting ->
                compilerSettingDeclaration(setting.compilerName).find(result)?.range?.let { it.last + 1 }
            }.maxOrNull() ?: 0
            val insertion = maxOf(markerInsertion, settingInsertion)
            val prefix = if (insertion == 0 || result[insertion - 1] == '\n') "" else "\n"
            val prelude = missingPrelude.sortedBy { it.first }.joinToString("\n", postfix = "\n") { it.second }
            result = result.substring(0, insertion) + prefix + prelude +
                result.substring(insertion)
        }
        return normalizeCompilerText(result)
    }

    fun stripCompilerArtifacts(source: String): String {
        var result = stripTopLevelExtensionDirectives(normalizeCompilerText(source))
        if (localSize != null) result = LOCAL_SIZE_LAYOUT.replace(result, "")
        compilerHostNames.values.forEach { name ->
            result = compilerHostDeclaration(name).replace(result, "")
        }
        return normalizeCompilerText(result)
    }

    fun replaceHostReferences(source: String): String {
        val replacements = buildMap {
            putAll(compilerHostNames)
            derivedMacros.forEach { macro -> put(macro.sourceName, macro.compilerName) }
            putAll(compilerDynamicNames)
        }
        return replaceCodeIdentifiers(source, replacements)
    }

    fun sourceDynamicName(compilerName: String): String? =
        compilerDynamicNames.entries.singleOrNull { it.value == compilerName }?.key

    fun forStructuralModule(
        source: String,
        fallbackSignature: LocalSizeAbiSignature?,
        modulePlan: IrisShaderContractPlan,
    ): IrisShaderContractPlan {
        if (fallbackSignature == null) return copy(
            compilerSource = source,
            derivedMacros = modulePlan.derivedMacros,
            compilerPrelude = modulePlan.compilerPrelude,
            compilerSettings = modulePlan.compilerSettings,
            compilerHostNames = modulePlan.compilerHostNames,
            compilerDynamicNames = modulePlan.compilerDynamicNames,
        )
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
            derivedMacros = modulePlan.derivedMacros,
            compilerPrelude = modulePlan.compilerPrelude.replace(
                modulePlan.localSize?.compilerLayout ?: local.compilerLayout,
                fixedLayout,
            ),
            compilerSettings = modulePlan.compilerSettings,
            compilerHostNames = modulePlan.compilerHostNames,
        )
    }

    fun restore(decompiledSource: String): IrisContractRestoration {
        var result = stripCompilerArtifacts(decompiledSource)
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
            val compilerValueNames = derivedMacros.map { it.compilerName } + compilerHostNames.values
            compilerValueNames.forEach { name ->
                val matches = compilerHostDeclaration(name).findAll(result).toList()
                require(matches.size <= 1) {
                    "$sourceName: compiler value $name has ${matches.size} declarations"
                }
                matches.singleOrNull()?.let { declaration ->
                    result = result.removeRange(declaration.range)
                }
            }
            val existingDeclarations = compilerSettings.associateWith { setting ->
                val matches = compilerSettingDeclaration(setting.compilerName).findAll(result).toList()
                require(matches.size <= 1) {
                    "$sourceName: compiler setting ${setting.compilerName} has ${matches.size} declarations"
                }
                matches.singleOrNull()
            }
            existingDeclarations.values.filterNotNull().sortedByDescending { it.range.first }.forEach { declaration ->
                result = result.removeRange(declaration.range)
            }
            val settingDeclarations = buildString {
                compilerSettings.forEach { setting ->
                    val existing = existingDeclarations.getValue(setting)?.value?.trimEnd('\r', '\n')
                    if (existing == null) append(renderSettingDeclarations(listOf(setting))) else appendLine(existing)
                }
            }
            val validationPrelude = if (compilerSettings.isEmpty()) {
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
        val discoveredHostDeclarations = HOST_CONST_START.findAll(source)
            .filter { lexical.isTopLevelCode(it.range.first) && isHostDeclarationName(it.groupValues[1]) }
            .map { match ->
                val semicolon = lexical.findCodeCharacter(';', match.range.last + 1)
                    ?: throw IllegalArgumentException(
                        "$sourceName:${lines.lineAt(match.range.first)}: unterminated Iris host declaration ${match.groupValues[1]}",
                    )
                val range = lines.fullLineRange(match.range.first..semicolon)
                val declaration = source.substring(match.range.first, semicolon + 1)
                val parsed = HOST_CONST_DECLARATION.matchEntire(declaration.trim())
                    ?: throw IllegalArgumentException(
                        "$sourceName:${lines.lineAt(match.range.first)}: Iris host declaration " +
                            "${match.groupValues[1]} is not a scalar compiler expression",
                    )
                HostDeclaration(
                    name = match.groupValues[1],
                    type = parsed.groupValues[1],
                    initializer = parsed.groupValues[3].trim(),
                    range = range,
                    declaration = declaration,
                    predicate = predicateAt(range.first, conditionalGroups, settingsByName),
                )
            }
            .toList()
        val hostDeclarationRanges = discoveredHostDeclarations.map { it.range }
        val identifierOffsets = indexIdentifierOccurrences(
            source,
            macroDefinitions.keys + discoveredHostDeclarations.map { it.name },
            lexical,
        )
        val hostCompilerNames = discoveredHostDeclarations.groupBy { it.name }.mapNotNull { (name, declarations) ->
            val references = identifierOffsets[name].orEmpty()
                .filterNot { offset -> hostDeclarationRanges.any { offset in it } }
                .filterNot { offset -> directives.any { offset in it.range } }
            if (references.isEmpty()) {
                null
            } else {
                require(name in COMPILER_READABLE_IRIS_HOST_NAMES) {
                    "$sourceName:${lines.lineAt(declarations.first().range.first)}: Iris host declaration " +
                        "$name is referenced by shader code"
                }
                name to uniqueHostCompilerName(source, name)
            }
        }.toMap()
        val hostDeclarations = discoveredHostDeclarations.map { declaration ->
            declaration.copy(compilerName = hostCompilerNames[declaration.name])
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
        val extensionRoots = conditionalGroups.filter { it.parentId == null }.filter { group ->
            atoms.any { atom -> atom.kind == IrisSourceContractKind.EXTENSION && atom.range.first in group.range }
        }
        directives.filter { located ->
            located.directive.kind in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.UNDEF) &&
                extensionRoots.any { located.range.first in it.range }
        }.forEach { located ->
            if (atoms.none { it.range == located.range }) {
                atoms += ContractAtom(IrisSourceContractKind.CONDITIONAL_CONTRACT, located.range, mask = false)
            }
        }
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
        val hostAndLocalRanges = localLayouts.map { it.range } + hostDeclarations.map { it.range }
        val hostAndLocalGroups = hostAndLocalRanges.mapNotNull { range ->
            conditionalGroups.filter { range.first in it.range }.maxByOrNull { it.depth }
        }.toSet()
        val directiveRanges = directives.map { it.range }
        val compilerMacroBodies = directives.filter { located ->
            located.directive.kind == PreprocessorDirectiveKind.DEFINE && located.directive.macroName != null
        }.groupBy(
            { requireNotNull(it.directive.macroName) },
            { it.directive.macroBody.orEmpty() },
        )
        val nonDirectiveIdentifierOffsets = IDENTIFIER.findAll(source)
            .filter { match ->
                match.value in compilerMacroBodies && directiveRanges.none { match.range.first in it }
            }
            .groupBy({ it.value }, { it.range.first })
        val codeRequiredMacroNames = nonDirectiveIdentifierOffsets.filterValues { offsets ->
            offsets.any { offset -> hostAndLocalRanges.none { offset in it } }
        }.keys.toMutableSet()
        while (true) {
            val before = codeRequiredMacroNames.size
            codeRequiredMacroNames.toList().forEach { name ->
                compilerMacroBodies[name].orEmpty().forEach { body ->
                    identifiers(body).filterTo(codeRequiredMacroNames, compilerMacroBodies::containsKey)
                }
            }
            if (codeRequiredMacroNames.size == before) break
        }
        fun hasNonContractUse(name: String): Boolean {
            if (name in codeRequiredMacroNames) return true
            val ownRanges = macroDefinitions[name].orEmpty().map { it.range }
            return (identifierOffsets[name].orEmpty() + nonDirectiveIdentifierOffsets[name].orEmpty()).any { offset ->
                ownRanges.none { offset in it } && hostAndLocalRanges.none { offset in it }
            }
        }
        val settingDependencyMemo = mutableMapOf<String, Boolean>()
        fun isSettingDependent(name: String, visiting: MutableSet<String>): Boolean {
            settingDependencyMemo[name]?.let { return it }
            if (!visiting.add(name)) return false
            val result = macroDefinitions[name].orEmpty().any { definition ->
                definition.predicate.settingNames().isNotEmpty() || identifiers(definition.body).any { identifier ->
                    identifier in settingsByName || isSettingDependent(identifier, visiting)
                }
            }
            visiting.remove(name)
            settingDependencyMemo[name] = result
            return result
        }
        macroDefinitions.forEach { (name, definitions) ->
            if (definitions.none { it.group in hostAndLocalGroups }) return@forEach
            if (!hasNonContractUse(name) || isSettingDependent(name, linkedSetOf())) {
                collectHelperMacros(name)
            }
        }
        while (true) {
            val selectedGroups = helperMacroNames.flatMap { macroDefinitions[it].orEmpty() }
                .mapNotNullTo(linkedSetOf()) { definition ->
                    definition.group?.takeIf { group ->
                        group.delimiters.any { delimiter ->
                            SETTING_IDENTIFIER.containsMatchIn(directiveCondition(delimiter.directive))
                        }
                    }
                }
            val before = helperMacroNames.size
            macroDefinitions.filterValues { definitions -> definitions.any { it.group in selectedGroups } }
                .keys
                .forEach(::collectHelperMacros)
            if (helperMacroNames.size == before) break
        }
        val compilerContractHelperNames = helperMacroNames.filterTo(linkedSetOf()) { name ->
            !hasNonContractUse(name) || isSettingDependent(name, linkedSetOf())
        }
        val discoveredHelperDefinitions = compilerContractHelperNames.flatMap { macroDefinitions[it].orEmpty() }
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
            compilerContractHelperNames,
            macroDefinitions,
            localLayouts,
            hostDeclarations,
            settingsByName,
            localAnalysis?.axisMacros.orEmpty(),
            directives,
            identifierOffsets,
        )
        val derivedMacros = derivedAnalysis.contracts
        val derivedMacroNames = derivedMacros.mapTo(hashSetOf()) { it.sourceName }
        val helperDefinitions = discoveredHelperDefinitions.filter { definition ->
            definition.name in derivedMacroNames ||
                definition.name in localAnalysis?.axisMacros.orEmpty() ||
                !hasNonContractUse(definition.name)
        }
        val hostCompilerAnalysis = buildHostCompilerDeclarations(
            sourceName,
            hostDeclarations,
            settingsByName,
            derivedMacros,
            localAnalysis?.axisMacros.orEmpty(),
        )

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
                val exactText = if (isDirectiveOnlyConditional(source, group, directives)) {
                    source.substring(group.range)
                } else {
                    synthesizeConditionalContract(source, group, groupedAtoms, conditionalGroups)
                }
                contractDrafts += ContractDraft(
                    IrisSourceContractKind.CONDITIONAL_CONTRACT,
                    group.range,
                    exactText,
                )
                consumedAtoms += groupedAtoms
                if (
                    groupedAtoms.all(ContractAtom::mask) &&
                    isContractOnlyConditional(source, group, groupedAtoms, conditionalGroups)
                ) {
                    maskRanges += group.range
                } else {
                    conditionalGroups.filter { candidate ->
                        candidate.id != group.id && candidate.range.first >= group.range.first &&
                            candidate.range.last <= group.range.last
                    }.forEach { candidate ->
                        val candidateAtoms = groupedAtoms.filter { atom ->
                            atom.range.first >= candidate.range.first && atom.range.last <= candidate.range.last
                        }
                        if (
                            candidateAtoms.isNotEmpty() &&
                            candidateAtoms.all(ContractAtom::mask) &&
                            isContractOnlyConditional(source, candidate, candidateAtoms, conditionalGroups)
                        ) {
                            maskRanges += candidate.range
                        }
                    }
                    groupedAtoms.filter { it.mask }.forEach { maskRanges += it.range }
                }
            }

        atoms.filter { it !in consumedAtoms }.forEach { atom ->
            contractDrafts += ContractDraft(atom.kind, atom.range, source.substring(atom.range))
            if (atom.mask) maskRanges += atom.range
        }

        val compilerIdentifierReplacements = linkedMapOf<String, String>()
        derivedMacros.forEach { derived ->
            compilerIdentifierReplacements[derived.sourceName] = derived.compilerName
        }
        localAnalysis?.axisMacros.orEmpty().forEach { (name, axis) ->
            compilerIdentifierReplacements[name] = "int(gl_WorkGroupSize.$axis)"
        }
        compilerIdentifierReplacements.putAll(hostCompilerNames)
        var compilerSource = replaceCodeIdentifiers(
            maskSourceRanges(source, maskRanges),
            compilerIdentifierReplacements,
        )

        val extensionLines = compilerExtensionLines(directives)
        val compilerPrelude = buildString {
            extensionLines.forEach { appendLine(it) }
            if (
                extensionLines.isNotEmpty() || settings.isNotEmpty() ||
                derivedMacros.isNotEmpty() || hostCompilerAnalysis.declarations.isNotEmpty() ||
                localAnalysis?.contract != null
            ) {
                appendLine(COMPILER_MARKER)
            }
            localAnalysis?.axisMacros.orEmpty().forEach { (name, axis) ->
                append("#define ")
                append(name)
                append(" int(gl_WorkGroupSize.")
                append(axis)
                appendLine(")")
            }
            derivedMacros.forEach {
                append("const ")
                append(it.compilerType.glslName)
                append(' ')
                append(it.compilerName)
                append(" = ")
                append(it.compilerExpression)
                appendLine(";")
            }
            hostCompilerAnalysis.declarations.forEach(::appendLine)
            localAnalysis?.contract?.let { append(it.compilerLayout) }
        }
        val dynamicTopLevel = lowerDynamicTopLevelConstants(
            compilerSource,
            settings.mapTo(linkedSetOf()) { it.name } +
                derivedMacros.map { it.compilerName } +
                hostCompilerNames.values +
                "gl_WorkGroupSize",
        )
        compilerSource = dynamicTopLevel.source
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
                draft.range,
                nearestBefore?.anchor,
                nearestAfter?.anchor,
                placement,
            )
        }
        val structuralIssues = buildList {
            extractionErrors.forEach { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            localAnalysis?.error?.let { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            derivedAnalysis.error?.let { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
            hostCompilerAnalysis.error?.let { add(IrisStructuralIssue(IrisStructuralIssueKind.UNSUPPORTED, it)) }
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
            hostCompilerNames,
            dynamicTopLevel.aliases,
        )
    }

    private fun buildDerivedMacros(
        sourceName: String,
        helperMacroNames: Set<String>,
        macroDefinitions: Map<String, List<ContractMacroDefinition>>,
        localLayouts: List<LocalLayout>,
        hostDeclarations: List<HostDeclaration>,
        settings: Map<String, ShaderSetting>,
        axisMacros: Map<String, Char>,
        directives: List<ContractDirective>,
        identifierOffsets: Map<String, List<Int>>,
    ): DerivedMacroAnalysis {
        val helperRanges = helperMacroNames.flatMap { macroDefinitions[it].orEmpty() }.map { it.range }
        val excluded = helperRanges + localLayouts.map { it.range } + hostDeclarations.map { it.range }
        val directlyUsed = helperMacroNames.filterTo(linkedSetOf()) { name ->
            name !in axisMacros && (
                identifierOffsets[name].orEmpty().any { offset ->
                    excluded.none { offset in it } && directives.none { offset in it.range }
                } || hostDeclarations.any { declaration ->
                    declaration.compilerName != null && name in identifiers(declaration.initializer)
                }
                )
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
        val contractTypes = mutableMapOf<String, ShaderSettingType>()
        ordered.forEach { name ->
            val definitions = macroDefinitions[name].orEmpty().sortedBy { it.range.first }
            if (definitions.isEmpty() || definitions.any { !SCALAR_MACRO_BODY.matches(stripComments(it.body).trim()) }) {
                return DerivedMacroAnalysis(error = "$sourceName: derived Iris contract macro $name is not a provable scalar expression")
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
            val type = if (definitions.any { definition ->
                    CONTRACT_FLOAT_LITERAL.containsMatchIn(stripComments(definition.body)) ||
                        identifiers(definition.body).any { identifier ->
                            settings[identifier]?.type == ShaderSettingType.FLOAT ||
                                contractTypes[identifier] == ShaderSettingType.FLOAT
                        }
                }
            ) {
                ShaderSettingType.FLOAT
            } else {
                ShaderSettingType.INT
            }
            contractTypes[name] = type
            contracts += IrisDerivedMacroContract(name, compilerNames.getValue(name), type, expression)
        }
        return DerivedMacroAnalysis(contracts)
    }

    private fun buildHostCompilerDeclarations(
        sourceName: String,
        declarations: List<HostDeclaration>,
        settings: Map<String, ShaderSetting>,
        derivedMacros: List<IrisDerivedMacroContract>,
        axisMacros: Map<String, Char>,
    ): HostCompilerAnalysis {
        val compilerGroups = declarations.filter { it.compilerName != null }.groupBy { it.name }
        if (compilerGroups.isEmpty()) return HostCompilerAnalysis()
        val derivedNames = derivedMacros.associate { it.sourceName to it.compilerName }
        val compilerDeclarations = mutableListOf<String>()
        compilerGroups.values.sortedBy { group -> group.minOf { it.range.first } }.forEach { group ->
            val name = group.first().name
            val types = group.map { it.type }.distinct()
            if (types.size != 1) {
                return HostCompilerAnalysis(error = "$sourceName: Iris host declaration $name changes type across settings: $types")
            }
            val compilerNames = group.mapNotNull { it.compilerName }.distinct()
            check(compilerNames.size == 1)
            val unsupportedPredicateNames = group.flatMapTo(linkedSetOf()) { it.predicate.identifierNames() }
                .filterNot { it in settings || it == "defined" || it == "true" || it == "false" }
            if (unsupportedPredicateNames.isNotEmpty()) {
                return HostCompilerAnalysis(
                    error = "$sourceName: Iris host declaration $name has unsupported predicate identifiers " +
                        unsupportedPredicateNames.sorted(),
                )
            }
            val relevantSettings = settings.values.filter { setting ->
                group.any { setting.name in it.predicate.settingNames() }
            }
            val assignments = enumerateAssignments(relevantSettings, MAX_LOCAL_SIZE_ASSIGNMENTS)
                ?: return HostCompilerAnalysis(
                    error = "$sourceName: Iris host declaration $name exceeds $MAX_LOCAL_SIZE_ASSIGNMENTS assignments",
                )
            val ambiguous = assignments.firstOrNull { assignment ->
                group.count { it.predicate.evaluate(assignment, settings) == true } != 1
            }
            if (ambiguous != null) {
                return HostCompilerAnalysis(
                    error = "$sourceName: Iris host declaration $name is not uniquely defined for $ambiguous",
                )
            }
            val bodies = assignments.associateWith { assignment ->
                val declaration = group.single { it.predicate.evaluate(assignment, settings) == true }
                convertMacroBody(declaration.initializer, settings, axisMacros, derivedNames)
            }
            val expression = renderDerivedDecision(relevantSettings, assignments, bodies)
            compilerDeclarations += "const ${types.single()} ${compilerNames.single()} = $expression;"
        }
        return HostCompilerAnalysis(compilerDeclarations)
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
private data class HostDeclaration(
    val name: String,
    val type: String,
    val initializer: String,
    val range: IntRange,
    val declaration: String,
    val predicate: ContractPredicate,
    val compilerName: String? = null,
)
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

private data class HostCompilerAnalysis(
    val declarations: List<String> = emptyList(),
    val error: String? = null,
)

private data class DynamicTopLevelConstant(
    val range: IntRange,
    val name: String,
    val initializer: String,
    val indent: String,
)

private data class DynamicTopLevelLowering(
    val source: String,
    val aliases: Map<String, String> = emptyMap(),
)

private data class ContractSourceReplacement(
    val range: IntRange,
    val text: String,
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

internal data class LocatedAnchor(val anchor: IrisSourceAnchor, val range: IntRange)

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
        val directiveOffsets = BooleanArray(source.length)
        directivePhysicalRanges(source).forEach { range ->
            for (offset in range) directiveOffsets[offset] = true
        }
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
            if (directiveOffsets[cursor]) {
                code[cursor] = false
                cursor++
                continue
            }
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

    fun depthAt(offset: Int): Int = depth.getOrElse(offset) { 0 }

    fun enclosingBlockEnd(offset: Int): Int? {
        val targetDepth = depthAt(offset)
        if (targetDepth == 0) return null
        for (cursor in offset until source.length) {
            if (source[cursor] == '}' && isCode(cursor) && depthAt(cursor) == targetDepth) return cursor
        }
        return null
    }

    fun matchingBlockEnd(openBrace: Int): Int? {
        if (source.getOrNull(openBrace) != '{' || !isCode(openBrace)) return null
        val targetDepth = depthAt(openBrace) + 1
        for (cursor in openBrace + 1 until source.length) {
            if (source[cursor] == '}' && isCode(cursor) && depthAt(cursor) == targetDepth) return cursor
        }
        return null
    }

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
        predicate.takeIf { expression -> IDENTIFIER.findAll(expression).any { it.value in settings } }
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
                if (gap.all(Char::isWhitespace)) {
                    append(gap)
                } else if (lastOrNull() !in listOf('\r', '\n')) {
                    append(LINE_ENDING.find(gap)?.value ?: "\n")
                }
            }
            append(source.substring(range))
            previousEnd = range.last
        }
    }
}

private fun isDirectiveOnlyConditional(
    source: String,
    group: ContractConditionalGroup,
    directives: List<ContractDirective>,
): Boolean {
    val covered = directives.asSequence()
        .map { it.range }
        .filter { it.first >= group.range.first && it.last <= group.range.last }
        .toList()
    return stripComments(copyOutsideRanges(source, group.range, covered)).isBlank()
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
    }
    return stripComments(copyOutsideRanges(source, group.range, covered)).isBlank()
}

private fun maskSourceRanges(source: String, ranges: Collection<IntRange>): String {
    val merged = mergeRanges(ranges, source.indices)
    if (merged.isEmpty()) return source
    return buildString(source.length) {
        var cursor = 0
        merged.forEach { range ->
            append(source, cursor, range.first)
            for (index in range) {
                append(if (source[index] == '\r' || source[index] == '\n') source[index] else ' ')
            }
            cursor = range.last + 1
        }
        append(source, cursor, source.length)
    }
}

private fun copyOutsideRanges(source: String, bounds: IntRange, ranges: Collection<IntRange>): String {
    val merged = mergeRanges(ranges, bounds)
    if (merged.isEmpty()) return source.substring(bounds)
    return buildString(bounds.count()) {
        var cursor = bounds.first
        merged.forEach { range ->
            append(source, cursor, range.first)
            cursor = range.last + 1
        }
        append(source, cursor, bounds.last + 1)
    }
}

private fun mergeRanges(ranges: Collection<IntRange>, bounds: IntRange): List<IntRange> {
    if (ranges.isEmpty() || bounds.isEmpty()) return emptyList()
    val sorted = ranges.asSequence()
        .mapNotNull { range ->
            val first = maxOf(range.first, bounds.first)
            val last = minOf(range.last, bounds.last)
            if (first <= last) first..last else null
        }
        .sortedBy(IntRange::first)
        .toList()
    if (sorted.isEmpty()) return emptyList()
    val merged = ArrayList<IntRange>(sorted.size)
    var current = sorted.first()
    sorted.drop(1).forEach { range ->
        if (range.first <= current.last + 1) {
            current = current.first..maxOf(current.last, range.last)
        } else {
            merged += current
            current = range
        }
    }
    merged += current
    return merged
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

private fun stripTopLevelExtensionDirectives(source: String): String {
    var conditionalDepth = 0
    return source.split('\n').joinToString("\n") { line ->
        val directive = DIRECTIVE_NAME.matchEntire(line)?.groupValues?.get(1)?.lowercase()
        when (directive) {
            "if", "ifdef", "ifndef" -> {
                conditionalDepth++
                line
            }
            "endif" -> {
                conditionalDepth = (conditionalDepth - 1).coerceAtLeast(0)
                line
            }
            "extension" -> if (
                conditionalDepth == 0 &&
                EXTENSION_DECLARATION.matchEntire(line.trim())?.groupValues?.get(1)
                    ?.startsWith("GL_KHR_shader_subgroup_") != true
            ) "" else line
            else -> line
        }
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
    val replacements = buildMap {
        settings.forEach { (name, setting) -> put(name, setting.compilerName) }
        axisMacros.forEach { (name, axis) -> put(name, "int(gl_WorkGroupSize.$axis)") }
        putAll(derivedMacros)
    }
    return replaceIdentifiers(stripComments(body).trim(), replacements)
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

private fun directivePhysicalRanges(source: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var continuation = false
    var offset = 0
    while (offset < source.length) {
        val newline = source.indexOfAny(charArrayOf('\r', '\n'), offset)
        val contentEnd = if (newline < 0) source.length else newline
        val lineEnd = when {
            newline < 0 -> source.length
            source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
            else -> newline + 1
        }
        val content = source.substring(offset, contentEnd)
        val directive = continuation || content.trimStart().startsWith('#')
        if (directive) result += offset until lineEnd
        continuation = directive && content.trimEnd().endsWith('\\')
        offset = lineEnd
    }
    return result
}

private fun replaceIdentifiers(source: String, replacements: Map<String, String>): String {
    if (replacements.isEmpty()) return source
    return IDENTIFIER.replace(source) { match -> replacements[match.value] ?: match.value }
}

private fun replaceCodeIdentifiers(source: String, replacements: Map<String, String>): String {
    if (replacements.isEmpty()) return source
    val lexical = ContractLexicalMap(source)
    val directiveRanges = directivePhysicalRanges(source)
    val matches = IDENTIFIER.findAll(source).filter { match ->
        match.value in replacements && lexical.isCode(match.range.first) &&
            directiveRanges.none { range -> match.range.first in range }
    }.toList()
    if (matches.isEmpty()) return source
    return buildString(source.length) {
        var cursor = 0
        matches.forEach { match ->
            append(source, cursor, match.range.first)
            append(replacements.getValue(match.value))
            cursor = match.range.last + 1
        }
        append(source, cursor, source.length)
    }
}

private fun lowerDynamicTopLevelConstants(source: String, initialDynamicNames: Set<String>): DynamicTopLevelLowering {
    if (initialDynamicNames.isEmpty()) return DynamicTopLevelLowering(source)
    val lexical = ContractLexicalMap(source)
    val constants = DYNAMIC_TOP_LEVEL_CONST_START.findAll(source).mapNotNull { match ->
        if (!lexical.isTopLevelCode(match.range.first)) return@mapNotNull null
        val semicolon = lexical.findCodeCharacter(';', match.range.last + 1)
            ?.takeIf(lexical::isTopLevelCode)
            ?: return@mapNotNull null
        DynamicTopLevelConstant(
            match.range.first..semicolon,
            match.groupValues[2],
            source.substring(match.range.last + 1, semicolon).trim(),
            match.groupValues[1],
        )
    }.toList()
    if (constants.isEmpty()) return DynamicTopLevelLowering(source)

    val dynamicNames = initialDynamicNames.toMutableSet()
    val lowered = linkedSetOf<DynamicTopLevelConstant>()
    var changed: Boolean
    do {
        changed = false
        constants.filterNot(lowered::contains).forEach { constant ->
            if (identifiers(constant.initializer).none(dynamicNames::contains)) return@forEach
            lowered += constant
            dynamicNames += constant.name
            changed = true
        }
    } while (changed)
    if (lowered.isEmpty()) return DynamicTopLevelLowering(source)

    val occupiedNames = IDENTIFIER.findAll(source).mapTo(linkedSetOf(), MatchResult::value)
    val aliases = lowered.sortedBy { it.name }.associate { constant ->
        var alias = "SM_DYNAMIC_${constant.name}"
        while (alias in occupiedNames) alias += '_'
        occupiedNames += alias
        constant.name to alias
    }
    val loweredRanges = lowered.map(DynamicTopLevelConstant::range)
    val directiveRanges = directivePhysicalRanges(source)
    val shadowRanges = localShadowRanges(source, aliases.keys, lexical)
    val replacements = mutableListOf<ContractSourceReplacement>()

    lowered.forEach { constant ->
        val expression = replaceFragmentIdentifiers(
            stripComments(constant.initializer).replace(WHITESPACE, " ").trim(),
            aliases,
        )
        replacements += ContractSourceReplacement(
            constant.range,
            "${constant.indent}#define ${aliases.getValue(constant.name)} ($expression)",
        )
    }
    IDENTIFIER.findAll(source).forEach { match ->
        val alias = aliases[match.value] ?: return@forEach
        val offset = match.range.first
        if (!lexical.isCode(offset)) return@forEach
        if (directiveRanges.any { offset in it }) return@forEach
        if (loweredRanges.any { offset in it }) return@forEach
        if (shadowRanges.getValue(match.value).any { offset in it }) return@forEach
        replacements += ContractSourceReplacement(match.range, alias)
    }
    return DynamicTopLevelLowering(
        replacements.sortedByDescending { it.range.first }.fold(source) { result, replacement ->
            result.replaceRange(replacement.range, replacement.text)
        },
        aliases,
    )
}

private fun localShadowRanges(
    source: String,
    names: Set<String>,
    lexical: ContractLexicalMap,
): Map<String, List<IntRange>> {
    val declarations = names.associateWith { mutableListOf<IntRange>() }
    LOCAL_DECLARATION.findAll(source).forEach { match ->
        val name = match.groups[1]?.value ?: return@forEach
        if (name !in names) return@forEach
        val offset = match.groups[1]?.range?.first ?: return@forEach
        if (!lexical.isCode(offset) || lexical.isTopLevel(offset)) return@forEach
        declarations.getValue(name) += offset..(lexical.enclosingBlockEnd(offset) ?: return@forEach)
    }

    FUNCTION_START.findAll(source).forEach { function ->
        if (!lexical.isTopLevelCode(function.range.first)) return@forEach
        val openParen = source.indexOf('(', function.range.first)
        val openBrace = source.indexOf('{', function.range.first)
        val closeParen = source.lastIndexOf(')', openBrace)
        if (openParen < 0 || closeParen < openParen || openBrace < 0 || openBrace > function.range.last) return@forEach
        val closeBrace = lexical.matchingBlockEnd(openBrace) ?: return@forEach
        IDENTIFIER.findAll(source, openParen + 1).takeWhile { it.range.first < closeParen }.filter {
            it.value in names && lexical.isCode(it.range.first)
        }.forEach { match ->
            declarations.getValue(match.value) += match.range.first..closeBrace
        }
    }
    return declarations
}

private val LOCAL_DECLARATION = Regex(
        "(?m)(?:^|[;{}])[\\t ]*" +
            "(?:(?:const|precise|highp|mediump|lowp|in|out|inout)\\s+)*" +
            "[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\[[^]\\r\\n]*])?\\s+([A-Za-z_][A-Za-z0-9_]*)\\b",
    )

private fun replaceFragmentIdentifiers(source: String, replacements: Map<String, String>): String {
    if (replacements.isEmpty()) return source
    val lexical = ContractLexicalMap(source)
    val matches = IDENTIFIER.findAll(source).filter {
        it.value in replacements && lexical.isCode(it.range.first)
    }.toList()
    return matches.asReversed().fold(source) { result, match ->
        result.replaceRange(match.range, replacements.getValue(match.value))
    }
}

private fun indexIdentifierOccurrences(
    source: String,
    targetNames: Set<String>,
    lexical: ContractLexicalMap,
): Map<String, List<Int>> {
    if (targetNames.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, MutableList<Int>>()
    IDENTIFIER.findAll(source).forEach { match ->
        if (match.value in targetNames && lexical.isCode(match.range.first)) {
            result.getOrPut(match.value, ::mutableListOf) += match.range.first
        }
    }
    return result
}

internal fun findStableAnchors(source: String): List<LocatedAnchor> {
    val lexical = ContractLexicalMap(source)
    val result = mutableListOf<LocatedAnchor>()
    VERSION_LINE.find(source)?.let { result += LocatedAnchor(IrisSourceAnchor(IrisAnchorKind.VERSION, "version"), it.range) }
    var lineOffset = 0
    while (lineOffset < source.length) {
        val newline = source.indexOfAny(charArrayOf('\r', '\n'), lineOffset)
        val contentEnd = if (newline < 0) source.length else newline
        val lineEnd = when {
            newline < 0 -> source.length
            source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
            else -> newline + 1
        }
        val line = source.substring(lineOffset, contentEnd)
        val firstCode = line.indexOfFirst { !it.isWhitespace() }
        if (
            firstCode >= 0 && lexical.isTopLevelCode(lineOffset + firstCode) &&
            ("uniform" in line || " in " in line || " out " in line)
        ) {
            STABLE_ABI_DECLARATION.find(line)?.let { match ->
                result += LocatedAnchor(
                    IrisSourceAnchor(
                        IrisAnchorKind.DECLARATION,
                        "${match.groupValues[1]}:${match.groupValues[3]}",
                    ),
                    (lineOffset + match.range.first)..(lineOffset + match.range.last),
                )
            }
        }
        lineOffset = lineEnd
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
    val branchBegin = source.indexOf("// SHADESMITH_BRANCH_OWNED_MAIN_BEGIN")
    val branchEnd = source.indexOf("// SHADESMITH_BRANCH_OWNED_MAIN_END")
    if (branchBegin >= 0 && branchEnd > branchBegin) {
        val mainAnchor = IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
        val branchMains = result.filter { it.anchor == mainAnchor && it.range.first in branchBegin..branchEnd }
        if (branchMains.isNotEmpty()) {
            result.removeAll { it.anchor == mainAnchor }
            result += LocatedAnchor(mainAnchor, branchBegin until branchEnd + "// SHADESMITH_BRANCH_OWNED_MAIN_END".length)
        }
    }
    val mainAnchor = IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
    val mainMatches = result.filter { it.anchor == mainAnchor }
    mutuallyExclusiveMainOwner(source, mainMatches)?.let { owner ->
        result.removeAll { it.anchor == mainAnchor }
        result += LocatedAnchor(mainAnchor, owner)
    }
    return result.sortedBy { it.range.first }
}

private fun mutuallyExclusiveMainOwner(source: String, matches: List<LocatedAnchor>): IntRange? {
    if (matches.size < 2) return null
    val lines = ContractLineMap(source)
    val directives = PreprocessorProtection.protect(source, "restored shader").directives.map { directive ->
        ContractDirective(directive, lines.directiveRange(directive))
    }
    return buildConditionalGroups(directives).asSequence()
        .filter { group ->
            "SETTING_" in group.opener.directive.exactText && matches.all { it.range.first in group.range }
        }
        .filter { group ->
            val arms = matches.map { match ->
                group.delimiters.indexOfLast { it.range.last < match.range.first }
            }
            arms.none { it < 0 } && arms.distinct().size == matches.size
        }
        .maxByOrNull { it.depth }
        ?.range
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

private fun normalizeCompilerText(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
private fun identifiers(source: String): List<String> = IDENTIFIER.findAll(stripComments(source)).map { it.value }.toList()
private fun identifierRegex(name: String): Regex = "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
private fun stripComments(source: String): String = BLOCK_COMMENT.replace(LINE_COMMENT.replace(source, ""), "")

private fun isHostDeclarationName(name: String): Boolean {
    return name in IRIS_HOST_NAMES || IRIS_HOST_NAME_PATTERNS.any { it.matches(name) }
}

private fun uniqueHostCompilerName(source: String, name: String): String {
    var candidate = "SM_IRIS_HOST_$name"
    while (identifierRegex(candidate).containsMatchIn(source)) candidate += '_'
    return candidate
}

private fun compilerHostDeclaration(name: String): Regex {
    return (
        "(?m)^[\\t ]*const[\\t ]+(?:int|float|bool|vec[234]|ivec3)[\\t ]+" +
            Regex.escape(name) + "\\b[^;\\r\\n]*;[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)"
        ).toRegex()
}

private fun compilerSettingDeclaration(name: String): Regex {
    return Regex(
        "(?m)^[\\t ]*(?:layout[\\t ]*\\([\\t ]*constant_id[\\t ]*=[^)]*\\)[\\t ]*)?" +
            "const[\\t ]+(?:bool|int|float)[\\t ]+${Regex.escape(name)}\\b[^;\\r\\n]*;" +
            "[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)",
    )
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
private val HOST_CONST_DECLARATION =
    "const[\\t ]+(int|float|bool|vec[234]|ivec3)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([\\s\\S]+?)\\s*;".toRegex()
private val DYNAMIC_TOP_LEVEL_CONST_START =
    ("(?m)^([\\t ]*)const[\\t ]+(?:lowp[\\t ]+|mediump[\\t ]+|highp[\\t ]+)?" +
        "[A-Za-z_][A-Za-z0-9_]*[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*=").toRegex()
private val IRIS_COMMENT_DIRECTIVE =
    "/\\*[\\t ]*(?:DRAWBUFFERS|RENDERTARGETS|SHADOWRES|SHADOWFOV|SHADOWHPL|GAUX4FORMAT):[\\s\\S]*?\\*/".toRegex()
private val EXTENSION_DECLARATION =
    "#\\s*extension\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(require|enable|warn|disable)".toRegex()
private val DIRECTIVE_NAME = "[\\t ]*#[\\t ]*([A-Za-z]+)\\b[^\\r\\n]*".toRegex()
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
private val SCALAR_MACRO_BODY = "[-+*/%(). A-Za-z0-9_]+".toRegex()
private val CONTRACT_FLOAT_LITERAL =
    "(?<![A-Za-z0-9_])(?:(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[0-9]+[eE][-+]?[0-9]+)".toRegex()
private val LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
private val LINE_COMMENT = "//[^\\r\\n]*".toRegex()
private val BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/".toRegex()
private val WHITESPACE = "\\s+".toRegex()
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
private val COMPILER_READABLE_IRIS_HOST_NAMES = IRIS_HOST_NAMES - setOf("workGroups", "workGroupsRender")
private val IRIS_HOST_NAME_PATTERNS = listOf(
    "(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])(?:Format|Clear|ClearColor|MipmapEnabled)".toRegex(),
    "shadowHardwareFiltering\\d+".toRegex(),
    "shadowtex\\d+(?:Mipmap|Nearest)".toRegex(),
    "shadow\\d+MinMagNearest".toRegex(),
    "shadowcolor\\d+(?:Mipmap|Nearest|Format|Clear|ClearColor)".toRegex(),
    "shadowColor\\d+(?:Mipmap|Nearest|MinMagNearest)".toRegex(),
)
