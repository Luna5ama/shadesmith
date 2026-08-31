package dev.luna5ama.shadesmith

internal enum class PreprocessorDirectiveKind {
    DEFINE,
    UNDEF,
    IF,
    IFDEF,
    IFNDEF,
    ELIF,
    ELSE,
    ENDIF,
    VERSION,
    EXTENSION,
    PRAGMA,
    LINE,
    ERROR,
    WARNING,
    INCLUDE,
    DISABLED_DEFINE,
}

internal enum class PreprocessorDisposition {
    EVALUATED,
    RESTORED,
    REJECTED,
}

internal enum class PreprocessorFeature {
    OBJECT_LIKE_MACRO,
    FUNCTION_LIKE_MACRO,
    TOKEN_PASTE,
    STRINGIFY,
    IDENTIFIER_ALIAS,
    RESOURCE_ALIAS,
    TYPE_OR_QUALIFIER,
    SETTING,
    WORKGROUP_LITERAL,
    CONDITIONAL,
    DEFINED_EXPRESSION,
    NUMERIC_EXPRESSION,
    NESTED_CONDITIONAL,
    BLOCK_SCOPE,
    DECLARATION_SHAPE,
    INCLUDE_GUARD,
    DISABLED_OPTION,
    CONST_SPECIALIZATION,
}

internal data class PreprocessorDirective(
    val index: Int,
    val kind: PreprocessorDirectiveKind,
    val disposition: PreprocessorDisposition,
    val sourceLine: Int,
    val endLine: Int,
    val conditionalDepth: Int,
    val conditionalId: Int?,
    val exactText: String,
    val macroName: String?,
    val macroBody: String?,
    val macroFunctionLike: Boolean,
    val expression: String?,
    val braceDepth: Int,
    val features: Set<PreprocessorFeature>,
)

internal data class PreprocessorRestoration(
    val directiveIndex: Int,
    val sourceLine: Int,
    val compilerText: String,
    val originalText: String,
)

internal data class PreprocessorCompilerBlocker(
    val directiveIndex: Int,
    val sourceLine: Int,
    val reason: String,
)

internal class PreprocessorProtectionException(
    val sourceName: String,
    val sourceLine: Int,
    val reason: String,
    val disposition: PreprocessorDisposition = PreprocessorDisposition.REJECTED,
) : IllegalArgumentException("$sourceName:$sourceLine: $reason")

/**
 * A lossless intermediate representation, not an implicit default-setting shader.
 *
 * [compilerRepresentation] masks configurable directives while retaining every branch body. Call
 * [compilerSource] before passing it to a compiler: it rejects contracts which still require an
 * explicit compiler-copy or structural-planning step.
 */
internal data class ProtectedPreprocessorSource(
    val sourceName: String,
    val originalSource: String,
    val compilerRepresentation: String,
    val directives: List<PreprocessorDirective>,
    val restorations: List<PreprocessorRestoration>,
    val compilerBlockers: List<PreprocessorCompilerBlocker>,
) {
    fun restore(source: String = compilerRepresentation): String {
        var result = source
        restorations.forEach { restoration ->
            val first = result.indexOf(restoration.compilerText)
            if (first < 0) {
                throw PreprocessorProtectionException(
                    sourceName,
                    restoration.sourceLine,
                    "protected preprocessor placeholder is missing during restoration",
                )
            }
            if (result.indexOf(restoration.compilerText, first + restoration.compilerText.length) >= 0) {
                throw PreprocessorProtectionException(
                    sourceName,
                    restoration.sourceLine,
                    "protected preprocessor placeholder is duplicated during restoration",
                )
            }
            result = result.replaceRange(
                first,
                first + restoration.compilerText.length,
                restoration.originalText,
            )
        }
        return result
    }

    fun compilerSource(): String {
        compilerBlockers.firstOrNull()?.let {
            throw PreprocessorProtectionException(sourceName, it.sourceLine, it.reason)
        }
        return compilerRepresentation
    }
}

