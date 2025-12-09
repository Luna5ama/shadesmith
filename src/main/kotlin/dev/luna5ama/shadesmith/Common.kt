package dev.luna5ama.shadesmith

enum class PassPrefix {
    BEGIN,
    DEFERRED,
    COMPOSITE,
    PREPARE;

    val actualName = this.name.lowercase()

    override fun toString(): String {
        return actualName
    }
}

val IRIS_PASS_PREFIX = listOf(
    "begin",
    "prepare",
    "deferred",
    "composite"
)

val IDENTIFIER_REGEX_STR = """[A-Za-z_][A-Za-z0-9_]*"""

val PASS_NAME_REGEX = ( "(${IRIS_PASS_PREFIX.joinToString("|")})(\\d+)((?:_[a-z])?)").toRegex()