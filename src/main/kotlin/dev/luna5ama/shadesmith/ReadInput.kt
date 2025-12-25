package dev.luna5ama.shadesmith

import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

context(ioContext: IOContext)
fun readAllCompositeStyleShaders() : List<ShaderFile> {
    val compositePassStages = listOf("vsh", "fsh", "gsh", "csh")
    fun suffixToString(index: Int): String {
       return if (index == 0) "" else index.toString()
    }
    return IRIS_PASS_PREFIX.asSequence().flatMap { prefix ->
        compositePassStages.flatMap { stage ->
            if (stage == "csh") {
                (0..99).asSequence().flatMap { numberSuffix ->
                    val numberSuffixStr = suffixToString(numberSuffix)
                    sequenceOf(numberSuffixStr) +  ('a'..'z').asSequence().map { charSuffix ->
                        "${numberSuffixStr}_${charSuffix}"
                    }
                }.map {
                    "$prefix$it.csh"
                }
            } else {
                (0..99).asSequence().map { numberSuffix ->
                    "$prefix${suffixToString(numberSuffix)}.$stage"
                }
            }
        }
    }.toList().parallelStream().map {
        ioContext.readInputRoot(it)
    }.filter {
        it != null
    }.map {
        it!!.copy(compositeStyle = true)
    }.toList()
}

context(ioContext: IOContext)
fun readOtherShaders(): List<ShaderFile> {
    val validExtensions = setOf("vsh", "fsh", "gsh", "csh", "tcs", "tes")
    return ioContext.inputPath.listDirectoryEntries().parallelStream()
        .filter { it.extension in validExtensions }
        .filter { path -> IRIS_PASS_PREFIX.none { path.name.startsWith(it.actualName) } }
        .map { ioContext.readInputRoot(it.name) }
        .filter { it != null }
        .map { it!! }
        .toList()
}