internal object PreprocessorProtection {
    private const val CONST_MARKER = "/*const*/"
    private val cache = object : LinkedHashMap<ProtectionCacheKey, ProtectedPreprocessorSource>(16, 0.75f, true) {}
    private var cacheCharacters = 0L

    fun protect(source: String, sourceName: String = "<shader>"): ProtectedPreprocessorSource {
        return protectCached(source, sourceName, evaluateCompilerDirectives = false)
    }

    fun protectGeneratedCompilerSource(
        source: String,
        sourceName: String = "<shader>",
    ): ProtectedPreprocessorSource {
        return protectCached(source, sourceName, evaluateCompilerDirectives = true)
    }

    private fun protectCached(
        source: String,
        sourceName: String,
        evaluateCompilerDirectives: Boolean,
    ): ProtectedPreprocessorSource {
        val key = ProtectionCacheKey(source, evaluateCompilerDirectives)
        synchronized(cache) {
            cache[key]?.let { return it.copy(sourceName = sourceName) }
        }
        val computed = protectUncached(source, sourceName, evaluateCompilerDirectives)
        synchronized(cache) {
            cache[key]?.let { return it.copy(sourceName = sourceName) }
            cache[key] = computed
            cacheCharacters += key.characterWeight
            val iterator = cache.entries.iterator()
            while (cacheCharacters > CACHE_CHARACTER_BUDGET && iterator.hasNext()) {
                cacheCharacters -= iterator.next().key.characterWeight
                iterator.remove()
            }
        }
        return computed
    }

    private fun protectUncached(
        source: String,
        sourceName: String,
        evaluateCompilerDirectives: Boolean,
    ): ProtectedPreprocessorSource {
        require(sourceName.isNotBlank()) { "Preprocessor source name cannot be blank" }

        val lines = lexPhysicalLines(source)
        val drafts = mutableListOf<DirectiveDraft>()
        val restorations = mutableListOf<PreprocessorRestoration>()
        val conditionals = mutableListOf<ConditionalFrame>()
        val representation = StringBuilder(source.length)
        val placeholderNamespace = findPlaceholderNamespace(source)
        var nextConditionalId = 0
        val constRegions = mutableListOf<ConstRegion>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.directiveStart == null && line.content.trim() == CONST_MARKER) {
                val openRegion = constRegions.lastOrNull()
                val currentPath = conditionals.map { it.id }
                if (openRegion == null) {
                    constRegions += ConstRegion(line.number, currentPath)
                } else if (currentPath == openRegion.conditionalPath) {
                    constRegions.removeAt(constRegions.lastIndex)
                } else if (
                    currentPath.size > openRegion.conditionalPath.size &&
                    currentPath.take(openRegion.conditionalPath.size) == openRegion.conditionalPath
                ) {
                    constRegions += ConstRegion(line.number, currentPath)
                } else {
                    reject(
                        sourceName,
                        line.number,
                        "/*const*/ region from line ${openRegion.sourceLine} crosses a conditional boundary",
                    )
                }
                representation.append(line.fullText)
                index++
                continue
            }

            val directiveStart = line.directiveStart
            if (directiveStart == null) {
                representation.append(line.fullText)
                index++
                continue
            }

            var endIndex = index
            while (lines[endIndex].continuesDirective) {
                if (endIndex + 1 >= lines.size) {
                    reject(sourceName, line.number, "preprocessor line continuation has no following line")
                }
                endIndex++
            }
            val directiveLines = lines.subList(index, endIndex + 1)
            val exactText = directiveLines.joinToString("") { it.fullText }
            val parsed = parseDirective(exactText, directiveStart, sourceName, line.number)
            val inConstRegion = constRegions.isNotEmpty()
            val disposition = if (
                (inConstRegion || evaluateCompilerDirectives) &&
                parsed.kind != PreprocessorDirectiveKind.DISABLED_DEFINE
            ) {
                PreprocessorDisposition.EVALUATED
            } else {
                PreprocessorDisposition.RESTORED
            }

