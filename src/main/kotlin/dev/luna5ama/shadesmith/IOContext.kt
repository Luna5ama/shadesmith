package dev.luna5ama.shadesmith

import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull

class IOContext(val inputPath: Path, val outputPath: Path) {
    private val inputPathResolver = PathResolver(inputPath)
    private val outputPathResolver = PathResolver(outputPath)
    private val cache = ConcurrentHashMap<Path, Optional<ShaderFile>>()

    private val directoryCreated = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    val config = runCatching {
        Json.decodeFromString<Config>(inputPath.resolve("shadesmith.json").readText())
    }.getOrElse {
        Config()
    }

    internal val programActivations = inputPath.resolve("shaders.properties").let { path ->
        if (path.isRegularFile()) ProgramActivationIndex.parse(path.readText()) else ProgramActivationIndex.empty()
    }

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

    fun readInput(path: Path): ShaderFile? {
        return cache.computeIfAbsent(path) {
            if (!it.exists()) return@computeIfAbsent Optional.empty()
            val code = it.readText()
            Optional.of(ShaderFile(it,  code, includeGuarded = code.contains("#define INCLUDE_")))
        }.getOrNull()
    }

    fun writeOutput(shaderFile: ShaderFile) {
        writeOutput(shaderFile.path, shaderFile.code)
    }

    fun writeOutput(path: Path, text: String) {
        val actualPath = path.absolute()
        val parentPath = actualPath.parent
        if (directoryCreated.add(parentPath.absolutePathString())) {
            parentPath.createDirectories()
        }
        actualPath.writeText(text)
    }
}

context(ioContext: IOContext)
fun Path.toOutputPath(): Path {
    return ioContext.toOutputPath(this)
}

context(ioContext: IOContext)
fun ShaderFile.writeOutput() {
    ioContext.writeOutput(this)
}
