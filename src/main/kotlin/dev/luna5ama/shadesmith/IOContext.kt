package dev.luna5ama.shadesmith

import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.jvm.optionals.getOrNull

class IOContext(val inputPath: Path, val outputPath: Path) {
    private val inputPathResolver = PathResolver(inputPath)
    private val cache = ConcurrentHashMap<Path, Optional<ShaderFile>>()

    fun readInputRoot(rootPath: String): ShaderFile? {
        return readInput(inputPathResolver.resolve(rootPath))
    }

    fun readInput(path: Path): ShaderFile? {
        val key = path.normalize().absolute()
        return cache.computeIfAbsent(key) {
            if (!it.exists()) return@computeIfAbsent Optional.empty()
            val fileName = it.name
            val fileDir = it.parent.relativeTo(inputPath).pathString
            val code = it.readText()
            Optional.of(ShaderFile(fileName, fileDir, code))
        }.getOrNull()
    }

    fun readAllCompositeStyleShaders() : List<ShaderFile> {
        return IRIS_PASS_PREFIX.asSequence().flatMap { prefix ->
            (0..99).asSequence().flatMap { numberSuffix ->
                sequenceOf(numberSuffix.toString()) +  ('a'..'z').asSequence().map { charSuffix ->
                    "${numberSuffix}_${charSuffix}"
                }
            }.map {
                "$prefix$it.csh"
            }
        }.mapNotNull {
            readInputRoot(it)
        }.toList()
    }
}