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

val PASS_NAME_REGEX = ("(${IRIS_PASS_PREFIX.joinToString("|")})(\\d*)((?:_[a-z])?)").toRegex()
val LINE_COMMENT_REGEX = "//.*$".toRegex(RegexOption.MULTILINE)
