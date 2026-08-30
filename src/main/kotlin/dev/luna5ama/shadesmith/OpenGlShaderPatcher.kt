package dev.luna5ama.shadesmith

internal enum class ShaderAbiKind {
    INPUT,
    OUTPUT,
    UNIFORM,
    UNIFORM_BLOCK,
    STORAGE_BLOCK,
}

internal data class ShaderAbiKey(val kind: ShaderAbiKind, val name: String)

internal data class ShaderAbiEntry(
    val key: ShaderAbiKey,
    val type: String,
    val arraySuffix: String,
    val qualifiers: Set<String>,
    val qualifiersBeforeStorage: List<String>,
    val qualifiersAfterStorage: List<String>,
    val layout: Set<String>,
    val blockSignature: ShaderBlockSignature?,
)

internal data class ShaderBlockSignature(val body: String, val instance: String)

internal data class ShaderAbiContract(
    val entries: Map<ShaderAbiKey, ShaderAbiEntry>,
    val workGroupSize: Triple<Int, Int, Int>?,
    val stageLayouts: Set<String>,
)

internal data class GeneratedShaderLayout(
    val key: ShaderAbiKey,
    val qualifier: String,
    val value: Int,
)

internal data class OpenGlShaderPatch(
    val sourceName: String,
    val stage: ShaderStage,
    val compilerSource: String,
    val generatedLayouts: List<GeneratedShaderLayout>,
    val restorableTypeDeclarations: Map<String, String>,
    val restorableDeclarations: Map<ShaderAbiKey, String>,
    val irisContracts: IrisShaderContractPlan,
    val originalContract: ShaderAbiContract,
    val integerMacros: Map<String, Long>,
)

internal class OpenGlShaderPatchException(
    val sourceName: String,
    val stage: ShaderStage,
    val sourceLine: Int?,
    val reason: String,
) : IllegalArgumentException(
    buildString {
        append(sourceName)
        sourceLine?.let {
            append(':')
            append(it)
        }
        append(" [")
        append(stage.glslangName)
        append("]: ")
        append(reason)
    },
)

internal class OpenGlShaderPatcher {
    fun patch(
        protectedSource: ProtectedPreprocessorSource,
        stage: ShaderStage,
        preferredLayouts: List<GeneratedShaderLayout> = emptyList(),
        sourceContracts: IrisShaderContractPlan? = null,
        expectedContract: ShaderAbiContract? = null,
    ): OpenGlShaderPatch {
        val contracts = try {
            sourceContracts ?: IrisShaderContractExtractor.extract(
                protectedSource.originalSource,
                protectedSource.sourceName,
            )
        } catch (exception: IllegalArgumentException) {
            val message = exception.message.orEmpty()
            val sourceLine = "^${Regex.escape(protectedSource.sourceName)}:(\\d+):".toRegex()
                .find(message)
                ?.groupValues
                ?.get(1)
                ?.toInt()
            fail(
                protectedSource.sourceName,
                stage,
                sourceLine,
                message.substringAfter(": ", message),
            )
        }
        contracts.structuralReason?.let { reason ->
            fail(protectedSource.sourceName, stage, null, reason)
        }
        val contractCompilerSource = if (sourceContracts == null) {
            contracts.compilerSource
        } else {
            contracts.prepareCompilerSource(protectedSource.compilerSource())
        }
        var source = normalizeLineEndings(contractCompilerSource)
        if (expectedContract != null) {
            source = materializeBlockArrayExtents(
                source,
                expectedContract,
                protectedSource.sourceName,
                stage,
            )
        }
        val version = VERSION_REGEX.find(source)
            ?: fail(protectedSource.sourceName, stage, null, "compiler source has no #version directive")
        var compilerSource = source.replaceRange(version.range, "#version 460 core")

        val originalDeclarations = parseDeclarations(source)
        val declarations = parseDeclarations(compilerSource)
        rejectUnsupportedDeclarations(compilerSource, declarations, protectedSource.sourceName, stage)
        val generatedLayouts = allocateLayouts(
            compilerSource,
            declarations,
            preferredLayouts,
            protectedSource.sourceName,
            stage,
        )
        compilerSource = applyGeneratedLayouts(compilerSource, declarations, generatedLayouts)

        return OpenGlShaderPatch(
            sourceName = protectedSource.sourceName,
            stage = stage,
            compilerSource = normalizeOutput(compilerSource),
            generatedLayouts = generatedLayouts,
            restorableTypeDeclarations = collectRestorableTypeDeclarations(
                source,
                protectedSource.sourceName,
                stage,
            ),
            restorableDeclarations = collectRestorableDeclarations(
                source,
                originalDeclarations,
                protectedSource.sourceName,
                stage,
            ),
            irisContracts = contracts,
            originalContract = analyzeContract(source, protectedSource.sourceName, stage),
            integerMacros = resolveIntegerObjectMacros(source),
        )
    }

    fun restore(decompiledSource: String, patch: OpenGlShaderPatch): String {
        val core = restoreCore(decompiledSource, patch)
        val restored = when (val restoration = restoreContracts(core, patch)) {
            is IrisContractRestoration.Restored -> restoration.source
            is IrisContractRestoration.StructuralPreservation -> fail(
                patch.sourceName,
                patch.stage,
                null,
                restoration.reason,
            )
        }
        validateContract(restored, patch)
        return restored.trimEnd() + "\n"
    }

