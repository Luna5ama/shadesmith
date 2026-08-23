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
    val builtIns: Set<String>,
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
    val originalVersion: String,
    val generatedLayouts: List<GeneratedShaderLayout>,
    val restorableDeclarations: Map<ShaderAbiKey, String>,
    val restoredDirectives: List<String>,
    val restoredIrisContracts: List<String>,
    val originalContract: ShaderAbiContract,
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
        alwaysRestorableResources: Set<String> = emptySet(),
    ): OpenGlShaderPatch {
        val source = normalizeLineEndings(protectedSource.compilerSource())
        val versionDirectives = protectedSource.directives.filter {
            it.kind == PreprocessorDirectiveKind.VERSION
        }
        if (versionDirectives.size != 1) {
            fail(
                protectedSource.sourceName,
                stage,
                null,
                "expected exactly one #version directive, found ${versionDirectives.size}",
            )
        }
        val versionDirective = versionDirectives.single()
        val originalVersion = normalizeDirective(versionDirective.exactText)
        val versionRange = sourceLineRange(source, versionDirective.sourceLine)
        if (source.substring(versionRange) != originalVersion) {
            fail(
                protectedSource.sourceName,
                stage,
                versionDirective.sourceLine,
                "#version restoration metadata does not match compiler source",
            )
        }
        var compilerSource = source.replaceRange(versionRange, "#version 460 core")
        compilerSource = patchIrisCompilerDeclarations(
            compilerSource,
            protectedSource.sourceName,
            stage,
        )

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
            originalVersion = originalVersion,
            generatedLayouts = generatedLayouts,
            restorableDeclarations = collectRestorableDeclarations(
                source,
                originalDeclarations,
                alwaysRestorableResources,
            ),
            restoredDirectives = collectRestoredDirectives(protectedSource),
            restoredIrisContracts = collectIrisSourceContracts(
                source,
                protectedSource.sourceName,
                stage,
            ),
            originalContract = analyzeContract(source, protectedSource.sourceName, stage),
        )
    }

    fun restore(decompiledSource: String, patch: OpenGlShaderPatch): String {
        var restored = normalizeOutput(decompiledSource)
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
        restored = restoreMissingDeclarations(restored, patch)
        restored = restoreMissingQualifiers(restored, patch)
        restored = restoreSourceContracts(restored, patch)
        validateContract(restored, patch)
        return normalizeOutput(restored)
    }

    fun validateContract(restoredSource: String, patch: OpenGlShaderPatch) {
        val actual = analyzeContract(restoredSource, patch.sourceName, patch.stage)
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
            if (!blockSignatureCompatible(expectedEntry.blockSignature, actualEntry.blockSignature)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "${key.kind}:${key.name} block declaration changed from ${expectedEntry.blockSignature} " +
                        "to ${actualEntry.blockSignature}",
                )
            }
        }

        if (!actual.builtIns.containsAll(expected.builtIns)) {
            fail(
                patch.sourceName,
                patch.stage,
                null,
                "built-ins disappeared after round-trip: ${(expected.builtIns - actual.builtIns).sorted()}",
            )
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
        patch.restoredDirectives.forEach { directive ->
            if (!normalizeLineEndings(restoredSource).contains(directive)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "source directive was not restored: ${directive.lineSequence().first()}",
                )
            }
        }
        patch.restoredIrisContracts.forEach { contract ->
            if (!normalizeLineEndings(restoredSource).contains(contract)) {
                fail(
                    patch.sourceName,
                    patch.stage,
                    null,
                    "Iris source contract was not restored: ${contract.lineSequence().first()}",
                )
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
            occupy(occupied.getValue(layoutNamespace(declaration)), value, declaration.locationSlots())
        }

        return buildList {
            declarations.forEach { declaration ->
                val qualifier = requiredLayoutQualifier(declaration) ?: return@forEach
                if (declaration.layoutValue(qualifier) != null) return@forEach
                val namespace = layoutNamespace(declaration)
                val slots = declaration.locationSlots()
                val preferredLayout = preferred[declaration.key]
                val value = if (preferredLayout != null) {
                    if (preferredLayout.qualifier != qualifier) {
                        fail(
                            sourceName,
                            stage,
                            sourceLine(source, declaration.range.first),
                            "preferred layout kind changed for ${declaration.key.kind}:${declaration.key.name}",
                        )
                    }
                    if (!isFree(occupied.getValue(namespace), preferredLayout.value, slots)) {
                        fail(
                            sourceName,
                            stage,
                            sourceLine(source, declaration.range.first),
                            "preferred $qualifier ${preferredLayout.value} collides for " +
                                "${declaration.key.kind}:${declaration.key.name}",
                        )
                    }
                    preferredLayout.value
                } else {
                    firstFree(occupied.getValue(namespace), slots)
                }
                occupy(occupied.getValue(namespace), value, slots)
                add(GeneratedShaderLayout(declaration.key, qualifier, value))
            }
        }
    }

    private fun applyGeneratedLayouts(
        source: String,
        declarations: List<ParsedDeclaration>,
        generatedLayouts: List<GeneratedShaderLayout>,
    ): String {
        val declarationsByKey = declarations.associateBy { it.key }
        val insertions = generatedLayouts.map { generated ->
            val declaration = declarationsByKey.getValue(generated.key)
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

    private fun restoreSourceContracts(source: String, patch: OpenGlShaderPatch): String {
        var result = removeIrisSourceContracts(source)
        val version = VERSION_REGEX.find(result)
            ?: fail(patch.sourceName, patch.stage, null, "spirv-cross output has no #version directive")
        result = result.replaceRange(version.range, patch.originalVersion)
        val missingContracts = patch.restoredDirectives.filterNot { result.contains(it) } +
            patch.restoredIrisContracts
        if (missingContracts.isEmpty()) return result

        val restoredVersion = VERSION_REGEX.find(result)!!
        val insertionOffset = restoredVersion.range.last + 1
        val insertion = buildString {
            append('\n')
            missingContracts.forEach {
                append(it)
                if (!it.endsWith('\n')) append('\n')
            }
        }
        result = result.substring(0, insertionOffset) + insertion + result.substring(insertionOffset)
        return result
    }

    private fun restoreMissingDeclarations(source: String, patch: OpenGlShaderPatch): String {
        val actualKeys = parseDeclarations(source).mapTo(mutableSetOf()) { it.key }
        val missing = patch.originalContract.entries.keys.filterNot { it in actualKeys }
        val declarations = missing.mapNotNull { patch.restorableDeclarations[it] }
        if (declarations.isEmpty()) return source

        val version = VERSION_REGEX.find(source)
            ?: fail(patch.sourceName, patch.stage, null, "spirv-cross output has no #version directive")
        val insertionOffset = version.range.last + 1
        val insertion = buildString {
            append('\n')
            declarations.forEach {
                append(it)
                if (!it.endsWith('\n')) append('\n')
            }
        }
        return source.substring(0, insertionOffset) + insertion + source.substring(insertionOffset)
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

    private fun collectRestoredDirectives(source: ProtectedPreprocessorSource): List<String> {
        val regular = source.directives.filter {
            it.disposition == PreprocessorDisposition.RESTORED &&
                it.kind in RESTORED_SOURCE_DIRECTIVES
        }.map { normalizeDirective(it.exactText) }
        val evaluated = source.directives.filter {
            it.disposition == PreprocessorDisposition.EVALUATED &&
                it.kind != PreprocessorDirectiveKind.VERSION
        }.map { normalizeDirective(it.exactText) }

        return buildList {
            addAll(regular)
            if (evaluated.isNotEmpty()) {
                add(
                    buildString {
                        appendLine("/*const*/")
                        evaluated.forEach {
                            append(it)
                            if (!it.endsWith('\n')) append('\n')
                        }
                        append("/*const*/")
                    },
                )
            }
        }
    }

    private fun collectIrisSourceContracts(
        source: String,
        sourceName: String,
        stage: ShaderStage,
    ): List<String> {
        val lexicalMap = buildLexicalMap(source)
        IRIS_CONST_DIRECTIVE_REGEX.findAll(source).firstOrNull { match ->
            lexicalMap.isCode(match.range.first) &&
                !lexicalMap.isTopLevelCode(match.range.first) &&
                isIrisConstDirectiveName(match.groupValues[3])
        }?.let { directive ->
            fail(
                sourceName,
                stage,
                sourceLine(source, directive.range.first),
                "Iris const directive ${directive.groupValues[3]} must be declared at global scope",
            )
        }
        return findIrisSourceContracts(source).map { it.text }
    }

    private fun collectRestorableDeclarations(
        source: String,
        declarations: List<ParsedDeclaration>,
        alwaysRestorableResources: Set<String>,
    ): Map<ShaderAbiKey, String> {
        val lexicalMap = buildLexicalMap(source)
        return declarations.filter { declaration ->
            declaration.key.kind !in BLOCK_KINDS && (
                declaration.key.name in alwaysRestorableResources ||
                    Regex("\\b${Regex.escape(declaration.key.name)}\\b").findAll(source).none { reference ->
                        reference.range.first !in declaration.range && lexicalMap.code[reference.range.first]
                    }
            )
        }.associate { declaration -> declaration.key to source.substring(declaration.range) }
    }

    private fun patchIrisCompilerDeclarations(source: String, sourceName: String, stage: ShaderStage): String {
        val lexicalMap = buildLexicalMap(source)
        val formatDirectives = IRIS_CONST_DIRECTIVE_REGEX.findAll(source).filter { match ->
            lexicalMap.isTopLevelCode(match.range.first) &&
                match.groupValues[2] == "int" &&
                IRIS_FORMAT_DIRECTIVE_NAME_REGEX.matches(match.groupValues[3])
        }.toList()

        formatDirectives.forEach { directive ->
            val name = directive.groupValues[3]
            val referenced = Regex("\\b${Regex.escape(name)}\\b").findAll(source).any { reference ->
                reference.range.first !in directive.range && lexicalMap.code[reference.range.first]
            }
            if (referenced) {
                fail(
                    sourceName,
                    stage,
                    sourceLine(source, directive.range.first),
                    "Iris format directive $name is also referenced by shader code",
                )
            }
        }

        var result = source
        formatDirectives.asReversed().forEach { directive ->
            result = result.replaceRange(directive.groups[5]!!.range, "0")
        }
        return result
    }

    private fun removeIrisSourceContracts(source: String): String {
        var result = source
        findIrisSourceContracts(source).asReversed().forEach { contract ->
            result = result.removeRange(contract.range)
        }
        return result
    }

    private fun findIrisSourceContracts(source: String): List<IrisSourceContract> {
        val lexicalMap = buildLexicalMap(source)
        val candidates = buildList<IrisSourceContract> {
            IRIS_CONST_DIRECTIVE_REGEX.findAll(source).filter { match ->
                lexicalMap.isTopLevelCode(match.range.first) &&
                    isIrisConstDirectiveName(match.groupValues[3])
            }.forEach { add(IrisSourceContract(it.range, it.value)) }
            IRIS_COMMENT_DIRECTIVE_REGEX.findAll(source).filter { match ->
                lexicalMap.isCode(match.range.first)
            }.forEach { add(IrisSourceContract(it.range, it.value)) }
        }.sortedWith(compareBy<IrisSourceContract> { it.range.first }.thenByDescending { it.range.last })

        return buildList<IrisSourceContract> {
            candidates.forEach { candidate ->
                if (none { rangesOverlap(it.range, candidate.range) }) add(candidate)
            }
        }.sortedBy { it.range.first }
    }

    private fun isIrisConstDirectiveName(name: String): Boolean {
        return name in IRIS_CONST_DIRECTIVE_NAMES || IRIS_CONST_DIRECTIVE_NAME_PATTERNS.any { it.matches(name) }
    }

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean {
        return first.first <= second.last && second.first <= first.last
    }

    private fun analyzeContract(source: String, sourceName: String, stage: ShaderStage): ShaderAbiContract {
        val declarations = parseDeclarations(normalizeLineEndings(source))
        val entries = declarations.associate { declaration ->
            declaration.key to ShaderAbiEntry(
                key = declaration.key,
                type = declaration.type,
                arraySuffix = declaration.arraySuffix.replace(WHITESPACE_REGEX, ""),
                qualifiers = declaration.qualifiers,
                qualifiersBeforeStorage = declaration.qualifiersBeforeStorage,
                qualifiersAfterStorage = declaration.qualifiersAfterStorage,
                layout = declaration.layoutItems.mapTo(mutableSetOf()) { it.normalized },
                blockSignature = blockSignature(source, declaration, sourceName, stage),
            )
        }
        if (entries.size != declarations.size) {
            fail(sourceName, stage, null, "duplicate stage ABI declaration names are unsupported")
        }

        val code = stripComments(source)
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
            builtIns = BUILTIN_REGEX.findAll(code).mapTo(mutableSetOf()) { it.value },
            workGroupSize = workGroup,
            stageLayouts = stageLayouts,
        )
    }

    private fun blockSignature(
        source: String,
        declaration: ParsedDeclaration,
        sourceName: String,
        stage: ShaderStage,
    ): ShaderBlockSignature? {
        if (declaration.key.kind !in BLOCK_KINDS) return null
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
        return ShaderBlockSignature(
            body = stripComments(source.substring(openBrace, closeBrace + 1)).replace(WHITESPACE_REGEX, ""),
            instance = stripComments(source.substring(closeBrace + 1, semicolon)).replace(WHITESPACE_REGEX, ""),
        )
    }

    private fun blockSignatureCompatible(
        expected: ShaderBlockSignature?,
        actual: ShaderBlockSignature?,
    ): Boolean {
        if (expected == actual) return true
        return expected != null && actual != null &&
            expected.body == actual.body &&
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
        val variables = VARIABLE_DECLARATION_REGEX.findAll(source).mapNotNull { match ->
            if (!lexicalMap.isTopLevelCode(match.range.first)) return@mapNotNull null
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
        val blocks = BLOCK_DECLARATION_REGEX.findAll(source).mapNotNull { match ->
            if (!lexicalMap.isTopLevelCode(match.range.first)) return@mapNotNull null
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
        }
        return (variables + blocks).sortedBy { it.range.first }.toList()
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
            ShaderAbiKind.UNIFORM -> if (isOpaqueType(declaration.type)) {
                LayoutNamespace.RESOURCE_BINDING
            } else {
                LayoutNamespace.UNIFORM_LOCATION
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

    private fun normalizeDirective(text: String): String {
        return normalizeLineEndings(text).removeSuffix("\n")
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

    private fun sourceLineRange(source: String, lineNumber: Int): IntRange {
        var start = 0
        repeat(lineNumber - 1) {
            start = source.indexOf('\n', start).let { newline ->
                if (newline < 0) source.length else newline + 1
            }
        }
        val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        return start until end
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

    private enum class LayoutNamespace {
        INPUT_LOCATION,
        OUTPUT_LOCATION,
        UNIFORM_LOCATION,
        RESOURCE_BINDING,
        UNIFORM_BLOCK_BINDING,
        STORAGE_BLOCK_BINDING,
    }

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

    private data class SourceInsertion(val offset: Int, val text: String)
    private data class IrisSourceContract(val range: IntRange, val text: String)
    private data class LexicalMap(val depth: IntArray, val code: BooleanArray) {
        fun isCode(offset: Int): Boolean = code[offset]
        fun isTopLevelCode(offset: Int): Boolean = code[offset] && depth[offset] == 0
    }

    companion object {
        private const val QUALIFIER =
            "(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise|highp|mediump|lowp|" +
                "coherent|volatile|restrict|readonly|writeonly)"
        private val VERSION_REGEX = """(?m)^[\t ]*#version[^\r\n]*""".toRegex()
        private val VARIABLE_DECLARATION_REGEX = (
            "(?m)^([\\t ]*)(?:(layout\\s*\\(([^)\\r\\n]*)\\)\\s*))?" +
                "((?:$QUALIFIER\\s+)*)(uniform|in|out)\\s+((?:$QUALIFIER\\s+)*)" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)" +
                "(\\s*(?:\\[[^]\\r\\n]*])*)\\s*;"
            ).toRegex()
        private val BLOCK_DECLARATION_REGEX = (
            "(?m)^([\\t ]*)(?:(layout\\s*\\(([^)\\r\\n]*)\\)\\s*))?" +
                "((?:$QUALIFIER\\s+)*)(uniform|buffer)\\s+((?:$QUALIFIER\\s+)*)" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s*\\{"
            ).toRegex()
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
        private val IRIS_CONST_DIRECTIVE_REGEX = (
            "(?m)^([\\t ]*const[\\t ]+(int|float|vec2|ivec3|vec4|bool)[\\t ]+)" +
                "([A-Za-z_][A-Za-z0-9_]*)([\\t ]*=[\\t ]*)([^;\\r\\n]+)(;[^\\r\\n]*)"
            ).toRegex()
        private val IRIS_COMMENT_DIRECTIVE_REGEX =
            """/\*[\t ]*(?:DRAWBUFFERS|RENDERTARGETS|SHADOWRES|SHADOWFOV|SHADOWHPL|GAUX4FORMAT):[\s\S]*?\*/"""
                .toRegex()
        private val IRIS_CONST_DIRECTIVE_NAMES = setOf(
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
        private val IRIS_CONST_DIRECTIVE_NAME_PATTERNS = listOf(
            "(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])(?:Format|Clear|ClearColor|MipmapEnabled)".toRegex(),
            "shadowHardwareFiltering\\d+".toRegex(),
            "shadowtex\\d+(?:Mipmap|Nearest)".toRegex(),
            "shadow\\d+MinMagNearest".toRegex(),
            "shadowcolor\\d+(?:Mipmap|Nearest|Format|Clear|ClearColor)".toRegex(),
            "shadowColor\\d+(?:Mipmap|Nearest|MinMagNearest)".toRegex(),
        )
        private val IRIS_FORMAT_DIRECTIVE_NAME_REGEX =
            "(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]|shadowcolor\\d+)Format".toRegex()
        private val STAGE_LAYOUT_REGEX =
            """(?m)^\s*layout\s*\(([^)]*)\)\s*(in|out)\s*;""".toRegex()
        private val MAIN_REGEX = """\bvoid\s+main\s*\(""".toRegex()
        private val BUILTIN_REGEX = """\bgl_[A-Za-z_][A-Za-z0-9_]*\b""".toRegex()
        private val MATRIX_TYPE_REGEX = """(?:d?mat|f16mat)([234])(?:x[234])?""".toRegex()
        private val ARRAY_SIZE_REGEX = """\[\s*(\d+)\s*]""".toRegex()
        private val SIZED_ARRAY_SUFFIX = """\[\d+]""".toRegex()
        private val BLOCK_INSTANCE_NAME_REGEX = """[A-Za-z_][A-Za-z0-9_]*""".toRegex()
        private val WHITESPACE_REGEX = """\s+""".toRegex()
        private val LINE_COMMENT_REGEX = """//[^\r\n]*""".toRegex()
        private val BLOCK_COMMENT_REGEX = """/\*[\s\S]*?\*/""".toRegex()
        private val RESTORED_SOURCE_DIRECTIVES = setOf(
            PreprocessorDirectiveKind.EXTENSION,
            PreprocessorDirectiveKind.PRAGMA,
            PreprocessorDirectiveKind.DISABLED_DEFINE,
        )
        private val BLOCK_KINDS = setOf(ShaderAbiKind.UNIFORM_BLOCK, ShaderAbiKind.STORAGE_BLOCK)
    }
}
