package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal enum class ShaderSettingType(val glslName: String) {
    BOOL("bool"),
    INT("int"),
    FLOAT("float"),
}

internal enum class ShaderControlKind {
    IRIS_SCALAR,
    IRIS_PRESENCE,
    HOST_PRESENCE,
}

internal enum class ShaderDerivedControlKind {
    SCALAR,
    PRESENCE,
}

internal data class ShaderSetting(
    val name: String,
    val type: ShaderSettingType,
    val defaultValue: String,
    val domain: List<String>,
    val presenceToggle: Boolean,
    val specializationId: Int,
    val sourceSlices: List<String>,
    val controlKind: ShaderControlKind = if (presenceToggle) {
        ShaderControlKind.IRIS_PRESENCE
    } else {
        ShaderControlKind.IRIS_SCALAR
    },
    val compilerName: String = if (controlKind == ShaderControlKind.HOST_PRESENCE) "SM_HOST_$name" else "SM_$name",
)

internal data class ShaderDerivedControl(
    val name: String,
    val kind: ShaderDerivedControlKind,
    val compilerExpression: String,
    val settingDependencies: Set<String>,
    val sourceSlices: List<String>,
    val sourceConditionalId: Int,
)

internal data class ShaderMacroDependency(
    val name: String,
    val functionLike: Boolean,
    val unconditionallyDefined: Boolean,
    val dependencies: Set<String>,
    val settingDependencies: Set<String>,
    val sourceSlices: List<String>,
    val derivedControl: ShaderDerivedControl? = null,
)

internal enum class ShaderSourceRegionKind {
    FUNCTION,
    TOP_LEVEL_DECLARATION,
}

internal data class ShaderSourceRegion(
    val kind: ShaderSourceRegionKind,
    val name: String?,
    val sourceLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val exactSlice: String,
)

internal enum class ShaderConditionalDisposition {
    COMPILER_NO_OP,
    CONTROL_FLOW_STATEMENT,
    CONTROL_FLOW_EXPRESSION,
    CONTROL_FLOW_FUNCTION,
    STRUCTURAL,
    UNRELATED,
}

internal data class ShaderConditionalRegion(
    val id: Int,
    val parentId: Int?,
    val sourceLine: Int,
    val endLine: Int,
    val exactSlice: String,
    val settingDependencies: Set<String>,
    val disposition: ShaderConditionalDisposition,
    val reason: String?,
)

internal data class ShaderCompilerCopyBlocker(
    val sourceLine: Int,
    val reason: String,
)

internal data class ShaderTokenPasteLowering(
    val macroName: String,
    val sourceLine: Int,
    val sourceDirective: String,
    val loweredDirective: String,
    val helperPrototypes: String,
    val helperNames: Set<String>,
    val candidateIdentifiers: Set<String>,
    val candidateCoverageComplete: Boolean,
    val settingDependencies: Set<String>,
)

internal data class ShaderCompilerCopyPlan(
    val sourceName: String,
    val originalSource: String,
    val compilerSource: String?,
    val compilerCandidateSource: String,
    val settings: List<ShaderSetting>,
    val derivedControls: List<ShaderDerivedControl>,
    val macros: List<ShaderMacroDependency>,
    val sourceRegions: List<ShaderSourceRegion>,
    val conditionals: List<ShaderConditionalRegion>,
    val structuralBlockers: List<ShaderCompilerCopyBlocker>,
    val irisContracts: IrisShaderContractPlan,
    val tokenPasteLowerings: List<ShaderTokenPasteLowering>,
) {
    val compilerModuleCount: Int
        get() = if (compilerSource == null) 0 else 1
}

internal object ShaderCompilerCopyPlanner {
    fun plan(
        source: String,
        sourceName: String = "<shader>",
        localSizeIdSupported: Boolean = true,
        localSizeProbeDiagnostic: String? = null,
    ): ShaderCompilerCopyPlan {
        val initialProtection = PreprocessorProtection.protect(source, sourceName)
        val initialSourceMap = SourceMap(source, sourceName)
        initialSourceMap.directives += initialProtection.directives
        val settingCandidates = collectSettingCandidates(initialProtection.directives)
        val initialMacroDrafts = collectMacros(initialProtection.directives)
        var initialMacroDependencies = resolveMacroDependencies(initialMacroDrafts)
        val initialGroups = buildConditionalGroups(initialProtection.directives, initialSourceMap)
        populateConditionalDependencies(initialGroups, initialMacroDependencies)
        val initialDerivedControls = collectDerivedControls(
            source,
            initialSourceMap,
            initialProtection.directives,
            initialGroups,
        )
        initialMacroDependencies = resolveMacroDependencies(initialMacroDrafts, initialDerivedControls)
        populateConditionalDependencies(initialGroups, initialMacroDependencies)

        val referencedSettings = initialGroups.flatMapTo(linkedSetOf()) { it.settingDependencies }
        val definitionRanges = initialProtection.directives
            .filter { it.macroName?.startsWith(SETTING_PREFIX) == true }
            .map { initialSourceMap.directiveRange(it) }
        val sourceWithoutDefinitions = applyReplacements(
            source,
            definitionRanges.map { Replacement(it.first, it.last + 1, maskSource(source.substring(it))) },
        )
        val executableSource = maskCommentsAndStrings(sourceWithoutDefinitions)
        referencedSettings += SETTING_TOKEN.findAll(executableSource).map { it.value }
        referencedSettings += initialMacroDependencies
            .filter { macro -> macro.settingDependencies.isNotEmpty() && macro.name in identifiers(executableSource) }
            .flatMap { it.settingDependencies }
        val sourceWithoutDirectives = applyReplacements(
            sourceWithoutDefinitions,
            initialProtection.directives.map { directive ->
                initialSourceMap.directiveRange(directive).let { range ->
                    Replacement(range.first, range.last + 1, maskSource(sourceWithoutDefinitions.substring(range)))
                }
            },
        )
        val unresolvedPresenceSettings = referencedSettings.filterTo(sortedSetOf()) { name ->
            settingCandidates[name].isNullOrEmpty() &&
                !IDENTIFIER_TOKEN(name).containsMatchIn(maskCommentsAndStrings(sourceWithoutDirectives)) &&
                initialGroups.filter { name in it.settingDependencies }.flatMap { it.delimiters }.all { branch ->
                    when (branch.kind) {
                        PreprocessorDirectiveKind.IFDEF,
                        PreprocessorDirectiveKind.IFNDEF,
                        -> branch.macroName == name
                        PreprocessorDirectiveKind.IF,
                        PreprocessorDirectiveKind.ELIF,
                        -> {
                            val expression = branch.expression.orEmpty()
                            val withoutDefined = DEFINED_IDENTIFIER.replace(expression) { match ->
                                if (match.groupValues[1] == name) "1" else match.value
                            }
                            !IDENTIFIER_TOKEN(name).containsMatchIn(withoutDefined)
                        }
                        else -> true
                    }
                }
        }

        val blockers = mutableListOf<ShaderCompilerCopyBlocker>()
        val optionControls = referencedSettings.sorted().mapNotNull { name ->
            val candidates = settingCandidates[name].orEmpty()
            if (candidates.isEmpty()) {
                if (name !in unresolvedPresenceSettings) {
                    blockers += ShaderCompilerCopyBlocker(
                        firstSettingUseLine(source, initialSourceMap, name),
                        "setting $name has no scalar option definition in this compiler root",
                    )
                }
                null
            } else {
                mergeSettingCandidates(name, candidates)?.also { candidate ->
                    if (candidate.reason != null) {
                        blockers += ShaderCompilerCopyBlocker(candidate.sourceLine, candidate.reason)
                    }
                }?.takeIf { it.reason == null }
            }
        }
        val hostControls = collectHostPresenceCandidates(
            source,
            initialSourceMap,
            initialProtection.directives,
            initialGroups,
            initialMacroDependencies,
            unresolvedPresenceSettings,
        )
        val controls = (optionControls + hostControls).distinctBy { it.name }.sortedBy { it.name }
        val existingIds = EXISTING_SPECIALIZATION_ID.findAll(source)
            .map { it.groupValues[1].toInt() }
            .toMutableSet()
        val reservedNames = identifiers(maskCommentsAndStrings(source)).toMutableSet()
        var nextId = 0
        val typedSettings = controls.map { candidate ->
            while (nextId in existingIds) nextId++
            val controlKind = requireNotNull(candidate.controlKind)
            val compilerName = allocateCompilerName(candidate.name, controlKind, reservedNames)
            val result = ShaderSetting(
                name = candidate.name,
                type = requireNotNull(candidate.type),
                defaultValue = requireNotNull(candidate.defaultValue),
                domain = candidate.domain,
                presenceToggle = candidate.presenceToggle,
                specializationId = nextId,
                sourceSlices = candidate.sourceSlices,
                controlKind = controlKind,
                compilerName = compilerName,
            )
            existingIds += nextId
            nextId++
            result
        }
        val settingsByName = typedSettings.associateBy { it.name }

        val irisContracts = IrisShaderContractExtractor.extract(
            source,
            sourceName,
            typedSettings,
            existingIds,
            localSizeIdSupported,
            localSizeProbeDiagnostic,
        )
        val tokenPasteLowering = lowerSettingTokenPasteDispatches(
            irisContracts.compilerSource,
            sourceName,
            typedSettings,
        )
        blockers += tokenPasteLowering.blockers
        val planningSource = tokenPasteLowering.source
        val protection = PreprocessorProtection.protect(planningSource, sourceName)
        val sourceMap = SourceMap(planningSource, sourceName)
        sourceMap.directives += protection.directives
        val macroDrafts = collectMacros(protection.directives)
        var macroDependencies = resolveMacroDependencies(macroDrafts)
        val groups = buildConditionalGroups(protection.directives, sourceMap)
        populateConditionalDependencies(groups, macroDependencies)
        val derivedControls = collectDerivedControls(
            planningSource,
            sourceMap,
            protection.directives,
            groups,
            settingsByName,
        )
        macroDependencies = resolveMacroDependencies(macroDrafts, derivedControls)
        populateConditionalDependencies(groups, macroDependencies)
        val regions = findSourceRegions(planningSource, sourceMap)
        irisContracts.structuralReason?.let { reason ->
            blockers += ShaderCompilerCopyBlocker(1, reason)
        }

        val groupById = groups.associateBy { it.id }
        groups.sortedByDescending { it.depth }.forEach { group ->
            classifyGroup(
                group,
                groupById,
                planningSource,
                sourceMap,
                settingsByName,
                macroDependencies,
                derivedControls,
                regions,
            )
            if (group.disposition == ShaderConditionalDisposition.STRUCTURAL) {
                blockers += ShaderCompilerCopyBlocker(group.opener.sourceLine, requireNotNull(group.reason))
            }
        }
        blockers += findStructuralSettingUses(
            planningSource,
            sourceMap,
            protection.directives,
            typedSettings.filter { it.controlKind != ShaderControlKind.HOST_PRESENCE }.mapTo(hashSetOf()) { it.name },
            macroDependencies,
        )
        protection.directives.filter {
            it.kind == PreprocessorDirectiveKind.UNDEF && it.macroName in settingsByName
        }.forEach {
            blockers += ShaderCompilerCopyBlocker(
                it.sourceLine,
                "setting ${it.macroName} is mutated with #undef",
            )
        }

        val distinctBlockers = blockers.distinctBy { it.sourceLine to it.reason }.sortedBy { it.sourceLine }
        val compilerCandidate = buildCompilerSource(
            planningSource,
            sourceMap,
            protection.directives,
            typedSettings,
            groups,
            macroDependencies,
            derivedControls,
        )
        return ShaderCompilerCopyPlan(
            sourceName = sourceName,
            originalSource = source,
            compilerSource = compilerCandidate.takeIf { distinctBlockers.isEmpty() },
            compilerCandidateSource = compilerCandidate,
            settings = typedSettings,
            derivedControls = derivedControls,
            macros = macroDependencies,
            sourceRegions = regions,
            conditionals = groups.map { group ->
                ShaderConditionalRegion(
                    id = group.id,
                    parentId = group.parentId,
                    sourceLine = group.opener.sourceLine,
                    endLine = requireNotNull(group.endif).endLine,
                    exactSlice = planningSource.substring(group.range),
                    settingDependencies = group.settingDependencies.toSortedSet(),
                    disposition = group.disposition,
                    reason = group.reason,
                )
            },
            structuralBlockers = distinctBlockers,
            irisContracts = irisContracts,
            tokenPasteLowerings = tokenPasteLowering.lowerings,
        )
    }

