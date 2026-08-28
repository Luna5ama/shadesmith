package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

internal data class ShaderSetting(
    val name: String,
    val type: ShaderSettingType,
    val defaultValue: String,
    val domain: List<String>,
    val presenceToggle: Boolean,
    val specializationId: Int,
    val sourceSlices: List<String>,
) {
    val compilerName: String
        get() = "SM_$name"
}

internal data class ShaderMacroDependency(
    val name: String,
    val functionLike: Boolean,
    val dependencies: Set<String>,
    val settingDependencies: Set<String>,
    val sourceSlices: List<String>,
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

internal data class ShaderCompilerCopyPlan(
    val sourceName: String,
    val originalSource: String,
    val compilerSource: String?,
    val compilerCandidateSource: String,
    val settings: List<ShaderSetting>,
    val macros: List<ShaderMacroDependency>,
    val sourceRegions: List<ShaderSourceRegion>,
    val conditionals: List<ShaderConditionalRegion>,
    val structuralBlockers: List<ShaderCompilerCopyBlocker>,
    val irisContracts: IrisShaderContractPlan,
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
        val initialMacroDependencies = resolveMacroDependencies(collectMacros(initialProtection.directives))
        val initialGroups = buildConditionalGroups(initialProtection.directives, initialSourceMap)

        val referencedSettings = linkedSetOf<String>()
        initialGroups.forEach { group ->
            group.settingDependencies += settingDependencies(
                group.delimiters.mapNotNull { it.expression }.joinToString(" "),
                initialMacroDependencies,
            )
            referencedSettings += group.settingDependencies
        }
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

        val blockers = mutableListOf<ShaderCompilerCopyBlocker>()
        val settings = referencedSettings.sorted().mapNotNull { name ->
            val candidates = settingCandidates[name].orEmpty()
            if (candidates.isEmpty()) {
                blockers += ShaderCompilerCopyBlocker(
                    firstSettingUseLine(source, initialSourceMap, name),
                    "setting $name has no scalar option definition in this compiler root",
                )
                null
            } else {
                mergeSettingCandidates(name, candidates)?.also { candidate ->
                    if (candidate.reason != null) {
                        blockers += ShaderCompilerCopyBlocker(candidate.sourceLine, candidate.reason)
                    }
                }?.takeIf { it.reason == null }
            }
        }
        val existingIds = EXISTING_SPECIALIZATION_ID.findAll(source)
            .map { it.groupValues[1].toInt() }
            .toMutableSet()
        var nextId = 0
        val typedSettings = settings.map { candidate ->
            while (nextId in existingIds) nextId++
            val result = ShaderSetting(
                name = candidate.name,
                type = requireNotNull(candidate.type),
                defaultValue = requireNotNull(candidate.defaultValue),
                domain = candidate.domain,
                presenceToggle = candidate.presenceToggle,
                specializationId = nextId,
                sourceSlices = candidate.sourceSlices,
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
        val planningSource = irisContracts.compilerSource
        val protection = PreprocessorProtection.protect(planningSource, sourceName)
        val sourceMap = SourceMap(planningSource, sourceName)
        sourceMap.directives += protection.directives
        val macroDependencies = resolveMacroDependencies(collectMacros(protection.directives))
        val groups = buildConditionalGroups(protection.directives, sourceMap)
        val regions = findSourceRegions(planningSource, sourceMap)
        groups.forEach { group ->
            group.settingDependencies += settingDependencies(
                group.delimiters.mapNotNull { it.expression }.joinToString(" "),
                macroDependencies,
            )
        }
        irisContracts.structuralReason?.let { reason ->
            blockers += ShaderCompilerCopyBlocker(1, reason)
        }

        val groupById = groups.associateBy { it.id }
        groups.sortedByDescending { it.depth }.forEach { group ->
            classifyGroup(group, groupById, planningSource, sourceMap, settingsByName, macroDependencies, regions)
            if (group.disposition == ShaderConditionalDisposition.STRUCTURAL) {
                blockers += ShaderCompilerCopyBlocker(group.opener.sourceLine, requireNotNull(group.reason))
            }
        }
        blockers += findStructuralSettingUses(
            planningSource,
            sourceMap,
            protection.directives,
            settingsByName.keys,
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
        )
        return ShaderCompilerCopyPlan(
            sourceName = sourceName,
            originalSource = source,
            compilerSource = compilerCandidate.takeIf { distinctBlockers.isEmpty() },
            compilerCandidateSource = compilerCandidate,
            settings = typedSettings,
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
        )
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
            directive.sourceLine,
            listOf(directive.exactText),
        )
    }

    private fun mergeSettingCandidates(name: String, candidates: List<SettingCandidate>): SettingCandidate? {
        val invalid = candidates.firstOrNull { it.reason != null }
        if (invalid != null) return invalid
        val signatures = candidates.map {
            listOf(it.type, it.defaultValue, it.domain, it.presenceToggle)
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
                    bodies = definitions.map { it.macroBody.orEmpty() },
                    sourceSlices = definitions.map { it.exactText },
                )
            }
    }

    private fun resolveMacroDependencies(drafts: List<MacroDraft>): List<ShaderMacroDependency> {
        val byName = drafts.associateBy { it.name }
        val direct = drafts.associate { draft ->
            draft.name to draft.bodies.flatMapTo(linkedSetOf()) { identifiers(stripComments(it)) - draft.name }
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
                dependencies = direct.getValue(draft.name),
                settingDependencies = settingsFor(draft.name, linkedSetOf()),
                sourceSlices = draft.sourceSlices,
            )
        }
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
            val functions = branches.map { parseFunctionBranch(it.renderedBody) }
            val signatures = functions.mapNotNull { it?.normalizedSignature }.distinct()
            if (
                branches.last().directive.kind == PreprocessorDirectiveKind.ELSE &&
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

    private fun buildCompilerSource(
        source: String,
        sourceMap: SourceMap,
        directives: List<PreprocessorDirective>,
        settings: List<ShaderSetting>,
        groups: List<ConditionalGroup>,
        macros: List<ShaderMacroDependency>,
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
        directives.filter {
            it.kind in SETTING_DEFINITION_DIRECTIVES &&
                it.macroName in settingsByName
        }.forEach {
            val range = sourceMap.directiveRange(it)
            if (rootTransformed.none { group -> range.first >= group.range.first && range.last <= group.range.last }) {
                replacements += Replacement(range.first, range.last + 1, maskSource(source.substring(range)))
            }
        }
        var result = applyReplacements(source, replacements)
        result = replaceIdentifierTokens(result, settings.associate { it.name to it.compilerName })
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

            ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT -> buildString {
                branches.forEachIndexed { index, branch ->
                    if (branch.directive.kind == PreprocessorDirectiveKind.ELSE) {
                        append("else {\n")
                    } else {
                        if (index > 0) append("else ")
                        append("if (")
                        append(requireNotNull(convertCondition(branch.directive, settings, macros)))
                        append(") {\n")
                    }
                    append(branch.renderedBody)
                    if (branch.renderedBody.isNotEmpty() && !branch.renderedBody.endsWith('\n')) append('\n')
                    append("}\n")
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
                val functions = branches.map { requireNotNull(parseFunctionBranch(it.renderedBody)) }
                buildString {
                    append(functions.first().signature.trimEnd())
                    append(" {\n")
                    branches.zip(functions).forEachIndexed { index, (branch, function) ->
                        if (branch.directive.kind == PreprocessorDirectiveKind.ELSE) {
                            append("else {\n")
                        } else {
                            if (index > 0) append("else ")
                            append("if (")
                            append(requireNotNull(convertCondition(branch.directive, settings, macros)))
                            append(") {\n")
                        }
                        append(function.body)
                        if (function.body.isNotEmpty() && !function.body.endsWith('\n')) append('\n')
                        append("}\n")
                    }
                    append("}\n")
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
        val macroByName = macros.associateBy { it.name }
        val raw = when (directive.kind) {
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            -> {
                val setting = settings[directive.macroName] ?: return null
                if (!setting.presenceToggle) return null
                return if (directive.kind == PreprocessorDirectiveKind.IFDEF) setting.compilerName else "!${setting.compilerName}"
            }

            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.ELIF,
            -> directive.expression.orEmpty().substringBefore("//").trim()

            else -> return null
        }
        var expression = raw
        expression = DEFINED_SETTING.replace(expression) { match ->
            val name = match.groupValues[1]
            settings[name]?.takeIf { it.presenceToggle }?.compilerName ?: match.value
        }
        expression = DEFINED_SETTING_BARE.replace(expression) { match ->
            val name = match.groupValues[1]
            settings[name]?.takeIf { it.presenceToggle }?.compilerName ?: match.value
        }
        var failed = false
        expression = IDENTIFIER.findAll(expression).toList().asReversed().fold(expression) { value, match ->
            val token = match.value
            val replacement = when {
                token in settings -> settings.getValue(token).compilerName
                macroByName[token]?.settingDependencies?.isNotEmpty() == true -> {
                    val macro = macroByName.getValue(token)
                    if (macro.functionLike || macro.sourceSlices.size != 1) {
                        failed = true
                        token
                    } else {
                        val body = stripComments(macro.sourceSlices.single().substringAfter(macro.name)).trim()
                        val expanded = replaceIdentifierTokens(
                            body,
                            settings.mapValues { it.value.compilerName },
                        )
                        "($expanded)"
                    }
                }

                else -> token
            }
            value.replaceRange(match.range, replacement)
        }
        if (failed || DEFINED_REMAINS.containsMatchIn(expression)) return null
        val numericSettings = settings.values.filter {
            it.type != ShaderSettingType.BOOL && IDENTIFIER_TOKEN(it.compilerName).containsMatchIn(expression)
        }
        if (numericSettings.isNotEmpty() && !COMPARISON_OPERATOR.containsMatchIn(expression)) {
            val simple = numericSettings.singleOrNull() ?: return null
            expression = when (expression.trim()) {
                simple.compilerName -> "${simple.compilerName} != 0"
                "!${simple.compilerName}" -> "${simple.compilerName} == 0"
                else -> return null
            }
        }
        return expression
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
        val abiBlockLines = hashSetOf<Int>()
        var inAbiBlock = false
        sourceMap.lines.forEach { line ->
            val lexical = maskCommentsAndStrings(line.text)
            val opensDeclarationBlock = line.braceDepth == 0 && '{' in lexical &&
                !FUNCTION_WITH_OPEN.containsMatchIn(lexical)
            if (!inAbiBlock && opensDeclarationBlock) inAbiBlock = true
            if (inAbiBlock) abiBlockLines += line.number
            if (inAbiBlock && line.braceDepth <= 1 && '}' in lexical) inAbiBlock = false
        }
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
        return sourceMap.lines.mapNotNull { line ->
            if (line.number in directiveLines) return@mapNotNull null
            val lineIdentifiers = identifiers(maskCommentsAndStrings(line.text))
            val settingDependencies = buildSet {
                addAll(lineIdentifiers.filter(settingNames::contains))
                lineIdentifiers.forEach { addAll(macrosByName[it]?.settingDependencies.orEmpty()) }
            }
            if (settingDependencies.isEmpty()) return@mapNotNull null
            val tokenPaste = TOKEN_PASTE.containsMatchIn(line.text) || lineIdentifiers.any {
                usesTokenPaste(it, linkedSetOf())
            }
            when {
                LAYOUT_USE.containsMatchIn(line.text) -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a layout or execution-mode directive",
                )

                line.number in abiBlockLines -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a resource block member or layout",
                )

                line.braceDepth == 0 && ABI_DECLARATION.containsMatchIn(line.text) -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a resource or stage-interface declaration",
                )

                line.braceDepth == 0 && FUNCTION_WITH_OPEN.containsMatchIn(line.text) -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting affects a function signature or ABI",
                )

                tokenPaste -> ShaderCompilerCopyBlocker(
                    line.number,
                    "setting participates in token paste",
                )

                else -> null
            }
        }
    }

    private fun findSourceRegions(source: String, sourceMap: SourceMap): List<ShaderSourceRegion> {
        val masked = maskCommentsAndStrings(source)
        val result = mutableListOf<ShaderSourceRegion>()
        val functionRanges = mutableListOf<IntRange>()
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
                    if (start <= cursor && functionRanges.none { start in it || cursor in it }) {
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

    private fun parseFunctionBranch(source: String): FunctionBranch? {
        val masked = maskCommentsAndStrings(source)
        val match = FUNCTION_WITH_OPEN.find(masked) ?: return null
        if (masked.substring(0, match.range.first).isNotBlank()) return null
        val open = masked.indexOf('{', match.range.first)
        val close = matchingBrace(masked, open)
        if (open < 0 || close < 0 || masked.substring(close + 1).isNotBlank()) return null
        val signature = source.substring(match.range.first, open)
        return FunctionBranch(
            signature = signature,
            normalizedSignature = signature.replace(WHITESPACE, " ").trim(),
            body = source.substring(open + 1, close),
        )
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
        return buildString(source.length) {
            var cursor = 0
            while (cursor < source.length) {
                val char = source[cursor]
                if (!char.isAsciiIdentifierStart()) {
                    append(char)
                    cursor++
                    continue
                }
                val start = cursor++
                while (cursor < source.length && source[cursor].isAsciiIdentifierPart()) cursor++
                val token = source.substring(start, cursor)
                append(replacements[token] ?: token)
            }
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
        val sourceLine: Int,
        val sourceSlices: List<String>,
        val reason: String? = null,
    ) {
        companion object {
            fun invalid(name: String, directive: PreprocessorDirective, reason: String): SettingCandidate {
                return invalid(name, directive.sourceLine, listOf(directive.exactText), reason)
            }

            fun invalid(name: String, line: Int, slices: List<String>, reason: String): SettingCandidate {
                return SettingCandidate(name, null, null, emptyList(), false, line, slices, reason)
            }
        }
    }

    private data class MacroDraft(
        val name: String,
        val functionLike: Boolean,
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
                SourceLine(index + 1, source.substring(start, end), depths.getOrElse(index) { 0 })
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

    private data class SourceLine(val number: Int, val text: String, val braceDepth: Int)

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
    private val SETTING_TOKEN = "\\bSETTING_[A-Za-z0-9_]+\\b".toRegex()
    private val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    private fun IDENTIFIER_TOKEN(name: String) = "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
    private val OPTION_DOMAIN = "//\\s*\\[([^]\\r\\n]+)]".toRegex()
    private val WHITESPACE = "\\s+".toRegex()
    private val INT_LITERAL = "[-+]?(?:0[xX][0-9A-Fa-f]+|[0-9]+)".toRegex()
    private val FLOAT_LITERAL = "[-+]?(?:(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[0-9]+[eE][-+]?[0-9]+)[fF]?".toRegex()
    private val STRING_LITERAL = "\"(?:[^\"\\\\]|\\\\.)*\"".toRegex()
    private val BLOCK_COMMENT = "/\\*.*?\\*/".toRegex(setOf(RegexOption.DOT_MATCHES_ALL))
    private val EXISTING_SPECIALIZATION_ID = "(?:constant_id|local_size_[xyz]_id)\\s*=\\s*([0-9]+)".toRegex()
    private val DEFINED_SETTING = "\\bdefined\\s*\\(\\s*(SETTING_[A-Za-z0-9_]+)\\s*\\)".toRegex()
    private val DEFINED_SETTING_BARE = "\\bdefined\\s+(SETTING_[A-Za-z0-9_]+)".toRegex()
    private val DEFINED_REMAINS = "\\bdefined\\b".toRegex()
    private val COMPARISON_OPERATOR = "==|!=|<=|>=|<|>".toRegex()
    private val CASE_LABEL = "(?m)^[ \\t]*(?:case\\b[^:]*|default)[ \\t]*:".toRegex()
    private val TOKEN_PASTE = "##".toRegex()
    private val CONTROL_STATEMENT = "\\b(?:if|for|while|switch|return|break|continue|discard)\\b".toRegex()
    private val RETURN_SUFFIX = "\\breturn\\s*$".toRegex()
    private val LOCAL_DECLARATION = "(?:^|[;{}])\\s*(?:(?:const|precise|highp|mediump|lowp)\\s+)*(?:bool|int|uint|float|double|[biud]?vec[234]|d?mat[234](?:x[234])?)\\s+([A-Za-z_][A-Za-z0-9_]*)".toRegex()
    private val FUNCTION_WITH_OPEN = "(?m)^[ \\t]*(?:[A-Za-z_][A-Za-z0-9_]*[ \\t]+)+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*\\([^;{}]*\\)[ \\t]*\\{".toRegex()
    private val LAYOUT_USE = "\\blayout\\s*\\(".toRegex()
    private val ABI_DECLARATION = "\\b(?:uniform|buffer|in|out|attribute|varying|shared)\\b".toRegex()
    private val ABI_BLOCK_DECLARATION = "\\b(?:uniform|buffer)\\b[^{;]*\\{".toRegex()
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
) {
    val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()
    private val materializedSourceCache = mutableMapOf<SourceMaterializationKey, String>()

    init {
        require(clangExecutable.isNotBlank()) { "clang executable cannot be blank" }
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
                        irisContracts = request.plan.irisContracts,
                        settings = request.plan.settings,
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
        val pending = mutableListOf<Pair<Int, SourceMaterializationRequest>>()
        requests.forEachIndexed { index, request ->
            val cached = materializedSourceCache[request.cacheKey()]
            if (cached == null) {
                pending += index to request
            } else {
                results[index] = SourceMaterialization.Success(cached)
            }
        }
        val materialized = pending.map { it.second }.chunked(CLANG_BATCH_SIZE).flatMap { chunk ->
            val prepared = chunk.map(::prepare)
            if (prepared.size == 1) {
                listOf(runSingle(prepared.single()))
            } else {
                runBatch(prepared) ?: prepared.map(::runSingle)
            }
        }
        pending.zip(materialized).forEach { (indexed, result) ->
            val (index, request) = indexed
            if (result is SourceMaterialization.Success) {
                materializedSourceCache.putIfAbsent(request.cacheKey(), result.source)
            }
            results[index] = result
        }
        return results.map { requireNotNull(it) }
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
            "${safeName(sourceName)}-${stage.glslangName}-${safeName(moduleName)}-${shortHash(source)}",
        )
        artifactDirectory.createDirectories()
        val inputPath = artifactDirectory.resolve("compiler-copy.glsl")
        val clangInputPath = artifactDirectory.resolve("clang-input.glsl")
        val outputPath = artifactDirectory.resolve("materialized.glsl")
        val stdoutPath = artifactDirectory.resolve("clang.stdout.log")
        val stderrPath = artifactDirectory.resolve("clang.stderr.log")
        inputPath.writeText(source)
        val protected = protectGlslDirectives(normalizePunctuationTokenPaste(source))
        var marker = "__SHADESMITH_COMPILER_COPY_${shortHash("$sourceName\u0000$moduleName\u0000$source")}__"
        while (marker in protected.source) marker += '_'
        clangInputPath.writeText("${marker}BEGIN\n${protected.source}\n${marker}END\n")
        Files.deleteIfExists(outputPath)
        Files.writeString(stdoutPath, "")
        Files.writeString(stderrPath, "")
        return PreparedCompilerCopy(
            request,
            artifactDirectory,
            clangInputPath,
            outputPath,
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
        val exitCode = execute(command, batchDirectory, stdoutPath, stderrPath) ?: return null
        if (exitCode != 0) return null
        val output = stdoutPath.readText()
        val stderr = stderrPath.readText()
        val parsed = prepared.map { compilerCopy ->
            parseMaterialized(output, compilerCopy)?.also { source ->
                compilerCopy.outputPath.writeText(source)
                compilerCopy.stdoutPath.writeText(source)
                compilerCopy.stderrPath.writeText(stderr)
            }
        }
        if (parsed.any { it == null }) return null
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
                    "unable to start clang",
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
        if (exitCode == null) {
            return SourceMaterialization.Failure(
                failure(
                    prepared.request.sourceName,
                    prepared.request.stage,
                    prepared.artifactDirectory,
                    command,
                    "unable to start clang",
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
        prepared.outputPath.writeText(materialized)
        metrics?.recordCompilerModules(1)
        return SourceMaterialization.Success(materialized)
    }

    private fun execute(
        command: List<String>,
        workingDirectory: Path,
        stdoutPath: Path,
        stderrPath: Path,
    ): Int? {
        return try {
            metrics?.recordClangProcess()
            if (processGate == null) {
                processRunner.execute(command, workingDirectory, stdoutPath, stderrPath)
            } else {
                processGate.run { processRunner.execute(command, workingDirectory, stdoutPath, stderrPath) }
            }
        } catch (_: IOException) {
            null
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
        return ProtectedCompilerCopySource(
            PROTECTED_GLSL_DIRECTIVE.replace(source) { match ->
                match.groupValues[1] + "//$namespace" + match.groupValues[2]
            },
            namespace,
        )
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
        val outputPath: Path,
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
        private val CLANG_ARGUMENTS = listOf("-C", "-E", "-P", "-Wno-microsoft-include", "-x", "c")

        internal fun cacheContract(clangExecutable: String): String {
            return (listOf(clangExecutable, "batch-size=$CLANG_BATCH_SIZE") + CLANG_ARGUMENTS + "<inputs...>")
                .joinToString("\u0000")
        }

        private val INVALID_PATH_CHAR = "[^A-Za-z0-9._-]".toRegex()
        private val PROTECTED_GLSL_DIRECTIVE = "(?m)^([ \\t]*)(#(?:version|extension|pragma|line)\\b)".toRegex()
        private val TOKEN_PASTE_BEFORE_OPENING_DELIMITER = "##(?=[ \\t]*[({\\[])".toRegex()
        private val PATH_SENSITIVE_PREPROCESSOR_BUILTIN =
            "\\b__(?:FILE|BASE_FILE)__\\b".toRegex()
    }
}

private fun String.asCompilerCopyDiagnosticArgument(): String {
    if (none { it.isWhitespace() || it == '"' }) return this
    return "\"${replace("\"", "\\\"")}\""
}