            val conditionalState = updateConditionalState(
                parsed,
                disposition,
                conditionals,
                nextConditionalId,
                sourceName,
                line.number,
            )
            if (parsed.kind in CONDITIONAL_OPENERS) nextConditionalId++

            val features = classifyFeatures(
                parsed,
                disposition,
                conditionalState.depth,
                line.braceDepth,
            )
            val draft = DirectiveDraft(
                index = drafts.size,
                kind = parsed.kind,
                disposition = disposition,
                sourceLine = line.number,
                endLine = directiveLines.last().number,
                conditionalDepth = conditionalState.depth,
                conditionalId = conditionalState.id,
                exactText = exactText,
                macroName = parsed.macro?.name,
                macroBody = parsed.macro?.body,
                macroFunctionLike = parsed.macro?.functionLike == true,
                expression = parsed.expression,
                braceDepth = line.braceDepth,
                features = features.toMutableSet(),
            )
            drafts += draft

            if (shouldMask(draft)) {
                val compilerText = maskDirective(placeholderNamespace, draft.index, directiveLines)
                representation.append(compilerText)
                restorations += PreprocessorRestoration(
                    directiveIndex = draft.index,
                    sourceLine = draft.sourceLine,
                    compilerText = compilerText,
                    originalText = exactText,
                )
            } else {
                representation.append(exactText)
            }