    private fun lowerSettingTokenPasteDispatches(
        source: String,
        sourceName: String,
        settings: List<ShaderSetting>,
    ): TokenPasteLoweringResult {
        if (settings.none { it.type == ShaderSettingType.INT } || "##" !in source) {
            return TokenPasteLoweringResult(source, emptyList(), emptyList())
        }
        val protection = PreprocessorProtection.protect(source, sourceName)
        val sourceMap = SourceMap(source, sourceName).also { it.directives += protection.directives }
        val definitions = protection.directives.filter {
            it.kind == PreprocessorDirectiveKind.DEFINE && it.macroName != null
        }
        val functionDefinitions = definitions.mapNotNull { directive ->
            parseFunctionMacro(directive)?.let { ParsedFunctionMacro(directive, it.first, it.second) }
        }
        val functionMacros = functionDefinitions.groupBy { requireNotNull(it.directive.macroName) }.mapNotNull { (name, items) ->
            val parameterLists = items.map(ParsedFunctionMacro::parameters).distinct()
            if (parameterLists.size != 1) null else name to FunctionMacro(name, parameterLists.single(), items)
        }.toMap()
        if (functionMacros.isEmpty()) return TokenPasteLoweringResult(source, emptyList(), emptyList())
        val objectMacros = definitions.filterNot { it.macroFunctionLike }.groupBy { requireNotNull(it.macroName) }
            .mapValues { (_, items) -> items.map { it.macroBody.orEmpty() } }
        val typeLikeObjectMacros = definitions.filter {
            !it.macroFunctionLike && PreprocessorFeature.TYPE_OR_QUALIFIER in it.features
        }.mapNotNull(PreprocessorDirective::macroName).toSet()
        val domains = mutableMapOf<MacroParameter, TokenValueDomain>()
        val edges = functionMacros.values.flatMap { caller ->
            caller.definitions.flatMap { definition ->
                findMacroInvocations(definition.body, functionMacros).map { invocation ->
                    MacroInvocationEdge(caller, invocation)
                }
            }
        }
        val sourceWithoutMacroDefinitions = applyReplacements(
            source,
            definitions.map { directive ->
                val range = sourceMap.directiveRange(directive)
                Replacement(range.first, range.last + 1, maskSource(source.substring(range)))
            },
        )
        val roots = findMacroInvocations(sourceWithoutMacroDefinitions, functionMacros)
        val settingsByName = settings.associateBy(ShaderSetting::name)

        var iteration = 0
        while (iteration <= functionMacros.size) {
            var changed = false
            roots.forEach { invocation ->
                changed = mergeInvocationDomains(
                    invocation,
                    emptyMap(),
                    emptySet(),
                    functionMacros,
                    objectMacros,
                    settingsByName,
                    domains,
                ) || changed
            }
            edges.forEach { edge ->
                val environment = edge.caller.parameters.associateWith { parameter ->
                    domains[MacroParameter(edge.caller.name, parameter)]
                }.mapNotNull { (name, domain) -> domain?.let { name to it } }.toMap()
                changed = mergeInvocationDomains(
                    edge.invocation,
                    environment,
                    edge.caller.parameters.toSet(),
                    functionMacros,
                    objectMacros,
                    settingsByName,
                    domains,
                ) || changed
            }
            if (!changed) break
            iteration++
        }
        val identifiers = identifiers(maskCommentsAndStrings(source)).toSet()
        val functionSignatures = collectFunctionSignatures(source)
        val constantTypes = collectConstantTypes(source)
        val lowerings = mutableListOf<TokenPasteLowering>()
        val loweringBlockers = mutableListOf<ShaderCompilerCopyBlocker>()
        functionMacros.values.forEach macroLoop@ { macro ->
            if (macro.definitions.map(ParsedFunctionMacro::body).distinct().size != 1) return@macroLoop
            val helperName = tokenPasteHelperName(macro.name)
            var emittedHelperDefinitions = false
            macro.definitions.forEach definitionLoop@ { definition ->
                if ("##" !in definition.body) return@definitionLoop
                val chain = parseTokenPasteChain(definition.body, macro.parameters) ?: return@definitionLoop
                val pastedDomains = chain.parameters.map { parameter ->
                    parameter to (domains[MacroParameter(macro.name, parameter)] ?: return@definitionLoop)
                }
                if (pastedDomains.none { (_, domain) -> domain.settings.isNotEmpty() }) return@definitionLoop
                if (pastedDomains.any { (_, domain) -> domain.values.isEmpty() }) {
                    return@definitionLoop
                }
                val orderedDomains = pastedDomains.map { (parameter, domain) ->
                    parameter to domain.values.sortedWith(
                        compareBy<String> { it.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it },
                    )
                }
                val combinations = cartesianTokenValues(orderedDomains)
                if (combinations.isEmpty() || combinations.size > MAX_TOKEN_PASTE_DISPATCH_CASES) {
                    return@definitionLoop
                }
                val existingHelper = reuseTokenPasteHelper(
                    macro,
                    definition,
                    chain,
                    functionSignatures,
                    helperName,
                )
                val candidatePattern = chain.candidatePattern()
                val allCandidates = identifiers.mapNotNull { identifier ->
                    val match = candidatePattern.matchEntire(identifier) ?: return@mapNotNull null
                    val assignment = linkedMapOf<String, String>()
                    chain.captureParameters.forEachIndexed { index, parameter ->
                        val value = match.groupValues[index + 1]
                        val previous = assignment.putIfAbsent(parameter, value)
                        if (previous != null && previous != value) return@mapNotNull null
                    }
                    assignment.toMap() to identifier
                }.toMap()
                val candidates = if (existingHelper == null) allCandidates else emptyMap()
                if (existingHelper == null && combinations.any { it !in candidates }) return@definitionLoop
                if (existingHelper == null && combinations.any { candidates[it] in typeLikeObjectMacros }) {
                    return@definitionLoop
                }
                val helper = existingHelper ?: renderTokenPasteHelper(
                    macro,
                    definition,
                    chain,
                    orderedDomains,
                    combinations,
                    candidates,
                    functionMacros,
                    functionSignatures,
                    constantTypes,
                    helperName,
                )
                if (helper == null) {
                    describeTokenPasteSignatureFailure(
                        definition,
                        chain,
                        orderedDomains,
                        pastedDomains,
                        combinations,
                        candidates,
                        functionMacros,
                        functionSignatures,
                        constantTypes,
                    )?.let { reason ->
                        loweringBlockers += ShaderCompilerCopyBlocker(definition.directive.sourceLine, reason)
                        return@definitionLoop
                    }
                }
                val dispatch = helper?.macroBody
                    ?: renderTokenPasteDispatch(definition.body, chain, orderedDomains, candidates)
                if ("##" in normalizeTokenPastePunctuation(dispatch)) return@definitionLoop
                val exact = definition.directive.exactText
                val bodyOffset = exact.indexOf(definition.body)
                if (bodyOffset < 0) return@definitionLoop
                val loweredDirective = exact.replaceRange(bodyOffset, bodyOffset + definition.body.length, dispatch)
                val lowered = buildString {
                    helper?.prototypes?.let(::append)
                    append(loweredDirective)
                }
                val range = sourceMap.directiveRange(definition.directive)
                lowerings += TokenPasteLowering(
                    Replacement(range.first, range.last + 1, lowered),
                    helper?.definitions.orEmpty().takeIf { !emittedHelperDefinitions }.orEmpty(),
                    ShaderTokenPasteLowering(
                        macroName = macro.name,
                        sourceLine = definition.directive.sourceLine,
                        sourceDirective = exact,
                        loweredDirective = loweredDirective,
                        helperPrototypes = helper?.prototypes.orEmpty(),
                        helperNames = if (helper == null) emptySet() else setOf(helperName),
                        candidateIdentifiers = combinations.mapNotNull(allCandidates::get).toSortedSet(),
                        candidateCoverageComplete = combinations.all(allCandidates::containsKey),
                        settingDependencies = pastedDomains.flatMapTo(sortedSetOf()) { (_, domain) -> domain.settings },
                    ),
                )
                emittedHelperDefinitions = emittedHelperDefinitions || helper != null
            }
        }
        val lowered = applyReplacements(source, lowerings.map(TokenPasteLowering::replacement))
        val helpers = lowerings.map(TokenPasteLowering::helperDefinitions).filter(String::isNotBlank)
        val loweredSource = if (helpers.isEmpty()) {
            lowered
        } else {
            lowered.trimEnd() + "\n\n" + helpers.joinToString("\n") + "\n"
        }
        return TokenPasteLoweringResult(
            loweredSource,
            loweringBlockers,
            lowerings.map(TokenPasteLowering::contract).distinct(),
        )
    }

    internal fun tokenPasteHelperName(macroName: String): String {
        val encoded = macroName.map { character -> character.code.toString(16).padStart(2, '0') }.joinToString("")
        return "SM_TOKEN_PASTE_M$encoded"
    }

    private fun mergeInvocationDomains(
        invocation: ParsedMacroInvocation,
        environment: Map<String, TokenValueDomain>,
        callerParameters: Set<String>,
        functionMacros: Map<String, FunctionMacro>,
        objectMacros: Map<String, List<String>>,
        settings: Map<String, ShaderSetting>,
        domains: MutableMap<MacroParameter, TokenValueDomain>,
    ): Boolean {
        val macro = functionMacros[invocation.name] ?: return false
        if (macro.parameters.size != invocation.arguments.size) return false
        var changed = false
        macro.parameters.zip(invocation.arguments).forEach { (parameter, argument) ->
            val token = stripOuterParentheses(stripComments(argument).trim())
            if (token in callerParameters && token !in environment) return@forEach
            val domain = resolveTokenValueDomain(argument, environment, objectMacros, settings, linkedSetOf())
                ?: return@forEach
            val key = MacroParameter(macro.name, parameter)
            val merged = domains[key]?.merge(domain) ?: domain
            if (merged != domains[key]) {
                domains[key] = merged
                changed = true
            }
        }
        return changed
    }

    private fun resolveTokenValueDomain(
        expression: String,
        environment: Map<String, TokenValueDomain>,
        objectMacros: Map<String, List<String>>,
        settings: Map<String, ShaderSetting>,
        visiting: MutableSet<String>,
    ): TokenValueDomain? {
        val value = stripOuterParentheses(stripComments(expression).trim())
        canonicalTokenInteger(value)?.let { return TokenValueDomain(setOf(it), emptySet()) }
        if (!IDENTIFIER.matches(value)) return null
        environment[value]?.let { return it }
        settings[value]?.takeIf { it.type == ShaderSettingType.INT }?.let { setting ->
            return TokenValueDomain(
                setting.domain.mapNotNull(::canonicalTokenInteger).toSet(),
                setOf(setting.name),
            ).takeIf { it.values.size == setting.domain.size }
        }
        val bodies = objectMacros[value] ?: return TokenValueDomain(setOf(value), emptySet())
        if (!visiting.add(value)) return null
        val resolved = bodies.mapNotNull { body ->
            resolveTokenValueDomain(body, environment, objectMacros, settings, visiting)
        }
        visiting.remove(value)
        if (resolved.size != bodies.size || resolved.isEmpty()) return null
        return resolved.reduce(TokenValueDomain::merge)
    }

    private fun canonicalTokenInteger(value: String): String? {
        if (!INT_LITERAL.matches(value)) return null
        return runCatching {
            val unsigned = value.removePrefix("+")
            when {
                unsigned.startsWith("-0x", true) -> -unsigned.substring(3).toLong(16)
                unsigned.startsWith("0x", true) -> unsigned.substring(2).toLong(16)
                else -> unsigned.toLong()
            }.toString()
        }.getOrNull()?.takeIf { !it.startsWith('-') }
    }

    private fun stripOuterParentheses(source: String): String {
        var result = source
        while (result.startsWith('(') && result.endsWith(')')) {
            var depth = 0
            var wraps = true
            result.forEachIndexed { index, character ->
                when (character) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0 && index != result.lastIndex) wraps = false
            }
            if (!wraps || depth != 0) break
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun parseFunctionMacro(directive: PreprocessorDirective): Pair<List<String>, String>? {
        if (!directive.macroFunctionLike) return null
        val name = requireNotNull(directive.macroName)
        val exact = directive.exactText
        val nameOffset = exact.indexOf(name)
        if (nameOffset < 0) return null
        val open = exact.indexOf('(', nameOffset + name.length)
        if (open < 0) return null
        val close = matchingDelimiter(exact, open, '(', ')') ?: return null
        val parameters = splitMacroArguments(exact.substring(open + 1, close)) ?: return null
        if (parameters.any { !IDENTIFIER.matches(it) }) return null
        return parameters to directive.macroBody.orEmpty()
    }

    private fun findMacroInvocations(
        source: String,
        macros: Map<String, FunctionMacro>,
    ): List<ParsedMacroInvocation> {
        val masked = maskCommentsAndStrings(source)
        val result = mutableListOf<ParsedMacroInvocation>()
        IDENTIFIER.findAll(masked).forEach { match ->
            val name = match.value
            if (name !in macros) return@forEach
            var cursor = match.range.last + 1
            while (cursor < masked.length && masked[cursor].isWhitespace()) cursor++
            if (masked.getOrNull(cursor) != '(') return@forEach
            val close = matchingDelimiter(masked, cursor, '(', ')') ?: return@forEach
            val arguments = splitMacroArguments(source.substring(cursor + 1, close)) ?: return@forEach
            result += ParsedMacroInvocation(name, arguments)
        }
        return result.distinct()
    }

    private fun matchingDelimiter(source: String, open: Int, opening: Char, closing: Char): Int? {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun splitMacroArguments(source: String): List<String>? {
        if (source.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var parentheses = 0
        var brackets = 0
        var braces = 0
        source.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
                '{' -> braces++
                '}' -> braces--
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    result += source.substring(start, index).trim()
                    start = index + 1
                }
            }
            if (parentheses < 0 || brackets < 0 || braces < 0) return null
        }
        if (parentheses != 0 || brackets != 0 || braces != 0) return null
        result += source.substring(start).trim()
        return result.takeIf { it.none(String::isEmpty) }
    }

    private fun parseTokenPasteChain(body: String, parameters: List<String>): TokenPasteChain? {
        val matches = TOKEN_PASTE_CHAIN.findAll(body).mapNotNull { match ->
            val parts = match.value.split(TOKEN_PASTE_SEPARATOR).map(String::trim)
            val pasted = parts.filter(parameters::contains).distinct()
            if (pasted.isEmpty()) null else TokenPasteChain(match.range, parts, pasted)
        }.toList()
        if (matches.size != 1) return null
        val chain = matches.single()
        val outside = body.removeRange(chain.range)
        if (chain.parameters.any { IDENTIFIER_TOKEN(it).containsMatchIn(outside) }) return null
        return chain
    }

    private fun cartesianTokenValues(
        domains: List<Pair<String, List<String>>>,
    ): List<Map<String, String>> {
        var rows = listOf<Map<String, String>>(emptyMap())
        domains.forEach { (name, values) ->
            rows = rows.flatMap { row -> values.map { value -> row + (name to value) } }
            if (rows.size > MAX_TOKEN_PASTE_DISPATCH_CASES) return emptyList()
        }
        return rows
    }

    private fun renderTokenPasteDispatch(
        body: String,
        chain: TokenPasteChain,
        domains: List<Pair<String, List<String>>>,
        candidates: Map<Map<String, String>, String>,
    ): String {
        fun candidate(assignment: Map<String, String>): String {
            val identifier = requireNotNull(candidates[assignment])
            return normalizeTokenPastePunctuation(body.replaceRange(chain.range, identifier))
        }
        fun render(depth: Int, assignment: Map<String, String>): String {
            if (depth == domains.size) return candidate(assignment)
            val (parameter, values) = domains[depth]
            var result = render(depth + 1, assignment + (parameter to values.last()))
            values.dropLast(1).asReversed().forEach { value ->
                val selected = render(depth + 1, assignment + (parameter to value))
                result = "((($parameter) == $value) ? ($selected) : ($result))"
            }
            return result
        }
        return render(0, emptyMap())
    }

    private fun reuseTokenPasteHelper(
        macro: FunctionMacro,
        definition: ParsedFunctionMacro,
        chain: TokenPasteChain,
        functionSignatures: Map<String, Set<ValueSignature>>,
        helperName: String,
    ): TokenPasteHelper? {
        val signatures = functionSignatures[helperName].orEmpty()
        if (signatures.isEmpty()) return null
        val call = parseTokenPasteCall(definition.body, chain) ?: return null
        val valueParameters = call.arguments.map { stripOuterParentheses(it.trim()) }
        if (valueParameters.any { it !in macro.parameters || it in chain.parameters }) return null
        val orderedValueParameters = valueParameters.distinct()
        val parameterCount = chain.parameters.size + orderedValueParameters.size
        val compatibleSignatures = signatures.filter { signature ->
            signature.returnType != "void" &&
                signature.parameterTypes.size == parameterCount &&
                signature.parameterTypes.take(chain.parameters.size).all { it == "int" }
        }
        if (compatibleSignatures.isEmpty()) return null
        val parameterNames = chain.parameters + orderedValueParameters
        val prototypes = compatibleSignatures.sortedWith(
            compareBy<ValueSignature> { it.returnType }.thenBy { it.parameterTypes.joinToString("\u0000") },
        ).joinToString("") { signature ->
            val parameters = parameterNames.zip(signature.parameterTypes).joinToString(", ") { (name, type) ->
                "$type $name"
            }
            "${signature.returnType} $helperName($parameters);\n"
        }
        return TokenPasteHelper(
            macroBody = "$helperName(${(chain.parameters + orderedValueParameters).joinToString(", ")})",
            prototypes = prototypes,
            definitions = "",
        )
    }

    private fun describeTokenPasteSignatureFailure(
        definition: ParsedFunctionMacro,
        chain: TokenPasteChain,
        domains: List<Pair<String, List<String>>>,
        pastedDomains: List<Pair<String, TokenValueDomain>>,
        combinations: List<Map<String, String>>,
        candidates: Map<Map<String, String>, String>,
        functionMacros: Map<String, FunctionMacro>,
        functionSignatures: Map<String, Set<ValueSignature>>,
        constantTypes: Map<String, String>,
    ): String? {
        if (domains.any { (_, values) -> values.any { canonicalTokenInteger(it) == null } }) return null
        val call = parseTokenPasteCall(definition.body, chain) ?: return null
        val candidateNames = combinations.map { assignment ->
            requireNotNull(candidates[assignment])
        }.distinct().sorted()
        val signatures = candidateNames.associateWith { candidate ->
            inferCandidateSignatures(
                candidate,
                functionMacros,
                functionSignatures,
                constantTypes,
                linkedSetOf(),
            ).filter { it.parameterTypes.size == call.arguments.size }.sortedWith(
                compareBy<ValueSignature> { it.returnType }.thenBy { it.parameterTypes.joinToString("\u0000") },
            )
        }
        val expansionDomain = combinations.joinToString(prefix = "[", postfix = "]") { assignment ->
            val values = chain.parameters.joinToString(",") { parameter ->
                "$parameter=${requireNotNull(assignment[parameter])}"
            }
            "{$values}->${requireNotNull(candidates[assignment])}"
        }
        val candidateSignatures = signatures.entries.joinToString(prefix = "[", postfix = "]") { (name, values) ->
            val rendered = values.takeIf(List<ValueSignature>::isNotEmpty)?.joinToString("|") { signature ->
                "${signature.returnType}(${signature.parameterTypes.joinToString(",")})"
            } ?: "<unresolved>"
            "$name=$rendered"
        }
        val dependencyComponent = pastedDomains.flatMapTo(sortedSetOf()) { (_, domain) -> domain.settings }
        return "token-paste macro ${definition.directive.macroName} at line ${definition.directive.sourceLine} " +
            "has no common typed lowering; expansion_domain=$expansionDomain; " +
            "candidate_signatures=$candidateSignatures; dependency_component=$dependencyComponent"
    }

