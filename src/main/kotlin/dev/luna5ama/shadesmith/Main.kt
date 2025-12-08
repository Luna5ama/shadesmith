package dev.luna5ama.shadesmith

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

object Main  {
    @JvmStatic
    @OptIn(ExperimentalPathApi::class)
    fun main(args: Array<String>) {
        check(args.size >= 2) { "Missing path argument"}

        val inputPath = Path(args[0]).toAbsolutePath()
        val outputPath = Path(args[1]).toAbsolutePath()

        check(inputPath.exists() && inputPath.isDirectory())

        outputPath.createDirectories()
        outputPath.listDirectoryEntries().forEach {
            it.deleteRecursively()
        }

        val ioContext = IOContext(inputPath, outputPath)
        val inputFiles = ioContext.readAllCompositeStyleShaders()

        inputFiles.forEach {
            println("${it.fileDir}/${it.fileName}")
        }
    }
}