    fun restoreCore(decompiledSource: String, patch: OpenGlShaderPatch): String {
        var restored = normalizeOutput(patch.irisContracts.stripCompilerArtifacts(decompiledSource))
        patch.generatedLayouts.forEach { generated ->
            val declarations = parseDeclarations(restored)
            val declaration = declarations.singleOrNull { it.key == generated.key }
            if (declaration == null) {
                if (generated.key in patch.restorableDeclarations) return@forEach
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "generated ${generated.qualifier} target ${generated.key.kind}:${generated.key.name} is missing after decompilation",
                )
            }
            restored = removeGeneratedLayout(restored, declaration, generated, patch)
        }
        restored = restoreDeclarations(restored, patch)
        restored = restoreMissingQualifiers(restored, patch)
        return restored.trimEnd() + "\n"
    }

    fun restoreContracts(source: String, patch: OpenGlShaderPatch): IrisContractRestoration {
        return patch.irisContracts.restore(source)
    }

    fun validateContract(
        restoredSource: String,
        patch: OpenGlShaderPatch,
        validateSourceContracts: Boolean = true,
    ) {
        val compilerView = patch.irisContracts.prepareCompilerSource(restoredSource)
        val actual = analyzeContract(compilerView, patch.sourceName, patch.stage, patch.integerMacros)
        val expected = patch.originalContract
        val missing = expected.entries.keys - actual.entries.keys
        val unexpected = actual.entries.keys - expected.entries.keys
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            fail(
                patch.sourceName,
                patch.stage,
                null,
                buildString {
                    append("stage ABI entries changed")
                    if (missing.isNotEmpty()) append("; missing ${missing.sortedForDiagnostic()}")
                    if (unexpected.isNotEmpty()) append("; unexpected ${unexpected.sortedForDiagnostic()}")
                },
            )
        }

        expected.entries.forEach { (key, expectedEntry) ->
            val actualEntry = actual.entries.getValue(key)
            if (
                actualEntry.type != expectedEntry.type ||
                !arraySuffixCompatible(expectedEntry, actualEntry, patch.stage)
            ) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "${key.kind}:${key.name} type changed from ${expectedEntry.type}${expectedEntry.arraySuffix} " +
                        "to ${actualEntry.type}${actualEntry.arraySuffix}",
                )
            }
            if (!actualEntry.qualifiers.containsAll(expectedEntry.qualifiers)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "${key.kind}:${key.name} lost qualifiers ${expectedEntry.qualifiers - actualEntry.qualifiers}",
                )
            }
            if (!actualEntry.layout.containsAll(expectedEntry.layout)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "${key.kind}:${key.name} lost layout contract ${expectedEntry.layout - actualEntry.layout}",
                )
            }
            val restoredBlockSignature = patch.restorableDeclarations[key]?.let { original ->
                val declaration = parseDeclarations(original).singleOrNull { it.key == key }
                declaration?.let {
                    blockSignature(original, it, patch.sourceName, patch.stage, emptyMap())
                }
            }
            if (
                expectedEntry.blockSignature != actualEntry.blockSignature &&
                restoredBlockSignature != actualEntry.blockSignature
            ) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "${key.kind}:${key.name} block declaration changed from ${expectedEntry.blockSignature} " +
                        "to ${actualEntry.blockSignature}",
                )
            }
        }

        if (actual.workGroupSize != expected.workGroupSize) {
            fail(
                patch.sourceName,
                patch.stage,
                null,
                "workgroup size changed from ${expected.workGroupSize} to ${actual.workGroupSize}",
            )
        }
        if (!actual.stageLayouts.containsAll(expected.stageLayouts)) {
            fail(
                patch.sourceName,
                patch.stage,
                null,
                "stage layout changed: missing ${(expected.stageLayouts - actual.stageLayouts).sorted()}",
            )
        }
        if (!MAIN_REGEX.containsMatchIn(restoredSource)) {
            fail(patch.sourceName, patch.stage, null, "entry point main disappeared after round-trip")
        }
        if (validateSourceContracts) {
            patch.irisContracts.contracts.forEach { contract ->
                if (!restoredSource.contains(contract.exactText)) {
                    fail(
                        patch.sourceName,
                        patch.stage,
                        contract.sourceLine,
                        "Iris ${contract.kind} source contract was not restored exactly",
                    )
                }
            }
        }
    }

    private fun allocateLayouts(
        source: String,
        declarations: List<ParsedDeclaration>,
        preferredLayouts: List<GeneratedShaderLayout>,
        sourceName: String,
        stage: ShaderStage,
    ): List<GeneratedShaderLayout> {
        val preferred = preferredLayouts.associateBy { it.key }
        val occupied = LayoutNamespace.entries.associateWith { mutableSetOf<Int>() }.toMutableMap()
        val occupiedBy = LayoutNamespace.entries.associateWith { mutableMapOf<Int, ShaderAbiKey>() }

        fun record(namespace: LayoutNamespace, value: Int, slots: Int, key: ShaderAbiKey) {
            repeat(slots) { occupiedBy.getValue(namespace).putIfAbsent(value + it, key) }
        }

        declarations.forEach { declaration ->
            val qualifier = requiredLayoutQualifier(declaration) ?: return@forEach
            val existing = declaration.layoutValue(qualifier) ?: return@forEach
            val value = existing.toIntOrNull()
                ?: fail(
                    sourceName,
                    stage,
                    sourceLine(source, declaration.range.first),
                    "$qualifier for ${declaration.key.kind}:${declaration.key.name} must be an integer literal",
                )
            val namespace = layoutNamespace(declaration)
            val slots = declaration.locationSlots()
            occupy(occupied.getValue(namespace), value, slots)
            record(namespace, value, slots, declaration.key)
        }

        val generatedCandidates = declarations.groupBy(ParsedDeclaration::key).mapNotNull { (key, variants) ->
            val missing = variants.filter { declaration ->
                val qualifier = requiredLayoutQualifier(declaration) ?: return@filter false
                declaration.layoutValue(qualifier) == null
            }
            if (missing.isEmpty()) return@mapNotNull null
            val qualifiers = missing.mapNotNull(::requiredLayoutQualifier).distinct()
            val namespaces = missing.map(::layoutNamespace).distinct()
            if (qualifiers.size != 1 || namespaces.size != 1) {
                fail(
                    sourceName,
                    stage,
                    sourceLine(source, missing.first().range.first),
                    "conditional ABI variants require incompatible generated layouts for ${key.kind}:${key.name}",
                )
            }
            LayoutCandidate(key, qualifiers.single(), namespaces.single(), missing.maxOf { it.locationSlots() })
        }

        generatedCandidates.forEach { candidate ->
            val preferredLayout = preferred[candidate.key] ?: return@forEach
            val qualifier = candidate.qualifier
            if (preferredLayout.qualifier != qualifier) {
                fail(
                    sourceName,
                    stage,
                    null,
                    "preferred layout kind changed for ${candidate.key.kind}:${candidate.key.name}",
                )
            }
            val namespace = candidate.namespace
            val owners = (preferredLayout.value until preferredLayout.value + candidate.slots)
                .mapNotNull(occupiedBy.getValue(namespace)::get)
                .distinct()
            if (owners.any { it != candidate.key } && !namespace.allowsGeneratedAlias) {
                fail(
                    sourceName,
                    stage,
                    null,
                    "preferred $qualifier ${preferredLayout.value} collides for " +
                        "${candidate.key.kind}:${candidate.key.name}; occupied by $owners",
                )
            }
            occupy(occupied.getValue(namespace), preferredLayout.value, candidate.slots)
            record(namespace, preferredLayout.value, candidate.slots, candidate.key)
        }

        return buildList {
            generatedCandidates.forEach { candidate ->
                val preferredLayout = preferred[candidate.key]
                val value = if (preferredLayout != null) {
                    preferredLayout.value
                } else {
                    val firstFree = firstFree(occupied.getValue(candidate.namespace), candidate.slots)
                    if (candidate.namespace == LayoutNamespace.SAMPLER_BINDING && firstFree >= MAX_COMPILER_SAMPLER_BINDINGS) {
                        0
                    } else {
                        firstFree
                    }
                }
                if (preferredLayout == null) {
                    occupy(occupied.getValue(candidate.namespace), value, candidate.slots)
                    record(candidate.namespace, value, candidate.slots, candidate.key)
                }
                add(GeneratedShaderLayout(candidate.key, candidate.qualifier, value))
            }
        }
    }

    private fun applyGeneratedLayouts(
        source: String,
        declarations: List<ParsedDeclaration>,
        generatedLayouts: List<GeneratedShaderLayout>,
    ): String {
        val declarationsByKey = declarations.groupBy { it.key }
        val insertions = generatedLayouts.flatMap { generated ->
            declarationsByKey.getValue(generated.key).mapNotNull { declaration ->
                if (declaration.layoutValue(generated.qualifier) != null) return@mapNotNull null
                if (declaration.layoutRange == null) {
                    SourceInsertion(
                        declaration.range.first + declaration.indent.length,
                        "layout(${generated.qualifier} = ${generated.value}) ",
                    )
                } else {
                    val close = source.indexOf(')', declaration.layoutRange.first)
                    val separator = if (declaration.layoutItems.isEmpty()) "" else ", "
                    SourceInsertion(close, "$separator${generated.qualifier} = ${generated.value}")
                }
            }
        }

        var result = source
        insertions.sortedByDescending { it.offset }.forEach {
            result = result.substring(0, it.offset) + it.text + result.substring(it.offset)
        }
        return result
    }

    private fun removeGeneratedLayout(
        source: String,
        declaration: ParsedDeclaration,
        generated: GeneratedShaderLayout,
        patch: OpenGlShaderPatch,
    ): String {
        val layoutRange = declaration.layoutRange
            ?: fail(
                patch.sourceName,
                patch.stage,
                sourceLine(source, declaration.range.first),
                "generated ${generated.qualifier} disappeared from ${generated.key.kind}:${generated.key.name}",
            )
        val matching = declaration.layoutItems.singleOrNull { it.key == generated.qualifier }
            ?: fail(
                patch.sourceName,
                patch.stage,
                sourceLine(source, declaration.range.first),
                "generated ${generated.qualifier} disappeared from ${generated.key.kind}:${generated.key.name}",
            )
        if (matching.value?.toIntOrNull() != generated.value) {
            fail(
                patch.sourceName,
                patch.stage,
                sourceLine(source, declaration.range.first),
                "generated ${generated.qualifier} for ${generated.key.kind}:${generated.key.name} changed from " +
                    "${generated.value} to ${matching.value}",
            )
        }

        val remaining = declaration.layoutItems.filterNot { it.key == generated.qualifier }
        val replacement = if (remaining.isEmpty()) {
            ""
        } else {
            "layout(${remaining.joinToString(", ") { it.original }}) "
        }
        return source.replaceRange(layoutRange, replacement)
    }

    private fun restoreDeclarations(source: String, patch: OpenGlShaderPatch): String {
        val actual = parseDeclarations(source).associateBy { it.key }
        val lexicalMap = buildLexicalMap(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        patch.restorableDeclarations.forEach { (key, original) ->
            val declaration = actual[key] ?: return@forEach
            val declarationRange = declarationSourceRange(source, declaration, patch.sourceName, patch.stage)
            if (key.kind !in BLOCK_KINDS) {
                replacements += declarationRange to original
                return@forEach
            }

            val expected = patch.originalContract.entries.getValue(key).blockSignature
            val current = blockSignature(source, declaration, patch.sourceName, patch.stage)
            if (!blockSignatureRestorable(expected, current)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    sourceLine(source, declaration.range.first),
                    "${key.kind}:${key.name} block declaration changed from $expected to $current",
                )
            }
            replacements += declarationRange to original

            if (expected!!.instance.isEmpty() && current!!.instance.isNotEmpty()) {
                val instanceAccess = (
                    "(?<![A-Za-z0-9_])${Regex.escape(current.instance)}[\\t \\r\\n]*\\.[\\t \\r\\n]*"
                    ).toRegex()
                instanceAccess.findAll(source)
                    .filter { lexicalMap.isCode(it.range.first) && it.range.first !in declarationRange }
                    .forEach { replacements += it.range to "" }
            }
        }
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, original) ->
            result = result.replaceRange(range, original)
        }

        val existingTypes = parseTypeDeclarationNames(result)
        val typeDeclarations = patch.restorableTypeDeclarations.filterKeys { it !in existingTypes }.values
        val declarations = patch.restorableDeclarations.filterKeys { it !in actual }.values
        if (typeDeclarations.isEmpty() && declarations.isEmpty()) return result

        val version = VERSION_REGEX.find(result)
            ?: fail(patch.sourceName, patch.stage, null, "spirv-cross output has no #version directive")
        val insertionOffset = version.range.last + 1
        val insertion = buildString {
            append('\n')
            typeDeclarations.forEach {
                append(it)
                if (!it.endsWith('\n')) append('\n')
            }
            declarations.forEach {
                append(it)
                if (!it.endsWith('\n')) append('\n')
            }
        }
        return result.substring(0, insertionOffset) + insertion + result.substring(insertionOffset)
    }

    private fun restoreMissingQualifiers(source: String, patch: OpenGlShaderPatch): String {
        var result = source
        patch.originalContract.entries.forEach { (key, expected) ->
            val declaration = parseDeclarations(result).singleOrNull { it.key == key } ?: return@forEach
            val missing = expected.qualifiers - declaration.qualifiers
            if (missing.isEmpty()) return@forEach

            val before = expected.qualifiersBeforeStorage.filter { it in missing }
            val after = expected.qualifiersAfterStorage.filter { it in missing }
            if ((before + after).toSet() != missing) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    sourceLine(result, declaration.range.first),
                    "cannot restore qualifiers $missing for ${key.kind}:${key.name}",
                )
            }
            val insertions = buildList {
                if (before.isNotEmpty()) {
                    add(SourceInsertion(declaration.storageRange.first, "${before.joinToString(" ")} "))
                }
                if (after.isNotEmpty()) {
                    add(SourceInsertion(declaration.storageRange.last + 1, " ${after.joinToString(" ")}"))
                }
            }
            insertions.sortedByDescending { it.offset }.forEach { insertion ->
                result = result.substring(0, insertion.offset) + insertion.text + result.substring(insertion.offset)
            }
        }
        return result
    }

    private fun collectRestorableDeclarations(
        source: String,
        declarations: List<ParsedDeclaration>,
        sourceName: String,
        stage: ShaderStage,
    ): Map<ShaderAbiKey, String> {
        return declarations.associate { declaration ->
            declaration.key to source.substring(declarationSourceRange(source, declaration, sourceName, stage))
        }
    }

    private fun collectRestorableTypeDeclarations(
        source: String,
        sourceName: String,
        stage: ShaderStage,
    ): Map<String, String> {
        val lexicalMap = buildLexicalMap(source)
        val declarations = STRUCT_DECLARATION_REGEX.findAll(source)
            .filter { lexicalMap.isTopLevelCode(it.range.first) }
            .map { match ->
                val range = bracedDeclarationRange(source, match.range.first, "struct ${match.groupValues[1]}", sourceName, stage)
                match.groupValues[1] to source.substring(range)
            }
            .toList()
        return declarations.groupBy({ it.first }, { it.second })
            .mapNotNull { (name, bodies) ->
                val distinctBodies = bodies.distinct()
                if (distinctBodies.size == 1) name to distinctBodies.single() else null
            }
            .toMap(linkedMapOf())
    }

    private fun parseTypeDeclarationNames(source: String): Set<String> {
        val lexicalMap = buildLexicalMap(source)
        return STRUCT_DECLARATION_REGEX.findAll(source)
            .filter { lexicalMap.isTopLevelCode(it.range.first) }
            .mapTo(linkedSetOf()) { it.groupValues[1] }
    }

    private fun analyzeContract(
        source: String,
        sourceName: String,
        stage: ShaderStage,
        inheritedIntegerMacros: Map<String, Long> = emptyMap(),
    ): ShaderAbiContract {
        val contractSource = expandAbiQualifierMacros(normalizeLineEndings(source))
        val declarations = parseDeclarations(contractSource)
        val integerMacros = inheritedIntegerMacros + resolveIntegerObjectMacros(contractSource)
        val entries = declarations.associate { declaration ->
            declaration.key to ShaderAbiEntry(
                key = declaration.key,
                type = declaration.type,
                arraySuffix = declaration.arraySuffix.replace(WHITESPACE_REGEX, ""),
                qualifiers = declaration.qualifiers,
                qualifiersBeforeStorage = declaration.qualifiersBeforeStorage,
                qualifiersAfterStorage = declaration.qualifiersAfterStorage,
                layout = declaration.layoutItems.mapTo(mutableSetOf()) { it.normalized },
                blockSignature = blockSignature(contractSource, declaration, sourceName, stage, integerMacros),
            )
        }
        val code = stripComments(contractSource)
        val workGroup = WORKGROUP_LAYOUT_REGEX.find(code)?.groupValues?.get(1)?.let {
            val items = parseLayoutItems(it).associate { item -> item.key to item.value }
            Triple(
                parseLayoutInteger(items, "local_size_x", 1, sourceName, stage),
                parseLayoutInteger(items, "local_size_y", 1, sourceName, stage),
                parseLayoutInteger(items, "local_size_z", 1, sourceName, stage),
            )
        }
        val stageLayouts = STAGE_LAYOUT_REGEX.findAll(code).mapNotNull { match ->
            val items = parseLayoutItems(match.groupValues[1])
                .filterNot { it.key.startsWith("local_size_") }
                .map { it.normalized }
                .sorted()
            if (items.isEmpty()) null else "${match.groupValues[2]}:${items.joinToString(",")}"
        }.toSet()

        return ShaderAbiContract(
            entries = entries,
            workGroupSize = workGroup,
            stageLayouts = stageLayouts,
        )
    }

    private fun expandAbiQualifierMacros(source: String): String {
        val aliases = ABI_QUALIFIER_MACRO.findAll(source).mapNotNull { match ->
            val value = match.groupValues[2].trim()
            val tokens = value.split(WHITESPACE_REGEX)
            if (tokens.none { it == "uniform" || it == "buffer" }) return@mapNotNull null
            if (tokens.any { it !in ABI_QUALIFIER_TOKENS }) return@mapNotNull null
            match.groupValues[1] to value
        }.toMap()
        if (aliases.isEmpty()) return source
        return source.lineSequence().joinToString("\n") { line ->
            if (line.trimStart().startsWith('#')) {
                line
            } else {
                aliases.entries.fold(line) { value, (name, replacement) ->
                    Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])")
                        .replace(value, replacement)
                }
            }
        } + "\n"
    }

    private fun blockSignature(
        source: String,
        declaration: ParsedDeclaration,
        sourceName: String,
        stage: ShaderStage,
        integerMacros: Map<String, Long> = resolveIntegerObjectMacros(source),
    ): ShaderBlockSignature? {
        if (declaration.key.kind !in BLOCK_KINDS) return null
        val range = blockDeclarationRange(source, declaration, sourceName, stage)
        return ShaderBlockSignature(
            body = canonicalizeIntegerArraySizes(
                expandIntegerObjectMacros(
                    stripComments(source.substring(range.openBrace, range.closeBrace + 1)),
                    integerMacros,
                )
                    .replace(WHITESPACE_REGEX, ""),
            ),
            instance = stripComments(source.substring(range.closeBrace + 1, range.semicolon)).replace(WHITESPACE_REGEX, ""),
        )
    }

    private fun materializeBlockArrayExtents(
        source: String,
        expectedContract: ShaderAbiContract,
        sourceName: String,
        stage: ShaderStage,
    ): String {
        val declarations = parseDeclarations(source).associateBy { it.key }
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val touched = linkedSetOf<ShaderAbiKey>()
        expectedContract.entries.forEach { (key, entry) ->
            val expected = entry.blockSignature ?: return@forEach
            val declaration = declarations[key] ?: return@forEach
            val range = blockDeclarationRange(source, declaration, sourceName, stage)
            val body = source.substring(range.openBrace, range.closeBrace + 1)
            val actualExtents = BLOCK_ARRAY_EXTENT.findAll(body).toList()
            val expectedExtents = BLOCK_ARRAY_EXTENT.findAll(expected.body).toList()
            if (actualExtents.size != expectedExtents.size) return@forEach
            actualExtents.zip(expectedExtents).forEach { (actualExtent, expectedExtent) ->
                val actualExpression = actualExtent.groupValues[1]
                val expectedValue = IntegerConstantExpressionParser(expectedExtent.groupValues[1]).parse()
                    ?: return@forEach
                val actualValue = IntegerConstantExpressionParser(actualExpression).parse()
                if (actualValue != null) return@forEach
                val expressionRange = requireNotNull(actualExtent.groups[1]).range
                replacements += (range.openBrace + expressionRange.first until
                    range.openBrace + expressionRange.last + 1) to expectedValue.toString()
                touched += key
            }
        }
        if (replacements.isEmpty()) return source
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        val materialized = parseDeclarations(result).associateBy { it.key }
        touched.forEach { key ->
            val declaration = materialized[key] ?: fail(sourceName, stage, null, "$key disappeared during ABI materialization")
            val actual = blockSignature(result, declaration, sourceName, stage, emptyMap())
            val expected = expectedContract.entries.getValue(key).blockSignature
            if (!blockSignatureRestorable(expected, actual)) {
                fail(sourceName, stage, null, "$key could not materialize proven array extents: $expected vs $actual")
            }
        }
        return result
    }

    private fun declarationSourceRange(
        source: String,
        declaration: ParsedDeclaration,
        sourceName: String,
        stage: ShaderStage,
    ): IntRange {
        if (declaration.key.kind !in BLOCK_KINDS) return declaration.range
        return declaration.range.first..blockDeclarationRange(source, declaration, sourceName, stage).semicolon
    }

    private fun blockDeclarationRange(
        source: String,
        declaration: ParsedDeclaration,
        sourceName: String,
        stage: ShaderStage,
    ): BlockDeclarationRange {
        val lexicalMap = buildLexicalMap(source)
        val openBrace = source.indexOf('{', declaration.range.first)
        val closeBrace = (openBrace + 1 until source.length).firstOrNull { offset ->
            source[offset] == '}' && lexicalMap.isCode(offset) && lexicalMap.depth[offset] == 1
        } ?: fail(
            sourceName,
            stage,
            sourceLine(source, declaration.range.first),
            "unterminated ${declaration.key.kind}:${declaration.key.name} block",
        )
        val semicolon = (closeBrace + 1 until source.length).firstOrNull { offset ->
            source[offset] == ';' && lexicalMap.isTopLevelCode(offset)
        } ?: fail(
            sourceName,
            stage,
            sourceLine(source, declaration.range.first),
            "${declaration.key.kind}:${declaration.key.name} block has no terminating semicolon",
        )
        return BlockDeclarationRange(openBrace, closeBrace, semicolon)
    }

    private fun bracedDeclarationRange(
        source: String,
        start: Int,
        description: String,
        sourceName: String,
        stage: ShaderStage,
    ): IntRange {
        val lexicalMap = buildLexicalMap(source)
        val openBrace = source.indexOf('{', start)
        val closeBrace = (openBrace + 1 until source.length).firstOrNull { offset ->
            source[offset] == '}' && lexicalMap.isCode(offset) && lexicalMap.depth[offset] == 1
        } ?: fail(
            sourceName,
            stage,
            sourceLine(source, start),
            "unterminated $description declaration",
        )
        val semicolon = (closeBrace + 1 until source.length).firstOrNull { offset ->
            source[offset] == ';' && lexicalMap.isTopLevelCode(offset)
        } ?: fail(
            sourceName,
            stage,
            sourceLine(source, start),
            "$description declaration has no terminating semicolon",
        )
        return start..semicolon
    }

    private fun blockSignatureRestorable(
        expected: ShaderBlockSignature?,
        actual: ShaderBlockSignature?,
    ): Boolean {
        if (expected == actual) return true
        val actualBody = if (expected != null && !BLOCK_MEMBER_OFFSET_LAYOUT.containsMatchIn(expected.body)) {
            actual?.body?.let { BLOCK_MEMBER_OFFSET_LAYOUT.replace(it, "") }
        } else {
            actual?.body
        }
        return expected != null && actual != null &&
            expected.body == actualBody &&
            expected.instance.isEmpty() &&
            BLOCK_INSTANCE_NAME_REGEX.matches(actual.instance)
    }

    private fun rejectUnsupportedDeclarations(
        source: String,
        declarations: List<ParsedDeclaration>,
        sourceName: String,
        stage: ShaderStage,
    ) {
        val parsedRanges = declarations.map { it.range }
        CUSTOM_INTERFACE_BLOCK_REGEX.findAll(source).firstOrNull { match ->
            isTopLevelCode(source, match.range.first) && parsedRanges.none { match.range.first in it }
        }?.let {
            fail(
                sourceName,
                stage,
                sourceLine(source, it.range.first),
                "custom stage interface blocks are not supported by SPIR-V round-trip patching",
            )
        }
        ABI_DECLARATION_CANDIDATE_REGEX.findAll(source).firstOrNull { match ->
            isTopLevelCode(source, match.range.first) && parsedRanges.none { match.range.first in it }
        }?.let {
            fail(
                sourceName,
                stage,
                sourceLine(source, it.range.first),
                "unsupported top-level stage or resource declaration",
            )
        }
    }

    private fun parseDeclarations(source: String): List<ParsedDeclaration> {
        val lexicalMap = buildLexicalMap(source)
        val topLevelLineStarts = topLevelLineStarts(source, lexicalMap)
        val variables = topLevelLineStarts.mapNotNull { offset ->
            val match = VARIABLE_DECLARATION_REGEX.matchAt(source, offset) ?: return@mapNotNull null
            val storage = match.groupValues[5]
            ParsedDeclaration(
                range = match.range,
                indent = match.groupValues[1],
                layoutRange = match.groups[2]?.range,
                layoutItems = parseLayoutItems(match.groupValues[3]),
                storageRange = match.groups[5]!!.range,
                key = ShaderAbiKey(
                    when (storage) {
                        "in" -> ShaderAbiKind.INPUT
                        "out" -> ShaderAbiKind.OUTPUT
                        else -> ShaderAbiKind.UNIFORM
                    },
                    match.groupValues[8],
                ),
                type = match.groupValues[7],
                arraySuffix = match.groupValues[9].trim(),
                qualifiersBeforeStorage = parseQualifierList(match.groupValues[4]),
                qualifiersAfterStorage = parseQualifierList(match.groupValues[6]),
            )
        }
        val blocks = topLevelLineStarts.mapNotNull { offset ->
            val match = BLOCK_DECLARATION_REGEX.matchAt(source, offset) ?: return@mapNotNull null
            val storage = match.groupValues[5]
            val name = match.groupValues[7]
            ParsedDeclaration(
                range = match.range,
                indent = match.groupValues[1],
                layoutRange = match.groups[2]?.range,
                layoutItems = parseLayoutItems(match.groupValues[3]),
                storageRange = match.groups[5]!!.range,
                key = ShaderAbiKey(
                    if (storage == "buffer") ShaderAbiKind.STORAGE_BLOCK else ShaderAbiKind.UNIFORM_BLOCK,
                    name,
                ),
                type = name,
                arraySuffix = "",
                qualifiersBeforeStorage = parseQualifierList(match.groupValues[4]),
                qualifiersAfterStorage = parseQualifierList(match.groupValues[6]),
            )
        }.toList()
        val macroBlocks = topLevelLineStarts.mapNotNull { offset ->
            val match = BLOCK_MACRO_DECLARATION_REGEX.matchAt(source, offset) ?: return@mapNotNull null
            if (blocks.any { it.range.overlaps(match.range) }) return@mapNotNull null
            val layoutItems = parseLayoutItems(match.groupValues[3])
            if (layoutItems.none { it.key == "std430" || it.key == "binding" }) return@mapNotNull null
            val name = match.groupValues[5]
            ParsedDeclaration(
                range = match.range,
                indent = match.groupValues[1],
                layoutRange = match.groups[2]?.range,
                layoutItems = layoutItems,
                storageRange = match.groups[4]!!.range,
                key = ShaderAbiKey(ShaderAbiKind.STORAGE_BLOCK, name),
                type = name,
                arraySuffix = "",
                qualifiersBeforeStorage = emptyList(),
                qualifiersAfterStorage = emptyList(),
            )
        }
        return (variables + blocks + macroBlocks).sortedBy { it.range.first }.toList()
    }

    private fun topLevelLineStarts(source: String, lexicalMap: LexicalMap): List<Int> {
        val result = mutableListOf<Int>()
        var lineStart = 0
        while (lineStart < source.length) {
            var content = lineStart
            while (content < source.length && source[content] in "\t ") content++
            if (content < source.length && source[content] !in "\r\n" && lexicalMap.isTopLevelCode(content)) {
                result += lineStart
            }
            val newline = source.indexOfAny(charArrayOf('\r', '\n'), content)
            lineStart = when {
                newline < 0 -> source.length
                source[newline] == '\r' && source.getOrNull(newline + 1) == '\n' -> newline + 2
                else -> newline + 1
            }
        }
        return result
    }

    private fun requiredLayoutQualifier(declaration: ParsedDeclaration): String? {
        return when (declaration.key.kind) {
            ShaderAbiKind.INPUT,
            ShaderAbiKind.OUTPUT,
            -> if (declaration.key.name.startsWith("gl_")) null else "location"

            ShaderAbiKind.UNIFORM -> if (isOpaqueType(declaration.type)) "binding" else "location"
            ShaderAbiKind.UNIFORM_BLOCK,
            ShaderAbiKind.STORAGE_BLOCK,
            -> "binding"
        }
    }

    private fun layoutNamespace(declaration: ParsedDeclaration): LayoutNamespace {
        return when (declaration.key.kind) {
            ShaderAbiKind.INPUT -> LayoutNamespace.INPUT_LOCATION
            ShaderAbiKind.OUTPUT -> LayoutNamespace.OUTPUT_LOCATION
            ShaderAbiKind.UNIFORM -> when {
                declaration.type.contains("sampler", ignoreCase = true) -> LayoutNamespace.SAMPLER_BINDING
                declaration.type.contains("image", ignoreCase = true) -> LayoutNamespace.IMAGE_BINDING
                declaration.type == "atomic_uint" -> LayoutNamespace.ATOMIC_COUNTER_BINDING
                else -> LayoutNamespace.UNIFORM_LOCATION
            }

            ShaderAbiKind.UNIFORM_BLOCK -> LayoutNamespace.UNIFORM_BLOCK_BINDING
            ShaderAbiKind.STORAGE_BLOCK -> LayoutNamespace.STORAGE_BLOCK_BINDING
        }
    }

    private fun ParsedDeclaration.locationSlots(): Int {
        if (requiredLayoutQualifier(this) == "binding") return 1
        val matrixColumns = MATRIX_TYPE_REGEX.matchEntire(type)?.groupValues?.get(1)?.toInt() ?: 1
        val arraySize = ARRAY_SIZE_REGEX.find(arraySuffix)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return matrixColumns * arraySize
    }

    private fun parseLayoutItems(content: String): List<LayoutItem> {
        if (content.isBlank()) return emptyList()
        return content.split(',').map { raw ->
            val original = raw.trim()
            val separator = original.indexOf('=')
            if (separator < 0) {
                LayoutItem(original, null, original)
            } else {
                val key = original.substring(0, separator).trim()
                val value = original.substring(separator + 1).trim()
                LayoutItem(key, value, "$key=$value")
            }
        }
    }

    private fun parseLayoutInteger(
        items: Map<String, String?>,
        key: String,
        default: Int,
        sourceName: String,
        stage: ShaderStage,
    ): Int {
        val value = items[key] ?: return default
        return value.toIntOrNull()
            ?: fail(sourceName, stage, null, "$key must be an integer literal for OpenGL SPIR-V")
    }

    private fun parseQualifierList(source: String): List<String> {
        return source.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
    }

    private fun arraySuffixCompatible(
        expected: ShaderAbiEntry,
        actual: ShaderAbiEntry,
        stage: ShaderStage,
    ): Boolean {
        if (expected.arraySuffix == actual.arraySuffix) return true
        return stage == ShaderStage.GEOMETRY &&
            expected.key.kind == ShaderAbiKind.INPUT &&
            expected.arraySuffix == "[]" &&
            SIZED_ARRAY_SUFFIX.matches(actual.arraySuffix)
    }

    private fun isOpaqueType(type: String): Boolean {
        return type.contains("sampler", ignoreCase = true) ||
            type.contains("image", ignoreCase = true) ||
            type == "atomic_uint"
    }

    private fun occupy(occupied: MutableSet<Int>, start: Int, slots: Int) {
        repeat(slots) { occupied += start + it }
    }

    private fun isFree(occupied: Set<Int>, start: Int, slots: Int): Boolean {
        return (0 until slots).none { start + it in occupied }
    }

    private fun firstFree(occupied: Set<Int>, slots: Int): Int {
        var candidate = 0
        while (!isFree(occupied, candidate, slots)) candidate++
        return candidate
    }

    private fun buildLexicalMap(source: String): LexicalMap {
        val depth = IntArray(source.length + 1)
        val code = BooleanArray(source.length + 1)
        var braceDepth = 0
        var blockComment = false
        var lineComment = false
        var quote: Char? = null
        var cursor = 0
        while (cursor < source.length) {
            depth[cursor] = braceDepth
            code[cursor] = !blockComment && !lineComment && quote == null
            val char = source[cursor]
            val next = source.getOrNull(cursor + 1)

            if (lineComment) {
                if (char == '\n') lineComment = false
                cursor++
                continue
            }
            if (blockComment) {
                if (char == '*' && next == '/') {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    blockComment = false
                    cursor += 2
                } else {
                    cursor++
                }
                continue
            }
            val currentQuote = quote
            if (currentQuote != null) {
                if (char == '\\' && next != null) {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    cursor += 2
                } else {
                    if (char == currentQuote) quote = null
                    cursor++
                }
                continue
            }

            when {
                char == '/' && next == '/' -> {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    lineComment = true
                    cursor += 2
                }

                char == '/' && next == '*' -> {
                    depth[cursor + 1] = braceDepth
                    code[cursor + 1] = false
                    blockComment = true
                    cursor += 2
                }

                char == '"' || char == '\'' -> {
                    quote = char
                    cursor++
                }

                char == '{' -> {
                    braceDepth++
                    cursor++
                }

                char == '}' -> {
                    if (braceDepth > 0) braceDepth--
                    cursor++
                }

                else -> cursor++
            }
        }
        depth[source.length] = braceDepth
        code[source.length] = !blockComment && !lineComment && quote == null
        return LexicalMap(depth, code)
    }

    private fun isTopLevelCode(source: String, offset: Int): Boolean {
        return buildLexicalMap(source).isTopLevelCode(offset)
    }

    private fun stripComments(source: String): String {
        val withoutLineComments = LINE_COMMENT_REGEX.replace(source) { " ".repeat(it.value.length) }
        return BLOCK_COMMENT_REGEX.replace(withoutLineComments) { comment ->
            comment.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }
    }

    private fun normalizeOutput(source: String): String {
        return normalizeLineEndings(source).trimEnd() + "\n"
    }

    private fun normalizeLineEndings(source: String): String {
        return source.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun sourceLine(source: String, offset: Int): Int {
        return source.take(offset).count { it == '\n' } + 1
    }

    private fun fail(
        sourceName: String,
        stage: ShaderStage,
        sourceLine: Int?,
        reason: String,
    ): Nothing {
        throw OpenGlShaderPatchException(sourceName, stage, sourceLine, reason)
    }

    private fun Set<ShaderAbiKey>.sortedForDiagnostic(): List<String> {
        return map { "${it.kind}:${it.name}" }.sorted()
    }

    private enum class LayoutNamespace(val allowsGeneratedAlias: Boolean = false) {
        INPUT_LOCATION,
        OUTPUT_LOCATION,
        UNIFORM_LOCATION,
        SAMPLER_BINDING(allowsGeneratedAlias = true),
        IMAGE_BINDING,
        ATOMIC_COUNTER_BINDING,
        UNIFORM_BLOCK_BINDING,
        STORAGE_BLOCK_BINDING,
    }

    private data class LayoutCandidate(
        val key: ShaderAbiKey,
        val qualifier: String,
        val namespace: LayoutNamespace,
        val slots: Int,
    )

    private data class ParsedDeclaration(
        val range: IntRange,
        val indent: String,
        val layoutRange: IntRange?,
        val layoutItems: List<LayoutItem>,
        val storageRange: IntRange,
        val key: ShaderAbiKey,
        val type: String,
        val arraySuffix: String,
        val qualifiersBeforeStorage: List<String>,
        val qualifiersAfterStorage: List<String>,
    ) {
        val qualifiers: Set<String>
            get() = (qualifiersBeforeStorage + qualifiersAfterStorage).toSet()

        fun layoutValue(key: String): String? = layoutItems.singleOrNull { it.key == key }?.value
    }

    private data class LayoutItem(val key: String, val value: String?, val normalized: String) {
        val original: String
            get() = if (value == null) key else "$key = $value"
    }

    private data class BlockDeclarationRange(val openBrace: Int, val closeBrace: Int, val semicolon: Int)

    private data class SourceInsertion(val offset: Int, val text: String)
    private data class LexicalMap(val depth: IntArray, val code: BooleanArray) {
        fun isCode(offset: Int): Boolean = code[offset]
        fun isTopLevelCode(offset: Int): Boolean = code[offset] && depth[offset] == 0
    }

    companion object {
        private const val MAX_COMPILER_SAMPLER_BINDINGS = 80
        private const val QUALIFIER =
            "(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise|highp|mediump|lowp|" +
                "coherent|volatile|restrict|readonly|writeonly)"
        private val VERSION_REGEX = """(?m)^[\t ]*#version[^\r\n]*""".toRegex()
        private val VARIABLE_DECLARATION_REGEX = (
            "(?m)^([\\t ]*)(?:(layout\\s*\\(([^)\\r\\n]*)\\)\\s*))?" +
                "((?:$QUALIFIER\\s+)*)(uniform|in|out)\\s+((?:$QUALIFIER\\s+)*)" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)" +
                "(\\s*(?:\\[[^]\\r\\n]*])*)[\\t ]*(?:=[\\t ]*([^;\\r\\n]+))?[\\t ]*;"
            ).toRegex()
        private val BLOCK_DECLARATION_REGEX = (
            "(?m)^([\\t ]*)(?:(layout\\s*\\(([^)\\r\\n]*)\\)\\s*))?" +
                "((?:$QUALIFIER\\s+)*)(uniform|buffer)\\s+((?:$QUALIFIER\\s+)*)" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s*\\{"
            ).toRegex()
        private val BLOCK_MACRO_DECLARATION_REGEX = (
            "(?m)^([\\t ]*)(?:(layout\\s*\\(([^)\\r\\n]*)\\)\\s*))?" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{"
            ).toRegex()
        private val ABI_QUALIFIER_MACRO =
            "(?m)^[\\t ]*#define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([^\\r\\n]+)$".toRegex()
        private val ABI_QUALIFIER_TOKENS = setOf(
            "uniform", "buffer", "coherent", "volatile", "restrict", "readonly", "writeonly",
        )
        private val STRUCT_DECLARATION_REGEX =
            "(?m)^[\\t ]*struct\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{".toRegex()
        private val CUSTOM_INTERFACE_BLOCK_REGEX = (
            "(?m)^[\\t ]*(?:layout\\s*\\([^)\\r\\n]*\\)\\s*)?(?:(?:$QUALIFIER)\\s+)*" +
                "(?:in|out)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\{"
            ).toRegex()
        private val ABI_DECLARATION_CANDIDATE_REGEX = (
            "(?m)^[\\t ]*(?:(?:layout\\s*\\([^)\\r\\n]*\\)\\s*)*)" +
                "(?:(?:$QUALIFIER)\\s+)*(?:uniform|buffer|in|out)\\s+" +
                "(?:(?:$QUALIFIER)\\s+)*[A-Za-z_][A-Za-z0-9_]*[^\\r\\n]*(?:;|\\{)"
            ).toRegex()
        private val WORKGROUP_LAYOUT_REGEX =
            """(?m)^\s*layout\s*\(([^)]*\blocal_size_[xyz]\b[^)]*)\)\s*in\s*;""".toRegex()
        private val STAGE_LAYOUT_REGEX =
            """(?m)^\s*layout\s*\(([^)]*)\)\s*(in|out)\s*;""".toRegex()
        private val MAIN_REGEX = """\bvoid\s+main\s*\(""".toRegex()
        private val MATRIX_TYPE_REGEX = """(?:d?mat|f16mat)([234])(?:x[234])?""".toRegex()
        private val ARRAY_SIZE_REGEX = """\[\s*(\d+)\s*]""".toRegex()
        private val SIZED_ARRAY_SUFFIX = """\[\d+]""".toRegex()
        private val BLOCK_INSTANCE_NAME_REGEX = """[A-Za-z_][A-Za-z0-9_]*""".toRegex()
        private val BLOCK_MEMBER_OFFSET_LAYOUT = """layout\(offset=[^)]+\)""".toRegex()
        private val BLOCK_ARRAY_EXTENT = """\[([^]]+)]""".toRegex()
        private val WHITESPACE_REGEX = """\s+""".toRegex()
        private val LINE_COMMENT_REGEX = """//[^\r\n]*""".toRegex()
        private val BLOCK_COMMENT_REGEX = """/\*[\s\S]*?\*/""".toRegex()
        private val BLOCK_KINDS = setOf(ShaderAbiKind.UNIFORM_BLOCK, ShaderAbiKind.STORAGE_BLOCK)
    }
}

private fun canonicalizeIntegerArraySizes(source: String): String {
    return INTEGER_ARRAY_SIZE.replace(source) { match ->
        val value = IntegerConstantExpressionParser(match.groupValues[1]).parse() ?: return@replace match.value
        "[$value]"
    }
}

internal fun resolveIntegerObjectMacros(source: String): Map<String, Long> {
    val definitions = INTEGER_OBJECT_MACRO.findAll(source).groupBy(
        { it.groupValues[1] },
        { it.groupValues[2].substringBefore("//").trim() },
    ).mapNotNull { (name, bodies) ->
        bodies.distinct().singleOrNull()?.let { name to it }
    }.toMap()
    val resolved = linkedMapOf<String, Long>()
    var changed: Boolean
    do {
        changed = false
        definitions.forEach { (name, body) ->
            if (name in resolved) return@forEach
            val expanded = INTEGER_IDENTIFIER.replace(body) { match ->
                resolved[match.value]?.toString() ?: match.value
            }
            val value = IntegerConstantExpressionParser(expanded).parse() ?: return@forEach
            resolved[name] = value
            changed = true
        }
    } while (changed)
    return resolved
}

private fun expandIntegerObjectMacros(source: String, macros: Map<String, Long>): String {
    if (macros.isEmpty()) return source
    return INTEGER_IDENTIFIER.replace(source) { match -> macros[match.value]?.toString() ?: match.value }
}

private val INTEGER_OBJECT_MACRO =
    "(?m)^[\\t ]*#define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]+([^\\r\\n]+)$".toRegex()
private val INTEGER_IDENTIFIER = "(?<![A-Za-z0-9_])[A-Za-z_][A-Za-z0-9_]*(?![A-Za-z0-9_])".toRegex()

private class IntegerConstantExpressionParser(private val source: String) {
    private var cursor = 0

    fun parse(): Long? {
        return try {
            val value = parseExpression()
            skipWhitespace()
            value.takeIf { cursor == source.length }
        } catch (_: ArithmeticException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseExpression(): Long {
        var value = parseTerm()
        while (true) {
            value = when {
                consume('+') -> Math.addExact(value, parseTerm())
                consume('-') -> Math.subtractExact(value, parseTerm())
                else -> return value
            }
        }
    }

    private fun parseTerm(): Long {
        var value = parseUnary()
        while (true) {
            value = when {
                consume('*') -> Math.multiplyExact(value, parseUnary())
                consume('/') -> value / parseUnary()
                consume('%') -> value % parseUnary()
                else -> return value
            }
        }
    }

    private fun parseUnary(): Long {
        return when {
            consume('+') -> parseUnary()
            consume('-') -> Math.negateExact(parseUnary())
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Long {
        if (consume('(')) {
            val value = parseExpression()
            require(consume(')'))
            return value
        }
        skipWhitespace()
        val start = cursor
        val radix = if (source.startsWith("0x", cursor, ignoreCase = true)) {
            cursor += 2
            16
        } else {
            10
        }
        val digitsStart = cursor
        while (cursor < source.length && source[cursor].digitToIntOrNull(radix) != null) cursor++
        require(cursor > digitsStart)
        val digits = source.substring(digitsStart, cursor)
        while (cursor < source.length && source[cursor] in "uUlL") cursor++
        require(cursor > start)
        return digits.toLong(radix)
    }

    private fun consume(expected: Char): Boolean {
        skipWhitespace()
        if (source.getOrNull(cursor) != expected) return false
        cursor++
        return true
    }

    private fun skipWhitespace() {
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
    }
}

private val INTEGER_ARRAY_SIZE = """\[([^]]+)]""".toRegex()
