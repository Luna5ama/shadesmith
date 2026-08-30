package dev.luna5ama.shadesmith

internal data class CompilerCopyEarlyReturnRejection(
    val function: String,
    val line: Int,
    val reason: String,
)

internal data class CompilerCopyEarlyReturnNormalization(
    val source: String,
    val transformedFunctions: List<String>,
    val rejectedFunctions: List<CompilerCopyEarlyReturnRejection>,
) {
    fun renderReport(): String = buildString {
        appendLine("contract: ${CompilerCopyEarlyReturnNormalizer.CACHE_CONTRACT}")
        appendLine("transformed: ${transformedFunctions.size}")
        transformedFunctions.forEach { appendLine("  $it") }
        appendLine("rejected: ${rejectedFunctions.size}")
        rejectedFunctions.forEach { rejection ->
            appendLine("  ${rejection.function}@${rejection.line}: ${rejection.reason}")
        }
    }
}

/**
 * Rewrites only loop-free selection trees whose early returns can be represented as a single
 * final return. The compiler copy is disposable; the original source remains the restoration
 * contract.
 */
internal object CompilerCopyEarlyReturnNormalizer {
    const val CACHE_CONTRACT = "compiler-copy-early-return-v1"

    fun normalize(source: String): CompilerCopyEarlyReturnNormalization {
        val functions = scanFunctions(source)
        if (functions.isEmpty()) {
            return CompilerCopyEarlyReturnNormalization(source, emptyList(), emptyList())
        }
        val replacements = mutableListOf<Replacement>()
        val transformed = mutableListOf<String>()
        val rejected = mutableListOf<CompilerCopyEarlyReturnRejection>()
        functions.forEach { function ->
            val body = source.substring(function.bodyStart + 1, function.bodyEnd)
            val maskedBody = maskCode(body)
            val returnCount = RETURN_TOKEN.findAll(maskedBody).count()
            if (returnCount == 0 || returnCount == 1 && hasSingleTrailingReturn(maskedBody)) return@forEach
            val label = "${function.name}@${function.line}"
            val unsafeReason = unsafeReason(body, maskedBody)
            if (unsafeReason != null) {
                rejected += CompilerCopyEarlyReturnRejection(function.name, function.line, unsafeReason)
                return@forEach
            }
            val block = try {
                StatementParser(body).parse()
            } catch (e: ParseFailure) {
                rejected += CompilerCopyEarlyReturnRejection(function.name, function.line, e.message.orEmpty())
                return@forEach
            }
            val flow = try {
                flow(block.statements)
            } catch (e: ParseFailure) {
                rejected += CompilerCopyEarlyReturnRejection(function.name, function.line, e.message.orEmpty())
                return@forEach
            }
            val validationFailure = validate(function, block, flow)
            if (validationFailure != null) {
                rejected += CompilerCopyEarlyReturnRejection(function.name, function.line, validationFailure)
                return@forEach
            }
            val resultName = if (function.returnType == "void") {
                null
            } else {
                uniqueResultName(source)
            }
            val lowered = lowerSequence(block.statements, resultName)
            val replacement = buildString {
                append('{')
                append('\n')
                if (resultName != null) appendLine("    ${function.returnType} $resultName;")
                lowered.forEach { render(it, this, 1) }
                if (resultName == null) {
                    appendLine("    return;")
                } else {
                    appendLine("    return $resultName;")
                }
                append('}')
            }
            replacements += Replacement(function.bodyStart, function.bodyEnd + 1, replacement)
            transformed += label
        }
        val normalized = replacements.sortedByDescending(Replacement::start).fold(source) { current, replacement ->
            current.replaceRange(replacement.start, replacement.end, replacement.value)
        }
        return CompilerCopyEarlyReturnNormalization(normalized, transformed, rejected)
    }

    private fun validate(
        function: FunctionSlice,
        block: BlockStatement,
        flow: Flow,
    ): String? {
        if (function.returnType != "void" && !flow.alwaysReturns) {
            return "non-void function does not provably return on every path"
        }
        fun validateReturns(statement: Statement): String? {
            when (statement) {
                is ReturnStatement -> {
                    if (function.returnType == "void" && statement.expression != null) {
                        return "void function returns a value"
                    }
                    if (function.returnType != "void" && statement.expression == null) {
                        return "non-void function has an empty return"
                    }
                }
                is IfStatement -> {
                    validateReturns(statement.thenBranch)?.let { return it }
                    statement.elseBranch?.let(::validateReturns)?.let { return it }
                }
                is BlockStatement -> statement.statements.forEach { child ->
                    validateReturns(child)?.let { return it }
                }
                is RawStatement -> Unit
            }
            return null
        }
        block.statements.forEach { statement ->
            validateReturns(statement)?.let { return it }
        }
        return validateReducibleSequence(block.statements, hasExternalContinuation = false)
    }

