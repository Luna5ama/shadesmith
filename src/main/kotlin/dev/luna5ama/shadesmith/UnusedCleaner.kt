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

private val FUNC_EXCLUDE_PREFIX = listOf("colors2", "voxy_emitFragment")

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
                data class ConditionalFrame(
                    val branchStartDepths: Set<Int>,
                    val branchEndDepths: MutableSet<Int>
                )

                val conditionalStack = ArrayDeque<ConditionalFrame>()
                var depths = mutableSetOf(1)
                var index = it.range.last + 1
                val directives = PREPROCESSOR_CONDITIONAL_REGEX.findAll(newCode, index).iterator()

                while (index < newCode.length) {
                    val directive = if (directives.hasNext()) directives.next() else null
                    val boundary = directive?.range?.first ?: newCode.length

                    while (index < boundary) {
                        when (newCode[index++]) {
                            '{' -> depths = depths.mapTo(mutableSetOf()) { it + 1 }
                            '}' -> depths = depths.mapTo(mutableSetOf()) { it - 1 }
                        }
                        if (depths.size == 1 && depths.contains(0)) return@run index
                    }

                    if (directive == null) break

                    when (directive.groupValues[1]) {
                        "if", "ifdef", "ifndef" -> conditionalStack.addLast(
                            ConditionalFrame(depths.toSet(), mutableSetOf())
                        )

                        "elif", "else" -> {
                            val frame = conditionalStack.lastOrNull()
                                ?: error("Unexpected #${directive.groupValues[1]} in function $funcName")
                            frame.branchEndDepths.addAll(depths)
                            depths = frame.branchStartDepths.toMutableSet()
                        }

                        "endif" -> {
                            val frame = conditionalStack.removeLastOrNull()
                                ?: error("Unexpected #endif in function $funcName")
                            frame.branchEndDepths.addAll(depths)
                            depths = frame.branchEndDepths.toMutableSet()
                        }
                    }

                    index = directive.range.last + 1
                    if (depths.size == 1 && depths.contains(0)) return@run index
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
