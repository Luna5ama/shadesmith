package dev.luna5ama.shadesmith

import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension


private val TOKEN_DELIMITER_REGEX = """\s+|(?=[{}()\[\];,.\-!])|(?<=[{}()\[\];,.\-!])""".toRegex()

private val FUNCTION_HEADER_REGEX =
    """^\s*($IDENTIFIER_REGEX_STR)\s+($IDENTIFIER_REGEX_STR)\s*(\([\s\w_,]*?\))\s*\{""".toRegex(RegexOption.MULTILINE)

private val PREPROCESSOR_CONDITIONAL_REGEX =
    """^\s*#\s*(if|ifdef|ifndef|elif|else|endif)\b.*$""".toRegex(RegexOption.MULTILINE)

private val UNIFORM_REGEX =
    """^\s*uniform\s+($IDENTIFIER_REGEX_STR)\s+($IDENTIFIER_REGEX_STR)\s*;.*$""".toRegex(RegexOption.MULTILINE)

private val FUNC_EXCLUDE_PREFIX = listOf("colors_", "voxy_emitFragment")

fun cleanUnused(file: ShaderFile): ShaderFile {
    var newCode = file.code
    var tokenCounts = newCode.split(TOKEN_DELIMITER_REGEX)
        .groupingBy { it }
        .eachCount()

    data class FuncInfo(val name: String, val fullRange: IntRange, val bodyRange: IntRange, val headerRange: IntRange)


    newCode = run {
        val currCode = newCode
        val tokenCountWithoutFuncNameInHeader = tokenCounts.toMutableMap()
        val funcInfo = FUNCTION_HEADER_REGEX.findAll(newCode).map {
            val (_, funcName) = it.destructured
            val endIndex = run {
                data class DepthState(val depth: Int, val conditions: Map<String, Boolean>)
                data class ConditionalFrame(
                    var remainingStates: MutableSet<DepthState>,
                    val branchEndStates: MutableSet<DepthState>,
                    var hasElse: Boolean = false,
                )

                val conditionalStack = ArrayDeque<ConditionalFrame>()
                var depths = mutableSetOf(DepthState(1, emptyMap()))
                var index = it.range.last + 1
                val directives = PREPROCESSOR_CONDITIONAL_REGEX.findAll(newCode, index).iterator()

                fun splitByCondition(
                    states: Set<DepthState>,
                    key: String,
                    requiredValue: Boolean,
                ): Pair<MutableSet<DepthState>, MutableSet<DepthState>> {
                    val matching = mutableSetOf<DepthState>()
                    val remaining = mutableSetOf<DepthState>()
                    for (state in states) {
                        val value = state.conditions[key]
                        if (value == null) {
                            matching.add(state.copy(conditions = state.conditions + (key to requiredValue)))
                            remaining.add(state.copy(conditions = state.conditions + (key to !requiredValue)))
                        } else if (value == requiredValue) {
                            matching.add(state)
                        } else {
                            remaining.add(state)
                        }
                    }
                    return matching to remaining
                }

                fun condition(directive: MatchResult): Pair<String, Boolean> {
                    val command = directive.groupValues[1]
                    val expression = directive.value.substringAfter('#').trim().removePrefix(command).trim()
                    if (command == "ifdef" || command == "ifndef") {
                        val name = expression.removeSurrounding("(", ")")
                        return "defined:$name" to (command == "ifdef")
                    }

                    val normalized = expression.replace(Regex("\\s+"), "")
                    val defined = Regex("(!?)defined\\(?($IDENTIFIER_REGEX_STR)\\)?").matchEntire(normalized)
                    if (defined != null) {
                        val (_, negation, name) = defined.groupValues
                        return "defined:$name" to negation.isEmpty()
                    }

                    val negatedIdentifier = Regex("!($IDENTIFIER_REGEX_STR)").matchEntire(normalized)
                    if (negatedIdentifier != null) {
                        return "expression:${negatedIdentifier.groupValues[1]}" to false
                    }
                    return "expression:$normalized" to true
                }

                while (index < newCode.length) {
                    val directive = if (directives.hasNext()) directives.next() else null
                    val boundary = directive?.range?.first ?: newCode.length

                    while (index < boundary) {
                        when (newCode[index++]) {
                            '{' -> depths = depths.mapTo(mutableSetOf()) { it.copy(depth = it.depth + 1) }
                            '}' -> depths = depths.mapTo(mutableSetOf()) { it.copy(depth = it.depth - 1) }
                        }
                        if (depths.isNotEmpty() && depths.all { it.depth == 0 }) return@run index
                    }

                    if (directive == null) break

                    when (directive.groupValues[1]) {
                        "if", "ifdef", "ifndef" -> {
                            val (key, requiredValue) = condition(directive)
                            val (matching, remaining) = splitByCondition(depths, key, requiredValue)
                            conditionalStack.addLast(ConditionalFrame(remaining, mutableSetOf()))
                            depths = matching
                        }

                        "elif", "else" -> {
                            val frame = conditionalStack.lastOrNull()
                                ?: error("Unexpected #${directive.groupValues[1]} in function $funcName")
                            frame.branchEndStates.addAll(depths)
                            if (directive.groupValues[1] == "else") {
                                depths = frame.remainingStates
                                frame.remainingStates = mutableSetOf()
                                frame.hasElse = true
                            } else {
                                val (key, requiredValue) = condition(directive)
                                val (matching, remaining) = splitByCondition(
                                    frame.remainingStates,
                                    key,
                                    requiredValue,
                                )
                                frame.remainingStates = remaining
                                depths = matching
                            }
                        }

                        "endif" -> {
                            val frame = conditionalStack.removeLastOrNull()
                                ?: error("Unexpected #endif in function $funcName")
                            frame.branchEndStates.addAll(depths)
                            if (!frame.hasElse) frame.branchEndStates.addAll(frame.remainingStates)
                            depths = frame.branchEndStates
                        }
                    }

                    index = directive.range.last + 1
                    if (depths.isNotEmpty() && depths.all { it.depth == 0 }) return@run index
                }

                error("Unbalanced function body for $funcName")
            }
            tokenCountWithoutFuncNameInHeader[funcName] = tokenCountWithoutFuncNameInHeader[funcName]!! - 1
            FuncInfo(
                name = funcName,
                fullRange = it.range.first..<endIndex,
                bodyRange = (it.range.last)..<endIndex,
                headerRange = it.range.first..<it.range.last
            )
        }.toMutableList()

        val removedFuncIndices = mutableListOf<Int>()
        val remainingFuncIndices = funcInfo.indices.filterTo(mutableListOf()) {
            val func = funcInfo[it]
            if (func.name == "main") return@filterTo false
            FUNC_EXCLUDE_PREFIX.none { prefix -> func.name.startsWith(prefix) }
        }

        do {
            val removedAny = remainingFuncIndices.removeIf { index ->
                val funcInfo = funcInfo[index]
                if (tokenCountWithoutFuncNameInHeader[funcInfo.name]!! >= 1) return@removeIf false

                val funcText = currCode.substring(funcInfo.fullRange)

                funcText.split(TOKEN_DELIMITER_REGEX)
                    .groupingBy { it }
                    .eachCount()
                    .forEach { (token, count) ->
                        val newCount = if (token == funcInfo.name) count - 1 else count
                        tokenCountWithoutFuncNameInHeader[token] =
                            tokenCountWithoutFuncNameInHeader[token]!! - newCount
                    }

                removedFuncIndices.add(index)
                true
            }
        } while (removedAny)

        val charArray = currCode.toCharArray()
        removedFuncIndices.forEach { index ->
            val range = funcInfo[index].fullRange
            charArray.fill(' ', range.first, range.last + 1)
        }
        String(charArray)
    }

    tokenCounts = newCode.split(TOKEN_DELIMITER_REGEX)
        .groupingBy { it }
        .eachCount()

    val isFinalShader = file.path.name.lowercase() == "final.fsh"
        newCode = run {
            val currCode = newCode
            val newTokenCounts = tokenCounts.toMutableMap()
            val newLines = currCode.lineSequence().toMutableList()

            val lineWithDefines = newLines.indices.asSequence().mapNotNull { index ->
                DEFINE_REGEX.find(newLines[index])?.let {
                    val (_, name, _) = it.destructured
                    newTokenCounts[name] = newTokenCounts[name]!! - 1
                    index to it
                }
            }.filter { (_, matchResult) ->
                val (_, name, rest) = matchResult.destructured

                if (name.startsWith("_shadesmith_")
                    || name.startsWith("history_")
                    || name.startsWith("transient_")
                    || name.startsWith("persistent_")) {
                    return@filter true
                }

                if (!isFinalShader && name.startsWith("SETTING_")) {
                    return@filter true
                }

                false
            }.toMutableList()


            do {
                val removedSome = lineWithDefines.removeIf { (lineIndex, matchResult) ->
                    val (_, name, rest) = matchResult.destructured

                    val booleanSetting = name.startsWith("SETTING_") && rest.isBlank()
                    val requiredCount = if (booleanSetting) 2 else 1

                    if (newTokenCounts[name]!! >= requiredCount) return@removeIf false

                    newLines[lineIndex] = ""
                    if (booleanSetting) {
                        newLines[lineIndex + 1] = ""
                        newLines[lineIndex + 2] = ""
                    }

                    matchResult.value.split(TOKEN_DELIMITER_REGEX)
                        .groupingBy { it }
                        .eachCount()
                        .forEach { (token, count) ->
                            val newCount = if (token == name) count - 1 else count
                            newTokenCounts[token] = newTokenCounts[token]!! - newCount
                        }
                    true
                }
            } while (removedSome)

            newLines.filter { it.isNotBlank() }.joinToString("\n")
        }

        tokenCounts = newCode.split(TOKEN_DELIMITER_REGEX)
            .groupingBy { it }
            .eachCount()

    newCode = run {
        val currCode = newCode
        UNIFORM_REGEX.replace(currCode) {
            val (_, name) = it.destructured

            if (tokenCounts[name]!! < 2) {
                ""
            } else {
                it.value
            }
        }
    }

    return file.copy(code = newCode.lineSequence().filter { it.isNotBlank() }.joinToString("\n"))
}