    private fun validateReducibleSequence(
        statements: List<Statement>,
        hasExternalContinuation: Boolean,
    ): String? {
        statements.forEachIndexed { index, statement ->
            val hasContinuation = index < statements.lastIndex || hasExternalContinuation
            when (statement) {
                is IfStatement -> {
                    validateReducibleBranch(statement.thenBranch, hasContinuation, "then")?.let { return it }
                    statement.elseBranch?.let { branch ->
                        validateReducibleBranch(branch, hasContinuation, "else")?.let { return it }
                    }
                }
                is BlockStatement -> if (flow(statement).containsReturn) {
                    return "standalone local scope contains an early return"
                }
                is RawStatement, is ReturnStatement -> Unit
            }
        }
        return null
    }

    private fun validateReducibleBranch(
        statement: Statement,
        hasContinuation: Boolean,
        branchName: String,
    ): String? {
        val branchFlow = flow(statement)
        if (branchFlow.containsReturn && !branchFlow.alwaysReturns && hasContinuation) {
            return "returning $branchName-branch falls through across a local scope"
        }
        if (!branchFlow.containsReturn) return null
        val branchContinuation = hasContinuation && !branchFlow.alwaysReturns
        return when (statement) {
            is BlockStatement -> validateReducibleSequence(statement.statements, branchContinuation)
            is IfStatement -> validateReducibleSequence(listOf(statement), branchContinuation)
            is RawStatement, is ReturnStatement -> null
        }
    }

    private fun lowerSequence(statements: List<Statement>, resultName: String?): List<OutputStatement> {
        if (statements.isEmpty()) return emptyList()
        val first = statements.first()
        val rest = statements.drop(1)
        return when (first) {
            is ReturnStatement -> {
                check(rest.isEmpty()) { "unreachable statements escaped flow validation" }
                if (resultName == null) emptyList() else listOf(
                    OutputRaw("$resultName = ${requireNotNull(first.expression)};"),
                )
            }
            is RawStatement -> listOf(OutputRaw(first.raw)) + lowerSequence(rest, resultName)
            is BlockStatement -> {
                check(!flow(first).containsReturn)
                listOf(OutputRaw(first.raw)) + lowerSequence(rest, resultName)
            }
            is IfStatement -> {
                val statementFlow = flow(first)
                if (!statementFlow.containsReturn) {
                    listOf(OutputRaw(first.raw)) + lowerSequence(rest, resultName)
                } else {
                    val continuation = lowerSequence(rest, resultName)
                    val thenFlow = flow(first.thenBranch)
                    val thenOutput = lowerBranch(first.thenBranch, thenFlow, continuation, resultName)
                    val elseFlow = first.elseBranch?.let(::flow) ?: Flow()
                    val elseOutput = if (first.elseBranch == null) {
                        continuation
                    } else {
                        lowerBranch(first.elseBranch, elseFlow, continuation, resultName)
                    }
                    listOf(OutputIf(first.condition, thenOutput, elseOutput))
                }
            }
        }
    }

    private fun lowerBranch(
        statement: Statement,
        branchFlow: Flow,
        continuation: List<OutputStatement>,
        resultName: String?,
    ): List<OutputStatement> {
        if (branchFlow.alwaysReturns) return lowerStatement(statement, resultName)
        if (branchFlow.containsReturn) {
            check(continuation.isEmpty()) { "non-reducible branch escaped validation" }
            return lowerStatement(statement, resultName)
        }
        if (continuation.isEmpty()) return listOf(OutputRaw(statement.raw))
        return listOf(OutputBlock(listOf(OutputRaw(statement.raw)))) + continuation
    }

    private fun lowerStatement(statement: Statement, resultName: String?): List<OutputStatement> {
        return when (statement) {
            is BlockStatement -> lowerSequence(statement.statements, resultName)
            is ReturnStatement -> if (resultName == null) emptyList() else listOf(
                OutputRaw("$resultName = ${requireNotNull(statement.expression)};"),
            )
            is IfStatement -> lowerSequence(listOf(statement), resultName)
            is RawStatement -> error("raw statement cannot terminate a branch")
        }
    }

