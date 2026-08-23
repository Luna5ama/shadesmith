package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class ShaderVariantMaterializationException(
    val sourceName: String,
    val stage: ShaderStage,
    val artifactDirectory: Path,
    val command: List<String>,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append(sourceName)
        append(" [")
        append(stage.glslangName)
        append("] failed during clang variant materialization: ")
        append(detail)
        appendLine()
        append("Command: ")
        append(command.joinToString(" ") { it.asMaterializerDiagnosticArgument() })
        appendLine()
        append("Artifacts: ")
        append(artifactDirectory.toAbsolutePath().normalize())
    },
    cause,
)

internal class ShaderVariantMaterializer(
    workingDirectory: Path,
    private val clangExecutable: String = "clang",
) {
    val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()

    init {
        require(clangExecutable.isNotBlank()) { "clang executable cannot be blank" }
        this.workingDirectory.createDirectories()
    }

    fun materialize(
        sourceName: String,
        stage: ShaderStage,
        protection: ProtectedPreprocessorSource,
        probe: TextureAccessProbe,
    ): List<SpirvShaderVariant> {
        val probeProtection = PreprocessorProtection.protect(probe.source, sourceName)
        val requiredBranches = requiredPreprocessorBranches(protection)
        val structure = ConditionalStructure.from(probeProtection.directives)
        val requestDirectory = workingDirectory.resolve(
            "${safeName(sourceName)}-${stage.glslangName}-${shortHash(probe.source)}",
        )
        requestDirectory.createDirectories()

        val specifications = linkedMapOf<String, VariantSpecification>()
        val targets: List<PreprocessorBranchSelection?> = if (requiredBranches.isEmpty()) {
            listOf(null)
        } else {
            requiredBranches.sortedWith(
                compareBy(PreprocessorBranchSelection::conditionalId, PreprocessorBranchSelection::branchIndex),
            )
        }
        targets.forEach { target ->
            val forcedBranches = if (target == null) emptyMap() else structure.pathTo(target)
            val forcedSource = forceBranches(probe.source, probeProtection.directives, structure, forcedBranches)
            val coverage = forcedBranches.mapNotNullTo(linkedSetOf()) { (conditionalId, branchIndex) ->
                PreprocessorBranchSelection(conditionalId, branchIndex).takeIf { it in requiredBranches }
            }
            val name = target?.let { "branch-${it.conditionalId}-${it.branchIndex}" } ?: "default"
            val existing = specifications[forcedSource]
            if (existing == null) {
                specifications[forcedSource] = VariantSpecification(name, forcedSource, coverage.toMutableSet())
            } else {
                existing.coveredBranches += coverage
            }
        }

        return specifications.values.mapIndexed { index, specification ->
            val materialized = runClang(
                sourceName,
                stage,
                specification.name,
                specification.source,
                requestDirectory.resolve(index.toString().padStart(4, '0')),
            )
            SpirvShaderVariant(
                name = specification.name,
                source = materialized,
                coveredBranches = specification.coveredBranches,
                resourceMarkers = probe.markers,
                conservativeAccess = probe.conservativeAccess,
            )
        }
    }

    private fun runClang(
        sourceName: String,
        stage: ShaderStage,
        variantName: String,
        source: String,
        artifactDirectory: Path,
    ): String {
        artifactDirectory.createDirectories()
        val inputPath = artifactDirectory.resolve("forced.glsl")
        val clangInputPath = artifactDirectory.resolve("clang-input.glsl")
        val outputPath = artifactDirectory.resolve("materialized.glsl")
        val stdoutPath = artifactDirectory.resolve("clang.stdout.log")
        val stderrPath = artifactDirectory.resolve("clang.stderr.log")
        inputPath.writeText(source)
        val clangSource = protectGlslDirectives(normalizePunctuationTokenPaste(source))
        clangInputPath.writeText(clangSource.source)
        Files.deleteIfExists(outputPath)
        Files.writeString(stdoutPath, "")
        Files.writeString(stderrPath, "")

        val command = listOf(
            clangExecutable,
            "-C",
            "-E",
            "-P",
            "-Wno-microsoft-include",
            "-x",
            "c",
            clangInputPath.absolutePathString(),
            "-o",
            outputPath.absolutePathString(),
        )
        val exitCode = try {
            ProcessBuilder(command)
                .directory(artifactDirectory.toFile())
                .redirectOutput(stdoutPath.toFile())
                .redirectError(stderrPath.toFile())
                .start()
                .waitFor()
        } catch (e: IOException) {
            throw failure(sourceName, stage, artifactDirectory, command, "unable to start clang for $variantName", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure(sourceName, stage, artifactDirectory, command, "interrupted while materializing $variantName", e)
        }
        if (exitCode != 0) {
            throw failure(sourceName, stage, artifactDirectory, command, "clang exited with code $exitCode for $variantName")
        }
        if (!outputPath.isRegularFile()) {
            throw failure(sourceName, stage, artifactDirectory, command, "clang produced no output for $variantName")
        }

        val materialized = restoreGlslDirectives(outputPath.readText(), clangSource.namespace)
        outputPath.writeText(materialized)
        return materialized
    }

    private fun forceBranches(
        source: String,
        directives: List<PreprocessorDirective>,
        structure: ConditionalStructure,
        forcedBranches: Map<Int, Int>,
    ): String {
        if (forcedBranches.isEmpty()) return source
        val lineStarts = buildList {
            add(0)
            LINE_ENDING.findAll(source).forEach { add(it.range.last + 1) }
        }
        val replacements = directives.mapNotNull { directive ->
            val selectedBranch = forcedBranches[directive.conditionalId] ?: return@mapNotNull null
            val branch = structure.branchByDirective[directive.index] ?: return@mapNotNull null
            if (directive.kind !in CONDITIONAL_TEST_DIRECTIVES) return@mapNotNull null
            val start = lineStarts[directive.sourceLine - 1]
            val end = if (directive.endLine < lineStarts.size) lineStarts[directive.endLine] else source.length
            val exact = source.substring(start, end)
            val keyword = if (directive.kind == PreprocessorDirectiveKind.ELIF) "#elif" else "#if"
            Replacement(start, end, forcedDirective(exact, keyword, branch.branchIndex == selectedBranch))
        }
        if (replacements.isEmpty()) return source

        return buildString(source.length) {
            var cursor = 0
            replacements.sortedBy { it.start }.forEach { replacement ->
                append(source, cursor, replacement.start)
                append(replacement.text)
                cursor = replacement.end
            }
            append(source, cursor, source.length)
        }
    }

    private fun forcedDirective(exact: String, keyword: String, selected: Boolean): String {
        val indentation = exact.substringBefore('#')
        val endings = LINE_ENDING.findAll(exact).map { it.value }.toList()
        return buildString {
            append(indentation)
            append(keyword)
            append(if (selected) " 1" else " 0")
            endings.forEach { append(it) }
        }
    }

    private fun protectGlslDirectives(source: String): ProtectedMaterializerSource {
        var namespace = "__SHADESMITH_MATERIALIZE__"
        while (source.contains(namespace)) namespace += "_"
        return ProtectedMaterializerSource(
            source = PROTECTED_GLSL_DIRECTIVE.replace(source) { match ->
                match.groupValues[1] + "//$namespace" + match.groupValues[2]
            },
            namespace = namespace,
        )
    }

    private fun restoreGlslDirectives(source: String, namespace: String): String {
        return source.replace("//$namespace", "")
    }

    private fun normalizePunctuationTokenPaste(source: String): String {
        return TOKEN_PASTE_BEFORE_OPENING_DELIMITER.replace(source, "")
    }

    private fun failure(
        sourceName: String,
        stage: ShaderStage,
        artifactDirectory: Path,
        command: List<String>,
        detail: String,
        cause: Throwable? = null,
    ): ShaderVariantMaterializationException {
        return ShaderVariantMaterializationException(
            sourceName,
            stage,
            artifactDirectory,
            command,
            "$detail; see clang.stdout.log and clang.stderr.log",
            cause,
        )
    }

    private data class VariantSpecification(
        val name: String,
        val source: String,
        val coveredBranches: MutableSet<PreprocessorBranchSelection>,
    )

    private data class Replacement(val start: Int, val end: Int, val text: String)

    private data class ProtectedMaterializerSource(val source: String, val namespace: String)

    private data class BranchLocation(val conditionalId: Int, val branchIndex: Int)

    private data class ConditionalNode(
        val parent: BranchLocation?,
        val disposition: PreprocessorDisposition,
    )

    private data class ConditionalFrame(val conditionalId: Int, var branchIndex: Int)

    private data class ConditionalStructure(
        val nodes: Map<Int, ConditionalNode>,
        val branchByDirective: Map<Int, BranchLocation>,
    ) {
        fun pathTo(target: PreprocessorBranchSelection): Map<Int, Int> {
            val result = linkedMapOf<Int, Int>()
            var current: BranchLocation? = BranchLocation(target.conditionalId, target.branchIndex)
            while (current != null) {
                val node = nodes[current.conditionalId]
                    ?: error("Missing conditional ${current.conditionalId} while materializing a shader variant")
                if (node.disposition == PreprocessorDisposition.RESTORED) {
                    result[current.conditionalId] = current.branchIndex
                }
                current = node.parent
            }
            return result
        }

        companion object {
            fun from(directives: List<PreprocessorDirective>): ConditionalStructure {
                val nodes = linkedMapOf<Int, ConditionalNode>()
                val branches = linkedMapOf<Int, BranchLocation>()
                val stack = mutableListOf<ConditionalFrame>()
                directives.forEach { directive ->
                    when (directive.kind) {
                        in PREPROCESSOR_CONDITIONAL_OPENERS -> {
                            val conditionalId = requireNotNull(directive.conditionalId)
                            val parent = stack.lastOrNull()?.let { BranchLocation(it.conditionalId, it.branchIndex) }
                            nodes[conditionalId] = ConditionalNode(parent, directive.disposition)
                            stack += ConditionalFrame(conditionalId, 0)
                            branches[directive.index] = BranchLocation(conditionalId, 0)
                        }

                        PreprocessorDirectiveKind.ELIF,
                        PreprocessorDirectiveKind.ELSE,
                        -> {
                            val frame = stack.last()
                            frame.branchIndex++
                            branches[directive.index] = BranchLocation(frame.conditionalId, frame.branchIndex)
                        }

                        PreprocessorDirectiveKind.ENDIF -> stack.removeAt(stack.lastIndex)
                        else -> Unit
                    }
                }
                return ConditionalStructure(nodes, branches)
            }
        }
    }

    private fun safeName(name: String): String {
        return name.replace(INVALID_PATH_CHAR, "_").trim('_').take(80).ifEmpty { "shader" }
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private val INVALID_PATH_CHAR = "[^A-Za-z0-9._-]".toRegex()
        private val LINE_ENDING = "\\r\\n|\\n|\\r".toRegex()
        private val PROTECTED_GLSL_DIRECTIVE =
            "(?m)^([ \\t]*)(#(?:version|extension|pragma|line)\\b)".toRegex()
        private val TOKEN_PASTE_BEFORE_OPENING_DELIMITER = """##(?=[ \t]*[({\[])""".toRegex()
        private val CONDITIONAL_TEST_DIRECTIVES = PREPROCESSOR_CONDITIONAL_OPENERS + PreprocessorDirectiveKind.ELIF
    }
}

internal fun requiredPreprocessorBranches(
    protection: ProtectedPreprocessorSource,
): Set<PreprocessorBranchSelection> {
    val groups = protection.directives
        .filter { it.disposition == PreprocessorDisposition.RESTORED && it.conditionalId != null }
        .groupBy { it.conditionalId!! }
    return buildSet {
        groups.forEach { (conditionalId, directives) ->
            if (directives.any { PreprocessorFeature.INCLUDE_GUARD in it.features }) return@forEach
            val branchDirectives = directives.filter {
                it.kind in PREPROCESSOR_CONDITIONAL_OPENERS ||
                    it.kind == PreprocessorDirectiveKind.ELIF ||
                    it.kind == PreprocessorDirectiveKind.ELSE
            }
            if (branchDirectives.none { PreprocessorFeature.SETTING in it.features }) return@forEach
            branchDirectives.indices.forEach { branchIndex ->
                add(PreprocessorBranchSelection(conditionalId, branchIndex))
            }
            if (
                branchDirectives.none { it.kind == PreprocessorDirectiveKind.ELSE } &&
                !hasExhaustiveOptionDomain(branchDirectives, protection.directives)
            ) {
                add(PreprocessorBranchSelection(conditionalId, branchDirectives.size))
            }
        }
    }
}

private fun hasExhaustiveOptionDomain(
    branchDirectives: List<PreprocessorDirective>,
    directives: List<PreprocessorDirective>,
): Boolean {
    if (branchDirectives.isEmpty() || branchDirectives.any { it.kind == PreprocessorDirectiveKind.ELSE }) return false

    val domains = directives.asSequence()
        .filter { it.kind == PreprocessorDirectiveKind.DEFINE && it.macroName != null }
        .mapNotNull { directive ->
            optionDomain(directive.macroBody.orEmpty())?.let { directive.macroName!! to it }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.distinct().singleOrNull() }
        .filterValues { it != null }
        .mapValues { (_, value) -> value!! }
    val comparisons = branchDirectives.map { directive ->
        optionEquality(directive.exactText, domains.keys) ?: return false
    }
    val macroName = comparisons.first().first
    if (comparisons.any { it.first != macroName }) return false
    val domain = domains[macroName] ?: return false
    val coveredValues = comparisons.mapTo(hashSetOf()) { it.second }
    return domain.isNotEmpty() && domain.all { it in coveredValues }
}

private fun optionDomain(macroBody: String): Set<String>? {
    val values = OPTION_DOMAIN.find(macroBody)?.groupValues?.get(1)
        ?.trim()
        ?.split(WHITESPACE)
        ?.filterTo(linkedSetOf()) { it.isNotEmpty() }
    return values?.takeIf { it.isNotEmpty() }
}

private fun optionEquality(
    directiveText: String,
    optionMacros: Set<String>,
): Pair<String, String>? {
    val expression = CONDITIONAL_EXPRESSION.find(
        directiveText
            .replace("\\\r\n", "")
            .replace("\\\n", "")
            .replace("\\\r", ""),
    )?.groupValues?.get(1)?.substringBefore("//")?.trim() ?: return null
    val match = SIMPLE_EQUALITY.matchEntire(expression) ?: return null
    val left = match.groupValues[1]
    val right = match.groupValues[2]
    return when {
        left in optionMacros && right !in optionMacros -> left to right
        right in optionMacros && left !in optionMacros -> right to left
        else -> null
    }
}

private val PREPROCESSOR_CONDITIONAL_OPENERS = setOf(
    PreprocessorDirectiveKind.IF,
    PreprocessorDirectiveKind.IFDEF,
    PreprocessorDirectiveKind.IFNDEF,
)
private val OPTION_DOMAIN = """//\s*\[([^]\r\n]+)]""".toRegex()
private val WHITESPACE = """\s+""".toRegex()
private val CONDITIONAL_EXPRESSION = """(?s)^\s*#\s*(?:if|elif)\b(.*)$""".toRegex()
private const val OPTION_TOKEN = """(?:[A-Za-z_][A-Za-z0-9_]*|[-+]?(?:0[xX][0-9A-Fa-f]+|(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)[uUlLfF]?)"""
private val SIMPLE_EQUALITY = """\s*\(*\s*($OPTION_TOKEN)\s*==\s*($OPTION_TOKEN)\s*\)*\s*""".toRegex()

private fun String.asMaterializerDiagnosticArgument(): String {
    if (none { it.isWhitespace() || it == '"' }) return this
    return "\"${replace("\"", "\\\"")}\""
}
