package dev.luna5ama.shadesmith

import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

context(ioContext: IOContext)
fun resolveIncludes(inputFiles: List<ShaderFile>): List<ShaderFile> {
    val usedFiles = inputFiles.associateByTo(ConcurrentHashMap()) { it.path }

    val includeRegex = """#include\s+"([^"]+)"""".toRegex()
    val prefix = "#include "

    fun resolve(file: ShaderFile) {
        file.code.lineSequence()
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length).trim().removeSurrounding("\"") }
            .toList()
            .forEach {
                val includedFile = ioContext.readInput(file.path.resolve(it))
                if (includedFile != null && usedFiles.putIfAbsent(includedFile.path, includedFile) == null) {
                    resolve(includedFile)
                }
            }
    }

    inputFiles.parallelStream().forEach {
        resolve(it)
    }

    val includeGuardRegex =
        """([\s\S]*)(#ifndef INCLUDE_\S*\s+#define INCLUDE_\S*\s+\S*)([\S\s]*)(#endif)([\s\S]*)""".toRegex()

    val protectRegex = """^[\t ]*#(?!include)""".toRegex(RegexOption.MULTILINE)

    val protect = "//DONOTPROCESS"
    fun prepareForPreprocessor(shaderFile: ShaderFile): ShaderFile {
        fun protect(content: String): String {
            return protectRegex.replace(content) {
                "$protect${it.value}"
            }
        }

        val matchResult = includeGuardRegex.matchEntire(shaderFile.code)
        var newCode = if (matchResult == null) {
            protect(shaderFile.code)
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

        newCode = includeRegex.replace(newCode) {
            val includePath = it.groupValues[1]
            "#include \"${includePath}.c\""
        }

        return shaderFile.copy(
            path = ioContext.toTempPath(shaderFile.path.resolve("/${shaderFile.path}.c")),
            code = newCode
        )
    }

    usedFiles.values.parallelStream()
        .map { prepareForPreprocessor(it) }
        .forEach {
            ioContext.writeOutput(it)
        }

    return inputFiles.stream()
        .map {
            val inputPath = ioContext.toTempPath(it.path.resolve("/${it.path}.c"))
            val proc = ProcessBuilder()
                .directory(ioContext.tempPath.toFile())
                .command("clang", "-C", "-E", "-P", "-Wno-microsoft-include", inputPath.absolutePathString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            it to proc
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