    private fun render(statement: OutputStatement, output: StringBuilder, indent: Int) {
        val prefix = "    ".repeat(indent)
        when (statement) {
            is OutputRaw -> {
                val text = statement.text.trim().trimIndent()
                if (text.isNotEmpty()) text.lineSequence().forEach { output.appendLine(prefix + it) }
            }
            is OutputBlock -> {
                output.appendLine("$prefix{")
                statement.statements.forEach { render(it, output, indent + 1) }
                output.appendLine("$prefix}")
            }
            is OutputIf -> {
                output.appendLine("$prefix${"if"} (${statement.condition.trim()}) {")
                statement.thenBranch.forEach { render(it, output, indent + 1) }
                output.appendLine("$prefix} else {")
                statement.elseBranch.forEach { render(it, output, indent + 1) }
                output.appendLine("$prefix}")
            }
        }
    }

    private fun flow(statement: Statement): Flow {
        return when (statement) {
            is ReturnStatement -> Flow(containsReturn = true, alwaysReturns = true, returnCount = 1)
            is RawStatement -> Flow()
            is BlockStatement -> flow(statement.statements)
            is IfStatement -> {
                val thenFlow = flow(statement.thenBranch)
                val elseFlow = statement.elseBranch?.let(::flow) ?: Flow()
                Flow(
                    containsReturn = thenFlow.containsReturn || elseFlow.containsReturn,
                    alwaysReturns = statement.elseBranch != null && thenFlow.alwaysReturns && elseFlow.alwaysReturns,
                    returnCount = thenFlow.returnCount + elseFlow.returnCount,
                )
            }
        }
    }

    private fun flow(statements: List<Statement>): Flow {
        var containsReturn = false
        var alwaysReturns = false
        var returnCount = 0
        statements.forEach { statement ->
            if (alwaysReturns) throw ParseFailure("unreachable statement follows a complete return")
            val statementFlow = flow(statement)
            containsReturn = containsReturn || statementFlow.containsReturn
            returnCount += statementFlow.returnCount
            alwaysReturns = statementFlow.alwaysReturns
        }
        return Flow(containsReturn, alwaysReturns, returnCount)
    }

    private fun unsafeReason(body: String, maskedBody: String): String? {
        if (PREPROCESSOR_DIRECTIVE.containsMatchIn(body)) return "preprocessor boundary inside function"
        UNSAFE_CONTROL.find(maskedBody)?.let { return "unsupported control flow '${it.value}'" }
        UNSAFE_EFFECT.find(maskedBody)?.let { return "unsupported control effect '${it.value}'" }
        UNSAFE_BARRIER.find(maskedBody)?.let { return "barrier or memory-barrier call '${it.value.trim()}'" }
        return null
    }

    private fun hasSingleTrailingReturn(maskedBody: String): Boolean {
        val match = RETURN_TOKEN.find(maskedBody) ?: return false
        var braceDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        for (index in 0 until match.range.first) {
            when (maskedBody[index]) {
                '{' -> braceDepth++
                '}' -> braceDepth--
                '(' -> parenDepth++
                ')' -> parenDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
            }
        }
        if (braceDepth != 0 || parenDepth != 0 || bracketDepth != 0) return false
        var index = match.range.last + 1
        while (index < maskedBody.length) {
            when (maskedBody[index]) {
                '(' -> parenDepth++
                ')' -> parenDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ';' -> if (parenDepth == 0 && bracketDepth == 0) {
                    return maskedBody.substring(index + 1).all(Char::isWhitespace)
                }
            }
            index++
        }
        return false
    }