    private fun renderTokenPasteHelper(
        macro: FunctionMacro,
        definition: ParsedFunctionMacro,
        chain: TokenPasteChain,
        domains: List<Pair<String, List<String>>>,
        combinations: List<Map<String, String>>,
        candidates: Map<Map<String, String>, String>,
        functionMacros: Map<String, FunctionMacro>,
        functionSignatures: Map<String, Set<ValueSignature>>,
        constantTypes: Map<String, String>,
        helperName: String,
    ): TokenPasteHelper? {
        if (domains.any { (_, values) -> values.any { canonicalTokenInteger(it) == null } }) return null
        val call = parseTokenPasteCall(definition.body, chain) ?: return null
        val valueParameters = call.arguments.map { stripOuterParentheses(it.trim()) }
        if (valueParameters.any { it !in macro.parameters || it in chain.parameters }) return null
        val orderedValueParameters = valueParameters.distinct()
        val signatureSets = combinations.map { assignment ->
            inferCandidateSignatures(
                requireNotNull(candidates[assignment]),
                functionMacros,
                functionSignatures,
                constantTypes,
                linkedSetOf(),
            ).filterTo(linkedSetOf()) {
                it.returnType != "void" && it.parameterTypes.size == valueParameters.size
            }
        }
        if (signatureSets.any(Set<ValueSignature>::isEmpty)) return null
        val commonSignatures = signatureSets.drop(1).fold(signatureSets.first().toSet()) { common, signatures ->
            common intersect signatures
        }
        val overloads = commonSignatures.mapNotNullTo(linkedSetOf()) { signature ->
            val types = linkedMapOf<String, String>()
            valueParameters.zip(signature.parameterTypes).forEach { (parameter, type) ->
                val previous = types.putIfAbsent(parameter, type)
                if (previous != null && previous != type) return@mapNotNullTo null
            }
            if (orderedValueParameters.any { it !in types }) return@mapNotNullTo null
            TokenPasteHelperOverload(
                signature.returnType,
                orderedValueParameters.map { it to requireNotNull(types[it]) },
            )
        }.sortedWith(compareBy<TokenPasteHelperOverload> { it.returnType }.thenBy { it.valueParameters.toString() })
        if (overloads.isEmpty()) return null
        val helperArguments = chain.parameters + orderedValueParameters
        val prototypes = overloads.joinToString("") { overload ->
            "${overload.returnType} $helperName(${renderTokenPasteHelperParameters(chain, overload)});\n"
        }
        val definitions = overloads.joinToString("\n\n") { overload ->
            buildString {
                append(overload.returnType)
                append(' ')
                append(helperName)
                append('(')
                append(renderTokenPasteHelperParameters(chain, overload))
                appendLine(") {")
                append(
                    renderTokenPasteHelperDispatch(
                        definition.body,
                        chain,
                        domains,
                        candidates,
                    ),
                )
                append('}')
            }
        }
        return TokenPasteHelper(
            macroBody = "$helperName(${helperArguments.joinToString(", ")})",
            prototypes = prototypes,
            definitions = definitions,
        )
    }

    private fun renderTokenPasteHelperDispatch(
        body: String,
        chain: TokenPasteChain,
        domains: List<Pair<String, List<String>>>,
        candidates: Map<Map<String, String>, String>,
        depth: Int = 0,
        assignment: Map<String, String> = emptyMap(),
        indent: String = "    ",
    ): String {
        if (depth == domains.size) {
            return buildString {
                append(indent)
                append("return ")
                append(renderTokenPasteCandidate(body, chain, requireNotNull(candidates[assignment])))
                appendLine(';')
            }
        }
        val (parameter, values) = domains[depth]
        return buildString {
            append(indent)
            append("switch (")
            append(parameter)
            appendLine(") {")
            values.dropLast(1).forEach { value ->
                append(indent)
                append("case ")
                append(value)
                appendLine(':')
                append(
                    renderTokenPasteHelperDispatch(
                        body,
                        chain,
                        domains,
                        candidates,
                        depth + 1,
                        assignment + (parameter to value),
                        "$indent    ",
                    ),
                )
            }
            append(indent)
            appendLine('}')
            append(
                renderTokenPasteHelperDispatch(
                    body,
                    chain,
                    domains,
                    candidates,
                    depth + 1,
                    assignment + (parameter to values.last()),
                    indent,
                ),
            )
        }
    }

    private fun renderTokenPasteHelperParameters(
        chain: TokenPasteChain,
        overload: TokenPasteHelperOverload,
    ): String {
        return (chain.parameters.map { "int $it" } + overload.valueParameters.map { (name, type) -> "$type $name" })
            .joinToString(", ")
    }

    private fun renderTokenPasteCandidate(body: String, chain: TokenPasteChain, candidate: String): String {
        return normalizeTokenPastePunctuation(body.replaceRange(chain.range, candidate))
    }

    private fun parseTokenPasteCall(body: String, chain: TokenPasteChain): TokenPasteCall? {
        val sentinel = "SM_TOKEN_PASTE_CANDIDATE"
        val replaced = stripOuterParentheses(
            normalizeTokenPastePunctuation(body.replaceRange(chain.range, sentinel)).trim(),
        )
        if (replaced == sentinel) return TokenPasteCall(emptyList())
        val match = IDENTIFIER.find(replaced) ?: return null
        if (match.range.first != 0 || match.value != sentinel) return null
        var cursor = match.range.last + 1
        while (cursor < replaced.length && replaced[cursor].isWhitespace()) cursor++
        if (replaced.getOrNull(cursor) != '(') return null
        val close = matchingDelimiter(replaced, cursor, '(', ')') ?: return null
        if (replaced.substring(close + 1).isNotBlank()) return null
        return TokenPasteCall(splitMacroArguments(replaced.substring(cursor + 1, close)) ?: return null)
    }

    private fun collectFunctionSignatures(source: String): Map<String, Set<ValueSignature>> {
        val masked = maskCommentsAndStrings(source)
        return FUNCTION_DEFINITION.findAll(masked).mapNotNull { match ->
            val returnType = match.groupValues[1]
            val name = match.groupValues[2]
            val parameters = splitMacroArguments(source.substring(match.groups[3]!!.range)) ?: return@mapNotNull null
            val parameterTypes = parameters.map { parseFunctionParameterType(it) ?: return@mapNotNull null }
            name to ValueSignature(returnType, parameterTypes)
        }.groupBy({ it.first }, { it.second }).mapValues { (_, signatures) -> signatures.toSet() }
    }

    private fun parseFunctionParameterType(parameter: String): String? {
        if ('[' in parameter || ']' in parameter) return null
        val tokens = IDENTIFIER.findAll(stripComments(parameter)).map { it.value }.toList()
        if (tokens.any { it == "out" || it == "inout" }) return null
        val type = tokens.firstOrNull { it !in PARAMETER_QUALIFIERS } ?: return null
        return type.takeIf(GLSL_TYPE::matches)
    }

    private fun collectConstantTypes(source: String): Map<String, String> {
        val masked = maskCommentsAndStrings(source)
        return CONSTANT_DEFINITION.findAll(masked).mapNotNull { match ->
            if ('[' in match.value) null else match.groupValues[2] to match.groupValues[1]
        }.toMap()
    }

    private fun inferCandidateSignatures(
        name: String,
        functionMacros: Map<String, FunctionMacro>,
        functionSignatures: Map<String, Set<ValueSignature>>,
        constantTypes: Map<String, String>,
        visiting: MutableSet<String>,
    ): Set<ValueSignature> {
        val result = functionSignatures[name].orEmpty().toMutableSet()
        constantTypes[name]?.let { result += ValueSignature(it, emptyList()) }
        val macro = functionMacros[name]
        val body = macro?.definitions?.map(ParsedFunctionMacro::body)?.distinct()?.singleOrNull()
        if (macro == null || body == null || !visiting.add(name)) return result
        result += inferMacroBodySignatures(
            macro,
            body,
            functionMacros,
            functionSignatures,
            constantTypes,
            visiting,
        )
        visiting.remove(name)
        return result
    }

