package dev.luna5ama.shadesmith

private val IDENTIFIER_REGEX_STR = """[A-Za-z_][A-Za-z0-9_]*"""

private val TOKEN_DELIMITER_REGEX = """\s+|(?=[{}()\[\];,.\-!])|(?<=[{}()\[\];,.\-!])""".toRegex()
private val DEFINE_REGEX = """^\s*((?://)?)#define\s+($IDENTIFIER_REGEX_STR)(.*)$""".toRegex()

private val FUNCTION_HEADER_REGEX =
    """^\s*($IDENTIFIER_REGEX_STR)\s+($IDENTIFIER_REGEX_STR)\s*(\([\s\w_,]*?\))\s*\{""".toRegex(RegexOption.MULTILINE)

private val COMMENT_PLACE_HOLDER_REGEX = "__COMMENT_([0-9]+)__".toRegex()

private val UNIFORM_REGEX =
    """^\s*uniform\s+($IDENTIFIER_REGEX_STR)\s+($IDENTIFIER_REGEX_STR)\s*;.*$""".toRegex(RegexOption.MULTILINE)

private val FUNC_EXCLUDE_PREFIX = listOf("colors2")

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
                (it.range.last + 1..newCode.lastIndex + 1).fold(1) { acc, index ->
                    if (acc == 0) return@run index
                    val c = newCode[index]
                    when (c) {
                        '{' -> acc + 1
                        '}' -> acc - 1
                        else -> acc
                    }
                }
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
            FUNC_EXCLUDE_PREFIX.none { prefix -> func.name.startsWith(prefix)  }
        }

        do {
            var removedAny = false

            remainingFuncIndices.removeIf { index ->
                val funcInfo = funcInfo[index]
                if (tokenCountWithoutFuncNameInHeader[funcInfo.name]!! < 1) {
//                    println("Removing unused function: ${funcInfo.name}")

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
                    removedAny = true
                    true
                } else {
                    false
                }
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


    newCode = run {
        val currCode = newCode
        val newTokenCounts = tokenCounts.toMutableMap()
        val newLines = currCode.lineSequence().toMutableList()

        val lineWithDefines = newLines.indices.mapNotNullTo(mutableListOf()) { index ->
            DEFINE_REGEX.find(newLines[index])?.let {
                val (_, name, _) = it.destructured
                newTokenCounts[name] = newTokenCounts[name]!! - 1
                index to it
            }
        }


        lineWithDefines.asSequence()
            .forEach { (lineIndex, matchResult) ->
                val (_, name, rest) = matchResult.destructured
                if (!name.startsWith("SETTING_")) return@forEach

                val booleanSetting = name.startsWith("SETTING_") && rest.isBlank()
                val requiredCount = if (booleanSetting) 2 else 1

                if (newTokenCounts[name]!! < requiredCount) {
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
                }
            }

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
//                println("Removing unused uniform: $name")
                ""
            } else {
                it.value
            }
        }
    }

    return file.copy(code = newCode.lineSequence().filter { it.isNotBlank() }.joinToString("\n"))
}