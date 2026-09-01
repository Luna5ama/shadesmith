package dev.luna5ama.shadesmith

import java.security.MessageDigest
import kotlin.io.path.name

internal enum class IrisRegistryMetadataKind {
    SETTING,
    PACK_GLOBAL,
}

internal data class IrisRegistryMetadataSlice(
    val kind: IrisRegistryMetadataKind,
    val key: String,
    val exactText: String,
    val sourceName: String,
    val sourceOrder: Int,
)

internal data class IrisFinalSourcePolicy(
    val sourceName: String,
    val globalSink: Boolean,
    val settings: Map<String, List<IrisRegistryMetadataSlice>>,
    val packGlobals: Map<String, List<IrisRegistryMetadataSlice>>,
    val localSettings: Map<String, List<IrisRegistryMetadataSlice>>,
    val localPackGlobals: Map<String, List<IrisRegistryMetadataSlice>>,
    val cacheContract: String,
)

internal object IrisCorpusMetadataRegistry {
    fun plan(files: List<ShaderFile>): Map<String, IrisFinalSourcePolicy> {
        val standalone = files.filter { file ->
            runCatching { ShaderEntryPoint.from(file.path, file.code).kind == ShaderEntryPointKind.STANDALONE }
                .getOrDefault(false)
        }
        val observations = standalone.associate { file ->
            val name = sourceName(file)
            name to extract(file.code, name)
        }
        val settings = canonicalBundles(
            IrisRegistryMetadataKind.SETTING,
            observations.mapValues { (_, slices) -> slices.filter { it.kind == IrisRegistryMetadataKind.SETTING } },
        )
        val globals = canonicalBundles(
            IrisRegistryMetadataKind.PACK_GLOBAL,
            observations.mapValues { (_, slices) -> slices.filter { it.kind == IrisRegistryMetadataKind.PACK_GLOBAL } },
        )
        val sinks = standalone.filter { it.path.name.equals("final.fsh", ignoreCase = true) }
        if (settings.isNotEmpty() || globals.isNotEmpty()) {
            require(sinks.size == 1) {
                "Iris corpus metadata requires exactly one final.fsh sink; found ${sinks.map(::sourceName).sorted()}"
            }
        } else {
            require(sinks.size <= 1) {
                "Iris corpus contains multiple final.fsh sinks: ${sinks.map(::sourceName).sorted()}"
            }
        }
        val sinkName = sinks.singleOrNull()?.let(::sourceName)
        val registryContract = buildString {
            appendLine("iris-final-source-policy-v2")
            appendLine("sink=${sinkName.orEmpty()}")
            (settings.values.flatten() + globals.values.flatten()).forEach { slice ->
                append(slice.kind)
                append('\t')
                append(slice.key)
                append('\t')
                append(slice.exactText)
                append('\u0000')
            }
        }
        return standalone.associate { file ->
            val name = sourceName(file)
            val local = observations.getValue(name)
            val localSettings = local.filter { it.kind == IrisRegistryMetadataKind.SETTING }.groupBy { it.key }
            val localGlobals = local.filter { it.kind == IrisRegistryMetadataKind.PACK_GLOBAL }.groupBy { it.key }
            name to IrisFinalSourcePolicy(
                sourceName = name,
                globalSink = name == sinkName,
                settings = settings,
                packGlobals = globals,
                localSettings = localSettings,
                localPackGlobals = localGlobals,
                cacheContract = sha256("$registryContract\nroot=$name\nsink=${name == sinkName}\n"),
            )
        }
    }

    private fun extract(source: String, sourceName: String): List<IrisRegistryMetadataSlice> {
        val result = mutableListOf<IrisRegistryMetadataSlice>()
        val protection = PreprocessorProtection.protect(source, sourceName)
        protection.directives.filter { directive ->
            directive.kind in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.DISABLED_DEFINE) &&
                !directive.macroFunctionLike &&
                (
                    directive.macroName?.startsWith("SETTING_") == true ||
                        IRIS_OPTION_DOMAIN_COMMENT.containsMatchIn(directive.exactText)
                    )
        }.forEach { directive ->
            result += IrisRegistryMetadataSlice(
                IrisRegistryMetadataKind.SETTING,
                requireNotNull(directive.macroName),
                directive.exactText,
                sourceName,
                directive.index,
            )
        }
        irisHostRegistryDeclarations(source, sourceName).forEach { declaration ->
            val key = declaration.name
            if (!passLocalHostName(key)) {
                result += IrisRegistryMetadataSlice(
                    IrisRegistryMetadataKind.PACK_GLOBAL,
                    key,
                    source.substring(declaration.range),
                    sourceName,
                    declaration.range.first,
                )
            }
        }
        irisCommentHostRegistryBlocks(source, sourceName).forEach { block ->
            block.names.forEach { name ->
                result += IrisRegistryMetadataSlice(
                    IrisRegistryMetadataKind.PACK_GLOBAL,
                    name,
                    source.substring(block.range),
                    sourceName,
                    block.range.first,
                )
            }
        }
        return result.sortedBy { it.sourceOrder }
    }

    private fun canonicalBundles(
        kind: IrisRegistryMetadataKind,
        observations: Map<String, List<IrisRegistryMetadataSlice>>,
    ): Map<String, List<IrisRegistryMetadataSlice>> {
        val byKey = observations.flatMap { (source, slices) ->
            slices.groupBy { it.key }.map { (key, bundle) -> Triple(key, source, bundle.sortedBy { it.sourceOrder }) }
        }.groupBy { it.first }
        return byKey.toSortedMap().mapValues { (key, bundles) ->
            val shapes = bundles.groupBy { (_, _, bundle) -> bundle.map { it.exactText } }
            require(shapes.size == 1) {
                val provenance = bundles.joinToString("; ") { (_, source, bundle) ->
                    "$source=${bundle.joinToString(" | ") { it.exactText.trimEnd() }}"
                }
                "Conflicting Iris $kind registry definition for $key: $provenance"
            }
            bundles.minWith(compareBy<Triple<String, String, List<IrisRegistryMetadataSlice>>> { it.second })
                .third
                .map { it.copy(sourceName = bundles.minOf { bundle -> bundle.second }) }
        }
    }

    private fun passLocalHostName(name: String): Boolean {
        return name in setOf("workGroups", "workGroupsRender") || name.endsWith("MipmapEnabled")
    }

    private fun sourceName(file: ShaderFile): String = file.path.toString().replace('\\', '/')

    private val IRIS_OPTION_DOMAIN_COMMENT =
        "(?://|/\\*)[^\\r\\n]*\\[[^]\\r\\n]+]".toRegex()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

}

