package dev.luna5ama.shadesmith

private val LINE_COMMENT_REGEX = "//.*$".toRegex(RegexOption.MULTILINE)
private val BLOCK_COMMENT_REGEX = """/\*[\s\S]*?\*/""".toRegex(RegexOption.MULTILINE)
private val COMMENT_PLACE_HOLDER_REGEX = "#__COMMENT_([0-9]+)__#".toRegex()

fun holdComments(file: ShaderFile): Pair<ShaderFile, List<String>> {
    val comments = mutableListOf<String>()
    var newCode = file.code

    newCode = BLOCK_COMMENT_REGEX.replace(newCode) {
        val comment = it.value
        val placeHolder = "#__COMMENT_${comments.size}__#"
        comments.add(comment)
        placeHolder
    }

    newCode = LINE_COMMENT_REGEX.replace(newCode) {
        val comment = it.value
        if (comment.startsWith("//#define ")) {
            return@replace it.value
        }
        val placeHolder = "#__COMMENT_${comments.size}__#"
        comments.add(comment)
        placeHolder
    }

    return file.copy(code = newCode) to comments
}

fun restoreComments(inputFile: Pair<ShaderFile, List<String>>): ShaderFile {
    val (file, comments) = inputFile
    var newCode = file.code

    newCode = COMMENT_PLACE_HOLDER_REGEX.replace(newCode) { matchResult ->
        val index = matchResult.groupValues[1].toInt()
        comments[index]
    }

    return file.copy(code = newCode)
}