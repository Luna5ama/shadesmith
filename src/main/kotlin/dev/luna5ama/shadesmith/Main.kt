package dev.luna5ama.shadesmith

import kotlin.io.path.*

object Main {
    @JvmStatic
    @OptIn(ExperimentalPathApi::class)
    fun main(args: Array<String>) {
        check(args.size >= 3) { "Missing path argument" }

        val inputPath = Path(args[0]).toAbsolutePath()
        val tempPath = Path(args[1]).toAbsolutePath()
        val outputPath = Path(args[2]).toAbsolutePath()

        check(inputPath.exists() && inputPath.isDirectory())

        outputPath.createDirectories()
//        outputPath.listDirectoryEntries().forEach {
//            it.deleteRecursively()
//        }

        val ioContext = IOContext(inputPath, tempPath, outputPath)
        val inputFiles = ioContext.readAllCompositeStyleShaders()

        val includeResolver = IncludeResolver(ioContext)
        val included = includeResolver.resolve(inputFiles)

        included.forEach {
            val newFile = it.copy(path = ioContext.toOutputPath(it.path))
            ioContext.writeOutput(newFile)
        }
    }
}