internal sealed interface IrisFinalSourceProcessing {
    data class Processed(val source: String) : IrisFinalSourceProcessing
    data class Preserved(val reason: String) : IrisFinalSourceProcessing
}

internal object IrisFinalSourceProcessor {
    fun process(
        request: SpirvOptimizationRequest,
        source: String,
        modules: List<SpirvModuleResult>,
    ): IrisFinalSourceProcessing {
        val runtimeFacing = when (val result = restoreRuntimeCompilerBranches(request.source, source)) {
            is IrisFinalSourceProcessing.Processed -> result.source
            is IrisFinalSourceProcessing.Preserved -> return result
        }
        val runtimeContracts = removeImplicitRuntimeBuiltinDeclarations(runtimeFacing)
        val runtimeTypes = SpirvFinalEmitter.restoreMissingSourceTypeDeclarations(request, runtimeContracts)
        val moduleComplete = when (val result = restoreMissingLiveDeclarations(runtimeTypes, request.source, modules)) {
            is IrisFinalSourceProcessing.Processed -> result.source
            is IrisFinalSourceProcessing.Preserved -> return result
        }
        val ordered = SpirvFinalEmitter.hoistLateDeclarationDependencies(moduleComplete)
        val live = pruneEntities(ordered, modules, request.finalSourcePolicy)
        val unique = deduplicateEquivalentDeclarations(live, request.source)
        val uniqueMacros = deduplicateDominatedMacroConditionalGroups(unique)
        val projected = projectMetadata(uniqueMacros, request.finalSourcePolicy)
        val macros = pruneMacros(projected.source, projected.retainedSettings)
        val bridged = restoreDerivedMacroBridges(macros, moduleComplete, modules, request.sourceName)
        if (COMPILER_ONLY_IDENTIFIER.containsMatchIn(maskStructuralCode(bridged))) {
            return IrisFinalSourceProcessing.Preserved(
                "${request.sourceName}: compiler-only shadesmith ABI identifier remains after final-source liveness",
            )
        }
        return IrisFinalSourceProcessing.Processed(bridged.trimEnd() + "\n")
    }

    private data class MetadataProjection(
        val source: String,
        val retainedSettings: Set<String>,
    )

    private fun restoreDerivedMacroBridges(
        source: String,
        contractSource: String,
        modules: List<SpirvModuleResult>,
        sourceName: String,
    ): String {
        val contractExpressions = modules.flatMap { it.irisContracts.derivedMacros }
            .groupBy(IrisDerivedMacroContract::sourceName)
            .mapValues { (name, contracts) ->
                val expressions = contracts.map(IrisDerivedMacroContract::compilerExpression).distinct()
                require(expressions.size == 1) {
                    "$sourceName: derived macro $name differs across structural modules"
                }
                expressions.single()
            }
        val plannedExpressions = if (modules.isEmpty()) {
            compilerDerivedScalarExpressions(
                ShaderCompilerCopyPlanner.plan(contractSource, "$sourceName#final-derived-macros"),
            )
        } else {
            mergeDerivedScalarExpressions(sourceName, modules)
        }
        val expressions = plannedExpressions + contractExpressions
        if (expressions.isEmpty()) return source
        val directives = locateDirectives(source)
        val conditionalGroups = directives.filter { it.directive.conditionalId != null }
            .groupBy { requireNotNull(it.directive.conditionalId) }
            .values.mapNotNull { group ->
                val ordered = group.sortedBy { it.range.first }
                val opener = ordered.firstOrNull { it.directive.kind in CONDITIONAL_OPENING_KINDS }
                    ?: return@mapNotNull null
                val end = ordered.lastOrNull { it.directive.kind == PreprocessorDirectiveKind.ENDIF }
                    ?: return@mapNotNull null
                Triple(opener, end, opener.range.first..end.range.last)
            }
        val masked = maskStructuralCode(source)
        val bridges = expressions.toSortedMap().mapNotNull { (name, expression) ->
            val references = identifierRegex(name).findAll(masked).toList()
            val firstReference = references.firstOrNull() ?: return@mapNotNull null
            val definitions = directives.filter { located ->
                located.directive.kind == PreprocessorDirectiveKind.DEFINE &&
                    located.directive.macroName == name
            }
            if (definitions.isEmpty() || definitions.any { it.range.last >= firstReference.range.first }) {
                return@mapNotNull null
            }
            val inactiveExpandedGuard = definitions.all { definition ->
                conditionalGroups.any { (opener, _, range) ->
                    definition.range.first in range &&
                        opener.directive.kind == PreprocessorDirectiveKind.IFNDEF &&
                        directives.any { prior ->
                            prior.range.first < opener.range.first &&
                                prior.directive.kind == PreprocessorDirectiveKind.DEFINE &&
                                prior.directive.macroName == opener.directive.macroName
                        }
                }
            }
            if (!inactiveExpandedGuard) return@mapNotNull null
            Triple(name, expression, firstReference.range.first)
        }
        if (bridges.isEmpty()) return source
        val insertion = bridges.minOf { it.third }.let { offset ->
            source.lastIndexOfAny(charArrayOf('\r', '\n'), offset - 1) + 1
        }
        val text = bridges.joinToString("") { (name, expression, _) ->
            "#ifndef $name\n#define $name ($expression)\n#endif\n"
        }
        return source.substring(0, insertion) + text + source.substring(insertion)
    }

