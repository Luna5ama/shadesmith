package dev.luna5ama.shadesmith

import dev.luna5ama.shadesmith.blockcode.generateHardcodedPBR
import kotlin.io.path.*

object Main {
    @JvmStatic
    @OptIn(ExperimentalPathApi::class)
    fun main(args: Array<String>) {
        check(args.size >= 2) { "Missing path argument" }

        val inputPath = Path(args[0]).toAbsolutePath()
        val outputPath = Path(args[1]).toAbsolutePath()

        check(inputPath.exists() && inputPath.isDirectory())
        check(
            outputPath.parent != null &&
                outputPath != inputPath &&
                !inputPath.startsWith(outputPath) &&
                !outputPath.startsWith(inputPath),
        ) {
            "Input and output shader directories must not overlap"
        }

        if (args.contains("--pbr")) {
            clearOutput(outputPath)
            val ioContext = IOContext(inputPath, outputPath)
            context(ioContext) {
                generateHardcodedPBR()
            }
            return
        }

        val artifactDirectory = outputPath.resolveSibling(".${outputPath.fileName}.spirv")
        runShaderPipeline(
            inputPath,
            outputPath,
            artifactDirectory,
            Path("shadesmith.shaders.properties").toAbsolutePath(),
        )
    }

    @OptIn(ExperimentalPathApi::class)
    internal fun runShaderPipeline(
        inputPath: java.nio.file.Path,
        outputPath: java.nio.file.Path,
        artifactDirectory: java.nio.file.Path,
        propertiesPath: java.nio.file.Path,
    ): List<OptimizedShaderFile> {
        val ioContext = IOContext(inputPath, outputPath)
        context(ioContext) {
            val inputFiles = readAllCompositeStyleShaders() + readOtherShaders()
            val included = resolveIncludes(inputFiles)
            val optimized = ShaderPipeline(artifactDirectory).optimize(included)

            clearOutput(outputPath)
            resolveTextures(optimized, propertiesPath)
            optimized.forEach {
                it.file.copy(path = it.file.path.toOutputPath()).writeOutput()
            }
            return optimized
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun clearOutput(outputPath: java.nio.file.Path) {
        outputPath.createDirectories()
        outputPath.listDirectoryEntries().forEach {
            it.deleteRecursively()
        }
    }
}
