package dev.luna5ama.shadesmith

import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

context(ioContext: IOContext)
fun resolveIncludes(inputFiles: List<ShaderFile>): List<ShaderFile> {
    val includeGuardRegex =
        """([\s\S]*)(#ifndef INCLUDE_\S*\s+#define INCLUDE_\S*\s+\S*)([\S\s]*)(#endif)([\s\S]*)""".toRegex()
    val protectRegex = """^[\t ]*#(?!include)""".toRegex(RegexOption.MULTILINE)
    val protect = "//DONOTPROCESS"
    val excluded = setOf("Options", "TextOptions")
    val constFlag = "/*const*/"

    fun prepareForPreprocessor(shaderFile: ShaderFile): ShaderFile {
        fun protect(content: String): String {

            return buildString {
                val lines = content.lines()
                var constFlagState = false

                for (line in lines) {
                    if (line == constFlag) {
                        constFlagState = !constFlagState
                        continue
                    }

                    if (!constFlagState) {
                        appendLine(protectRegex.replace(line) {
                            "$protect${it.value}"
                        })
                    } else {
                        appendLine(line)
                    }
                }
            }
        }


        val name = shaderFile.path.nameWithoutExtension
        var newCode = shaderFile.code
        if (name !in excluded) {
            newCode = newCode.replace(LINE_COMMENT_REGEX, "")
        }

        val matchResult = includeGuardRegex.matchEntire(newCode)
        newCode = if (matchResult == null) {
            protect(newCode)
        } else {
            val (before, guard1, content, guard2, after) = matchResult.destructured
            buildString {
                append(before)
                append(guard1)

                append(protect(content))

                append(guard2)
                append(after)
            }
        }

        return shaderFile.copy(
            code = newCode
        )
    }

    val usedFiles =
        inputFiles.associateTo(ConcurrentHashMap()) { it.path.absolutePathString() to prepareForPreprocessor(it) }

    val prefix = "#include "

    fun resolve(file: ShaderFile, included: MutableSet<String>): ShaderFile {
        val newFile = prepareForPreprocessor(file)
        val newCode = newFile.code.lineSequence()
            .map {
                if (!it.startsWith(prefix)) {
                    return@map it
                }

                val includePath = it.substring(prefix.length).trim().removeSurrounding("\"")
                val includedFile = ioContext.readInput(file.path.resolve(includePath))
                    ?: throw IllegalStateException("Included file not found: $includePath included from ${file.path.absolutePathString()}")
                val resolvedFile = usedFiles.getOrPut(includedFile.path.absolutePathString()) {
                    resolve(includedFile, included)
                }

                return@map resolvedFile.code
            }
            .joinToString("\n")

        return newFile.copy(code = newCode)
    }

    return inputFiles.parallelStream()
        .map { file ->
            val proc = ProcessBuilder()
                .directory(ioContext.tempPath.toFile())
                .command("clang", "-C", "-E", "-P", "-Wno-microsoft-include", "-")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            proc.outputStream.writer().use {
                it.write(resolve(file, mutableSetOf()).code)
            }

            file to proc
        }
        .toList()
        .parallelStream()
        .map { (file, proc) ->

            val newCode = proc.inputStream.bufferedReader().use {
                it.readText()
            }

            file.copy(code = newCode.replace(protect, ""))
        }
        .toList()
}