    private fun projectMetadata(source: String, policy: IrisFinalSourcePolicy?): MetadataProjection {
        if (policy == null) return MetadataProjection(source, emptySet())
        var result = source
        val retainedSettings = if (policy.globalSink) {
            policy.settings.keys
        } else {
            referencedSettingsOutsideOwnSlices(result, policy.localSettings)
        }
        policy.localSettings.toSortedMap().forEach { (name, slices) ->
            if (name !in retainedSettings) {
                result = removeExactSlices(result, slices)
            } else {
                result = deduplicateExactSlices(result, slices)
            }
        }
        policy.localPackGlobals.toSortedMap().forEach { (name, slices) ->
            val retain = policy.globalSink || referencedOutsideSlices(result, name, slices)
            result = if (retain) deduplicateExactSlices(result, slices) else removeExactSlices(result, slices)
        }
        if (policy.globalSink) {
            val missing = (policy.settings.values.flatten() + policy.packGlobals.values.flatten())
                .distinctBy(IrisRegistryMetadataSlice::exactText)
                .filter { slice -> slice.exactText !in result }
            if (missing.isNotEmpty()) {
                result = insertAfterPreamble(result, missing.joinToString("") { it.exactText })
            }
        }
        return MetadataProjection(result, retainedSettings)
    }

    private fun referencedSettingsOutsideOwnSlices(
        source: String,
        settings: Map<String, List<IrisRegistryMetadataSlice>>,
    ): Set<String> {
        if (settings.isEmpty()) return emptySet()
        val ownersByReference = buildMap<String, String> {
            settings.keys.forEach { name ->
                put(name, name)
                put("SM_$name", name)
            }
        }
        val stripped = removeExactSlices(source, settings.values.flatten())
        val retained = linkedSetOf<String>()
        IDENTIFIER.findAll(maskStructuralCode(stripped)).forEach { match ->
            ownersByReference[match.value]?.let(retained::add)
        }
        locateDirectives(stripped).forEach { located ->
            IDENTIFIER.findAll(located.directive.exactText).forEach { match ->
                ownersByReference[match.value]?.let(retained::add)
            }
        }
        settings.forEach { (owner, slices) ->
            slices.forEach { slice ->
                IDENTIFIER.findAll(slice.exactText).forEach { match ->
                    ownersByReference[match.value]?.takeIf { it != owner }?.let(retained::add)
                }
            }
        }
        return retained.toSortedSet()
    }

    private fun referencedOutsideSlices(
        source: String,
        name: String,
        slices: List<IrisRegistryMetadataSlice>,
    ): Boolean {
        val stripped = removeExactSlices(source, slices)
        val names = listOf(name, "SM_$name")
        return names.any { identifier ->
            identifierRegex(identifier).containsMatchIn(maskStructuralCode(stripped))
        } || locateDirectives(stripped).any { located ->
            names.any { identifier -> identifierRegex(identifier).containsMatchIn(located.directive.exactText) }
        }
    }

