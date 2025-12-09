package dev.luna5ama.shadesmith

context(ioContext: IOContext)
fun readAllCompositeStyleShaders() : List<ShaderFile> {
    return IRIS_PASS_PREFIX.asSequence().flatMap { prefix ->
        (0..99).asSequence().flatMap { numberSuffix ->
            sequenceOf(numberSuffix.toString()) +  ('a'..'z').asSequence().map { charSuffix ->
                "${numberSuffix}_${charSuffix}"
            }
        }.map {
            "$prefix$it.csh"
        }
    }.toList().parallelStream().map {
        ioContext.readInputRoot(it)
    }.filter {
        it != null
    }.map {
        it!!
    }.toList()
}