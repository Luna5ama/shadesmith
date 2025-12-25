package dev.luna5ama.shadesmith

enum class PassPrefix {
    SETUP,
    BEGIN,
    SHADOWCOMP,
    PREPARE,
    DEFERRED,
    COMPOSITE;

    val actualName = this.name.lowercase()

    override fun toString(): String {
        return actualName
    }
}

val IRIS_PASS_PREFIX = listOf(
    PassPrefix.BEGIN,
    PassPrefix.DEFERRED,
    PassPrefix.COMPOSITE,
)

val IDENTIFIER_REGEX_STR = """[A-Za-z_][A-Za-z0-9_]*"""
val DEFINE_REGEX = """^\s*((?://)?)#define\s+($IDENTIFIER_REGEX_STR)(.*)$""".toRegex()
val DEFINE_REGEX_N = """\s*((?://)?)#define\s+($IDENTIFIER_REGEX_STR)(.*)""".toRegex()

val PASS_NAME_REGEX = ( "(${IRIS_PASS_PREFIX.joinToString("|")})(\\d+)((?:_[a-z])?)").toRegex()
val LINE_COMMENT_REGEX = "//.*$".toRegex(RegexOption.MULTILINE)
val BLOCK_COMMENT_REGEX = """/\*[\s\S]*?\*/""".toRegex(RegexOption.MULTILINE)
val COMMENT_PLACE_HOLDER_REGEX = "#__COMMENT_([0-9]+)__#".toRegex()