    private fun pruneEntities(
        source: String,
        modules: List<SpirvModuleResult>,
        policy: IrisFinalSourcePolicy?,
    ): String {
        val entities = sourceStructuralEntities(source)
        if (entities.isEmpty()) return source
        val coreEntities = modules.flatMap { sourceStructuralEntities(it.liveSource) }
        val outputFunctionReferences = entities.filter { it.kind == StructuralEntityKind.FUNCTION }
            .flatMapTo(hashSetOf(), StructuralEntity::references)
        val coreFunctionReferences = coreEntities.filter { it.kind == StructuralEntityKind.FUNCTION }
            .flatMapTo(hashSetOf()) { entity -> entity.references + identifiers(entity.canonical) }
        val liveCoreDeclarations = coreEntities.filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                !COMPILER_ONLY_IDENTIFIER.containsMatchIn(entity.canonical) &&
                (
                    LIVE_STAGE_INTERFACE_DECLARATION.containsMatchIn(entity.canonical) ||
                        entity.symbol?.matches(GENERATED_LIVE_SYMBOL) == true ||
                        entity.symbol in coreFunctionReferences ||
                        blockMemberNames(entity.canonical).any { it in coreFunctionReferences }
                    )
        }
        val coreIdentities = liveCoreDeclarations.mapTo(hashSetOf()) { it.identity }
        val coreSymbols = liveCoreDeclarations.mapNotNullTo(hashSetOf()) { it.symbol }
        val bySymbol = entities.flatMap { entity ->
            buildList {
                entity.symbol?.let { add(it to entity) }
                val exact = source.substring(entity.range)
                if (entity.kind == StructuralEntityKind.DECLARATION && BLOCK_DECLARATION.containsMatchIn(exact)) {
                    blockMemberNames(exact).forEach { add(it to entity) }
                }
            }
        }.groupBy({ it.first }, { it.second })
        val live = linkedSetOf<StructuralEntity>()
        entities.filterTo(live) { entity ->
            entity.symbol == "main" || entity.identity in coreIdentities || entity.symbol in coreSymbols ||
                entity.symbol in outputFunctionReferences
        }
        val contractSymbols = modules.flatMap { module ->
            module.irisContracts.contracts.filter { contract ->
                contract.kind in PASS_LOCAL_CONTRACT_KINDS
            }.flatMap { identifiers(it.exactText) }
        }.toMutableSet()
        if (policy?.globalSink == true) {
            contractSymbols += policy.packGlobals.keys
        }
        contractSymbols.forEach { symbol -> live += bySymbol[symbol].orEmpty() }
        val macroDefinitions = locateDirectives(source).filter { located ->
            located.directive.kind == PreprocessorDirectiveKind.DEFINE && located.directive.macroName != null
        }.groupBy(
            { requireNotNull(it.directive.macroName) },
            { it.directive.macroBody.orEmpty() },
        )
        val queue = ArrayDeque(live)
        val macroQueue = ArrayDeque<String>()
        val visitedMacros = hashSetOf<String>()
        fun enqueueMacro(name: String) {
            if (name in macroDefinitions && visitedMacros.add(name)) macroQueue.addLast(name)
        }
        fun enqueueSymbol(name: String) {
            enqueueMacro(name)
            bySymbol[name].orEmpty().forEach { dependency ->
                if (live.add(dependency)) queue.addLast(dependency)
            }
        }
        while (queue.isNotEmpty() || macroQueue.isNotEmpty()) {
            if (queue.isNotEmpty()) {
                queue.removeFirst().references.forEach(::enqueueSymbol)
            } else {
                macroDefinitions.getValue(macroQueue.removeFirst()).forEach { body ->
                    identifiers(body).forEach(::enqueueSymbol)
                }
            }
        }
        val removals = entities.filterNot(live::contains).filter { entity ->
            entity.symbol != null && !protectedDeclaration(source.substring(entity.range), entity)
        }.map { it.range }
        return removeRanges(source, removals)
    }

    private fun deduplicateEquivalentDeclarations(source: String, original: String): String {
        val directives = locateDirectives(source)
        val conditionalGroups = directives.filter { it.directive.conditionalId != null }
            .groupBy { requireNotNull(it.directive.conditionalId) }
            .values.mapNotNull { group ->
                val ordered = group.sortedBy { it.range.first }
                val opener = ordered.firstOrNull { it.directive.kind in CONDITIONAL_OPENING_KINDS }
                    ?: return@mapNotNull null
                val end = ordered.lastOrNull { it.directive.kind == PreprocessorDirectiveKind.ENDIF }
                    ?: return@mapNotNull null
                Triple(opener, end, ordered.filter { it.directive.kind in CONDITIONAL_BRANCH_KINDS })
            }
        fun conditionalPath(offset: Int): List<String> = conditionalGroups.filter { (opener, end, _) ->
            offset > opener.range.last && offset < end.range.first
        }.sortedBy { it.first.range.first }.map { (_, _, branches) ->
            branches.takeWhile { it.range.first < offset }
                .joinToString("|") { conditionalBranchSignature(it.directive) }
        }
        val entities = sourceStructuralEntities(source)
        val removals = entities
            .filter { it.kind == StructuralEntityKind.DECLARATION }
            .groupBy { entity -> Triple(entity.canonical, entity.symbol, conditionalPath(entity.range.first)) }
            .values.flatMapTo(mutableListOf()) { duplicates -> duplicates.drop(1).map(StructuralEntity::range) }
        val originalResources = sourceStructuralEntities(original).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                RESOURCE_DECLARATION.containsMatchIn(entity.canonical)
        }
        val aliases = directives.filter { located ->
            located.directive.kind == PreprocessorDirectiveKind.DEFINE &&
                !located.directive.macroFunctionLike && located.directive.macroName != null
        }.groupBy(
            { requireNotNull(it.directive.macroName) },
            { it.directive.macroBody.orEmpty().trim() },
        ).mapNotNull { (name, bodies) ->
            bodies.distinct().singleOrNull()?.takeIf(IDENTIFIER::matches)?.let { name to it }
        }.toMap()
        fun resolvedSymbol(initial: String): String {
            var result = initial
            val visited = linkedSetOf<String>()
            while (result in aliases && visited.add(result)) result = aliases.getValue(result)
            return result
        }
        fun resourceKey(entity: StructuralEntity): String? =
            BLOCK_RESOURCE_NAME.find(entity.canonical)?.groupValues?.get(1)
                ?: entity.symbol?.let(::resolvedSymbol)
        val originalResourcesByKey = originalResources.groupBy { resourceKey(it) }
        entities.filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION &&
                RESOURCE_DECLARATION.containsMatchIn(entity.canonical)
        }.groupBy(::resourceKey).forEach { (key, group) ->
            val originals = originalResourcesByKey[key].orEmpty()
            if (originals.isNotEmpty() && group.size > originals.size) {
                val remainingPreferred = originals.groupingBy(StructuralEntity::semantic).eachCount().toMutableMap()
                val retained = linkedSetOf<StructuralEntity>()
                group.forEach { entity ->
                    val remaining = remainingPreferred[entity.semantic] ?: 0
                    if (remaining > 0) {
                        retained += entity
                        remainingPreferred[entity.semantic] = remaining - 1
                    }
                }
                group.filterNot(retained::contains).take(originals.size - retained.size).forEach(retained::add)
                group.filterNot(retained::contains).forEach { removals += it.range }
            }
        }
        return removeRanges(source, removals)
    }

    private fun deduplicateDominatedMacroConditionalGroups(source: String): String {
        data class MacroGroup(
            val range: IntRange,
            val key: String,
            val macros: Set<String>,
        )

        val directives = locateDirectives(source)
        val groups = directives.filter { it.directive.conditionalId != null }
            .groupBy { requireNotNull(it.directive.conditionalId) }
            .values.mapNotNull { group ->
                val ordered = group.sortedBy { it.range.first }
                val opener = ordered.firstOrNull { it.directive.kind in CONDITIONAL_OPENING_KINDS }
                    ?: return@mapNotNull null
                if (opener.directive.conditionalDepth != 0) return@mapNotNull null
                val end = ordered.lastOrNull { it.directive.kind == PreprocessorDirectiveKind.ENDIF }
                    ?: return@mapNotNull null
                val range = opener.range.first..end.range.last
                val contained = directives.filter { it.range.first >= range.first && it.range.last <= range.last }
                if (contained.any { it.directive.kind !in MACRO_CONDITIONAL_KINDS }) return@mapNotNull null
                val exact = source.substring(range)
                if (maskStructuralCode(exact).isNotBlank()) return@mapNotNull null
                val macros = contained.filter {
                    it.directive.kind in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.UNDEF)
                }.mapNotNullTo(linkedSetOf()) { it.directive.macroName }
                if (macros.isEmpty()) return@mapNotNull null
                MacroGroup(range, exact.replace("\r\n", "\n").trim(), macros)
            }.sortedBy { it.range.first }
        if (groups.isEmpty()) return source

        val retained = linkedMapOf<String, MacroGroup>()
        val removals = mutableListOf<IntRange>()
        groups.forEach { group ->
            val prior = retained[group.key]
            val mutated = prior != null && directives.any { located ->
                located.range.first > prior.range.last && located.range.last < group.range.first &&
                    located.directive.kind in setOf(PreprocessorDirectiveKind.DEFINE, PreprocessorDirectiveKind.UNDEF) &&
                    located.directive.macroName in group.macros
            }
            if (prior != null && !mutated) {
                removals += group.range
            } else {
                retained[group.key] = group
            }
        }
        return removeRanges(source, removals)
    }

    private fun conditionalBranchSignature(directive: PreprocessorDirective): String = when (directive.kind) {
        PreprocessorDirectiveKind.IFDEF -> "ifdef:${directive.macroName}"
        PreprocessorDirectiveKind.IFNDEF -> "ifndef:${directive.macroName}"
        PreprocessorDirectiveKind.IF -> "if:${directive.expression.orEmpty().trim()}"
        PreprocessorDirectiveKind.ELIF -> "elif:${directive.expression.orEmpty().trim()}"
        PreprocessorDirectiveKind.ELSE -> "else"
        else -> directive.exactText.trim()
    }

    private fun protectedDeclaration(text: String, entity: StructuralEntity): Boolean {
        if (entity.kind == StructuralEntityKind.FUNCTION) return false
        return LOCAL_SIZE_LAYOUT.containsMatchIn(text) ||
            text.trimStart().startsWith("precision ") ||
            entity.symbol?.startsWith("gl_") == true ||
            entity.symbol?.let(::isHostDeclarationName) == true
    }

    private fun blockMemberNames(source: String): Set<String> {
        val body = source.substringAfter('{', "").substringBeforeLast('}', "")
        return BLOCK_MEMBER.findAll(maskStructuralCode(body)).mapTo(linkedSetOf()) { it.groupValues[1] }
    }

    private fun pruneMacros(source: String, retainedSettings: Set<String>): String {
        val located = locateDirectives(source)
        val definitions = located.filter { it.directive.kind == PreprocessorDirectiveKind.DEFINE }
        val byName = definitions.mapNotNull { locatedDirective ->
            locatedDirective.directive.macroName?.let { it to locatedDirective }
        }.groupBy({ it.first }, { it.second })
        if (byName.isEmpty()) return source
        val definitionRanges = definitions.map { it.range }
        val code = removeRanges(source, definitionRanges)
        val seeds = identifiers(code).filterTo(linkedSetOf()) { it in byName }
        located.asSequence().filter { it.directive.kind != PreprocessorDirectiveKind.DEFINE }
            .flatMap { locatedDirective -> IDENTIFIER.findAll(locatedDirective.directive.exactText).map(MatchResult::value) }
            .filterTo(seeds) { it in byName }
        retainedSettings.filterTo(seeds) { it in byName }
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            byName[name].orEmpty().forEach { definition ->
                identifiers(definition.directive.macroBody.orEmpty()).forEach { dependency ->
                    if (dependency in byName && seeds.add(dependency)) queue.addLast(dependency)
                }
            }
        }
        val removals = byName.filterKeys { name ->
            name !in seeds && !name.startsWith("SETTING_") && !name.startsWith("SM_SETTING_")
        }.values.flatten().mapTo(mutableListOf()) { it.range }
        byName.values.forEach { definitionsForName ->
            definitionsForName.filter { it.directive.conditionalId == null }
                .groupBy { it.directive.exactText }
                .values.forEach { duplicates ->
                    removals += duplicates.drop(1).map(LocatedDirective::range)
                }
        }
        return removeRanges(source, removals)
    }

    internal fun restoreRuntimeCompilerBranches(original: String, emitted: String): IrisFinalSourceProcessing {
        var result = COMPAT_INPUT_DECLARATION.replace(unwrapCompilerGuards(emitted), "")
        if (!COMPILER_ONLY_IDENTIFIER.containsMatchIn(maskStructuralCode(result))) {
            return IrisFinalSourceProcessing.Processed(result)
        }
        val runtimeOriginal = unwrapCompilerGuards(original)
        result = restoreCompatibilityVertexInputs(original, runtimeOriginal, result)
        val remaining = COMPILER_ONLY_IDENTIFIER.findAll(maskStructuralCode(result)).map(MatchResult::value)
            .distinct().sorted().toList()
        return if (remaining.isEmpty()) {
            IrisFinalSourceProcessing.Processed(result)
        } else {
            val locations = result.lineSequence().withIndex().filter { (_, line) ->
                remaining.any { name -> identifierRegex(name).containsMatchIn(line) }
            }.joinToString { (index, line) -> "${index + 1}:${line.trim()}" }
            IrisFinalSourceProcessing.Preserved(
                "compiler-only compatibility inputs remain: $remaining at $locations",
            )
        }
    }

    internal fun prepareCompatibilityCompilerSource(original: String, runtimeSource: String): String {
        if ("shadesmith_position" !in original) return runtimeSource
        var result = runtimeSource
        result = FTRANSFORM_CALL.replace(
            result,
            "((projectionMatrix * modelViewMatrix) * vec4(shadesmith_position, 1.0))",
        )
        result = COMPAT_RUNTIME_LIGHTMAP.replace(
            result,
            "((vec2(shadesmith_lmCoord) + vec2(8.0)) * 0.00390625)",
        )
        result = identifierRegex("gl_NormalMatrix").replace(result, "normalMatrix")
        result = GL_NORMAL_XYZ.replace(result, "shadesmith_normal")
        result = identifierRegex("gl_Color").replace(result, "shadesmith_color")
        result = GL_TEXTURE_MATRIX_ZERO.replace(result, "textureMatrix")
        result = GL_MULTI_TEX_COORD_ZERO_XY.replace(result, "shadesmith_texCoord")
        result = identifierRegex("gl_MultiTexCoord0").replace(
            result,
            "vec4(shadesmith_texCoord, 0.0, 1.0)",
        )
        if ("shadesmith_dhMaterialId" in original) {
            result = identifierRegex("dhMaterialId").replace(result, "shadesmith_dhMaterialId")
        }
        val code = maskStructuralCode(result)
        val inputDeclarations = COMPAT_COMPILER_INPUTS.filter { (name, _) ->
            identifierRegex(name).containsMatchIn(code) && !compilerInputDeclaration(name).containsMatchIn(result)
        }.values
        val uniformDeclarations = COMPAT_COMPILER_UNIFORMS.filter { (name, _) ->
            identifierRegex(name).containsMatchIn(code) && !compilerUniformDeclaration(name).containsMatchIn(result)
        }.values
        val declarations = (inputDeclarations + uniformDeclarations).joinToString("")
        return if (declarations.isEmpty()) result else insertAfterPreamble(result, declarations)
    }

    private fun removeImplicitRuntimeBuiltinDeclarations(source: String): String {
        val removals = sourceStructuralEntities(source).filter { entity ->
            entity.kind == StructuralEntityKind.DECLARATION && entity.symbol in IMPLICIT_RUNTIME_BUILTINS
        }.map { it.range }
        return removeRanges(source, removals)
    }

    private fun restoreMissingLiveDeclarations(
        source: String,
        original: String,
        modules: List<SpirvModuleResult>,
    ): IrisFinalSourceProcessing {
        data class Candidate(
            val source: String,
            val entity: StructuralEntity,
            val original: Boolean,
        )

        val declared = sourceStructuralEntities(source).mapNotNullTo(hashSetOf()) { it.symbol }
        val originalCandidates = sourceStructuralEntities(original).map { Candidate(original, it, true) }
        val moduleCandidates = modules.flatMap { module ->
            sourceStructuralEntities(module.liveSource).map { Candidate(module.liveSource, it, false) }
        }.filter { candidate ->
            candidate.entity.symbol?.matches(GENERATED_LIVE_SYMBOL) == true ||
                SOURCE_TYPE_DECLARATION.containsMatchIn(candidate.entity.canonical)
        }
        val candidates = (originalCandidates + moduleCandidates).filter { candidate ->
            candidate.entity.kind == StructuralEntityKind.DECLARATION &&
                candidate.entity.symbol != null &&
                candidate.entity.symbol !in IMPLICIT_RUNTIME_BUILTINS &&
                !COMPILER_ONLY_IDENTIFIER.containsMatchIn(candidate.entity.canonical)
        }.groupBy { requireNotNull(it.entity.symbol) }
        if (candidates.isEmpty()) return IrisFinalSourceProcessing.Processed(source)
        val references = (
            identifiers(source) + locateDirectives(source).flatMap { identifiers(it.directive.exactText) }
            ).toCollection(linkedSetOf())
        val pending = ArrayDeque(references.filter { it !in declared && it in candidates }.sorted())
        val selected = linkedMapOf<String, Candidate>()
        while (pending.isNotEmpty()) {
            val symbol = pending.removeFirst()
            if (symbol in declared || symbol in selected) continue
            val choices = candidates.getValue(symbol).let { candidates ->
                candidates.filter(Candidate::original).ifEmpty { candidates }
            }
            val shapes = choices.groupBy { it.entity.semantic }
            if (shapes.size != 1) {
                return IrisFinalSourceProcessing.Preserved(
                    "live declaration $symbol differs across structural modules",
                )
            }
            val candidate = shapes.values.single().minBy { it.entity.range.first }
            selected[symbol] = candidate
            candidate.entity.references.filterTo(pending) { dependency ->
                dependency !in declared && dependency !in selected && dependency in candidates
            }
        }
        if (selected.isEmpty()) return IrisFinalSourceProcessing.Processed(source)
        val ordered = mutableListOf<Candidate>()
        val visited = hashSetOf<String>()
        fun visit(symbol: String) {
            if (!visited.add(symbol)) return
            selected.getValue(symbol).entity.references.filter(selected::containsKey).sorted().forEach(::visit)
            ordered += selected.getValue(symbol)
        }
        selected.keys.sorted().forEach(::visit)
        val insertion = ordered.joinToString("\n") { candidate ->
            candidate.source.substring(candidate.entity.range).trim()
        } + "\n"
        val firstFunction = sourceStructuralEntities(source)
            .firstOrNull { it.kind == StructuralEntityKind.FUNCTION }
            ?.range?.first
            ?: source.length
        val separator = if (firstFunction > 0 && source[firstFunction - 1] !in "\r\n") "\n" else ""
        return IrisFinalSourceProcessing.Processed(
            source.substring(0, firstFunction) + separator + insertion + source.substring(firstFunction),
        )
    }

    private fun restoreCompatibilityVertexInputs(
        original: String,
        runtimeOriginal: String,
        emitted: String,
    ): String {
        var result = emitted
        if ("shadesmith_position" in original && "ftransform()" in runtimeOriginal) {
            result = COMPAT_POSITION_EXPRESSION.replace(result) { match ->
                match.value.takeWhile(Char::isWhitespace) + "ftransform()"
            }
        }
        if ("normalMatrix" in original && "gl_NormalMatrix" in runtimeOriginal) {
            result = identifierRegex("normalMatrix").replace(result, "gl_NormalMatrix")
        }
        if ("shadesmith_normal" in original && "gl_Normal.xyz" in runtimeOriginal) {
            result = identifierRegex("shadesmith_normal").replace(result, "gl_Normal.xyz")
        }
        if ("return shadesmith_color;" in original && "return gl_Color;" in runtimeOriginal) {
            result = identifierRegex("shadesmith_color").replace(result, "gl_Color")
        }
        if ("shadesmith_texCoord" in original && "gl_MultiTexCoord0" in runtimeOriginal) {
            result = identifierRegex("textureMatrix").replace(result, "gl_TextureMatrix[0]")
            result = identifierRegex("shadesmith_texCoord").replace(result, "gl_MultiTexCoord0.xy")
        }
        if ("shadesmith_lmCoord" in original && "gl_MultiTexCoord1" in runtimeOriginal) {
            COMPAT_LIGHTMAP_EXPRESSIONS.forEach { pattern ->
                result = pattern.replace(result, "(gl_TextureMatrix[1] * gl_MultiTexCoord1).xy")
            }
        }
        if ("shadesmith_dhMaterialId" in original && "dhMaterialId" in runtimeOriginal) {
            result = identifierRegex("shadesmith_dhMaterialId").replace(result, "dhMaterialId")
        }
        return result
    }

    private fun unwrapCompilerGuards(source: String): String {
        var result = source
        while (true) {
            val directives = locateDirectives(result)
            val opener = directives.firstOrNull { located ->
                located.directive.kind == PreprocessorDirectiveKind.IFDEF &&
                    located.directive.macroName == "__clang__"
            } ?: return result
            val id = opener.directive.conditionalId ?: return result
            val group = directives.filter { it.directive.conditionalId == id }.sortedBy { it.range.first }
            val alternate = group.firstOrNull { it.directive.kind == PreprocessorDirectiveKind.ELSE } ?: return result
            val end = group.lastOrNull { it.directive.kind == PreprocessorDirectiveKind.ENDIF } ?: return result
            val replacement = result.substring(alternate.range.last + 1, end.range.first)
            result = result.replaceRange(opener.range.first, end.range.last + 1, replacement)
        }
    }

    private data class LocatedDirective(
        val directive: PreprocessorDirective,
        val range: IntRange,
    )

    private fun locateDirectives(source: String): List<LocatedDirective> {
        val directives = PreprocessorProtection.protect(source, "<final-source-liveness>").directives
        var cursor = 0
        return directives.map { directive ->
            val offset = source.indexOf(directive.exactText, cursor)
            require(offset >= 0) { "preprocessor directive location drifted during final-source liveness" }
            val range = offset until offset + directive.exactText.length
            cursor = range.last + 1
            LocatedDirective(directive, range)
        }
    }

    private fun removeExactSlices(source: String, slices: List<IrisRegistryMetadataSlice>): String {
        val texts = slices.map { it.exactText }.toSet()
        return removeRanges(source, texts.flatMap { occurrences(source, it) })
    }

    private fun deduplicateExactSlices(source: String, slices: List<IrisRegistryMetadataSlice>): String {
        val removals = slices.map { it.exactText }.toSet().flatMap { text -> occurrences(source, text).drop(1) }
        return removeRanges(source, removals)
    }

    private fun insertAfterPreamble(source: String, insertion: String): String {
        val lines = physicalLineRanges(source)
        var offset = lines.firstOrNull { source.substring(it).trimStart().startsWith("#version") }
            ?.last?.plus(1) ?: return source
        for (range in lines.dropWhile { it.last < offset }) {
            val line = source.substring(range).trim()
            if (line.isEmpty() || line.startsWith("#extension") || line.startsWith("#pragma")) {
                offset = range.last + 1
            } else {
                break
            }
        }
        return source.substring(0, offset) + insertion + source.substring(offset)
    }

    private fun occurrences(source: String, text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var cursor = 0
        while (cursor <= source.length - text.length) {
            val offset = source.indexOf(text, cursor)
            if (offset < 0) break
            result += offset until offset + text.length
            cursor = offset + text.length
        }
        return result
    }

    private fun removeRanges(source: String, ranges: List<IntRange>): String {
        val ordered = ranges.distinct().sortedBy { it.first }
        if (ordered.isEmpty()) return source
        return buildString(source.length - ordered.sumOf(IntRange::count)) {
            var cursor = 0
            ordered.forEach { range ->
                require(range.first >= cursor) { "overlapping final-source liveness ranges" }
                append(source, cursor, range.first)
                cursor = range.last + 1
            }
            append(source, cursor, source.length)
        }
    }

    private fun identifiers(source: String): List<String> = IDENTIFIER.findAll(maskStructuralCode(source))
        .map(MatchResult::value)
        .toList()

    private fun physicalLineRanges(source: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        while (start < source.length) {
            var end = start
            while (end < source.length && source[end] !in "\r\n") end++
            if (source.getOrNull(end) == '\r') end++
            if (source.getOrNull(end) == '\n') end++
            result += start until end
            start = end
        }
        return result
    }

    private fun identifierRegex(name: String): Regex =
        "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()

    private fun compilerInputDeclaration(name: String): Regex =
        "(?m)^[\\t ]*(?:flat[\\t ]+)?in\\b[^;\\r\\n]*\\b${Regex.escape(name)}\\b[^;\\r\\n]*;".toRegex()

    private fun compilerUniformDeclaration(name: String): Regex =
        "(?m)^[\\t ]*uniform\\b[^;\\r\\n]*\\b${Regex.escape(name)}\\b[^;\\r\\n]*;".toRegex()

    private val PASS_LOCAL_CONTRACT_KINDS = setOf(
        IrisSourceContractKind.COMMENT_DIRECTIVE,
        IrisSourceContractKind.EXTENSION,
        IrisSourceContractKind.PRAGMA,
        IrisSourceContractKind.LOCAL_SIZE,
    )
    private val CONDITIONAL_OPENING_KINDS = setOf(
        PreprocessorDirectiveKind.IF,
        PreprocessorDirectiveKind.IFDEF,
        PreprocessorDirectiveKind.IFNDEF,
    )
    private val CONDITIONAL_BRANCH_KINDS = CONDITIONAL_OPENING_KINDS + setOf(
        PreprocessorDirectiveKind.ELIF,
        PreprocessorDirectiveKind.ELSE,
    )
    private val MACRO_CONDITIONAL_KINDS = CONDITIONAL_BRANCH_KINDS + setOf(
        PreprocessorDirectiveKind.ENDIF,
        PreprocessorDirectiveKind.DEFINE,
        PreprocessorDirectiveKind.UNDEF,
    )
    private val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    private val LOCAL_SIZE_LAYOUT = "\\blocal_size_[xyz](?:_id)?\\b".toRegex()
    private val LIVE_STAGE_INTERFACE_DECLARATION = "\\b(?:in|out)\\b".toRegex()
    private val BLOCK_DECLARATION = "\\b(?:uniform|buffer)\\b[^{;]*\\{".toRegex()
    private val BLOCK_RESOURCE_NAME = "\\b(?:uniform|buffer)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*\\{".toRegex()
    private val SOURCE_TYPE_DECLARATION = "^struct[\\t ]+[A-Za-z_][A-Za-z0-9_]*\\b".toRegex()
    private val RESOURCE_DECLARATION = "\\b(?:uniform|buffer)\\b".toRegex()
    private val BLOCK_MEMBER = "\\b([A-Za-z_][A-Za-z0-9_]*)[\\t ]*(?:\\[[^]]*])?[\\t ]*;".toRegex()
    private val COMPILER_ONLY_IDENTIFIER = "\\bshadesmith_[A-Za-z0-9_]+\\b".toRegex()
    private val COMPAT_INPUT_DECLARATION = (
        "(?m)^[\\t ]*(?:layout[\\t ]*\\([^\\r\\n;]*\\)[\\t ]*)?" +
            "(?:[A-Za-z_][A-Za-z0-9_]*[\\t ]+)*in[\\t ]+[^;\\r\\n]*" +
            "\\bshadesmith_[A-Za-z0-9_]+\\b[^;\\r\\n]*;[\\t ]*(?:\\r?\\n|$)"
        ).toRegex()
    private val FTRANSFORM_CALL = "\\bftransform\\s*\\(\\s*\\)".toRegex()
    private val GL_NORMAL_XYZ = "\\bgl_Normal\\s*\\.\\s*xyz\\b".toRegex()
    private val GL_TEXTURE_MATRIX_ZERO = "\\bgl_TextureMatrix\\s*\\[\\s*0\\s*]".toRegex()
    private val GL_MULTI_TEX_COORD_ZERO_XY = "\\bgl_MultiTexCoord0\\s*\\.\\s*xy\\b".toRegex()
    private val COMPAT_RUNTIME_LIGHTMAP = (
        "\\(\\s*gl_TextureMatrix\\s*\\[\\s*1\\s*]\\s*\\*\\s*gl_MultiTexCoord1\\s*\\)\\s*\\.\\s*xy"
        ).toRegex()
    private val IMPLICIT_RUNTIME_BUILTINS = setOf("gl_NormalMatrix", "gl_TextureMatrix")
    private val GENERATED_LIVE_SYMBOL = "_[0-9]+".toRegex()
    private val COMPAT_COMPILER_INPUTS = linkedMapOf(
        "shadesmith_position" to "in vec3 shadesmith_position;\n",
        "shadesmith_normal" to "in vec3 shadesmith_normal;\n",
        "shadesmith_color" to "in vec4 shadesmith_color;\n",
        "shadesmith_texCoord" to "in vec2 shadesmith_texCoord;\n",
        "shadesmith_lmCoord" to "in ivec2 shadesmith_lmCoord;\n",
        "shadesmith_dhMaterialId" to "in int shadesmith_dhMaterialId;\n",
    )
    private val COMPAT_COMPILER_UNIFORMS = linkedMapOf(
        "projectionMatrix" to "uniform mat4 projectionMatrix;\n",
        "modelViewMatrix" to "uniform mat4 modelViewMatrix;\n",
        "normalMatrix" to "uniform mat3 normalMatrix;\n",
        "textureMatrix" to "uniform mat4 textureMatrix;\n",
    )
    private val COMPAT_POSITION_EXPRESSION = (
        "(?:\\(\\s*projectionMatrix\\s*\\*\\s*modelViewMatrix\\s*\\)|" +
            "projectionMatrix\\s*\\*\\s*modelViewMatrix)\\s*\\*\\s*" +
            "vec4\\s*\\(\\s*shadesmith_position\\s*,\\s*1(?:\\.0)?\\s*\\)"
        ).toRegex()
    private val COMPAT_LIGHTMAP_EXPRESSIONS = listOf(
        "\\(\\s*vec2\\s*\\(\\s*shadesmith_lmCoord\\s*\\)\\s*\\+\\s*vec2\\s*\\(\\s*8(?:\\.0)?\\s*\\)\\s*\\)\\s*\\*\\s*0\\.00390625".toRegex(),
        "\\(\\s*vec2\\s*\\(\\s*shadesmith_lmCoord\\s*\\)\\s*\\+\\s*8(?:\\.0)?\\s*\\)\\s*\\*\\s*\\(\\s*1(?:\\.0)?\\s*/\\s*256(?:\\.0)?\\s*\\)".toRegex(),
    )
}
