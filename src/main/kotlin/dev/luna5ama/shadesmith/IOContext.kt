package dev.luna5ama.shadesmith

import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull

class IOContext(val inputPath: Path, val tempPath: Path, val outputPath: Path) {
    private val inputPathResolver = PathResolver(inputPath)
    private val tempPathResolver = PathResolver(tempPath)
    private val outputPathResolver = PathResolver(outputPath)
    private val cache = ConcurrentHashMap<Path, Optional<ShaderFile>>()

    val config = Json.decodeFromString<Config>(inputPath.resolve("shadesmith.json").readText())

    fun resolveInputPath(path: String): Path {
        return inputPathResolver.resolve(path)
    }

    fun readInputRoot(rootPath: String): ShaderFile? {
        return readInput(inputPathResolver.resolve(rootPath))
    }

    fun toOutputPath(path: Path): Path {
        val relativePath = path.absolute().relativeTo(inputPath)
        return outputPathResolver.resolve(relativePath.pathString)
    }

    fun toTempPath(path: Path): Path {
        val relativePath = path.absolute().relativeTo(inputPath)
        return tempPathResolver.resolve(relativePath.pathString)
    }

    fun readInput(path: Path): ShaderFile? {
        return cache.computeIfAbsent(path) {
            if (!it.exists()) return@computeIfAbsent Optional.empty()
            val code = it.readText()
            Optional.of(ShaderFile(it,  code))
        }.getOrNull()
    }

    fun writeOutput(shaderFile: ShaderFile) {
        val actualPath = shaderFile.path.absolute()
        actualPath.parent.createDirectories()
        actualPath.writeText(shaderFile.code)
    }
}

context(ioContext: IOContext)
fun Path.toOutputPath(): Path {
    return ioContext.toOutputPath(this)
}

context(ioContext: IOContext)
fun Path.toTempPath(): Path {
    return ioContext.toTempPath(this)
}

context(ioContext: IOContext)
fun ShaderFile.writeOutput() {
    ioContext.writeOutput(this)
}