    private fun inferMacroBodySignatures(
        macro: FunctionMacro,
        body: String,
        functionMacros: Map<String, FunctionMacro>,
        functionSignatures: Map<String, Set<ValueSignature>>,
        constantTypes: Map<String, String>,
        visiting: MutableSet<String>,
    ): Set<ValueSignature> {
        val expression = stripOuterParentheses(body.trim())
        val invocation = parseWholeInvocation(expression)
        if (invocation != null) {
            val targetSignatures = inferCandidateSignatures(
                invocation.name,
                functionMacros,
                functionSignatures,
                constantTypes,
                visiting,
            )
            val inherited = targetSignatures.mapNotNullTo(linkedSetOf()) { signature ->
                if (signature.parameterTypes.size != invocation.arguments.size) return@mapNotNullTo null
                val types = linkedMapOf<String, String>()
                invocation.arguments.zip(signature.parameterTypes).forEach { (argument, type) ->
                    val parameter = stripOuterParentheses(argument.trim())
                    if (parameter !in macro.parameters) return@mapNotNullTo null
                    val previous = types.putIfAbsent(parameter, type)
                    if (previous != null && previous != type) return@mapNotNullTo null
                }
                if (macro.parameters.any { it !in types }) return@mapNotNullTo null
                ValueSignature(signature.returnType, macro.parameters.map { requireNotNull(types[it]) })
            }
            if (inherited.isNotEmpty()) return inherited
        }
        if (macro.parameters.size != 1) return emptySet()
        val parameter = macro.parameters.single()
        val matrixSymbols = identifiers(expression).distinct().mapNotNull { name ->
            val size = constantTypes[name]?.let(MATRIX_TYPE::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
            size?.let { name to it }
        }
        val parameterPattern = "\\(?\\s*\\b${Regex.escape(parameter)}\\b\\s*\\)?"
        val dotSizes = matrixSymbols.filter { (name, _) ->
            val matrixPattern = "\\b${Regex.escape(name)}\\b\\s*\\[[^]]+]"
            ("\\bdot\\s*\\(\\s*$parameterPattern\\s*,\\s*$matrixPattern\\s*\\)".toRegex()
                .containsMatchIn(expression) ||
                "\\bdot\\s*\\(\\s*$matrixPattern\\s*,\\s*$parameterPattern\\s*\\)".toRegex()
                    .containsMatchIn(expression))
        }.mapTo(linkedSetOf()) { it.second }
        if (dotSizes.size == 1) {
            val size = dotSizes.single()
            return setOf(ValueSignature("float", listOf("vec$size")))
        }
        val multiplySizes = matrixSymbols.filter { (name, _) ->
            val matrixPattern = "\\b${Regex.escape(name)}\\b"
            ("$parameterPattern\\s*\\*\\s*$matrixPattern".toRegex().containsMatchIn(expression) ||
                "$matrixPattern\\s*\\*\\s*$parameterPattern".toRegex().containsMatchIn(expression))
        }.mapTo(linkedSetOf()) { it.second }
        if (multiplySizes.size == 1) {
            val size = multiplySizes.single()
            val vector = "vec$size"
            return setOf(ValueSignature(vector, listOf(vector)))
        }
        val component = PARAMETER_COMPONENT.find(expression)?.takeIf { it.groupValues[1] == parameter }
            ?.groupValues?.get(2)?.singleOrNull()
        if (component != null) {
            val minimumSize = "xyzw".indexOf(component) + 1
            return (maxOf(2, minimumSize)..4).mapTo(linkedSetOf()) { size ->
                ValueSignature("float", listOf("vec$size"))
            }
        }
        return emptySet()
    }

    private fun parseWholeInvocation(source: String): ParsedMacroInvocation? {
        val match = IDENTIFIER.find(source) ?: return null
        if (match.range.first != 0) return null
        var cursor = match.range.last + 1
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        if (source.getOrNull(cursor) != '(') return null
        val close = matchingDelimiter(source, cursor, '(', ')') ?: return null
        if (source.substring(close + 1).isNotBlank()) return null
        val arguments = splitMacroArguments(source.substring(cursor + 1, close)) ?: return null
        return ParsedMacroInvocation(match.value, arguments)
    }

    private fun normalizeTokenPastePunctuation(source: String): String {
        return TOKEN_PASTE_BEFORE_DELIMITER.replace(source, "")
    }

    private data class ParsedFunctionMacro(
        val directive: PreprocessorDirective,
        val parameters: List<String>,
        val body: String,
    )

    private data class FunctionMacro(
        val name: String,
        val parameters: List<String>,
        val definitions: List<ParsedFunctionMacro>,
    )

    private data class ParsedMacroInvocation(val name: String, val arguments: List<String>)

    private data class MacroInvocationEdge(
        val caller: FunctionMacro,
        val invocation: ParsedMacroInvocation,
    )

    private data class MacroParameter(val macro: String, val parameter: String)

    private data class ValueSignature(val returnType: String, val parameterTypes: List<String>)

    private data class TokenPasteCall(val arguments: List<String>)

    private data class TokenPasteHelperOverload(
        val returnType: String,
        val valueParameters: List<Pair<String, String>>,
    )

    private data class TokenPasteHelper(
        val macroBody: String,
        val prototypes: String,
        val definitions: String,
    )

    private data class TokenPasteLowering(
        val replacement: Replacement,
        val helperDefinitions: String,
        val contract: ShaderTokenPasteLowering,
    )

    private data class TokenPasteLoweringResult(
        val source: String,
        val blockers: List<ShaderCompilerCopyBlocker>,
        val lowerings: List<ShaderTokenPasteLowering>,
    )

    private data class TokenValueDomain(
        val values: Set<String>,
        val settings: Set<String>,
    ) {
        fun merge(other: TokenValueDomain): TokenValueDomain {
            return TokenValueDomain(values + other.values, settings + other.settings)
        }
    }

    private data class TokenPasteChain(
        val range: IntRange,
        val parts: List<String>,
        val parameters: List<String>,
    ) {
        val captureParameters: List<String>
            get() = parts.filter(parameters::contains)

        fun candidatePattern(): Regex {
            return parts.joinToString("", "^", "$") { part ->
                if (part in parameters) "([A-Za-z0-9_]+)" else Regex.escape(part)
            }.toRegex()
        }
    }

    private fun collectSettingCandidates(
        directives: List<PreprocessorDirective>,
    ): Map<String, List<SettingCandidate>> {
        return directives.asSequence()
            .filter {
                it.kind == PreprocessorDirectiveKind.DEFINE ||
                    it.kind == PreprocessorDirectiveKind.DISABLED_DEFINE
            }
            .filter { it.macroName?.startsWith(SETTING_PREFIX) == true }
            .groupBy { requireNotNull(it.macroName) }
            .mapValues { (name, definitions) -> definitions.map { parseSettingCandidate(name, it) } }
    }

    private fun parseSettingCandidate(name: String, directive: PreprocessorDirective): SettingCandidate {
        if (directive.macroFunctionLike) {
            return SettingCandidate.invalid(
                name,
                directive,
                "setting $name is function-like and cannot become a scalar specialization constant",
            )
        }
        if (directive.kind == PreprocessorDirectiveKind.DISABLED_DEFINE) {
            return SettingCandidate(
                name,
                ShaderSettingType.BOOL,
                "false",
                listOf("false", "true"),
                presenceToggle = true,
                ShaderControlKind.IRIS_PRESENCE,
                directive.sourceLine,
                listOf(directive.exactText),
            )
        }

        val body = stripComments(directive.macroBody.orEmpty()).trim()
        if (body.isEmpty()) {
            return SettingCandidate(
                name,
                ShaderSettingType.BOOL,
                "true",
                listOf("false", "true"),
                presenceToggle = true,
                ShaderControlKind.IRIS_PRESENCE,
                directive.sourceLine,
                listOf(directive.exactText),
            )
        }
        val domainText = OPTION_DOMAIN.find(directive.macroBody.orEmpty())?.groupValues?.get(1)
        val rawDomain = domainText?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()
        val values = (rawDomain + body).distinct()
        val types = values.mapNotNull(::scalarType).distinct()
        if (types.size != 1 || values.any { scalarType(it) == null }) {
            return SettingCandidate.invalid(
                name,
                directive,
                "setting $name does not have a provable bool/int/float literal domain",
            )
        }
        val type = types.single()
        return SettingCandidate(
            name,
            type,
            normalizeScalar(body, type),
            (if (rawDomain.isEmpty()) listOf(body) else rawDomain).map { normalizeScalar(it, type) }.distinct(),
            presenceToggle = false,
            ShaderControlKind.IRIS_SCALAR,
            directive.sourceLine,
            listOf(directive.exactText),
        )
    }

    private fun mergeSettingCandidates(name: String, candidates: List<SettingCandidate>): SettingCandidate? {
        val invalid = candidates.firstOrNull { it.reason != null }
        if (invalid != null) return invalid
        val signatures = candidates.map {
            listOf(it.type, it.defaultValue, it.domain, it.presenceToggle, it.controlKind)
        }.distinct()
        if (signatures.size != 1) {
            return SettingCandidate.invalid(
                name,
                candidates.first().sourceLine,
                candidates.map { it.sourceSlices }.flatten(),
                "setting $name has conflicting definitions in the expanded compiler root",
            )
        }
        return candidates.first().copy(sourceSlices = candidates.flatMap { it.sourceSlices })
    }

    private fun collectMacros(directives: List<PreprocessorDirective>): List<MacroDraft> {
        return directives.asSequence()
            .filter { it.kind == PreprocessorDirectiveKind.DEFINE && it.macroName != null }
            .filterNot { it.macroName!!.startsWith(SETTING_PREFIX) }
            .groupBy { requireNotNull(it.macroName) }
            .map { (name, definitions) ->
                MacroDraft(
                    name = name,
                    functionLike = definitions.any { it.macroFunctionLike },
                    unconditionallyDefined = definitions.any { it.conditionalDepth == 0 } &&
                        directives.none {
                            it.kind == PreprocessorDirectiveKind.UNDEF && it.macroName == name
                        },
                    bodies = definitions.map { it.macroBody.orEmpty() },
                    sourceSlices = definitions.map { it.exactText },
                )
            }
    }

    private fun resolveMacroDependencies(
        drafts: List<MacroDraft>,
        derivedControls: List<ShaderDerivedControl> = emptyList(),
    ): List<ShaderMacroDependency> {
        val byName = drafts.associateBy { it.name }
        val derivedByName = derivedControls.associateBy { it.name }
        val direct = drafts.associate { draft ->
            val expressions = draft.bodies + listOfNotNull(derivedByName[draft.name]?.compilerExpression)
            draft.name to expressions.flatMapTo(linkedSetOf()) { identifiers(stripComments(it)) - draft.name }
        }
        val memo = mutableMapOf<String, Set<String>>()
        fun settingsFor(name: String, visiting: MutableSet<String>): Set<String> {
            memo[name]?.let { return it }
            if (!visiting.add(name)) return emptySet()
            val result = buildSet {
                direct[name].orEmpty().forEach { dependency ->
                    if (dependency.startsWith(SETTING_PREFIX)) add(dependency)
                    if (dependency in byName) addAll(settingsFor(dependency, visiting))
                }
            }
            visiting.remove(name)
            memo[name] = result
            return result
        }
        return drafts.map { draft ->
            ShaderMacroDependency(
                name = draft.name,
                functionLike = draft.functionLike,
                unconditionallyDefined = draft.unconditionallyDefined,
                dependencies = direct.getValue(draft.name),
                settingDependencies = settingsFor(draft.name, linkedSetOf()),
                sourceSlices = draft.sourceSlices,
                derivedControl = derivedByName[draft.name],
            )
        }
    }

    private fun populateConditionalDependencies(
        groups: List<ConditionalGroup>,
        macros: List<ShaderMacroDependency>,
    ) {
        groups.forEach { group ->
            group.settingDependencies.clear()
            group.settingDependencies += settingDependencies(
                group.delimiters.mapNotNull { it.expression }.joinToString(" "),
                macros,
            )
        }
    }

    private fun collectDerivedControls(
        source: String,
        sourceMap: SourceMap,
        directives: List<PreprocessorDirective>,
        groups: List<ConditionalGroup>,
        settings: Map<String, ShaderSetting> = emptyMap(),
    ): List<ShaderDerivedControl> {
        val delimiterIndexes = groups.flatMapTo(hashSetOf()) { group ->
            (group.delimiters + listOfNotNull(group.endif)).map { it.index }
        }
        val mutationsByName = directives.filter {
            it.kind in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.UNDEF) &&
                it.macroName?.startsWith(SETTING_PREFIX) == false
        }.groupBy { requireNotNull(it.macroName) }
        val result = mutableListOf<ShaderDerivedControl>()
        groups.filter { it.settingDependencies.isNotEmpty() }.forEach { group ->
            val branches = branchSlices(group, source, sourceMap)
            val internal = directives.filter { directive ->
                sourceMap.directiveRange(directive).first in group.range && directive.index !in delimiterIndexes
            }
            if (internal.isEmpty() || internal.any {
                    it.kind !in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.UNDEF) ||
                        it.macroFunctionLike
                }
            ) {
                return@forEach
            }
            val branchDirectives = branches.map { branch ->
                internal.filter { sourceMap.directiveRange(it).first in branch.bodyRange }
            }
            val bodyOnlyDirectives = branches.zip(branchDirectives).all { (branch, branchItems) ->
                val masked = branchItems.map { directive ->
                    val range = sourceMap.directiveRange(directive)
                    Replacement(
                        range.first - branch.bodyRange.first,
                        range.last + 1 - branch.bodyRange.first,
                        maskSource(source.substring(range)),
                    )
                }
                maskCommentsAndStrings(applyReplacements(branch.renderedBody, masked)).isBlank()
            }
            if (!bodyOnlyDirectives) return@forEach

            internal.mapNotNull { it.macroName }.distinct().sorted().forEach { name ->
                val allMutations = mutationsByName[name].orEmpty()
                if (allMutations.any { sourceMap.directiveRange(it).first !in group.range }) return@forEach
                val perBranch = branchDirectives.map { items ->
                    val matching = items.filter { it.macroName == name }
                    if (matching.size > 1) return@forEach
                    matching.singleOrNull()
                }
                val bodies = perBranch.map { directive ->
                    when (directive?.kind) {
                        PreprocessorDirectiveKind.DEFINE -> stripComments(directive.macroBody.orEmpty()).trim()
                        PreprocessorDirectiveKind.UNDEF, null -> null
                        else -> return@forEach
                    }
                }
                val presence = bodies.all { it == null || it.isEmpty() }
                val expression = if (presence) {
                    renderDerivedPresenceExpression(branches, bodies.map { it != null })
                } else {
                    val exhaustive = branches.lastOrNull()?.directive?.kind == PreprocessorDirectiveKind.ELSE ||
                        branchesCoverSettingDomains(branches, settings)
                    if (!exhaustive || bodies.any { it.isNullOrEmpty() }) {
                        return@forEach
                    }
                    renderDerivedScalarExpression(branches, bodies.filterNotNull())
                }
                result += ShaderDerivedControl(
                    name = name,
                    kind = if (presence) ShaderDerivedControlKind.PRESENCE else ShaderDerivedControlKind.SCALAR,
                    compilerExpression = expression,
                    settingDependencies = group.settingDependencies.toSortedSet(),
                    sourceSlices = allMutations.map { it.exactText },
                    sourceConditionalId = group.id,
                )
            }
        }
        return result.distinctBy { it.name }
    }

    private fun renderDerivedPresenceExpression(
        branches: List<ConditionalBranch>,
        defined: List<Boolean>,
    ): String {
        val predicates = effectiveBranchPredicates(branches)
        val selected = predicates.zip(defined).filter { it.second }.map { it.first }
        return when (selected.size) {
            0 -> "false"
            1 -> selected.single()
            else -> selected.joinToString(" || ", "(", ")") { "($it)" }
        }
    }

    private fun renderDerivedScalarExpression(
        branches: List<ConditionalBranch>,
        bodies: List<String>,
    ): String {
        var expression = bodies.last()
        for (index in branches.lastIndex - 1 downTo 0) {
            val predicate = requireNotNull(rawDirectiveCondition(branches[index].directive))
            expression = "(($predicate) ? (${bodies[index]}) : ($expression))"
        }
        return expression
    }

    private fun effectiveBranchPredicates(branches: List<ConditionalBranch>): List<String> {
        val previous = mutableListOf<String>()
        return branches.map { branch ->
            val current = rawDirectiveCondition(branch.directive)
            val prefix = previous.joinToString(" && ") { "!($it)" }
            if (current == null) {
                if (prefix.isEmpty()) "true" else "($prefix)"
            } else {
                previous += current
                if (prefix.isEmpty()) "($current)" else "(($prefix) && ($current))"
            }
        }
    }

    private fun branchesCoverSettingDomains(
        branches: List<ConditionalBranch>,
        settings: Map<String, ShaderSetting>,
    ): Boolean {
        if (settings.isEmpty() || branches.isEmpty()) return false
        val expressions = branches.mapNotNull { rawDirectiveCondition(it.directive) }
        if (expressions.size != branches.size) return true
        val dependencies = expressions.flatMapTo(sortedSetOf()) { identifiers(it) }.filter { it in settings }
        if (dependencies.isEmpty()) return false
        val domains = dependencies.map { name ->
            settings.getValue(name).domain.mapNotNull { value ->
                when (value) {
                    "true" -> 1L
                    "false" -> 0L
                    else -> value.toLongOrNull()
                }
            }.distinct().takeIf { it.size == settings.getValue(name).domain.size } ?: return false
        }
        if (domains.fold(1L) { size, domain -> size * domain.size } > 128L) return false
        val assignment = linkedMapOf<String, Long>()
        fun covered(depth: Int): Boolean {
            if (depth < dependencies.size) {
                val name = dependencies[depth]
                return domains[depth].all { value ->
                    assignment[name] = value
                    covered(depth + 1)
                }
            }
            return expressions.any { expression ->
                StructuralExpressionParser(
                    expression,
                    { name -> assignment[name] ?: settings[name]?.defaultValue?.toLongOrNull() ?: 0L },
                    { name -> settings[name]?.let { !it.presenceToggle || assignment[name] != 0L } ?: false },
                ).parse()?.let { it != 0L } == true
            }
        }
        return covered(0)
    }

    private fun rawDirectiveCondition(directive: PreprocessorDirective): String? {
        return when (directive.kind) {
            PreprocessorDirectiveKind.IFDEF -> "defined(${directive.macroName})"
            PreprocessorDirectiveKind.IFNDEF -> "!defined(${directive.macroName})"
            PreprocessorDirectiveKind.IF, PreprocessorDirectiveKind.ELIF -> directive.expression.orEmpty()
                .substringBefore("//").trim()
            PreprocessorDirectiveKind.ELSE -> null
            else -> null
        }
    }

    private fun collectHostPresenceCandidates(
        source: String,
        sourceMap: SourceMap,
        directives: List<PreprocessorDirective>,
        groups: List<ConditionalGroup>,
        macros: List<ShaderMacroDependency>,
        additionalNames: Set<String> = emptySet(),
    ): List<SettingCandidate> {
        val relevantSlices = buildList {
            groups.filter { it.settingDependencies.isNotEmpty() }.forEach { group ->
                addAll(group.delimiters.map { it.exactText })
            }
            macros.filter { it.settingDependencies.isNotEmpty() }.forEach { macro ->
                macro.derivedControl?.let { add(it.compilerExpression) }
                addAll(macro.sourceSlices)
            }
        }
        val macroNames = macros.mapTo(hashSetOf()) { it.name }
        val names = relevantSlices.flatMapTo(sortedSetOf()) { slice ->
            DEFINED_IDENTIFIER.findAll(slice).map { it.groupValues[1] }.filterNot {
                (it.startsWith(SETTING_PREFIX) && it !in additionalNames) || it.startsWith("GL_") || it in macroNames
            }
        }
        names += additionalNames
        val definedNames = directives.filter { it.kind == PreprocessorDirectiveKind.DEFINE }
            .mapNotNullTo(hashSetOf()) { it.macroName }
        return names.map { name ->
            val slices = relevantSlices.filter { slice ->
                DEFINED_IDENTIFIER.findAll(slice).any { it.groupValues[1] == name }
            }
            val default = if (name in definedNames) "true" else "false"
            SettingCandidate(
                name = name,
                type = ShaderSettingType.BOOL,
                defaultValue = default,
                domain = listOf("false", "true"),
                presenceToggle = true,
                controlKind = ShaderControlKind.HOST_PRESENCE,
                sourceLine = firstIdentifierUseLine(source, sourceMap, name),
                sourceSlices = slices,
            )
        }
    }

    private fun allocateCompilerName(
        sourceName: String,
        kind: ShaderControlKind,
        reservedNames: MutableSet<String>,
    ): String {
        var result = if (kind == ShaderControlKind.HOST_PRESENCE) "SM_HOST_$sourceName" else "SM_$sourceName"
        while (!reservedNames.add(result)) result += '_'
        return result
    }

    private fun buildConditionalGroups(
        directives: List<PreprocessorDirective>,
        sourceMap: SourceMap,
    ): List<ConditionalGroup> {
        val result = mutableListOf<ConditionalGroup>()
        val stack = mutableListOf<ConditionalGroup>()
        directives.forEach { directive ->
            when (directive.kind) {
                in CONDITIONAL_OPENERS -> {
                    val group = ConditionalGroup(
                        id = requireNotNull(directive.conditionalId),
                        parentId = stack.lastOrNull()?.id,
                        depth = stack.size,
                        opener = directive,
                        delimiters = mutableListOf(directive),
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
                    group.range = sourceMap.directiveRange(group.opener).first until
                        (sourceMap.directiveRange(directive).last + 1)
                }

                else -> Unit
            }
        }
        return result
    }

    private fun classifyGroup(
        group: ConditionalGroup,
        groups: Map<Int, ConditionalGroup>,
        source: String,
        sourceMap: SourceMap,
        settings: Map<String, ShaderSetting>,
        macros: List<ShaderMacroDependency>,
        derivedControls: List<ShaderDerivedControl>,
        regions: List<ShaderSourceRegion>,
    ) {
        if (group.settingDependencies.isEmpty()) {
            group.disposition = ShaderConditionalDisposition.UNRELATED
            return
        }
        val missing = group.settingDependencies - settings.keys
        if (missing.isNotEmpty()) {
            group.structural("conditional references unresolved settings ${missing.sorted()}")
            return
        }
        if (group.delimiters.any { it.braceDepth != group.opener.braceDepth } || requireNotNull(group.endif).braceDepth != group.opener.braceDepth) {
            group.structural("setting conditional crosses an unbalanced lexical scope")
            return
        }
        val childGroups = groups.values.filter { it.parentId == group.id }
        val structuralChild = childGroups.firstOrNull {
            it.settingDependencies.isNotEmpty() && it.disposition == ShaderConditionalDisposition.STRUCTURAL
        }
        if (structuralChild != null) {
            group.structural("setting conditional contains structural conditional ${structuralChild.id}")
            return
        }
        val derived = derivedControls.filter { it.sourceConditionalId == group.id }
        if (derived.isNotEmpty()) {
            val failed = derived.firstOrNull {
                convertConditionExpression(
                    it.compilerExpression,
                    settings,
                    macros,
                    requireBoolean = it.kind == ShaderDerivedControlKind.PRESENCE,
                ) == null
            }
            if (failed == null) {
                group.disposition = ShaderConditionalDisposition.COMPILER_NO_OP
            } else {
                group.structural("derived macro ${failed.name} cannot be represented as a typed GLSL expression")
            }
            return
        }
        val rawBranches = branchSlices(group, source, sourceMap)
        if (rawBranches.all { branch ->
                branch.bodyRange.isEmpty() || source.substring(branch.bodyRange).isBlank()
            }
        ) {
            group.disposition = ShaderConditionalDisposition.COMPILER_NO_OP
            return
        }
        val branches = rawBranches.map { branch ->
            val rendered = renderRange(
                branch.bodyRange.first,
                branch.bodyRange.last + 1,
                group.id,
                source,
                sourceMap,
                groups,
                settings,
                macros,
            )
            branch.copy(renderedBody = rendered)
        }
        val internalDirectives = directivesWithin(group, groups, sourceMap)
        if (internalDirectives.any { it.kind in STRUCTURAL_BODY_DIRECTIVES }) {
            group.structural("setting branch mutates macros/directives and cannot coexist in one GLSL module")
            return
        }
        val conditions = branches.filterNot { it.directive.kind == PreprocessorDirectiveKind.ELSE }
            .map { convertCondition(it.directive, settings, macros) }
        if (conditions.any { it == null }) {
            group.structural("preprocessor condition cannot be represented as a typed GLSL boolean expression")
            return
        }
        if (group.opener.braceDepth == 0) {
            val functions = branches.map { parseFunctionBranches(it.renderedBody) }
            val signatures = functions.mapNotNull { bundle ->
                bundle?.map(FunctionBranch::normalizedSignature)
            }.distinct()
            if (
                functions.all { it != null } &&
                signatures.size == 1
            ) {
                group.disposition = ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION
            } else {
                group.structural("setting conditional changes top-level declaration or ABI shape")
            }
            return
        }
        if (branches.any { TOKEN_PASTE.containsMatchIn(it.renderedBody) }) {
            group.structural("setting branch participates in token paste")
            return
        }
        if (branches.any { CASE_LABEL.containsMatchIn(maskCommentsAndStrings(it.renderedBody)) }) {
            group.structural("setting conditional cuts a case/default label region")
            return
        }
        if (branches.any { !balancedLexically(it.renderedBody) }) {
            group.structural("setting branch cuts a token, statement, or lexical scope")
            return
        }
        val isExpression = branches.last().directive.kind == PreprocessorDirectiveKind.ELSE &&
            branches.all { isCompleteExpression(it.renderedBody) }
        if (isExpression) {
            if (!isExpressionContextSafe(group, source)) {
                group.structural("setting conditional cuts surrounding expression tokens")
                return
            }
            val types = branches.mapNotNull { inferExpressionType(it.renderedBody) }.distinct()
            if (types.size > 1) {
                group.structural("conditional expression branches have incompatible scalar types ${types.sorted()}")
                return
            }
            group.disposition = ShaderConditionalDisposition.CONTROL_FLOW_EXPRESSION
            return
        }
        if (branches.any { !isCompleteStatementSequence(it.renderedBody) }) {
            group.structural("setting conditional does not cover complete expressions or complete statements")
            return
        }
        val declarations = branches.flatMap { localDeclarations(it.renderedBody) }.toSet()
        if (declarations.isNotEmpty()) {
            val function = regions.singleOrNull {
                it.kind == ShaderSourceRegionKind.FUNCTION && group.range.first in it.startOffset until it.endOffset
            }
            if (function == null) {
                group.structural("cannot prove branch-local declaration scope")
                return
            }
            val tail = source.substring(group.range.last + 1, function.endOffset)
            val leaked = declarations.filter { name -> IDENTIFIER_TOKEN(name).containsMatchIn(maskCommentsAndStrings(tail)) }
            if (leaked.isNotEmpty()) {
                group.structural("branch-local declarations leak past the conditional: ${leaked.sorted()}")
                return
            }
        }
        group.disposition = ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT
    }

    private fun booleanDerivedComparisonOutsideDomain(
        expression: String,
        macros: List<ShaderMacroDependency>,
    ): Boolean? {
        return macros.asSequence().filter { macro ->
            val derived = macro.derivedControl ?: return@filter false
            derived.kind == ShaderDerivedControlKind.PRESENCE || derived.sourceSlices.mapNotNull { slice ->
                stripComments(slice.substringAfter(macro.name)).trim().takeIf(String::isNotEmpty)
            }.let { values -> values.isNotEmpty() && values.all { it == "0" || it == "1" } }
        }.mapNotNull { macro ->
            val name = Regex.escape(macro.name)
            val direct = Regex(
                "(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])\\s*(==|!=)\\s*(-?[0-9]+)",
            ).find(expression)?.let { it.groupValues[1] to it.groupValues[2].toLong() }
            val reverse = Regex(
                "(-?[0-9]+)\\s*(==|!=)\\s*(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])",
            ).find(expression)?.let { it.groupValues[2] to it.groupValues[1].toLong() }
            val comparison = direct ?: reverse ?: return@mapNotNull null
            if (comparison.second in 0L..1L) return@mapNotNull null
            comparison.first == "!="
        }.firstOrNull()
    }

    private fun buildCompilerSource(
        source: String,
        sourceMap: SourceMap,
        directives: List<PreprocessorDirective>,
        settings: List<ShaderSetting>,
        groups: List<ConditionalGroup>,
        macros: List<ShaderMacroDependency>,
        derivedControls: List<ShaderDerivedControl>,
    ): String {
        val groupsById = groups.associateBy { it.id }
        val settingsByName = settings.associateBy { it.name }
        val transformed = groups.filter { it.disposition in COMPILER_TRANSFORM_DISPOSITIONS }
        val rootTransformed = transformed.filter { candidate ->
            transformed.none { enclosing ->
                enclosing !== candidate &&
                    enclosing.range.first <= candidate.range.first &&
                    enclosing.range.last >= candidate.range.last
            }
        }
        val replacements = mutableListOf<Replacement>()
        rootTransformed.forEach { group ->
            replacements += Replacement(
                group.range.first,
                group.range.last + 1,
                renderGroup(group, source, sourceMap, groupsById, settingsByName, macros),
            )
        }
        val sourceOptionNames = settings.filter { it.controlKind != ShaderControlKind.HOST_PRESENCE }
            .mapTo(hashSetOf()) { it.name }
        directives.filter {
            it.kind in SETTING_DEFINITION_DIRECTIVES &&
                it.macroName in sourceOptionNames
        }.forEach {
            val range = sourceMap.directiveRange(it)
            if (rootTransformed.none { group -> range.first >= group.range.first && range.last <= group.range.last }) {
                replacements += Replacement(range.first, range.last + 1, maskSource(source.substring(range)))
            }
        }
        var result = applyReplacements(source, replacements)
        val compilerControlReplacements = settings.filter { it.controlKind != ShaderControlKind.HOST_PRESENCE }
            .associate { it.name to it.compilerName }.toMutableMap()
        derivedControls.forEach { derived ->
            val expression = requireNotNull(
                convertConditionExpression(
                    derived.compilerExpression,
                    settingsByName,
                    macros,
                    requireBoolean = derived.kind == ShaderDerivedControlKind.PRESENCE,
                ),
            ) {
                "${sourceMap.sourceName}: derived control ${derived.name} lost its typed compiler expression"
            }
            compilerControlReplacements[derived.name] = "($expression)"
        }
        result = replaceIdentifierTokens(result, compilerControlReplacements)
        result = normalizeCompilerPresenceDirectives(result)
        result = relaxAggregateSpecializationInitializers(result, macros)
        if (settings.isEmpty()) return result

        val newline = when {
            "\r\n" in result -> "\r\n"
            '\r' in result -> "\r"
            else -> "\n"
        }
        val declarationBlock = buildString {
            settings.forEach { setting ->
                append("layout(constant_id = ")
                append(setting.specializationId)
                append(") const ")
                append(setting.type.glslName)
                append(' ')
                append(setting.compilerName)
                append(" = ")
                append(setting.defaultValue)
                append(';')
                append(newline)
            }
        }
        val version = VERSION_LINE.find(result)
            ?: throw PreprocessorProtectionException(sourceMap.sourceName, 1, "compiler root has no #version directive")
        val insertion = compilerDeclarationInsertionOffset(result, version.range.last + 1)
        val prefix = if (insertion == version.range.last + 1) newline else ""
        return result.substring(0, insertion) + prefix + declarationBlock + result.substring(insertion)
    }

    private fun relaxAggregateSpecializationInitializers(
        source: String,
        macros: List<ShaderMacroDependency>,
    ): String {
        data class GlobalConstant(
            val range: IntRange,
            val name: String,
            val initializer: String,
            val scalar: Boolean,
            val nonConstantCall: Boolean,
            val directlySettingDependent: Boolean,
        )

        val settingDependentMacros = macros.filter { it.settingDependencies.isNotEmpty() }.mapTo(hashSetOf()) { it.name }
        val masked = maskCommentsAndStrings(source)
        var braceDepth = 0
        val braceDepths = IntArray(masked.length + 1)
        masked.forEachIndexed { index, character ->
            braceDepths[index] = braceDepth
            when (character) {
                '{' -> braceDepth++
                '}' -> braceDepth--
            }
        }
        braceDepths[masked.length] = braceDepth
        val constants = GLOBAL_CONST_INITIALIZER.findAll(masked).mapNotNull { match ->
            if (braceDepths[match.range.first] != 0) return@mapNotNull null
            val semicolon = masked.indexOf(';', match.range.last + 1)
            if (semicolon < 0 || braceDepths[semicolon] != 0) return@mapNotNull null
            val declaration = source.substring(match.range.first, semicolon + 1)
            val settingDependent = COMPILER_SETTING_PREFIX in declaration || COMPILER_HOST_PREFIX in declaration ||
                identifiers(maskCommentsAndStrings(declaration)).any(settingDependentMacros::contains)
            val scalar = match.groupValues[1] in SCALAR_GLSL_TYPES &&
                match.groupValues[2].isEmpty() && match.groupValues[4].isEmpty()
            val initializer = declaration.substringAfter('=')
            val nonConstantCall = FUNCTION_CALL.findAll(maskCommentsAndStrings(initializer)).any { call ->
                call.groupValues[1] !in SCALAR_GLSL_TYPES
            }
            GlobalConstant(
                match.range.first..semicolon,
                match.groupValues[3],
                initializer,
                scalar,
                nonConstantCall,
                settingDependent,
            )
        }.toList()
        val relaxed = constants.filterTo(linkedSetOf()) {
            it.directlySettingDependent && (!it.scalar || it.nonConstantCall)
        }
        var changed: Boolean
        do {
            val relaxedNames = relaxed.mapTo(hashSetOf(), GlobalConstant::name)
            changed = constants.filterNot(relaxed::contains).any { constant ->
                identifiers(maskCommentsAndStrings(constant.initializer)).any(relaxedNames::contains) &&
                    relaxed.add(constant)
            }
        } while (changed)
        val replacements = relaxed.map { constant ->
            val start = constant.range.first
            Replacement(start, start + "const".length, " ".repeat("const".length))
        }
        return applyReplacements(source, replacements)
    }

    private fun compilerDeclarationInsertionOffset(source: String, versionEnd: Int): Int {
        var cursor = versionEnd
        var insertion = versionEnd
        while (cursor < source.length) {
            if (source[cursor] == '\r') {
                cursor++
                if (source.getOrNull(cursor) == '\n') cursor++
                insertion = cursor
            } else if (source[cursor] == '\n') {
                cursor++
                insertion = cursor
            }
            val lineEnd = source.indexOfAny(charArrayOf('\r', '\n'), cursor).let { if (it < 0) source.length else it }
            val line = source.substring(cursor, lineEnd).trim()
            if (line.isNotEmpty() && !line.startsWith("#extension")) break
            cursor = lineEnd
            insertion = lineEnd
        }
        return insertion
    }

    private fun renderGroup(
        group: ConditionalGroup,
        source: String,
        sourceMap: SourceMap,
        groups: Map<Int, ConditionalGroup>,
        settings: Map<String, ShaderSetting>,
        macros: List<ShaderMacroDependency>,
    ): String {
        val branches = branchSlices(group, source, sourceMap).map { branch ->
            branch.copy(
                renderedBody = renderRange(
                    branch.bodyRange.first,
                    branch.bodyRange.last + 1,
                    group.id,
                    source,
                    sourceMap,
                    groups,
                    settings,
                    macros,
                ),
            )
        }
        return when (group.disposition) {
            ShaderConditionalDisposition.COMPILER_NO_OP -> maskSource(source.substring(group.range))

            ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT -> run {
                val live = buildList {
                    branches.forEach { branch ->
                        val condition = if (branch.directive.kind == PreprocessorDirectiveKind.ELSE) {
                            null
                        } else {
                            requireNotNull(convertCondition(branch.directive, settings, macros))
                        }
                        if (condition == "false") return@forEach
                        add(branch to condition)
                        if (condition == null || condition == "true") return@buildList
                    }
                }
                if (live.isEmpty()) {
                    ""
                } else if (live.first().second == null || live.first().second == "true") {
                    live.first().first.renderedBody
                } else {
                    buildString {
                        live.forEachIndexed { index, (branch, condition) ->
                            if (condition == null || condition == "true") {
                                append("else {\n")
                            } else {
                                if (index > 0) append("else ")
                                append("if (")
                                append(condition)
                                append(") {\n")
                            }
                            append(branch.renderedBody)
                            if (branch.renderedBody.isNotEmpty() && !branch.renderedBody.endsWith('\n')) append('\n')
                            append("}\n")
                        }
                    }
                }
            }

            ShaderConditionalDisposition.CONTROL_FLOW_EXPRESSION -> {
                var expression = branches.last().renderedBody.trim()
                branches.dropLast(1).asReversed().forEach { branch ->
                    val condition = requireNotNull(convertCondition(branch.directive, settings, macros))
                    expression = "(($condition) ? (${branch.renderedBody.trim()}) : ($expression))"
                }
                expression
            }

            ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION -> {
                val functions = branches.map { requireNotNull(parseFunctionBranches(it.renderedBody)) }
                buildString {
                    functions.first().indices.forEach { functionIndex ->
                        append(functions.first()[functionIndex].signature.trimEnd())
                        append(" {\n")
                        branches.zip(functions).forEachIndexed { index, (branch, bundle) ->
                            if (branch.directive.kind == PreprocessorDirectiveKind.ELSE) {
                                append("else {\n")
                            } else {
                                if (index > 0) append("else ")
                                append("if (")
                                append(requireNotNull(convertCondition(branch.directive, settings, macros)))
                                append(") {\n")
                            }
                            val function = bundle[functionIndex]
                            append(function.body)
                            if (function.body.isNotEmpty() && !function.body.endsWith('\n')) append('\n')
                            append("}\n")
                        }
                        if (branches.last().directive.kind != PreprocessorDirectiveKind.ELSE) {
                            append("else {\n")
                            append(functions.first()[functionIndex].body)
                            if (
                                functions.first()[functionIndex].body.isNotEmpty() &&
                                !functions.first()[functionIndex].body.endsWith('\n')
                            ) append('\n')
                            append("}\n")
                        }
                        append("}\n")
                    }
                }
            }

            else -> source.substring(group.range)
        }
    }

    private fun renderRange(
        start: Int,
        end: Int,
        parentId: Int,
        source: String,
        sourceMap: SourceMap,
        groups: Map<Int, ConditionalGroup>,
        settings: Map<String, ShaderSetting>,
        macros: List<ShaderMacroDependency>,
    ): String {
        val transformed = groups.values.filter {
            it.id != parentId &&
                it.range.first >= start && it.range.last < end &&
                it.disposition in COMPILER_TRANSFORM_DISPOSITIONS
        }
        val direct = transformed.filter { candidate ->
            transformed.none { enclosing ->
                enclosing !== candidate &&
                    enclosing.range.first <= candidate.range.first &&
                    enclosing.range.last >= candidate.range.last
            }
        }.sortedBy { it.range.first }
        if (direct.isEmpty()) return source.substring(start, end)
        return buildString {
            var cursor = start
            direct.forEach { child ->
                append(source, cursor, child.range.first)
                append(renderGroup(
                    child,
                    source,
                    sourceMap,
                    groups,
                    settings,
                    macros,
                ))
                cursor = child.range.last + 1
            }
            append(source, cursor, end)
        }
    }

    private fun convertCondition(
        directive: PreprocessorDirective,
        settings: Map<String, ShaderSetting>,
        macros: List<ShaderMacroDependency>,
    ): String? {
        if (directive.kind == PreprocessorDirectiveKind.ELSE) return null
        val raw = when (directive.kind) {
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            -> {
                val name = directive.macroName ?: return null
                val expression = "defined($name)"
                if (directive.kind == PreprocessorDirectiveKind.IFDEF) expression else "!($expression)"
            }

            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.ELIF,
            -> directive.expression.orEmpty().substringBefore("//").trim()

            else -> return null
        }
        return convertConditionExpression(raw, settings, macros)
    }

    private fun convertConditionExpression(
        raw: String,
        settings: Map<String, ShaderSetting>,
        macros: List<ShaderMacroDependency>,
        visiting: Set<String> = emptySet(),
        requireBoolean: Boolean = true,
    ): String? {
        if (requireBoolean) {
            booleanDerivedComparisonOutsideDomain(raw, macros)?.let { return it.toString() }
        }
        val macroByName = macros.associateBy { it.name }
        var failed = false
        var expression = raw
        expression = DEFINED_IDENTIFIER.replace(expression) { match ->
            val name = match.groupValues[1]
            val setting = settings[name]
            when {
                setting?.presenceToggle == true -> setting.compilerName
                macroByName[name]?.unconditionallyDefined == true -> "true"
                macroByName[name]?.derivedControl?.kind == ShaderDerivedControlKind.PRESENCE -> {
                    val macro = macroByName.getValue(name)
                    val expanded = if (name in visiting) null else {
                        convertConditionExpression(
                            requireNotNull(macro.derivedControl).compilerExpression,
                            settings,
                            macros,
                            visiting + name,
                            requireBoolean = false,
                        )
                    }
                    if (expanded == null) {
                        failed = true
                        match.value
                    } else {
                        "($expanded)"
                    }
                }
                else -> match.value
            }
        }
        expression = DEFINED_IDENTIFIER_BARE.replace(expression) { match ->
            val name = match.groupValues[1]
            val setting = settings[name]
            when {
                setting?.presenceToggle == true -> setting.compilerName
                macroByName[name]?.unconditionallyDefined == true -> "true"
                macroByName[name]?.derivedControl?.kind == ShaderDerivedControlKind.PRESENCE -> {
                    val macro = macroByName.getValue(name)
                    val expanded = if (name in visiting) null else {
                        convertConditionExpression(
                            requireNotNull(macro.derivedControl).compilerExpression,
                            settings,
                            macros,
                            visiting + name,
                            requireBoolean = false,
                        )
                    }
                    if (expanded == null) {
                        failed = true
                        match.value
                    } else {
                        "($expanded)"
                    }
                }
                else -> match.value
            }
        }
        expression = IDENTIFIER.findAll(expression).toList().asReversed().fold(expression) { value, match ->
            val token = match.value
            val replacement = when {
                token in settings -> {
                    val control = settings.getValue(token)
                    if (control.controlKind == ShaderControlKind.HOST_PRESENCE) {
                        failed = true
                        token
                    } else {
                        control.compilerName
                    }
                }
                macroByName[token]?.settingDependencies?.isNotEmpty() == true -> {
                    val macro = macroByName.getValue(token)
                    if (macro.functionLike || token in visiting) {
                        failed = true
                        token
                    } else {
                        val body = macro.derivedControl?.compilerExpression ?: macro.sourceSlices.singleOrNull()
                            ?.let { stripComments(it.substringAfter(macro.name)).trim() }
                        val expanded = body?.let {
                            convertConditionExpression(
                                it,
                                settings,
                                macros,
                                visiting + token,
                                requireBoolean = false,
                            )
                        }
                        if (expanded == null) {
                            failed = true
                            token
                        } else {
                            "($expanded)"
                        }
                    }
                }

                else -> token
            }
            value.replaceRange(match.range, replacement)
        }
        if (failed || DEFINED_REMAINS.containsMatchIn(expression)) return null
        expression = LOGICAL_LEFT_INTEGER.replace(expression) { match ->
            val value = if (match.groupValues[3].toLong() == 0L) "false" else "true"
            match.groupValues[1] + match.groupValues[2] + value
        }
        expression = LOGICAL_RIGHT_INTEGER.replace(expression) { match ->
            val value = if (match.groupValues[3].toLong() == 0L) "false" else "true"
            match.groupValues[1] + match.groupValues[2] + value
        }
        if (requireBoolean) {
            INTEGER_LITERAL.matchEntire(expression.trim())?.let { literal ->
                return if (literal.value.toLong() == 0L) "false" else "true"
            }
        }
        staticBooleanValue(expression)?.let { expression = it.toString() }
        if (requireBoolean) {
            expression = coerceNumericBooleanOperands(expression, settings.values) ?: return null
        }
        return expression
    }

    private fun coerceNumericBooleanOperands(
        source: String,
        settings: Collection<ShaderSetting>,
    ): String? {
        val expression = stripOuterBooleanParentheses(source.trim())
        for (operator in listOf("||", "&&")) {
            val operands = splitTopLevelBoolean(expression, operator) ?: continue
            val converted = operands.map { operand ->
                coerceNumericBooleanOperands(operand, settings) ?: return null
            }
            return converted.joinToString(" $operator ", "(", ")")
        }
        if (expression.startsWith('!')) {
            val operand = coerceNumericBooleanOperands(expression.substring(1), settings) ?: return null
            return "!($operand)"
        }
        val numericSettings = settings.filter {
            it.type != ShaderSettingType.BOOL && IDENTIFIER_TOKEN(it.compilerName).containsMatchIn(expression)
        }
        if (numericSettings.isEmpty() || COMPARISON_OPERATOR.containsMatchIn(expression)) return expression
        val simple = numericSettings.singleOrNull() ?: return null
        return when (expression) {
            simple.compilerName -> "${simple.compilerName} != 0"
            "bool(${simple.compilerName})" -> expression
            else -> null
        }
    }

    private fun staticBooleanValue(source: String): Boolean? {
        val expression = stripOuterBooleanParentheses(source.trim())
        when (expression) {
            "true" -> return true
            "false" -> return false
        }
        INTEGER_LITERAL.matchEntire(expression)?.let { return it.value.toLong() != 0L }
        splitTopLevelBoolean(expression, "||")?.let { operands ->
            val values = operands.map(::staticBooleanValue)
            if (values.any { it == true }) return true
            if (values.all { it == false }) return false
            return null
        }
        splitTopLevelBoolean(expression, "&&")?.let { operands ->
            val values = operands.map(::staticBooleanValue)
            if (values.any { it == false }) return false
            if (values.all { it == true }) return true
            return null
        }
        if (expression.startsWith('!')) return staticBooleanValue(expression.substring(1))?.not()
        return null
    }

    private fun stripOuterBooleanParentheses(source: String): String {
        var result = source
        while (result.startsWith('(')) {
            var depth = 0
            var close = -1
            result.forEachIndexed { index, character ->
                when (character) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0 && close < 0) close = index
                    }
                }
            }
            if (close != result.lastIndex) break
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun splitTopLevelBoolean(source: String, operator: String): List<String>? {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        var cursor = 0
        while (cursor <= source.length - operator.length) {
            when (source[cursor]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && source.startsWith(operator, cursor)) {
                result += source.substring(start, cursor)
                cursor += operator.length
                start = cursor
            } else {
                cursor++
            }
        }
        if (result.isEmpty()) return null
        result += source.substring(start)
        return result
    }

    private fun branchSlices(group: ConditionalGroup, source: String, sourceMap: SourceMap): List<ConditionalBranch> {
        val end = requireNotNull(group.endif)
        val boundaries = group.delimiters + end
        return group.delimiters.mapIndexed { index, delimiter ->
            val startOffset = sourceMap.afterDirective(delimiter)
            val endOffset = sourceMap.directiveRange(boundaries[index + 1]).first
            ConditionalBranch(delimiter, startOffset until endOffset, source.substring(startOffset, endOffset))
        }
    }

    private fun directivesWithin(
        group: ConditionalGroup,
        groups: Map<Int, ConditionalGroup>,
        sourceMap: SourceMap,
    ): List<PreprocessorDirective> {
        val nestedDelimiterIndexes = groups.values.filter { it.parentId == group.id }
            .flatMap { it.delimiters + requireNotNull(it.endif) }
            .mapTo(hashSetOf()) { it.index }
        return sourceMap.directives.filter {
            val offset = sourceMap.directiveRange(it).first
            offset in group.range && it.index !in nestedDelimiterIndexes &&
                it.index !in group.delimiters.mapTo(hashSetOf()) { delimiter -> delimiter.index } &&
                it.index != group.endif?.index
        }
    }

    private fun settingDependencies(
        expression: String,
        macros: List<ShaderMacroDependency>,
    ): Set<String> {
        val macroByName = macros.associateBy { it.name }
        return buildSet {
            identifiers(stripComments(expression)).forEach { token ->
                if (token.startsWith(SETTING_PREFIX)) add(token)
                addAll(macroByName[token]?.settingDependencies.orEmpty())
            }
        }
    }

    private fun findStructuralSettingUses(
        source: String,
        sourceMap: SourceMap,
        directives: List<PreprocessorDirective>,
        settingNames: Set<String>,
        macros: List<ShaderMacroDependency>,
    ): List<ShaderCompilerCopyBlocker> {
        if (settingNames.isEmpty()) return emptyList()
        val directiveLines = directives.flatMapTo(hashSetOf()) { it.sourceLine..it.endLine }
        val maskedSource = maskCommentsAndStrings(source)
        val topLevelBlocks = scanTopLevelGlslBlocks(source, maskedSource)
        val abiBlocks = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.ABI }
        val abiBlockRanges = abiBlocks.map { it.fullRange }
        val functionSignatureRanges = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.FUNCTION }
            .map { it.prefixRange }
        val macrosByName = macros.associateBy { it.name }
        val tokenPasteMemo = mutableMapOf<String, Boolean>()
        fun usesTokenPaste(name: String, visiting: MutableSet<String>): Boolean {
            tokenPasteMemo[name]?.let { return it }
            val macro = macrosByName[name] ?: return false
            if (!visiting.add(name)) return false
            val result = macro.sourceSlices.any { TOKEN_PASTE.containsMatchIn(it) } ||
                macro.dependencies.any { usesTokenPaste(it, visiting) }
            visiting.remove(name)
            tokenPasteMemo[name] = result
            return result
        }
        fun settingDependencies(text: String): Set<String> {
            val lineIdentifiers = identifiers(maskCommentsAndStrings(text))
            return buildSet {
                addAll(lineIdentifiers.filter(settingNames::contains))
                lineIdentifiers.forEach { addAll(macrosByName[it]?.settingDependencies.orEmpty()) }
            }
        }
        val blocks = abiBlocks.mapNotNull { block ->
            val text = source.substring(block.fullRange)
            if (settingDependencies(text).isEmpty()) return@mapNotNull null
            val blockIdentifiers = identifiers(maskCommentsAndStrings(text))
            val tokenPaste = TOKEN_PASTE.containsMatchIn(text) || blockIdentifiers.any {
                usesTokenPaste(it, linkedSetOf())
            }
            ShaderCompilerCopyBlocker(
                sourceMap.lineAt(block.fullRange.first),
                if (tokenPaste) "setting participates in token paste" else "setting affects a resource block member or layout",
            )
        }
        val direct = sourceMap.lines.mapNotNull { line ->
            if (line.number in directiveLines) return@mapNotNull null
            val analysisText = maskGlslRanges(
                line.text,
                line.range.first,
                functionSignatureRanges + abiBlockRanges,
            )
            if (settingDependencies(analysisText).isEmpty()) return@mapNotNull null
            val lineIdentifiers = identifiers(maskCommentsAndStrings(analysisText))
            val tokenPaste = TOKEN_PASTE.containsMatchIn(analysisText) || lineIdentifiers.any {
                usesTokenPaste(it, linkedSetOf())
            }
            when {
                LAYOUT_USE.containsMatchIn(analysisText) -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a layout or execution-mode directive",
                )

                line.braceDepth == 0 && ABI_DECLARATION.containsMatchIn(analysisText) -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a resource or stage-interface declaration",
                )

                tokenPaste -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting participates in token paste",
                )

                else -> null
            }
        }
        val functions = topLevelBlocks.filter { it.kind == TopLevelGlslBlockKind.FUNCTION }.mapNotNull { block ->
            if (settingDependencies(source.substring(block.prefixRange)).isEmpty()) return@mapNotNull null
            ShaderCompilerCopyBlocker(
                sourceMap.lineAt(block.prefixRange.first),
                "setting affects a function signature or ABI",
            )
        }
        return (blocks + direct + functions).distinct()
    }

    private fun findSourceRegions(source: String, sourceMap: SourceMap): List<ShaderSourceRegion> {
        val masked = maskGlslRanges(
            maskCommentsAndStrings(source),
            0,
            sourceMap.directives.map(sourceMap::directiveRange),
        )
        val result = mutableListOf<ShaderSourceRegion>()
        val functionRanges = mutableListOf<IntRange>()
        val aggregateRanges = scanTopLevelGlslBlocks(source, masked)
            .filter { it.kind == TopLevelGlslBlockKind.ABI }
            .map { it.fullRange }
        aggregateRanges.forEach { range ->
            val start = range.first + source.substring(range).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            result += ShaderSourceRegion(
                ShaderSourceRegionKind.TOP_LEVEL_DECLARATION,
                null,
                sourceMap.lineAt(start),
                start,
                range.last + 1,
                source.substring(start, range.last + 1),
            )
        }
        FUNCTION_WITH_OPEN.findAll(masked).forEach { match ->
            val open = masked.indexOf('{', match.range.first)
            val close = matchingBrace(masked, open)
            if (open < 0 || close < 0) return@forEach
            val start = match.range.first + match.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            result += ShaderSourceRegion(
                ShaderSourceRegionKind.FUNCTION,
                match.groupValues[1],
                sourceMap.lineAt(start),
                start,
                close + 1,
                source.substring(start, close + 1),
            )
            functionRanges += start..close
        }
        var depth = 0
        var boundary = 0
        var lineStart = 0
        var cursor = 0
        while (cursor < masked.length) {
            when (masked[cursor]) {
                '{' -> depth++

                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0) boundary = cursor + 1
                }
                ';' -> if (depth == 0) {
                    val start = boundary + masked.substring(boundary, cursor).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    if (
                        start <= cursor &&
                        functionRanges.none { start in it || cursor in it } &&
                        aggregateRanges.none { start in it || cursor in it }
                    ) {
                        result += ShaderSourceRegion(
                            ShaderSourceRegionKind.TOP_LEVEL_DECLARATION,
                            null,
                            sourceMap.lineAt(start),
                            start,
                            cursor + 1,
                            source.substring(start, cursor + 1),
                        )
                    }
                    boundary = cursor + 1
                }

                '\r', '\n' -> {
                    if (depth == 0 && masked.substring(lineStart, cursor).trimStart().startsWith('#')) {
                        boundary = cursor + 1
                    }
                    if (masked[cursor] == '\r' && cursor + 1 < masked.length && masked[cursor + 1] == '\n') {
                        cursor++
                    }
                    lineStart = cursor + 1
                }
            }
            cursor++
        }
        return result.distinctBy { it.startOffset to it.endOffset }.sortedBy { it.startOffset }
    }

    private fun matchingBrace(source: String, open: Int): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun isCompleteExpression(source: String): Boolean {
        val text = maskCommentsAndStrings(source).trim()
        if (text.isEmpty() || ';' in text || '{' in text || '}' in text) return false
        if (CONTROL_STATEMENT.containsMatchIn(text)) return false
        return balancedLexically(text)
    }

    private fun isExpressionContextSafe(group: ConditionalGroup, source: String): Boolean {
        val before = source.substring(0, group.range.first).trimEnd()
        val after = source.substring(group.range.last + 1).trimStart()
        if (before.isEmpty() || after.isEmpty()) return false
        val previous = before.last()
        val next = after.first()
        val validPrevious = previous in "=([{,:?+-*/%!&|^<>" || RETURN_SUFFIX.containsMatchIn(before)
        val validNext = next in ";),]}:?+-*/%&|^<>,"
        return validPrevious && validNext
    }

    private fun isCompleteStatementSequence(source: String): Boolean {
        val text = maskCommentsAndStrings(source)
            .lineSequence()
            .filterNot { it.trimStart().startsWith('#') }
            .joinToString("\n")
            .trim()
        if (text.isEmpty()) return true
        if (!balancedLexically(text)) return false
        return text.last() == ';' || text.last() == '}'
    }

    private fun balancedLexically(source: String): Boolean {
        val text = maskCommentsAndStrings(source)
        val stack = mutableListOf<Char>()
        text.forEach { char ->
            when (char) {
                '(', '[', '{' -> stack += char
                ')' -> if (stack.removeLastOrNull() != '(') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
                '}' -> if (stack.removeLastOrNull() != '{') return false
            }
        }
        return stack.isEmpty()
    }

    private fun localDeclarations(source: String): List<String> {
        return LOCAL_DECLARATION.findAll(maskCommentsAndStrings(source)).map { it.groupValues[1] }.toList()
    }

    private fun inferExpressionType(source: String): String? {
        val value = source.trim().removeSurrounding("(", ")").trim()
        return when {
            value == "true" || value == "false" -> "bool"
            INT_LITERAL.matches(value) -> "int"
            FLOAT_LITERAL.matches(value) -> "float"
            STRING_LITERAL.matches(value) -> "string"
            else -> null
        }
    }

    private fun parseFunctionBranches(source: String): List<FunctionBranch>? {
        val masked = maskCommentsAndStrings(source)
        val result = mutableListOf<FunctionBranch>()
        var cursor = 0
        while (cursor < source.length) {
            val match = FUNCTION_WITH_OPEN.find(masked, cursor) ?: break
            if (masked.substring(cursor, match.range.first).isNotBlank()) return null
            val open = masked.indexOf('{', match.range.first)
            val close = matchingBrace(masked, open)
            if (open < 0 || close < 0) return null
            val signature = source.substring(match.range.first, open)
            result += FunctionBranch(
                signature = signature,
                normalizedSignature = signature.replace(WHITESPACE, " ").trim(),
                body = source.substring(open + 1, close),
            )
            cursor = close + 1
        }
        if (result.isEmpty() || masked.substring(cursor).isNotBlank()) return null
        return result
    }

    private fun scalarType(value: String): ShaderSettingType? {
        return when {
            value == "true" || value == "false" -> ShaderSettingType.BOOL
            INT_LITERAL.matches(value) -> ShaderSettingType.INT
            FLOAT_LITERAL.matches(value) -> ShaderSettingType.FLOAT
            else -> null
        }
    }

    private fun normalizeScalar(value: String, type: ShaderSettingType): String {
        val trimmed = value.trim()
        return if (type == ShaderSettingType.FLOAT && INT_LITERAL.matches(trimmed)) "$trimmed.0" else trimmed
    }

    private fun firstSettingUseLine(source: String, sourceMap: SourceMap, name: String): Int {
        return firstIdentifierUseLine(source, sourceMap, name)
    }

    private fun firstIdentifierUseLine(source: String, sourceMap: SourceMap, name: String): Int {
        val offset = IDENTIFIER_TOKEN(name).find(source)?.range?.first ?: return 1
        return sourceMap.lineAt(offset)
    }

    private fun identifiers(source: String): Set<String> = IDENTIFIER.findAll(source).mapTo(linkedSetOf()) { it.value }

    private fun stripComments(source: String): String {
        return source.replace(BLOCK_COMMENT, " ").substringBefore("//")
    }

    private fun maskCommentsAndStrings(source: String): String {
        val result = source.toCharArray()
        var blockComment = false
        var lineComment = false
        var quote: Char? = null
        var cursor = 0
        while (cursor < result.size) {
            val char = result[cursor]
            if (lineComment) {
                if (char == '\r' || char == '\n') lineComment = false else result[cursor] = ' '
                cursor++
                continue
            }
            if (blockComment) {
                if (char == '*' && cursor + 1 < result.size && result[cursor + 1] == '/') {
                    result[cursor] = ' '
                    result[cursor + 1] = ' '
                    blockComment = false
                    cursor += 2
                } else {
                    if (char != '\r' && char != '\n') result[cursor] = ' '
                    cursor++
                }
                continue
            }
            val currentQuote = quote
            if (currentQuote != null) {
                if (char == '\\') {
                    result[cursor] = ' '
                    if (cursor + 1 < result.size) result[cursor + 1] = ' '
                    cursor += 2
                } else {
                    if (char == currentQuote) quote = null
                    if (char != '\r' && char != '\n') result[cursor] = ' '
                    cursor++
                }
                continue
            }
            if (char == '/' && cursor + 1 < result.size && result[cursor + 1] == '/') {
                result[cursor] = ' '
                result[cursor + 1] = ' '
                lineComment = true
                cursor += 2
            } else if (char == '/' && cursor + 1 < result.size && result[cursor + 1] == '*') {
                result[cursor] = ' '
                result[cursor + 1] = ' '
                blockComment = true
                cursor += 2
            } else if (char == '"' || char == '\'') {
                quote = char
                result[cursor] = ' '
                cursor++
            } else {
                cursor++
            }
        }
        return result.concatToString()
    }

    private fun maskSource(source: String): String {
        return source.map { if (it == '\r' || it == '\n') it else ' ' }.joinToString("")
    }

    private fun applyReplacements(source: String, replacements: List<Replacement>): String {
        if (replacements.isEmpty()) return source
        val ordered = replacements.sortedBy { it.start }
        return buildString(source.length) {
            var cursor = 0
            ordered.forEach { replacement ->
                require(replacement.start >= cursor && replacement.end in replacement.start..source.length) {
                    "Compiler-copy replacements overlap or exceed the source: $replacement after offset $cursor"
                }
                append(source, cursor, replacement.start)
                append(replacement.text)
                cursor = replacement.end
            }
            append(source, cursor, source.length)
        }
    }

    private fun replaceIdentifierTokens(source: String, replacements: Map<String, String>): String {
        if (replacements.isEmpty()) return source
        val lexical = maskCommentsAndStrings(source)
        return buildString(source.length) {
            var cursor = 0
            while (cursor < source.length) {
                val char = lexical[cursor]
                if (!char.isAsciiIdentifierStart()) {
                    append(source[cursor])
                    cursor++
                    continue
                }
                val start = cursor++
                while (cursor < source.length && lexical[cursor].isAsciiIdentifierPart()) cursor++
                val token = source.substring(start, cursor)
                append(replacements[token] ?: token)
            }
        }
    }

    private fun normalizeCompilerPresenceDirectives(source: String): String {
        return REPLACED_PRESENCE_DIRECTIVE.replace(source) { match ->
            val indent = match.groupValues[1]
            val keyword = match.groupValues[2]
            val expression = match.groupValues[3]
            val comment = match.groupValues[4]
            val condition = if (keyword == "ifdef") expression else "!($expression)"
            "$indent#if $condition$comment"
        }
    }

    private fun Char.isAsciiIdentifierStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiIdentifierPart(): Boolean = isAsciiIdentifierStart() || this in '0'..'9'

    private data class SettingCandidate(
        val name: String,
        val type: ShaderSettingType?,
        val defaultValue: String?,
        val domain: List<String>,
        val presenceToggle: Boolean,
        val controlKind: ShaderControlKind?,
        val sourceLine: Int,
        val sourceSlices: List<String>,
        val reason: String? = null,
    ) {
        companion object {
            fun invalid(name: String, directive: PreprocessorDirective, reason: String): SettingCandidate {
                return invalid(name, directive.sourceLine, listOf(directive.exactText), reason)
            }

            fun invalid(name: String, line: Int, slices: List<String>, reason: String): SettingCandidate {
                return SettingCandidate(name, null, null, emptyList(), false, null, line, slices, reason)
            }
        }
    }

    private data class MacroDraft(
        val name: String,
        val functionLike: Boolean,
        val unconditionallyDefined: Boolean,
        val bodies: List<String>,
        val sourceSlices: List<String>,
    )

    private data class ConditionalGroup(
        val id: Int,
        val parentId: Int?,
        val depth: Int,
        val opener: PreprocessorDirective,
        val delimiters: MutableList<PreprocessorDirective>,
        var endif: PreprocessorDirective? = null,
        var range: IntRange = IntRange.EMPTY,
        val settingDependencies: MutableSet<String> = linkedSetOf(),
        var disposition: ShaderConditionalDisposition = ShaderConditionalDisposition.UNRELATED,
        var reason: String? = null,
    ) {
        fun structural(detail: String) {
            disposition = ShaderConditionalDisposition.STRUCTURAL
            reason = detail
        }
    }

    private data class ConditionalBranch(
        val directive: PreprocessorDirective,
        val bodyRange: IntRange,
        val renderedBody: String,
    )

    private data class Replacement(val start: Int, val end: Int, val text: String)

    private data class FunctionBranch(
        val signature: String,
        val normalizedSignature: String,
        val body: String,
    )

    private class SourceMap(val source: String, val sourceName: String = "<shader>") {
        val lines: List<SourceLine>
        val directives = mutableListOf<PreprocessorDirective>()
        private val starts: IntArray

        init {
            val startsList = mutableListOf(0)
            LINE_ENDING.findAll(source).forEach { startsList += it.range.last + 1 }
            starts = (startsList + source.length).distinct().toIntArray()
            val depths = lineBraceDepths(source)
            lines = startsList.mapIndexed { index, start ->
                val end = if (index + 1 < startsList.size) startsList[index + 1] else source.length
                SourceLine(index + 1, source.substring(start, end), start until end, depths.getOrElse(index) { 0 })
            }
        }

        fun directiveRange(directive: PreprocessorDirective): IntRange {
            val start = starts[directive.sourceLine - 1]
            val end = if (directive.endLine < starts.size - 1) starts[directive.endLine] else source.length
            return start until end
        }

        fun afterDirective(directive: PreprocessorDirective): Int = directiveRange(directive).last + 1

        fun lineAt(offset: Int): Int {
            val index = starts.binarySearch(offset)
            return if (index >= 0) index + 1 else -index - 1
        }
    }

    private data class SourceLine(val number: Int, val text: String, val range: IntRange, val braceDepth: Int)

    private fun lineBraceDepths(source: String): List<Int> {
        val masked = maskCommentsAndStrings(source)
        val result = mutableListOf<Int>()
        var depth = 0
        var cursor = 0
        while (cursor < masked.length) {
            result += depth
            while (cursor < masked.length && masked[cursor] != '\r' && masked[cursor] != '\n') {
                when (masked[cursor]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                }
                cursor++
            }
            if (cursor < masked.length && masked[cursor] == '\r' && cursor + 1 < masked.length && masked[cursor + 1] == '\n') {
                cursor += 2
            } else if (cursor < masked.length) {
                cursor++
            }
        }
        if (source.isEmpty()) return emptyList()
        if (source.endsWith('\n') || source.endsWith('\r')) result += depth
        return result
    }

    private val CONTROL_FLOW_DISPOSITIONS = setOf(
        ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT,
        ShaderConditionalDisposition.CONTROL_FLOW_EXPRESSION,
        ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION,
    )
    private val COMPILER_TRANSFORM_DISPOSITIONS =
        CONTROL_FLOW_DISPOSITIONS + ShaderConditionalDisposition.COMPILER_NO_OP
    private val CONDITIONAL_OPENERS = setOf(
        PreprocessorDirectiveKind.IF,
        PreprocessorDirectiveKind.IFDEF,
        PreprocessorDirectiveKind.IFNDEF,
    )
    private val STRUCTURAL_BODY_DIRECTIVES = setOf(
        PreprocessorDirectiveKind.DEFINE,
        PreprocessorDirectiveKind.UNDEF,
        PreprocessorDirectiveKind.EXTENSION,
        PreprocessorDirectiveKind.PRAGMA,
        PreprocessorDirectiveKind.ERROR,
        PreprocessorDirectiveKind.WARNING,
    )
    private val SETTING_DEFINITION_DIRECTIVES = setOf(
        PreprocessorDirectiveKind.DEFINE,
        PreprocessorDirectiveKind.DISABLED_DEFINE,
    )
    private const val SETTING_PREFIX = "SETTING_"
    private const val COMPILER_SETTING_PREFIX = "SM_SETTING_"
    private const val COMPILER_HOST_PREFIX = "SM_IRIS_HOST_"
    private val SETTING_TOKEN = "\\bSETTING_[A-Za-z0-9_]+\\b".toRegex()
    private val REPLACED_PRESENCE_DIRECTIVE =
        "(?m)^([\\t ]*)#[\\t ]*(ifdef|ifndef)[\\t ]+(\\([^\\r\\n]+\\))([\\t ]*(?://[^\\r\\n]*)?)$".toRegex()
    private val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    private fun IDENTIFIER_TOKEN(name: String) = "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
    private val OPTION_DOMAIN = "//\\s*\\[([^]\\r\\n]+)]".toRegex()
    private val WHITESPACE = "\\s+".toRegex()
    private val INT_LITERAL = "[-+]?(?:0[xX][0-9A-Fa-f]+|[0-9]+)".toRegex()
    private val FLOAT_LITERAL = "[-+]?(?:(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[0-9]+[eE][-+]?[0-9]+)[fF]?".toRegex()
    private val STRING_LITERAL = "\"(?:[^\"\\\\]|\\\\.)*\"".toRegex()
    private val BLOCK_COMMENT = "/\\*.*?\\*/".toRegex(setOf(RegexOption.DOT_MATCHES_ALL))
    private val EXISTING_SPECIALIZATION_ID = "(?:constant_id|local_size_[xyz]_id)\\s*=\\s*([0-9]+)".toRegex()
    private val DEFINED_IDENTIFIER = "\\bdefined\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)".toRegex()
    private val DEFINED_IDENTIFIER_BARE = "\\bdefined\\s+([A-Za-z_][A-Za-z0-9_]*)".toRegex()
    private val DEFINED_REMAINS = "\\bdefined\\b".toRegex()
    private val COMPARISON_OPERATOR = "==|!=|<=|>=|<|>".toRegex()
    private val INTEGER_LITERAL = "[0-9]+".toRegex()
    private val LOGICAL_LEFT_INTEGER =
        "(^|&&|\\|\\|)([\\t ]*\\(*[\\t ]*)([0-9]+)(?![A-Za-z0-9_.])(?=[\\t ]*(?:&&|\\|\\|))".toRegex()
    private val LOGICAL_RIGHT_INTEGER =
        "(&&|\\|\\|)([\\t ]*)([0-9]+)(?![A-Za-z0-9_.])".toRegex()
    private val CASE_LABEL = "(?m)^[ \\t]*(?:case\\b[^:]*|default)[ \\t]*:".toRegex()
    private val TOKEN_PASTE = "##".toRegex()
    private val TOKEN_PASTE_CHAIN =
        "(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*(?:[ \\t]*##[ \\t]*[A-Za-z_][A-Za-z0-9_]*)+)".toRegex()
    private val TOKEN_PASTE_SEPARATOR = "[ \\t]*##[ \\t]*".toRegex()
    private val TOKEN_PASTE_BEFORE_DELIMITER = "##(?=[ \\t]*[({\\[])".toRegex()
    private const val MAX_TOKEN_PASTE_DISPATCH_CASES = 256
    private val FUNCTION_DEFINITION =
        ("(?m)^[ \\t]*(?:(?:const|precise|highp|mediump|lowp)[ \\t]+)*" +
            "([A-Za-z_][A-Za-z0-9_]*)[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*" +
            "\\(([^;{}]*)\\)[ \\t\\r\\n]*\\{").toRegex()
    private val CONSTANT_DEFINITION =
        ("\\bconst\\s+(?:(?:precise|highp|mediump|lowp)\\s+)*" +
            "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)" +
            "\\s*(?:\\[[^]=;]*])?\\s*=").toRegex()
    private val GLSL_TYPE = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    private val MATRIX_TYPE = "mat([234])(?:x[234])?".toRegex()
    private val PARAMETER_COMPONENT = "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\)?\\s*\\.([xyzw])".toRegex()
    private val PARAMETER_QUALIFIERS = setOf(
        "const", "in", "out", "inout", "precise", "highp", "mediump", "lowp",
    )
    private val CONTROL_STATEMENT = "\\b(?:if|for|while|switch|return|break|continue|discard)\\b".toRegex()
    private val RETURN_SUFFIX = "\\breturn\\s*$".toRegex()
    private val LOCAL_DECLARATION =
        ("(?:^|[;{}])\\s*(?:(?:const|precise|highp|mediump|lowp)\\s+)*(?:bool|int|uint|float|double|" +
            "[biud]?vec[234]|d?mat[234](?:x[234])?|[A-Z][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)").toRegex()
    private val FUNCTION_WITH_OPEN = "(?m)^[ \\t]*(?:[A-Za-z_][A-Za-z0-9_]*[ \\t]+)+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*\\([^;{}]*\\)[ \\t]*\\{".toRegex()
    private val LAYOUT_USE = "\\blayout\\s*\\(".toRegex()
    private val ABI_DECLARATION = "\\b(?:uniform|buffer|in|out|attribute|varying|shared)\\b".toRegex()
    private val ABI_BLOCK_DECLARATION = "\\b(?:uniform|buffer)\\b[^{;]*\\{".toRegex()
    private val GLOBAL_CONST_INITIALIZER =
        ("\\bconst\\s+(?:(?:highp|mediump|lowp|precise)\\s+)*([A-Za-z_][A-Za-z0-9_]*)\\s*" +
            "((?:\\[[^=;]*])*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*((?:\\[[^=;]*])*)\\s*=").toRegex()
    private val SCALAR_GLSL_TYPES = setOf("bool", "int", "uint", "float", "double")
    private val FUNCTION_CALL = "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(".toRegex()
    private val VERSION_LINE = "(?m)^[ \\t]*#version\\b[^\\r\\n]*".toRegex()
    private val LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
}

