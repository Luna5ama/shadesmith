package dev.luna5ama.shadesmith

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

context(ioContext: IOContext)
fun resolveIncludes(inputFiles: List<ShaderFile>): List<ShaderFile> {
    val cache = ConcurrentHashMap<String, ShaderFile>()

    fun read(path: Path): ShaderFile {
        val normalized = path.toAbsolutePath().normalize()
        return cache.getOrPut(normalized.absolutePathString()) {
            ioContext.readInput(normalized)
                ?: throw IllegalStateException("Included file not found: ${normalized.name}")
        }
    }

    fun includePath(file: ShaderFile, value: String): Path {
        return if (value.startsWith('/')) {
            ioContext.resolveInputPath(value.removePrefix("/"))
        } else {
            file.path.parent.resolve(value)
        }.toAbsolutePath().normalize()
    }

    fun resolve(
        file: ShaderFile,
        includedGuards: MutableSet<String>,
        activeIncludes: MutableList<String>,
    ): ShaderFile {
        val fileIdentity = file.path.toAbsolutePath().normalize().absolutePathString()
        if (fileIdentity in activeIncludes) {
            val cycle = (activeIncludes + fileIdentity).joinToString(" -> ")
            throw IllegalStateException("Recursive unguarded shader include: $cycle")
        }
        activeIncludes += fileIdentity
        try {
            val withoutIncludes = INCLUDE_DIRECTIVE.replace(file.code) { it.groupValues[2] }
            val conditionals = PreprocessorProtection.protect(withoutIncludes, file.path.name).directives.filter {
                it.kind in setOf(
                    PreprocessorDirectiveKind.IF, PreprocessorDirectiveKind.IFDEF, PreprocessorDirectiveKind.IFNDEF,
                    PreprocessorDirectiveKind.ELIF, PreprocessorDirectiveKind.ELSE, PreprocessorDirectiveKind.ENDIF,
                ) && PreprocessorFeature.INCLUDE_GUARD !in it.features
            }
            val scopes = mutableListOf(includedGuards)
            var conditionalIndex = 0
            val expanded = INCLUDE_DIRECTIVE.replace(file.code) { match ->
                val line = file.code.take(match.range.first).count { it == '\n' } + 1
                while (conditionalIndex < conditionals.size && conditionals[conditionalIndex].sourceLine < line) {
                    when (conditionals[conditionalIndex++].kind) {
                        PreprocessorDirectiveKind.IF, PreprocessorDirectiveKind.IFDEF, PreprocessorDirectiveKind.IFNDEF ->
                            scopes += scopes.last().toMutableSet()
                        PreprocessorDirectiveKind.ELIF, PreprocessorDirectiveKind.ELSE ->
                            scopes[scopes.lastIndex] = scopes[scopes.lastIndex - 1].toMutableSet()
                        PreprocessorDirectiveKind.ENDIF -> scopes.removeAt(scopes.lastIndex)
                        else -> error("Unexpected conditional directive")
                    }
                }
                val path = includePath(file, match.groupValues[1])
                val includedFile = read(path)
                val identity = path.absolutePathString()
                val ending = match.groupValues[2]
                val scope = scopes.last()
                if (includedFile.includeGuarded && !scope.add(identity)) {
                    ending
                } else {
                    val included = resolve(includedFile, scope, activeIncludes).code
                    if (ending.isEmpty() || included.endsWith('\n') || included.endsWith('\r')) included else included + ending
                }
            }
            return file.copy(code = expanded)
        } finally {
            activeIncludes.removeAt(activeIncludes.lastIndex)
        }
    }

    return inputFiles.parallelStream()
        .map { resolve(it, mutableSetOf(), mutableListOf()) }
        .toList()
}

private val INCLUDE_DIRECTIVE =
    "(?m)^[ \\t]*#[ \\t]*include[ \\t]+[\"<]([^\">\\r\\n]+)[\">][^\\r\\n]*(\\r\\n|\\n|\\r|$)".toRegex()
