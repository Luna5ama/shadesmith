package dev.luna5ama.shadesmith

internal data class ProgramActivationContract(
    val program: String,
    private val propertiesSource: String,
    private val rules: List<ProgramActivationRule>,
) {
    val cacheContract: String = buildString {
        appendLine("program-activation-v1")
        appendLine(program)
        appendLine(propertiesSource)
        rules.forEach { appendLine(it.canonical) }
    }

    fun isProvenDisabled(
        assignment: Map<String, String>,
        settings: Map<String, ShaderSetting>,
    ): Boolean {
        val possible = rules.filter { rule -> rule.condition.possible(assignment, settings) }
        return possible.isNotEmpty() && possible.none(ProgramActivationRule::enabled)
    }
}

internal data class ProgramActivationRule(
    val condition: ProgramActivationCondition,
    val enabled: Boolean,
) {
    val canonical: String = "${condition.canonical}=${if (enabled) "true" else "false"}"
}

internal data class ProgramActivationCondition(
    private val terms: List<ProgramActivationTerm>,
) {
    val canonical: String = terms.joinToString(" && ") { term ->
        if (term.expected) "(${term.expression})" else "!(${term.expression})"
    }.ifEmpty { "true" }

    fun possible(assignment: Map<String, String>, settings: Map<String, ShaderSetting>): Boolean {
        return terms.none { term ->
            val value = evaluateActivationExpression(term.expression, assignment, settings)
            value != null && value != term.expected
        }
    }
}

internal data class ProgramActivationTerm(val expression: String, val expected: Boolean)

internal class ProgramActivationIndex private constructor(
    private val propertiesSource: String,
    private val rules: Map<String, List<ProgramActivationRule>>,
) {
    fun contractFor(program: String): ProgramActivationContract {
        return ProgramActivationContract(program, propertiesSource, rules[program].orEmpty())
    }

    companion object {
        fun parse(source: String): ProgramActivationIndex {
            data class Frame(
                val parent: List<ProgramActivationTerm>,
                val priorBranches: MutableList<String>,
                var current: List<ProgramActivationTerm>,
            )

            val rules = linkedMapOf<String, MutableList<ProgramActivationRule>>()
            val stack = mutableListOf<Frame>()
            var path = emptyList<ProgramActivationTerm>()
            source.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("#if ") -> {
                        val expression = line.removePrefix("#if ").substringBefore("//").trim()
                        require(expression.isNotEmpty()) { "shaders.properties:${index + 1}: empty #if expression" }
                        val current = path + ProgramActivationTerm(expression, true)
                        stack += Frame(path, mutableListOf(expression), current)
                        path = current
                    }
                    line.startsWith("#ifdef ") -> {
                        val name = line.removePrefix("#ifdef ").substringBefore("//").trim()
                        require(PROGRAM_IDENTIFIER.matches(name)) {
                            "shaders.properties:${index + 1}: invalid #ifdef identifier '$name'"
                        }
                        val expression = "defined($name)"
                        val current = path + ProgramActivationTerm(expression, true)
                        stack += Frame(path, mutableListOf(expression), current)
                        path = current
                    }
                    line.startsWith("#ifndef ") -> {
                        val name = line.removePrefix("#ifndef ").substringBefore("//").trim()
                        require(PROGRAM_IDENTIFIER.matches(name)) {
                            "shaders.properties:${index + 1}: invalid #ifndef identifier '$name'"
                        }
                        val expression = "defined($name)"
                        val current = path + ProgramActivationTerm(expression, false)
                        stack += Frame(path, mutableListOf(expression), current)
                        path = current
                    }
                    line.startsWith("#elif ") -> {
                        val frame = stack.lastOrNull()
                            ?: error("shaders.properties:${index + 1}: #elif has no matching #if")
                        val expression = line.removePrefix("#elif ").substringBefore("//").trim()
                        require(expression.isNotEmpty()) { "shaders.properties:${index + 1}: empty #elif expression" }
                        frame.current = frame.parent +
                            frame.priorBranches.map { ProgramActivationTerm(it, false) } +
                            ProgramActivationTerm(expression, true)
                        frame.priorBranches += expression
                        path = frame.current
                    }
                    line == "#else" -> {
                        val frame = stack.lastOrNull()
                            ?: error("shaders.properties:${index + 1}: #else has no matching #if")
                        frame.current = frame.parent + frame.priorBranches.map { ProgramActivationTerm(it, false) }
                        path = frame.current
                    }
                    line == "#endif" -> {
                        val frame = stack.removeLastOrNull()
                            ?: error("shaders.properties:${index + 1}: #endif has no matching #if")
                        path = frame.parent
                    }
                    else -> PROGRAM_ENABLED.matchEntire(line)?.let { match ->
                        rules.getOrPut(match.groupValues[1]) { mutableListOf() } += ProgramActivationRule(
                            ProgramActivationCondition(path),
                            match.groupValues[2] == "true",
                        )
                    }
                }
            }
            require(stack.isEmpty()) { "shaders.properties: conditional directive has no matching #endif" }
            return ProgramActivationIndex(propertiesSource = source, rules = rules)
        }

        fun empty(): ProgramActivationIndex = ProgramActivationIndex("", emptyMap())

        private val PROGRAM_ENABLED =
            "program\\.([A-Za-z0-9_]+)\\.enabled\\s*=\\s*(true|false)".toRegex()
        private val PROGRAM_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*".toRegex()
    }
}

private fun evaluateActivationExpression(
    expression: String,
    assignment: Map<String, String>,
    settings: Map<String, ShaderSetting>,
): Boolean? {
    val definedNames = PROGRAM_DEFINED.findAll(expression).map { match ->
        match.groupValues[1].ifEmpty { match.groupValues[2] }
    }.toSet()
    if (definedNames.any { name ->
            val setting = settings[name] ?: return@any true
            setting.presenceToggle && name !in assignment
        }
    ) {
        return null
    }
    val value = StructuralExpressionParser(
        expression,
        identifierValue = { name ->
            when (name) {
                "true" -> 1L
                "false" -> 0L
                else -> assignment[name]?.asProgramLong()
            }
        },
        isDefined = { name ->
            val setting = settings[name]
            when {
                setting == null -> false
                !setting.presenceToggle -> true
                else -> assignment.getValue(name).asProgramLong() != 0L
            }
        },
    ).parse() ?: return null
    return value != 0L
}

private fun String.asProgramLong(): Long? {
    val value = trim().removeSuffix("u").removeSuffix("U")
    return when {
        value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16)
        else -> value.toLongOrNull()
    }
}

private val PROGRAM_DEFINED =
    "\\bdefined\\s*(?:\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)|([A-Za-z_][A-Za-z0-9_]*))".toRegex()
