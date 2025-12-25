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

    fun prepareForPreprocessor(shaderFile: ShaderFile): ShaderFile {
        fun protect(content: String): String {
            return protectRegex.replace(content) {
                val defineMatch = DEFINE_REGEX_N.matchAt(content, it.range.first)
                if (defineMatch == null) {
                    "$protect${it.value}"
                } else {
                    val (_, defineName, defineValue) = defineMatch.destructured
                    if (!defineName.startsWith("usam_") && !defineName.startsWith("uimg_")) {
                        "$protect${it.value}"
                    } else {
                        it.value
                    }
                }
            }
        }

        val matchResult = includeGuardRegex.matchEntire(shaderFile.code)

        val name = shaderFile.path.nameWithoutExtension
        var newCode = shaderFile.code
        if (name !in excluded) {
            newCode = newCode.replace(LINE_COMMENT_REGEX, "")
        }

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
