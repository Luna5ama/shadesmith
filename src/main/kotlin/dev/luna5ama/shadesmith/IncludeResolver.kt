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
            val expanded = INCLUDE_DIRECTIVE.replace(file.code) { match ->
                val path = includePath(file, match.groupValues[1])
                val includedFile = read(path)
                val identity = path.absolutePathString()
                val ending = match.groupValues[2]
                if (includedFile.includeGuarded && !includedGuards.add(identity)) {
                    ending
                } else {
                    val included = resolve(includedFile, includedGuards, activeIncludes).code
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