internal class ShaderCompilerCopyException(
    val sourceName: String,
    val stage: ShaderStage,
    val artifactDirectory: Path,
    val command: List<String>,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append(sourceName)
        append(" [")
        append(stage.glslangName)
        append("] failed during compiler-copy materialization: ")
        append(detail)
        appendLine()
        append("Command: ")
        append(command.joinToString(" ") { it.asCompilerCopyDiagnosticArgument() })
        appendLine()
        append("Artifacts: ")
        append(artifactDirectory.toAbsolutePath().normalize())
    },
    cause,
)

internal fun interface ClangProcessRunner {
    fun execute(command: List<String>, workingDirectory: Path, stdoutPath: Path, stderrPath: Path): Int
}

private object SystemClangProcessRunner : ClangProcessRunner {
    override fun execute(command: List<String>, workingDirectory: Path, stdoutPath: Path, stderrPath: Path): Int {
        return ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectOutput(stdoutPath.toFile())
            .redirectError(stderrPath.toFile())
            .start()
            .waitFor()
    }
}

internal data class ShaderCompilerCopyMaterializationRequest(
    val sourceName: String,
    val stage: ShaderStage,
    val plan: ShaderCompilerCopyPlan,
    val probe: TextureAccessProbe,
    val moduleName: String = "compiler-copy",
)