            index = endIndex + 1
        }

        conditionals.lastOrNull()?.let {
            reject(sourceName, it.sourceLine, "conditional directive has no matching #endif")
        }
        constRegions.lastOrNull()?.let {
            reject(sourceName, it.sourceLine, "/*const*/ region has no closing marker")
        }

        markIncludeGuards(drafts)
        val directives = drafts.map { it.freeze() }
        val blockers = buildCompilerBlockers(directives)
        return ProtectedPreprocessorSource(
            sourceName = sourceName,
            originalSource = source,
            compilerRepresentation = representation.toString(),
            directives = directives,
            restorations = restorations,
            compilerBlockers = blockers,
        )
    }

    private data class ProtectionCacheKey(
        val source: String,
        val evaluateCompilerDirectives: Boolean,
    ) {
        val characterWeight: Long = source.length.toLong() * 3L
    }

    private const val CACHE_CHARACTER_BUDGET = 32L * 1024L * 1024L

    private fun updateConditionalState(
        directive: ParsedDirective,
        disposition: PreprocessorDisposition,
        stack: MutableList<ConditionalFrame>,
        nextId: Int,
        sourceName: String,
        sourceLine: Int,
    ): ConditionalState {
        return when (directive.kind) {
            in CONDITIONAL_OPENERS -> {
                val state = ConditionalState(nextId, stack.size)
                stack += ConditionalFrame(nextId, sourceLine, disposition)
                state
            }

            PreprocessorDirectiveKind.ELIF -> {
                val frame = stack.lastOrNull()
                    ?: reject(sourceName, sourceLine, "#elif has no matching conditional opener")
                requireSameDisposition(frame, disposition, sourceName, sourceLine)
                if (frame.hasElse) {
                    reject(sourceName, sourceLine, "#elif cannot follow #else from line ${frame.sourceLine}")
                }
                ConditionalState(frame.id, stack.lastIndex)
            }

            PreprocessorDirectiveKind.ELSE -> {
                val frame = stack.lastOrNull()
                    ?: reject(sourceName, sourceLine, "#else has no matching conditional opener")
                requireSameDisposition(frame, disposition, sourceName, sourceLine)
                if (frame.hasElse) {
                    reject(sourceName, sourceLine, "conditional from line ${frame.sourceLine} has multiple #else branches")
                }
                frame.hasElse = true
                ConditionalState(frame.id, stack.lastIndex)
            }

            PreprocessorDirectiveKind.ENDIF -> {
                val frame = stack.lastOrNull()
                    ?: reject(sourceName, sourceLine, "#endif has no matching conditional opener")
                requireSameDisposition(frame, disposition, sourceName, sourceLine)
                stack.removeAt(stack.lastIndex)
                ConditionalState(frame.id, stack.size)
            }

            else -> ConditionalState(stack.lastOrNull()?.id, stack.size)
        }
    }

    private fun requireSameDisposition(
        frame: ConditionalFrame,
        disposition: PreprocessorDisposition,
        sourceName: String,
        sourceLine: Int,
    ) {
        if (frame.disposition != disposition) {
            reject(
                sourceName,
                sourceLine,
                "conditional crosses a /*const*/ boundary opened at line ${frame.sourceLine}",
            )
        }
    }

    private fun parseDirective(
        exactText: String,
        start: DirectiveStart,
        sourceName: String,
        sourceLine: Int,
    ): ParsedDirective {
        val spliced = exactText
            .replace("\\\r\n", "")
            .replace("\\\n", "")
            .replace("\\\r", "")
        val match = (
            if (start.disabled) DISABLED_DEFINE_REGEX.find(spliced) else DIRECTIVE_REGEX.find(spliced)
            ) ?: reject(sourceName, sourceLine, "malformed preprocessor directive")
        val keyword = match.groupValues[1].lowercase()
        val rest = spliced.substring(match.range.last + 1)
        val kind = if (start.disabled) {
            PreprocessorDirectiveKind.DISABLED_DEFINE
        } else {
            DIRECTIVE_KINDS[keyword]
                ?: reject(sourceName, sourceLine, "unsupported preprocessor directive #$keyword")
        }

        if (kind == PreprocessorDirectiveKind.DISABLED_DEFINE) {
            return ParsedDirective(kind, parseMacro(rest, sourceName, sourceLine, required = false), null)
        }
        if (kind == PreprocessorDirectiveKind.DEFINE) {
            return ParsedDirective(kind, parseMacro(rest, sourceName, sourceLine, required = true), null)
        }
        if (kind == PreprocessorDirectiveKind.INCLUDE) {
            reject(sourceName, sourceLine, "#include must be expanded before preprocessor protection")
        }

        val expression = rest.trim()
        when (kind) {
            PreprocessorDirectiveKind.IF,
            PreprocessorDirectiveKind.ELIF,
            -> if (expression.isEmpty()) reject(sourceName, sourceLine, "#$keyword requires an expression")

            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            PreprocessorDirectiveKind.UNDEF,
            -> if (!IDENTIFIER_ONLY.matches(expression.substringBefore("//").trim())) {
                reject(sourceName, sourceLine, "#$keyword requires one identifier, got '$expression'")
            }

            else -> Unit
        }

        val macro = when (kind) {
            PreprocessorDirectiveKind.IFDEF,
            PreprocessorDirectiveKind.IFNDEF,
            PreprocessorDirectiveKind.UNDEF,
            -> MacroDescriptor(expression.substringBefore("//").trim(), false, "")

            else -> null
        }
        return ParsedDirective(kind, macro, expression)
    }

    private fun parseMacro(
        rest: String,
        sourceName: String,
        sourceLine: Int,
        required: Boolean,
    ): MacroDescriptor? {
        val text = rest.trimStart()
        val nameMatch = IDENTIFIER.find(text)
        if (nameMatch == null || nameMatch.range.first != 0) {
            if (!required) return null
            reject(sourceName, sourceLine, "#define requires a macro name")
        }

        val name = nameMatch.value
        var cursor = name.length
        val functionLike = cursor < text.length && text[cursor] == '('
        if (functionLike) {
            var depth = 0
            var closed = false
            while (cursor < text.length) {
                when (text[cursor]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            cursor++
                            closed = true
                            break
                        }
                    }
                }
                cursor++
            }
            if (!closed) reject(sourceName, sourceLine, "function-like macro $name has no closing parenthesis")
        }

        return MacroDescriptor(name, functionLike, text.substring(cursor).trim())
    }

    private fun classifyFeatures(
        directive: ParsedDirective,
        disposition: PreprocessorDisposition,
        conditionalDepth: Int,
        braceDepth: Int,
    ): Set<PreprocessorFeature> {
        return buildSet {
            if (disposition == PreprocessorDisposition.EVALUATED) {
                add(PreprocessorFeature.CONST_SPECIALIZATION)
            }
            if (directive.kind == PreprocessorDirectiveKind.DISABLED_DEFINE) {
                add(PreprocessorFeature.DISABLED_OPTION)
            }
            if (directive.kind in CONDITIONAL_KINDS) {
                add(PreprocessorFeature.CONDITIONAL)
                if (conditionalDepth > 0) add(PreprocessorFeature.NESTED_CONDITIONAL)
                if (braceDepth > 0) {
                    add(PreprocessorFeature.BLOCK_SCOPE)
                } else {
                    add(PreprocessorFeature.DECLARATION_SHAPE)
                }
                val expression = directive.expression.orEmpty()
                if (DEFINED_EXPRESSION.containsMatchIn(expression)) {
                    add(PreprocessorFeature.DEFINED_EXPRESSION)
                }
                if (NUMERIC_EXPRESSION.containsMatchIn(expression)) {
                    add(PreprocessorFeature.NUMERIC_EXPRESSION)
                }
                if (SETTING_NAME.containsMatchIn(expression)) add(PreprocessorFeature.SETTING)
            }

            directive.macro?.let { macro ->
                add(
                    if (macro.functionLike) PreprocessorFeature.FUNCTION_LIKE_MACRO
                    else PreprocessorFeature.OBJECT_LIKE_MACRO,
                )
                if (macro.name.startsWith("SETTING_")) add(PreprocessorFeature.SETTING)
                if (macro.body.contains("##")) add(PreprocessorFeature.TOKEN_PASTE)
                if (STRINGIFY_OPERATOR.containsMatchIn(macro.body)) add(PreprocessorFeature.STRINGIFY)

                val bodyToken = macro.body.substringBefore("//").trim()
                if (!macro.functionLike && IDENTIFIER_ONLY.matches(bodyToken)) {
                    add(PreprocessorFeature.IDENTIFIER_ALIAS)
                    if (RESOURCE_NAME.containsMatchIn(macro.name) || RESOURCE_NAME.containsMatchIn(bodyToken)) {
                        add(PreprocessorFeature.RESOURCE_ALIAS)
                    }
                    if (TYPE_OR_QUALIFIER.matches(bodyToken)) add(PreprocessorFeature.TYPE_OR_QUALIFIER)
                }
                if (WORKGROUP_NAME.containsMatchIn(macro.name) || WORKGROUP_NAME.containsMatchIn(macro.body)) {
                    add(PreprocessorFeature.WORKGROUP_LITERAL)
                }
            }
        }
    }

    private fun shouldMask(directive: DirectiveDraft): Boolean {
        if (directive.disposition == PreprocessorDisposition.EVALUATED) return false
        return directive.kind !in COMPILER_VISIBLE_DIRECTIVES
    }

    private fun maskDirective(
        namespace: String,
        directiveIndex: Int,
        lines: List<PhysicalLine>,
    ): String {
        return buildString {
            lines.forEachIndexed { physicalIndex, line ->
                append("//$namespace")
                append(directiveIndex.toString().padStart(6, '0'))
                if (physicalIndex > 0) {
                    append("_CONT_")
                    append(physicalIndex)
                }
                append("__")
                append(line.ending)
            }
        }
    }

    private fun markIncludeGuards(directives: List<DirectiveDraft>) {
        directives.filter {
            it.kind == PreprocessorDirectiveKind.IFNDEF &&
                it.conditionalDepth == 0 &&
                it.macroName != null
        }.forEach { opener ->
            val firstInside = directives.firstOrNull {
                it.index > opener.index &&
                    it.conditionalId == opener.conditionalId &&
                    it.conditionalDepth == 1
            }
            val closing = directives.firstOrNull {
                it.index > opener.index &&
                    it.conditionalId == opener.conditionalId &&
                    it.kind == PreprocessorDirectiveKind.ENDIF
            }
            if (
                firstInside?.kind == PreprocessorDirectiveKind.DEFINE &&
                firstInside.macroName == opener.macroName &&
                closing != null
            ) {
                opener.features += PreprocessorFeature.INCLUDE_GUARD
                firstInside.features += PreprocessorFeature.INCLUDE_GUARD
                closing.features += PreprocessorFeature.INCLUDE_GUARD
            }
        }
    }

    private fun buildCompilerBlockers(
        directives: List<PreprocessorDirective>,
    ): List<PreprocessorCompilerBlocker> {
        return directives.mapNotNull { directive ->
            if (directive.disposition != PreprocessorDisposition.RESTORED) return@mapNotNull null
            if (directive.kind == PreprocessorDirectiveKind.DISABLED_DEFINE) return@mapNotNull null
            val reason = when {
                directive.kind in CONDITIONAL_OPENERS &&
                    PreprocessorFeature.DECLARATION_SHAPE in directive.features ->
                    "top-level conditional can change GLSL declaration shape; structural compiler planning is required"

                directive.kind in CONDITIONAL_OPENERS ->
                    "conditional branch inside GLSL code requires compiler-copy control-flow lowering"

                PreprocessorFeature.TOKEN_PASTE in directive.features ->
                    "token-paste macro ${directive.macroName} cannot be replaced by a const variable"

                PreprocessorFeature.RESOURCE_ALIAS in directive.features ->
                    "resource identifier macro ${directive.macroName} requires token-preserving materialization"

                PreprocessorFeature.TYPE_OR_QUALIFIER in directive.features ->
                    "type or qualifier macro ${directive.macroName} cannot be replaced by a const variable"

                directive.kind == PreprocessorDirectiveKind.DEFINE ->
                    "preserved macro ${directive.macroName} requires explicit compiler materialization"

                directive.kind == PreprocessorDirectiveKind.UNDEF ->
                    "#undef requires explicit compiler materialization"

                directive.kind == PreprocessorDirectiveKind.ERROR ||
                    directive.kind == PreprocessorDirectiveKind.WARNING ->
                    "diagnostic directive requires explicit compiler-copy planning"

                else -> null
            }
            reason?.let { PreprocessorCompilerBlocker(directive.index, directive.sourceLine, it) }
        }
    }

    private fun findPlaceholderNamespace(source: String): String {
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "__SHADESMITH_PP_" else "__SHADESMITH_PP${suffix}_"
            if (!source.contains(candidate)) return candidate
            suffix++
        }
    }

    private fun lexPhysicalLines(source: String): List<PhysicalLine> {
        val rawLines = splitPhysicalLines(source)
        val result = ArrayList<PhysicalLine>(rawLines.size)
        var blockComment = false
        var braceDepth = 0
        var continuation = false

        rawLines.forEachIndexed { index, raw ->
            val directiveStart = if (!blockComment && !continuation) parseDirectiveStart(raw.content) else null
            result += PhysicalLine(
                number = index + 1,
                content = raw.content,
                ending = raw.ending,
                directiveStart = directiveStart,
                braceDepth = braceDepth,
            )

            val lexicalState = scanLexicalState(
                raw.content,
                blockComment,
                braceDepth,
                ignoreBraces = continuation || directiveStart != null,
            )
            blockComment = lexicalState.blockComment
            braceDepth = lexicalState.braceDepth
            continuation = (continuation || directiveStart != null) &&
                raw.ending.isNotEmpty() && raw.content.endsWith('\\')
        }
        return result
    }

    private fun parseDirectiveStart(content: String): DirectiveStart? {
        DISABLED_DEFINE_REGEX.find(content)?.let {
            return DirectiveStart(disabled = true)
        }
        DIRECTIVE_REGEX.find(content)?.let {
            return DirectiveStart(disabled = false)
        }
        return null
    }

    private fun splitPhysicalLines(source: String): List<RawPhysicalLine> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<RawPhysicalLine>()
        var start = 0
        var cursor = 0
        while (cursor < source.length) {
            val char = source[cursor]
            if (char != '\r' && char != '\n') {
                cursor++
                continue
            }
            val endingLength = if (char == '\r' && cursor + 1 < source.length && source[cursor + 1] == '\n') 2 else 1
            result += RawPhysicalLine(
                content = source.substring(start, cursor),
                ending = source.substring(cursor, cursor + endingLength),
            )
            cursor += endingLength
            start = cursor
        }
        if (start < source.length) {
            result += RawPhysicalLine(source.substring(start), "")
        }
        return result
    }

    private fun scanLexicalState(
        line: String,
        startsInBlockComment: Boolean,
        initialBraceDepth: Int,
        ignoreBraces: Boolean,
    ): LexicalState {
        var blockComment = startsInBlockComment
        var braceDepth = initialBraceDepth
        var quote: Char? = null
        var cursor = 0
        while (cursor < line.length) {
            if (blockComment) {
                if (cursor + 1 < line.length && line[cursor] == '*' && line[cursor + 1] == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor++
                }
                continue
            }

            val currentQuote = quote
            if (currentQuote != null) {
                if (line[cursor] == '\\') {
                    cursor += 2
                } else {
                    if (line[cursor] == currentQuote) quote = null
                    cursor++
                }
                continue
            }

            if (cursor + 1 < line.length && line[cursor] == '/' && line[cursor + 1] == '/') break
            if (cursor + 1 < line.length && line[cursor] == '/' && line[cursor + 1] == '*') {
                blockComment = true
                cursor += 2
                continue
            }
            if (line[cursor] == '"' || line[cursor] == '\'') {
                quote = line[cursor]
                cursor++
                continue
            }
            if (!ignoreBraces) {
                when (line[cursor]) {
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                }
            }
            cursor++
        }
        return LexicalState(blockComment, braceDepth)
    }

    private fun reject(sourceName: String, sourceLine: Int, reason: String): Nothing {
        throw PreprocessorProtectionException(sourceName, sourceLine, reason)
    }

    private val DIRECTIVE_KINDS = mapOf(
        "define" to PreprocessorDirectiveKind.DEFINE,
        "undef" to PreprocessorDirectiveKind.UNDEF,
        "if" to PreprocessorDirectiveKind.IF,
        "ifdef" to PreprocessorDirectiveKind.IFDEF,
        "ifndef" to PreprocessorDirectiveKind.IFNDEF,
        "elif" to PreprocessorDirectiveKind.ELIF,
        "else" to PreprocessorDirectiveKind.ELSE,
        "endif" to PreprocessorDirectiveKind.ENDIF,
        "version" to PreprocessorDirectiveKind.VERSION,
        "extension" to PreprocessorDirectiveKind.EXTENSION,
        "pragma" to PreprocessorDirectiveKind.PRAGMA,
        "line" to PreprocessorDirectiveKind.LINE,
        "error" to PreprocessorDirectiveKind.ERROR,
        "warning" to PreprocessorDirectiveKind.WARNING,
        "include" to PreprocessorDirectiveKind.INCLUDE,
    )

    private val CONDITIONAL_OPENERS = setOf(
        PreprocessorDirectiveKind.IF,
        PreprocessorDirectiveKind.IFDEF,
        PreprocessorDirectiveKind.IFNDEF,
    )
    private val CONDITIONAL_KINDS = CONDITIONAL_OPENERS + setOf(
        PreprocessorDirectiveKind.ELIF,
        PreprocessorDirectiveKind.ELSE,
        PreprocessorDirectiveKind.ENDIF,
    )
    private val COMPILER_VISIBLE_DIRECTIVES = setOf(
        PreprocessorDirectiveKind.VERSION,
        PreprocessorDirectiveKind.EXTENSION,
        PreprocessorDirectiveKind.PRAGMA,
        PreprocessorDirectiveKind.LINE,
        PreprocessorDirectiveKind.DISABLED_DEFINE,
    )

    private val DIRECTIVE_REGEX = """^[\t ]*#[\t ]*([A-Za-z_][A-Za-z0-9_]*)""".toRegex()
    private val DISABLED_DEFINE_REGEX = """^[\t ]*//[\t ]*#[\t ]*(define)\b""".toRegex(RegexOption.IGNORE_CASE)
    private val IDENTIFIER = """[A-Za-z_][A-Za-z0-9_]*""".toRegex()
    private val IDENTIFIER_ONLY = """[A-Za-z_][A-Za-z0-9_]*""".toRegex()
    private val DEFINED_EXPRESSION = """\bdefined\s*(?:\(|[A-Za-z_])""".toRegex()
    private val NUMERIC_EXPRESSION = """(?:\d|==|!=|<=|>=|<|>)""".toRegex()
    private val SETTING_NAME = """\bSETTING_[A-Za-z0-9_]*""".toRegex()
    private val STRINGIFY_OPERATOR = """(?<!#)#(?!#)""".toRegex()
    private val RESOURCE_NAME =
        """(?:sampler|image|texture|colortex|colorimg|depthtex|shadowtex|shadowcolor|usam_|uimg_)"""
            .toRegex(RegexOption.IGNORE_CASE)
    private val TYPE_OR_QUALIFIER =
        """(?:buffer|uniform|readonly|writeonly|coherent|restrict|volatile|shared|attribute|varying|in|out|inout|const|precise|highp|mediump|lowp|void|bool|int|uint|float|double|float16_t|[biud]?vec[234]|d?mat[234](?:x[234])?|f16vec[234]|f16mat[234])"""
            .toRegex()
    private val WORKGROUP_NAME = """(?:WORK_?GROUP|WORKGROUP|workGroups|local_size_)""".toRegex()

    private data class RawPhysicalLine(val content: String, val ending: String)

    private data class PhysicalLine(
        val number: Int,
        val content: String,
        val ending: String,
        val directiveStart: DirectiveStart?,
        val braceDepth: Int,
    ) {
        val fullText: String
            get() = content + ending

        val continuesDirective: Boolean
            get() = ending.isNotEmpty() && content.endsWith('\\')
    }

    private data class DirectiveStart(val disabled: Boolean)
    private data class MacroDescriptor(val name: String, val functionLike: Boolean, val body: String)
    private data class ParsedDirective(
        val kind: PreprocessorDirectiveKind,
        val macro: MacroDescriptor?,
        val expression: String?,
    )

    private data class ConditionalState(val id: Int?, val depth: Int)
    private data class ConstRegion(val sourceLine: Int, val conditionalPath: List<Int>)
    private data class ConditionalFrame(
        val id: Int,
        val sourceLine: Int,
        val disposition: PreprocessorDisposition,
        var hasElse: Boolean = false,
    )

    private data class DirectiveDraft(
        val index: Int,
        val kind: PreprocessorDirectiveKind,
        val disposition: PreprocessorDisposition,
        val sourceLine: Int,
        val endLine: Int,
        val conditionalDepth: Int,
        val conditionalId: Int?,
        val exactText: String,
        val macroName: String?,
        val macroBody: String?,
        val macroFunctionLike: Boolean,
        val expression: String?,
        val braceDepth: Int,
        val features: MutableSet<PreprocessorFeature>,
    ) {
        fun freeze(): PreprocessorDirective {
            return PreprocessorDirective(
                index,
                kind,
                disposition,
                sourceLine,
                endLine,
                conditionalDepth,
                conditionalId,
                exactText,
                macroName,
                macroBody,
                macroFunctionLike,
                expression,
                braceDepth,
                features.toSet(),
            )
        }
    }

    private data class LexicalState(val blockComment: Boolean, val braceDepth: Int)

}