    private fun uniqueResultName(source: String): String {
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "_sm_early_return_value" else "_sm_early_return_value_$suffix"
            if (!Regex("\\b${Regex.escape(candidate)}\\b").containsMatchIn(source)) return candidate
            suffix++
        }
    }

    private fun scanFunctions(source: String): List<FunctionSlice> {
        val masked = maskCode(source)
        val result = mutableListOf<FunctionSlice>()
        var boundary = 0
        var index = 0
        while (index < masked.length) {
            when (masked[index]) {
                ';' -> {
                    boundary = index + 1
                    index++
                }
                '{' -> {
                    val close = findMatching(masked, index, '{', '}') ?: break
                    parseFunctionHeader(source, masked, boundary, index, close)?.let(result::add)
                    boundary = close + 1
                    index = close + 1
                }
                else -> index++
            }
        }
        return result
    }

    private fun parseFunctionHeader(
        source: String,
        masked: String,
        boundary: Int,
        bodyStart: Int,
        bodyEnd: Int,
    ): FunctionSlice? {
        var closeParen = bodyStart - 1
        while (closeParen >= boundary && masked[closeParen].isWhitespace()) closeParen--
        if (closeParen < boundary || masked[closeParen] != ')') return null
        val openParen = findMatchingBackward(masked, closeParen, '(', ')') ?: return null
        var nameEnd = openParen - 1
        while (nameEnd >= boundary && masked[nameEnd].isWhitespace()) nameEnd--
        if (nameEnd < boundary || !masked[nameEnd].isIdentifierPart()) return null
        var nameStart = nameEnd
        while (nameStart > boundary && masked[nameStart - 1].isIdentifierPart()) nameStart--
        val name = source.substring(nameStart, nameEnd + 1)
        if (name in NON_FUNCTION_NAMES) return null
        val prefix = masked.substring(boundary, nameStart).trim()
        val returnType = IDENTIFIER.findAll(prefix).lastOrNull()?.value ?: return null
        if (prefix.contains('=') || prefix.contains('[') || prefix.contains(']')) return null
        return FunctionSlice(
            name = name,
            returnType = returnType,
            bodyStart = bodyStart,
            bodyEnd = bodyEnd,
            line = source.substring(0, bodyStart).count { character -> character == '\n' } + 1,
        )
    }

    private fun maskCode(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var lineLeadingWhitespace = true
        fun blank(position: Int) {
            if (chars[position] != '\n' && chars[position] != '\r') chars[position] = ' '
        }
        while (index < chars.size) {
            val character = chars[index]
            if (character == '\n' || character == '\r') {
                lineLeadingWhitespace = true
                index++
                continue
            }
            if (lineLeadingWhitespace && (character == ' ' || character == '\t')) {
                index++
                continue
            }
            if (lineLeadingWhitespace && character == '#') {
                do {
                    var lastCode = index
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        if (!chars[index].isWhitespace()) lastCode = index
                        blank(index++)
                    }
                    val continued = source[lastCode] == '\\'
                    while (index < chars.size && (chars[index] == '\n' || chars[index] == '\r')) index++
                    lineLeadingWhitespace = true
                    if (!continued) break
                } while (index < chars.size)
                continue
            }
            lineLeadingWhitespace = false
            if (character == '/' && index + 1 < chars.size && chars[index + 1] == '/') {
                while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') blank(index++)
                continue
            }
            if (character == '/' && index + 1 < chars.size && chars[index + 1] == '*') {
                blank(index++)
                blank(index++)
                while (index < chars.size) {
                    if (index + 1 < chars.size && chars[index] == '*' && chars[index + 1] == '/') {
                        blank(index++)
                        blank(index++)
                        break
                    }
                    blank(index++)
                }
                continue
            }
            if (character == '"' || character == '\'') {
                val quote = character
                blank(index++)
                var escaped = false
                while (index < chars.size) {
                    val current = chars[index]
                    blank(index++)
                    if (escaped) {
                        escaped = false
                    } else if (current == '\\') {
                        escaped = true
                    } else if (current == quote) {
                        break
                    }
                }
                continue
            }
            index++
        }
        return chars.concatToString()
    }

    private fun findMatching(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                open -> depth++
                close -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun findMatchingBackward(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (index in start downTo 0) {
            when (source[index]) {
                close -> depth++
                open -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun Char.isIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()

    private data class FunctionSlice(
        val name: String,
        val returnType: String,
        val bodyStart: Int,
        val bodyEnd: Int,
        val line: Int,
    )

    private data class Replacement(val start: Int, val end: Int, val value: String)
    private data class Flow(
        val containsReturn: Boolean = false,
        val alwaysReturns: Boolean = false,
        val returnCount: Int = 0,
    )

    private sealed interface Statement {
        val raw: String
    }

    private data class RawStatement(override val raw: String) : Statement
    private data class ReturnStatement(val expression: String?, override val raw: String) : Statement
    private data class IfStatement(
        val condition: String,
        val thenBranch: Statement,
        val elseBranch: Statement?,
        override val raw: String,
    ) : Statement
    private data class BlockStatement(val statements: List<Statement>, override val raw: String) : Statement

    private sealed interface OutputStatement
    private data class OutputRaw(val text: String) : OutputStatement
    private data class OutputBlock(val statements: List<OutputStatement>) : OutputStatement
    private data class OutputIf(
        val condition: String,
        val thenBranch: List<OutputStatement>,
        val elseBranch: List<OutputStatement>,
    ) : OutputStatement

    private class ParseFailure(message: String) : IllegalArgumentException(message)

    private class StatementParser(private val source: String) {
        private val masked = maskCode(source)
        private var index = 0

        fun parse(): BlockStatement {
            val statements = parseStatements(null)
            skipWhitespace()
            if (index != masked.length) throw ParseFailure("unexpected token at compiler-copy offset $index")
            return BlockStatement(statements, source)
        }

        private fun parseStatements(terminator: Char?): List<Statement> {
            val statements = mutableListOf<Statement>()
            while (true) {
                skipWhitespace()
                if (index >= masked.length) {
                    if (terminator != null) throw ParseFailure("unterminated block")
                    break
                }
                if (terminator != null && masked[index] == terminator) break
                statements += parseStatement()
            }
            return statements
        }

        private fun parseStatement(): Statement {
            skipWhitespace()
            return when {
                index < masked.length && masked[index] == '{' -> parseBlock()
                matchesKeyword("if") -> parseIf()
                matchesKeyword("return") -> parseReturn()
                else -> parseRaw()
            }
        }

        private fun parseBlock(): BlockStatement {
            val start = index
            index++
            val statements = parseStatements('}')
            if (index >= masked.length || masked[index] != '}') throw ParseFailure("unterminated block")
            index++
            return BlockStatement(statements, source.substring(start, index))
        }

        private fun parseIf(): IfStatement {
            val start = index
            index += 2
            skipWhitespace()
            if (index >= masked.length || masked[index] != '(') throw ParseFailure("if without a parenthesized condition")
            val conditionStart = index + 1
            val conditionEnd = findMatching(masked, index, '(', ')')
                ?: throw ParseFailure("unterminated if condition")
            val condition = source.substring(conditionStart, conditionEnd)
            index = conditionEnd + 1
            val thenBranch = parseStatement()
            skipWhitespace()
            val elseBranch = if (matchesKeyword("else")) {
                index += 4
                parseStatement()
            } else {
                null
            }
            return IfStatement(condition, thenBranch, elseBranch, source.substring(start, index))
        }

        private fun parseReturn(): ReturnStatement {
            val start = index
            index += 6
            val expressionStart = index
            var parenDepth = 0
            var bracketDepth = 0
            while (index < masked.length) {
                when (masked[index]) {
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                    '{', '}' -> if (parenDepth == 0 && bracketDepth == 0) {
                        throw ParseFailure("return crosses a structural boundary")
                    }
                    ';' -> if (parenDepth == 0 && bracketDepth == 0) {
                        val expression = source.substring(expressionStart, index).trim().takeIf(String::isNotEmpty)
                        index++
                        return ReturnStatement(expression, source.substring(start, index))
                    }
                }
                index++
            }
            throw ParseFailure("unterminated return statement")
        }

        private fun parseRaw(): RawStatement {
            val start = index
            var parenDepth = 0
            var bracketDepth = 0
            while (index < masked.length) {
                when (masked[index]) {
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                    '{', '}' -> if (parenDepth == 0 && bracketDepth == 0) {
                        throw ParseFailure("unsupported compound statement")
                    }
                    ';' -> if (parenDepth == 0 && bracketDepth == 0) {
                        index++
                        return RawStatement(source.substring(start, index))
                    }
                }
                index++
            }
            throw ParseFailure("unterminated complete statement")
        }

        private fun skipWhitespace() {
            while (index < masked.length && masked[index].isWhitespace()) index++
        }

        private fun matchesKeyword(keyword: String): Boolean {
            if (!masked.regionMatches(index, keyword, 0, keyword.length)) return false
            val before = masked.getOrNull(index - 1)
            val after = masked.getOrNull(index + keyword.length)
            return before?.isIdentifierPart() != true && after?.isIdentifierPart() != true
        }
    }

    private val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    private val RETURN_TOKEN = "\\breturn\\b".toRegex()
    private val PREPROCESSOR_DIRECTIVE = "(?m)^[\\t ]*#".toRegex()
    private val UNSAFE_CONTROL = "\\b(?:for|while|do|switch|case|default|break|continue)\\b".toRegex()
    private val UNSAFE_EFFECT = "\\b(?:discard|demote|terminateInvocation)\\b".toRegex()
    private val UNSAFE_BARRIER =
        "\\b(?:barrier|memoryBarrier[A-Za-z0-9_]*|groupMemoryBarrier)\\s*\\(".toRegex()
    private val NON_FUNCTION_NAMES = setOf("if", "for", "while", "switch")
}