internal sealed interface ShaderCompilerCopyMaterialization {
    data class Success(val module: SpirvCompilerModule) : ShaderCompilerCopyMaterialization
    data class Failure(val exception: ShaderCompilerCopyException) : ShaderCompilerCopyMaterialization
}

internal class ShaderCompilerCopyMaterializer(
    workingDirectory: Path,
    private val clangExecutable: String = "clang",
    private val processRunner: ClangProcessRunner = SystemClangProcessRunner,
    private val processGate: ExternalProcessGate? = null,
    private val metrics: PipelineMetrics? = null,
    private val cacheCharacterBudget: Long = DEFAULT_CACHE_CHARACTER_BUDGET,
) {
    val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()
    private val materializedSourceCache = LinkedHashMap<SourceMaterializationKey, String>(16, 0.75f, true)
    private var materializedSourceCacheCharacters = 0L

    init {
        require(clangExecutable.isNotBlank()) { "clang executable cannot be blank" }
        require(cacheCharacterBudget >= 0) { "compiler-copy cache character budget cannot be negative" }
        this.workingDirectory.createDirectories()
    }

    fun materialize(
        sourceName: String,
        stage: ShaderStage,
        plan: ShaderCompilerCopyPlan,
        probe: TextureAccessProbe,
        moduleName: String = "compiler-copy",
    ): SpirvCompilerModule {
        return when (
            val result = materializeBatch(
                listOf(ShaderCompilerCopyMaterializationRequest(sourceName, stage, plan, probe, moduleName)),
            ).single()
        ) {
            is ShaderCompilerCopyMaterialization.Success -> result.module
            is ShaderCompilerCopyMaterialization.Failure -> throw result.exception
        }
    }

    fun materializeBatch(
        requests: List<ShaderCompilerCopyMaterializationRequest>,
    ): List<ShaderCompilerCopyMaterialization> {
        val sources = requests.map { request ->
            require(request.moduleName.isNotBlank()) { "compiler module name cannot be blank" }
            val source = requireNotNull(request.plan.compilerSource) {
                "${request.sourceName} has structural compiler-copy blockers: " +
                    request.plan.structuralBlockers.joinToString { it.reason }
            }
            SourceMaterializationRequest(
                request.sourceName,
                request.stage,
                source,
                request.moduleName,
            )
        }
        return materializeSources(sources).zip(requests).map { (result, request) ->
            when (result) {
                is SourceMaterialization.Success -> ShaderCompilerCopyMaterialization.Success(
                    SpirvCompilerModule(
                        name = request.moduleName,
                        source = result.source,
                        resourceMarkers = request.probe.markers,
                        conservativeAccess = request.probe.conservativeAccess,
                        irisContracts = request.plan.irisContracts.withMaterializedCompilerSource(result.source),
                        settings = request.plan.settings,
                        tokenPasteLowerings = request.plan.tokenPasteLowerings,
                        derivedScalarExpressions = compilerDerivedScalarExpressions(request.plan),
                    ),
                )
                is SourceMaterialization.Failure -> ShaderCompilerCopyMaterialization.Failure(result.exception)
            }
        }
    }

    fun materializeSource(
        sourceName: String,
        stage: ShaderStage,
        source: String,
        moduleName: String,
    ): String {
        require(moduleName.isNotBlank()) { "compiler module name cannot be blank" }
        return when (
            val result = materializeSources(
                listOf(SourceMaterializationRequest(sourceName, stage, source, moduleName)),
            ).single()
        ) {
            is SourceMaterialization.Success -> result.source
            is SourceMaterialization.Failure -> throw result.exception
        }
    }

    @Synchronized
    private fun materializeSources(
        requests: List<SourceMaterializationRequest>,
    ): List<SourceMaterialization> {
        if (requests.isEmpty()) return emptyList()
        val results = arrayOfNulls<SourceMaterialization>(requests.size)
        val pending = linkedMapOf<SourceMaterializationKey, MutableList<Pair<Int, SourceMaterializationRequest>>>()
        requests.forEachIndexed { index, request ->
            val key = request.cacheKey()
            val cached = materializedSourceCache[key]
            if (cached == null) {
                pending.getOrPut(key) { mutableListOf() } += index to request
            } else {
                results[index] = SourceMaterialization.Success(cached)
            }
        }
        val pendingEntries = pending.entries.toList()
        val chunks = pendingEntries.map { it.value.first().second }.chunked(CLANG_BATCH_SIZE)
        fun materializeChunk(chunk: List<SourceMaterializationRequest>): List<SourceMaterialization> {
            val prepared = chunk.map(::prepare)
            return if (prepared.size == 1) {
                listOf(runSingle(prepared.single()))
            } else {
                runBatch(prepared) ?: prepared.map(::runSingle)
            }
        }
        val materialized = if (chunks.size <= 1) {
            chunks.flatMap(::materializeChunk)
        } else {
            val executor = Executors.newFixedThreadPool(minOf(CLANG_BATCH_PARALLELISM, chunks.size))
            try {
                executor.invokeAll(chunks.map { chunk -> Callable { materializeChunk(chunk) } })
                    .flatMap { future ->
                        try {
                            future.get()
                        } catch (e: ExecutionException) {
                            val cause = e.cause
                            when (cause) {
                                is RuntimeException -> throw cause
                                is Error -> throw cause
                                else -> throw IllegalStateException("Compiler-copy batch worker failed", cause)
                            }
                        }
                    }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Compiler-copy batch materialization interrupted", e)
            } finally {
                executor.shutdownNow()
            }
        }
        pendingEntries.zip(materialized).forEach { (entry, result) ->
            val duplicates = entry.value
            if (result is SourceMaterialization.Success) {
                cacheMaterializedSource(entry.key, result.source)
                duplicates.forEach { (index, _) -> results[index] = result }
            } else {
                val (representativeIndex, _) = duplicates.first()
                results[representativeIndex] = result
                duplicates.drop(1).forEach { (index, request) ->
                    results[index] = runSingle(prepare(request))
                }
            }
        }
        return results.map { requireNotNull(it) }
    }

    private fun cacheMaterializedSource(key: SourceMaterializationKey, source: String) {
        val characters = key.source.length.toLong() + key.pathIdentity.orEmpty().length + source.length
        if (characters > cacheCharacterBudget) return
        materializedSourceCache.remove(key)?.let { previous ->
            materializedSourceCacheCharacters -=
                key.source.length.toLong() + key.pathIdentity.orEmpty().length + previous.length
        }
        materializedSourceCache[key] = source
        materializedSourceCacheCharacters += characters
        val iterator = materializedSourceCache.entries.iterator()
        while (materializedSourceCacheCharacters > cacheCharacterBudget && iterator.hasNext()) {
            val entry = iterator.next()
            materializedSourceCacheCharacters -=
                entry.key.source.length.toLong() + entry.key.pathIdentity.orEmpty().length + entry.value.length
            iterator.remove()
        }
    }

    private fun SourceMaterializationRequest.cacheKey(): SourceMaterializationKey {
        val pathIdentity = if (PATH_SENSITIVE_PREPROCESSOR_BUILTIN.containsMatchIn(source)) {
            "$sourceName\u0000$moduleName"
        } else {
            null
        }
        return SourceMaterializationKey(stage, source, pathIdentity)
    }

    private fun prepare(request: SourceMaterializationRequest): PreparedCompilerCopy {
        val sourceName = request.sourceName
        val stage = request.stage
        val source = request.source
        val moduleName = request.moduleName
        val artifactDirectory = workingDirectory.resolve(
            "cc-${shortHash("$sourceName\u0000${stage.name}\u0000$moduleName\u0000$source")}",
        )
        artifactDirectory.createDirectories()
        val clangInputPath = artifactDirectory.resolve("clang-input.glsl")
        val stdoutPath = artifactDirectory.resolve("clang.stdout.log")
        val stderrPath = artifactDirectory.resolve("clang.stderr.log")
        val protected = protectGlslDirectives(normalizePunctuationTokenPaste(source))
        var marker = "__SHADESMITH_COMPILER_COPY_${shortHash("$sourceName\u0000$moduleName\u0000$source")}__"
        while (marker in protected.source) marker += '_'
        clangInputPath.writeText("${marker}BEGIN\n${protected.source}\n${marker}END\n")
        Files.writeString(stdoutPath, "")
        Files.writeString(stderrPath, "")
        return PreparedCompilerCopy(
            request,
            artifactDirectory,
            clangInputPath,
            stdoutPath,
            stderrPath,
            protected.namespace,
            marker,
        )
    }

    private fun runBatch(prepared: List<PreparedCompilerCopy>): List<SourceMaterialization>? {
        val batchIdentity = prepared.joinToString("\u0000") {
            "${it.request.sourceName}\u0000${it.request.stage.name}\u0000${it.request.moduleName}\u0000${it.marker}"
        }
        val batchDirectory = workingDirectory.resolve("batches").resolve("batch-${shortHash(batchIdentity)}")
        batchDirectory.createDirectories()
        val stdoutPath = batchDirectory.resolve("clang.stdout.log")
        val stderrPath = batchDirectory.resolve("clang.stderr.log")
        Files.writeString(stdoutPath, "")
        Files.writeString(stderrPath, "")
        val command = listOf(clangExecutable) + CLANG_ARGUMENTS + prepared.map { it.clangInputPath.absolutePathString() }
        val exitCode = try {
            execute(command, batchDirectory, stdoutPath, stderrPath)
        } catch (e: IOException) {
            stderrPath.writeText(e.stackTraceToString())
            return null
        }
        if (exitCode != 0) return null
        val output = stdoutPath.readText()
        val stderr = stderrPath.readText()
        val parsed = prepared.map { compilerCopy ->
            parseMaterialized(output, compilerCopy)
        }
        if (parsed.any { it == null }) return null
        prepared.forEach { compilerCopy ->
            Files.deleteIfExists(compilerCopy.clangInputPath)
        }
        Files.writeString(stdoutPath, "")
        if (stderr.isEmpty()) Files.writeString(stderrPath, "")
        metrics?.recordCompilerModules(prepared.size)
        return parsed.map { SourceMaterialization.Success(requireNotNull(it)) }
    }

    private fun runSingle(prepared: PreparedCompilerCopy): SourceMaterialization {
        Files.writeString(prepared.stdoutPath, "")
        Files.writeString(prepared.stderrPath, "")
        val command = listOf(clangExecutable) + CLANG_ARGUMENTS + prepared.clangInputPath.absolutePathString()
        val exitCode = try {
            execute(command, prepared.artifactDirectory, prepared.stdoutPath, prepared.stderrPath)
        } catch (e: IOException) {
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "unable to start clang: ${e.message}",
                    e,
                ),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "interrupted while running clang",
                    e,
                ),
            )
        }
        if (exitCode != 0) {
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "clang exited with code $exitCode",
                ),
            )
        }
        if (!prepared.stdoutPath.isRegularFile()) {
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "clang produced no output",
                ),
            )
        }
        val materialized = parseMaterialized(prepared.stdoutPath.readText(), prepared)
        if (materialized == null) {
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "clang output is missing compiler-copy markers",
                ),
            )
        }
        Files.deleteIfExists(prepared.clangInputPath)
        Files.writeString(prepared.stdoutPath, "")
        metrics?.recordCompilerModules(1)
        return SourceMaterialization.Success(materialized)
    }

    private fun execute(
        command: List<String>,
        workingDirectory: Path,
        stdoutPath: Path,
        stderrPath: Path,
    ): Int {
        metrics?.recordClangProcess()
        return if (processGate == null) {
            processRunner.execute(command, workingDirectory, stdoutPath, stderrPath)
        } else {
            processGate.run { processRunner.execute(command, workingDirectory, stdoutPath, stderrPath) }
        }
    }

    private fun parseMaterialized(output: String, prepared: PreparedCompilerCopy): String? {
        val marker = prepared.marker
        val begin = output.indexOf("${marker}BEGIN")
        val end = output.indexOf("${marker}END", begin + marker.length)
        if (begin < 0 || end < 0) return null
        var contentStart = begin + marker.length + "BEGIN".length
        while (contentStart < end && output[contentStart] in "\r\n") contentStart++
        var contentEnd = end
        while (contentEnd > contentStart && output[contentEnd - 1] in "\r\n") contentEnd--
        return restoreGlslDirectives(output.substring(contentStart, contentEnd), prepared.directiveNamespace)
    }

    private fun failure(
        sourceName: String,
        stage: ShaderStage,
        artifactDirectory: Path,
        command: List<String>,
        detail: String,
        cause: Throwable? = null,
    ): ShaderCompilerCopyException {
        return ShaderCompilerCopyException(
            sourceName,
            stage,
            artifactDirectory,
            command,
            "$detail; see clang.stdout.log and clang.stderr.log",
            cause,
        )
    }

    private fun protectGlslDirectives(source: String): ProtectedCompilerCopySource {
        var namespace = "__SHADESMITH_COMPILER_DIRECTIVE__"
        while (source.contains(namespace)) namespace += '_'
        val capabilityProtected = protectDirectiveOnlyCapabilityBlocks(source, namespace)
        return ProtectedCompilerCopySource(
            PROTECTED_GLSL_DIRECTIVE.replace(capabilityProtected) { match ->
                match.groupValues[1] + "//$namespace" + match.groupValues[2]
            },
            namespace,
        )
    }

    private fun protectDirectiveOnlyCapabilityBlocks(source: String, namespace: String): String {
        data class PhysicalLine(val start: Int, val end: Int, val content: String)
        val starts = buildList {
            add(0)
            COMPILER_LINE_ENDING.findAll(source).forEach { add(it.range.last + 1) }
        }.distinct()
        val lines = starts.mapIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { source.length }
            PhysicalLine(start, end, source.substring(start, end))
        }
        val hashes = mutableListOf<Int>()
        var index = 0
        while (index < lines.size) {
            val opening = lines[index].content.trimStart()
            if (!CAPABILITY_CONDITIONAL_OPEN.matches(opening.substringBefore('\r').substringBefore('\n'))) {
                index++
                continue
            }
            var depth = 0
            var cursor = index
            var closed = false
            var directiveOnly = true
            val blockHashes = mutableListOf<Int>()
            while (cursor < lines.size) {
                val line = lines[cursor]
                val text = line.content.trimStart().trimEnd('\r', '\n')
                if (text.isNotBlank()) {
                    if (!text.startsWith('#')) {
                        directiveOnly = false
                    } else {
                        blockHashes += line.start + line.content.indexOf('#')
                        when {
                            CONDITIONAL_OPEN.matches(text) -> depth++
                            CONDITIONAL_END.matches(text) -> {
                                depth--
                                if (depth == 0) {
                                    closed = true
                                    break
                                }
                            }
                        }
                    }
                }
                cursor++
            }
            if (closed && directiveOnly) {
                hashes += blockHashes
                index = cursor + 1
            } else {
                index++
            }
        }
        return hashes.distinct().sortedDescending().fold(source) { result, offset ->
            result.substring(0, offset) + "//$namespace" + result.substring(offset)
        }
    }

    private fun restoreGlslDirectives(source: String, namespace: String): String = source.replace("//$namespace", "")

    private fun normalizePunctuationTokenPaste(source: String): String = TOKEN_PASTE_BEFORE_OPENING_DELIMITER.replace(source, "")

    private fun safeName(name: String): String {
        return name.replace(INVALID_PATH_CHAR, "_").trim('_').take(80).ifEmpty { "shader" }
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class ProtectedCompilerCopySource(val source: String, val namespace: String)

    private data class SourceMaterializationRequest(
        val sourceName: String,
        val stage: ShaderStage,
        val source: String,
        val moduleName: String,
    )

    private data class SourceMaterializationKey(
        val stage: ShaderStage,
        val source: String,
        val pathIdentity: String?,
    )

    private data class PreparedCompilerCopy(
        val request: SourceMaterializationRequest,
        val artifactDirectory: Path,
        val clangInputPath: Path,
        val stdoutPath: Path,
        val stderrPath: Path,
        val directiveNamespace: String,
        val marker: String,
    )

    private sealed interface SourceMaterialization {
        data class Success(val source: String) : SourceMaterialization
        data class Failure(val exception: ShaderCompilerCopyException) : SourceMaterialization
    }

    companion object {
        internal const val CLANG_BATCH_SIZE = 20
        private const val CLANG_BATCH_PARALLELISM = 4
        internal const val DEFAULT_CACHE_CHARACTER_BUDGET = 128L * 1024L * 1024L
        private val CLANG_ARGUMENTS = listOf("-C", "-E", "-P", "-Wno-microsoft-include", "-x", "c")

        internal fun cacheContract(clangExecutable: String): String {
            return (listOf(clangExecutable, "batch-size=$CLANG_BATCH_SIZE") + CLANG_ARGUMENTS + "<inputs...>")
                .joinToString("\u0000")
        }

        private val INVALID_PATH_CHAR = "[^A-Za-z0-9._-]".toRegex()
        private val PROTECTED_GLSL_DIRECTIVE = "(?m)^([ \\t]*)(#(?:version|extension|pragma|line)\\b)".toRegex()
        private val COMPILER_LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
        private val CAPABILITY_CONDITIONAL_OPEN =
            "#[ \\t]*if\\b.*\\bdefined[ \\t]*(?:\\([ \\t]*)?GL_[A-Za-z0-9_]+.*".toRegex()
        private val CONDITIONAL_OPEN = "#[ \\t]*(?:if|ifdef|ifndef)\\b.*".toRegex()
        private val CONDITIONAL_END = "#[ \\t]*endif\\b.*".toRegex()
        private val TOKEN_PASTE_BEFORE_OPENING_DELIMITER = "##(?=[ \\t]*[({\\[])".toRegex()
        private val PATH_SENSITIVE_PREPROCESSOR_BUILTIN =
            "\\b__(?:FILE|BASE_FILE)__\\b".toRegex()
    }
}

private fun String.asCompilerCopyDiagnosticArgument(): String {
    if (none { it.isWhitespace() || it == '"' }) return this
    return "\"${replace("\"", "\\\"")}\""
}
