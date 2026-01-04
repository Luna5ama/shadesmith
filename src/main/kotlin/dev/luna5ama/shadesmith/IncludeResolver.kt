package dev.luna5ama.shadesmith

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
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

    val prefix = "#include "

    val cache = ConcurrentHashMap<String, ShaderFile>()

    fun resolveDirect(path: Path): ShaderFile {
        return cache.getOrPut(path.absolutePathString()) {
            val includedFile = ioContext.readInput(path)
                ?: throw IllegalStateException("Included file not found: ${path.name}")
            prepareForPreprocessor(includedFile)
        }
    }

    fun resolve(file: ShaderFile, included: MutableSet<String>): ShaderFile {
        val newCode = file.code.lineSequence()
            .map {
                if (!it.startsWith(prefix)) {
                    return@map it
                }

                val includePathStr = it.substring(prefix.length).trim().removeSurrounding("\"")
                val includePath = file.path.resolve(includePathStr)

                val pathStr = includePath.absolutePathString()
                val includedFile = resolveDirect(includePath)
                if (includedFile.includeGuarded && !included.add(pathStr)) {
                    return@map ""
                }

                val resolvedFile = resolve(includedFile, included)

                return@map resolvedFile.code
            }
            .joinToString("\n")

        return file.copy(code = newCode)
    }

    return inputFiles.parallelStream()
        .map { resolve(prepareForPreprocessor(it), mutableSetOf()) }
        .map { file ->
            val proc = ProcessBuilder()
                .command("clang", "-C", "-E", "-P", "-Wno-microsoft-include", "-")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            proc.outputStream.writer().use {
                it.write(file.code)
            }

            val newCode = proc.inputStream.bufferedReader().use {
                it.readText()
            }

            file.copy(code = newCode.replace(protect, ""))
        }
        .toList()
}
