package dev.luna5ama.shadesmith

import kotlin.io.path.*

object Main {
    @JvmStatic
    @OptIn(ExperimentalPathApi::class)
    fun main(args: Array<String>) {
        check(args.size == 2) { "Missing path argument" }

        val inputPath = Path(args[0]).toAbsolutePath()
        val outputPath = Path(args[1]).toAbsolutePath()

        check(inputPath.exists() && inputPath.isDirectory())

        outputPath.createDirectories()
        outputPath.listDirectoryEntries().forEach {
            it.deleteRecursively()
        }

        val ioContext = IOContext(inputPath, outputPath)
        context(ioContext) {
            val composites = readAllCompositeStyleShaders()
            val others = readOtherShaders()
            val inputFiles = composites + others

            val included = resolveIncludes(inputFiles)
            val cleaned = included.parallelStream()
                .map { holdComments(it) }
                .map  { it.copy(first = cleanUnused(it.first)) }
                .map { restoreComments(it) }
                .toList()

            resolveTextures(cleaned)

            cleaned.forEach {
                it.copy(path = it.path.toOutputPath()).writeOutput()
            }
        }
    }
}