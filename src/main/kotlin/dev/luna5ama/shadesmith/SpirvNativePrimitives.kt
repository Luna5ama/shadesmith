package dev.luna5ama.shadesmith

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

internal data class SpirvBinaryInventory(
    val capabilities: Map<Int, Int>,
    val opcodes: Map<Int, Int>,
) {
    val requiresVulkanCrossSemantics: Boolean
        get() = capabilities.keys.any { it in GROUP_NON_UNIFORM_CAPABILITIES }

    fun opcodeCount(opcode: Int): Int = opcodes[opcode] ?: 0

    fun render(): String {
        val capabilitySummary = capabilities.entries
            .filter { it.key in GROUP_NON_UNIFORM_CAPABILITIES }
            .sortedBy { it.key }
            .joinToString(", ") { (capability, count) ->
                "${CAPABILITY_NAMES[capability] ?: "Capability$capability"}=$count"
            }
            .ifEmpty { "none" }
        val opcodeSummary = opcodes.entries
            .filter { it.key in NATIVE_SUBGROUP_OPCODES }
            .sortedBy { it.key }
            .joinToString(", ") { (opcode, count) ->
                "${OPCODE_NAMES[opcode] ?: "Op$opcode"}=$count"
            }
            .ifEmpty { "none" }
        return "capabilities: $capabilitySummary\nopcodes: $opcodeSummary"
    }

    companion object {
        const val OP_GROUP_NON_UNIFORM_SHUFFLE_XOR = 346
        const val OP_GROUP_NON_UNIFORM_SHUFFLE = 345
        const val OP_GROUP_NON_UNIFORM_PARTITION_NV = 5296

        fun read(path: Path): SpirvBinaryInventory {
            val bytes = Files.readAllBytes(path)
            require(bytes.size >= SPIRV_HEADER_BYTES && bytes.size % Int.SIZE_BYTES == 0) {
                "Invalid SPIR-V byte length ${bytes.size}: $path"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val words = IntArray(bytes.size / Int.SIZE_BYTES) { buffer.int }
            require(words[0] == SPIRV_MAGIC) { "Invalid SPIR-V magic in $path" }

            val capabilities = linkedMapOf<Int, Int>()
            val opcodes = linkedMapOf<Int, Int>()
            var cursor = SPIRV_HEADER_WORDS
            while (cursor < words.size) {
                val instruction = words[cursor].toUInt()
                val wordCount = (instruction shr 16).toInt()
                val opcode = (instruction and 0xffffu).toInt()
                require(wordCount > 0 && cursor + wordCount <= words.size) {
                    "Invalid SPIR-V instruction at word $cursor in $path"
                }
                opcodes[opcode] = opcodes.getOrDefault(opcode, 0) + 1
                if (opcode == OP_CAPABILITY && wordCount >= 2) {
                    val capability = words[cursor + 1]
                    capabilities[capability] = capabilities.getOrDefault(capability, 0) + 1
                }
                cursor += wordCount
            }
            return SpirvBinaryInventory(capabilities, opcodes)
        }

        private const val SPIRV_MAGIC = 0x07230203
        private const val SPIRV_HEADER_WORDS = 5
        private const val SPIRV_HEADER_BYTES = SPIRV_HEADER_WORDS * Int.SIZE_BYTES
        private const val OP_CAPABILITY = 17
        private val GROUP_NON_UNIFORM_CAPABILITIES = (61..68).toSet() + 5297
        private val NATIVE_SUBGROUP_OPCODES = (333..366).toSet() + OP_GROUP_NON_UNIFORM_PARTITION_NV
        private val CAPABILITY_NAMES = mapOf(
            61 to "GroupNonUniform",
            62 to "GroupNonUniformVote",
            63 to "GroupNonUniformArithmetic",
            64 to "GroupNonUniformBallot",
            65 to "GroupNonUniformShuffle",
            66 to "GroupNonUniformShuffleRelative",
            67 to "GroupNonUniformClustered",
            68 to "GroupNonUniformQuad",
            5297 to "GroupNonUniformPartitionedNV",
        )
        private val OPCODE_NAMES = mapOf(
            333 to "OpGroupNonUniformElect",
            334 to "OpGroupNonUniformAll",
            335 to "OpGroupNonUniformAny",
            336 to "OpGroupNonUniformAllEqual",
            337 to "OpGroupNonUniformBroadcast",
            338 to "OpGroupNonUniformBroadcastFirst",
            339 to "OpGroupNonUniformBallot",
            340 to "OpGroupNonUniformInverseBallot",
            341 to "OpGroupNonUniformBallotBitExtract",
            342 to "OpGroupNonUniformBallotBitCount",
            343 to "OpGroupNonUniformBallotFindLSB",
            344 to "OpGroupNonUniformBallotFindMSB",
            345 to "OpGroupNonUniformShuffle",
            OP_GROUP_NON_UNIFORM_SHUFFLE_XOR to "OpGroupNonUniformShuffleXor",
            347 to "OpGroupNonUniformShuffleUp",
            348 to "OpGroupNonUniformShuffleDown",
            349 to "OpGroupNonUniformIAdd",
            350 to "OpGroupNonUniformFAdd",
            351 to "OpGroupNonUniformIMul",
            352 to "OpGroupNonUniformFMul",
            353 to "OpGroupNonUniformSMin",
            354 to "OpGroupNonUniformUMin",
            355 to "OpGroupNonUniformFMin",
            356 to "OpGroupNonUniformSMax",
            357 to "OpGroupNonUniformUMax",
            358 to "OpGroupNonUniformFMax",
            359 to "OpGroupNonUniformBitwiseAnd",
            360 to "OpGroupNonUniformBitwiseOr",
            361 to "OpGroupNonUniformBitwiseXor",
            362 to "OpGroupNonUniformLogicalAnd",
            363 to "OpGroupNonUniformLogicalOr",
            364 to "OpGroupNonUniformLogicalXor",
            365 to "OpGroupNonUniformQuadBroadcast",
            366 to "OpGroupNonUniformQuadSwap",
            OP_GROUP_NON_UNIFORM_PARTITION_NV to "OpGroupNonUniformPartitionNV",
        )
    }
}

internal sealed interface SpirvNativePrimitiveRestoration {
    data class Restored(val source: String) : SpirvNativePrimitiveRestoration
    data class Preserved(val reason: String) : SpirvNativePrimitiveRestoration
}

internal class SpirvNativePrimitiveContract private constructor(
    private val partitionedPrimitives: Set<String>,
    private val broadcastTypes: Set<String>,
) {
    val primitives: Set<String> = partitionedPrimitives +
        if (broadcastTypes.isEmpty()) emptySet() else setOf("subgroupBroadcast")
    val requiresCompilerAdapter: Boolean
        get() = broadcastTypes.isNotEmpty()
    val requiresCrossAdapter: Boolean
        get() = partitionedPrimitives.isNotEmpty()

    fun prepareCompilerSource(source: String): String {
        if (!requiresCompilerAdapter) return source
        return prepareSource(source, includePartitioned = false)
    }

    fun prepareCrossSource(source: String): String = prepareSource(source, includePartitioned = true)

    private fun prepareSource(source: String, includePartitioned: Boolean): String {
        require(ADAPTER_PREFIX !in source) { "compiler source already contains reserved native adapter prefix" }
        val adaptedPrimitives = buildSet {
            if (includePartitioned) addAll(partitionedPrimitives)
            if (requiresCompilerAdapter) add("subgroupBroadcast")
        }
        if (adaptedPrimitives.isEmpty()) return source
        val adapter = buildString {
            appendLine(ADAPTER_BEGIN)
            if (requiresCompilerAdapter) {
                appendLine("#extension GL_KHR_shader_subgroup_shuffle : enable")
            }
            adaptedPrimitives.sorted().forEach { primitive ->
                append("#define ")
                append(primitive)
                append(' ')
                append(ADAPTER_PREFIX)
                appendLine(primitive)
            }
            adaptedPrimitives.sorted().forEach { primitive ->
                append(renderAdapterFunctions(primitive, broadcastTypes))
            }
            appendLine(ADAPTER_END)
        }
        val offset = compilerPrologueEnd(source)
        val prefix = if (offset > 0 && source[offset - 1] !in "\r\n") "\n" else ""
        val suffix = if (offset < source.length && source[offset] !in "\r\n") "\n" else ""
        return source.substring(0, offset) + prefix + adapter + suffix + source.substring(offset)
    }

    fun restoreCrossOutput(source: String): SpirvNativePrimitiveRestoration {
        val ranges = ADAPTER_FUNCTION_HEADER.findAll(source).mapNotNull { match ->
            val openingBrace = source.indexOf('{', match.range.first)
            if (openingBrace < 0 || openingBrace > match.range.last) return@mapNotNull null
            val closingBrace = matchingBrace(source, openingBrace) ?: return@mapNotNull null
            var end = closingBrace + 1
            while (end < source.length && source[end] in "\t ") end++
            if (source.getOrNull(end) == '\r') end++
            if (source.getOrNull(end) == '\n') end++
            match.range.first until end
        }.toList()
        if (ranges.isEmpty()) {
            return SpirvNativePrimitiveRestoration.Preserved(
                "SPIRV-Cross native primitive adapter functions disappeared before restoration",
            )
        }
        var result = removeRanges(source, ranges)
        primitives.sortedByDescending(String::length).forEach { primitive ->
            val identifier = Regex(
                "(?<![A-Za-z0-9_])${Regex.escape(ADAPTER_PREFIX + primitive)}(?:_[0-9]+)?",
            )
            result = identifier.replace(result, primitive)
        }
        if (ADAPTER_PREFIX in result) {
            return SpirvNativePrimitiveRestoration.Preserved(
                "SPIRV-Cross native primitive adapter residue remains after restoration",
            )
        }
        val missing = primitives.filterNot { primitive -> identifierCall(primitive).containsMatchIn(result) }
        if (missing.isNotEmpty()) {
            return SpirvNativePrimitiveRestoration.Preserved(
                "SPIRV-Cross removed native primitive calls ${missing.sorted()}",
            )
        }
        return SpirvNativePrimitiveRestoration.Restored(result.trimEnd() + "\n")
    }

    companion object {
        fun plan(source: String): SpirvNativePrimitiveContract? {
            val partitioned = PARTITIONED_CALL.findAll(source).mapTo(sortedSetOf()) { it.groupValues[1] }
            val unsupported = partitioned.filterNot {
                it == "subgroupPartitionNV" || PARTITIONED_REDUCTION.matches(it)
            }
            require(unsupported.isEmpty()) {
                "unsupported native partitioned subgroup primitives ${unsupported.sorted()}"
            }
            val broadcastTypes = BROADCAST_CALL.findAll(source)
                .filterNot { call -> BROADCAST_CONSTANT_ID.matches(call.groupValues[2].trim()) }
                .mapTo(sortedSetOf()) { call -> resolveBroadcastType(source, call.groupValues[1]) }
            if (partitioned.isEmpty() && broadcastTypes.isEmpty()) return null
            return SpirvNativePrimitiveContract(partitioned, broadcastTypes)
        }

        private fun resolveBroadcastType(source: String, valueName: String): String {
            val types = Regex(
                "\\b(${BROADCAST_TYPES.joinToString("|") { Regex.escape(it) }})[\\t ]+" +
                    Regex.escape(valueName) + "\\b",
            ).findAll(source).mapTo(sortedSetOf()) { it.groupValues[1] }
            require(types.size == 1) {
                "dynamic subgroupBroadcast value $valueName has unresolved types ${types.sorted()}"
            }
            return types.single()
        }

        private fun renderAdapterFunctions(primitive: String, broadcastTypes: Set<String>): String {
            if (primitive == "subgroupBroadcast") {
                return broadcastTypes.joinToString("") { type ->
                    "$type $ADAPTER_PREFIX$primitive($type value, uint id) { return subgroupShuffle(value, id); }\n"
                }
            }
            if (primitive == "subgroupPartitionNV") {
                return "uvec4 $ADAPTER_PREFIX$primitive(uint value) { return uvec4(value); }\n"
            }
            val bitwise = primitive.contains("AndNV") || primitive.contains("OrNV") || primitive.contains("XorNV")
            val types = if (bitwise) BITWISE_TYPES else NUMERIC_TYPES
            return types.joinToString("") { type ->
                "$type $ADAPTER_PREFIX$primitive($type value, uvec4 partitionMask) { return value; }\n"
            }
        }

        private fun compilerPrologueEnd(source: String): Int {
            val version = VERSION_LINE.find(source)
                ?: throw IllegalArgumentException("native primitive compiler copy has no #version directive")
            var cursor = lineEnd(source, version.range.last + 1)
            var blockComment = false
            var continuation = false
            while (cursor < source.length) {
                val end = lineEnd(source, cursor)
                val line = source.substring(cursor, end).trimEnd('\r', '\n')
                val trimmed = line.trim()
                val directive = continuation || trimmed.startsWith('#')
                if (blockComment) {
                    if ("*/" in trimmed) blockComment = false
                } else if (trimmed.startsWith("/*")) {
                    blockComment = "*/" !in trimmed
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !directive) {
                    return cursor
                }
                continuation = directive && line.trimEnd().endsWith('\\')
                cursor = end
            }
            return cursor
        }

        private fun lineEnd(source: String, start: Int): Int {
            var end = start
            while (end < source.length && source[end] !in "\r\n") end++
            if (source.getOrNull(end) == '\r') end++
            if (source.getOrNull(end) == '\n') end++
            return end
        }

        private fun matchingBrace(source: String, opening: Int): Int? {
            var depth = 0
            var cursor = opening
            var blockComment = false
            var lineComment = false
            var quote: Char? = null
            while (cursor < source.length) {
                val char = source[cursor]
                val next = source.getOrNull(cursor + 1)
                if (lineComment) {
                    if (char == '\n') lineComment = false
                    cursor++
                    continue
                }
                if (blockComment) {
                    if (char == '*' && next == '/') {
                        blockComment = false
                        cursor += 2
                    } else {
                        cursor++
                    }
                    continue
                }
                val currentQuote = quote
                if (currentQuote != null) {
                    if (char == '\\' && next != null) cursor += 2
                    else {
                        if (char == currentQuote) quote = null
                        cursor++
                    }
                    continue
                }
                when {
                    char == '/' && next == '/' -> {
                        lineComment = true
                        cursor += 2
                    }
                    char == '/' && next == '*' -> {
                        blockComment = true
                        cursor += 2
                    }
                    char == '"' || char == '\'' -> {
                        quote = char
                        cursor++
                    }
                    char == '{' -> {
                        depth++
                        cursor++
                    }
                    char == '}' -> {
                        depth--
                        if (depth == 0) return cursor
                        cursor++
                    }
                    else -> cursor++
                }
            }
            return null
        }

        private fun removeRanges(source: String, ranges: List<IntRange>): String {
            val ordered = ranges.distinct().sortedBy { it.first }
            return buildString(source.length - ordered.sumOf(IntRange::count)) {
                var cursor = 0
                ordered.forEach { range ->
                    require(range.first >= cursor) { "overlapping native adapter function ranges" }
                    append(source, cursor, range.first)
                    cursor = range.last + 1
                }
                append(source, cursor, source.length)
            }
        }

        private fun identifierCall(name: String): Regex {
            return "(?<![A-Za-z0-9_])${Regex.escape(name)}[\\t ]*\\(".toRegex()
        }

        private const val ADAPTER_PREFIX = "SM_SPIRV_CROSS_NATIVE_"
        private const val ADAPTER_BEGIN = "// SHADESMITH_NATIVE_PRIMITIVE_ADAPTER_BEGIN"
        private const val ADAPTER_END = "// SHADESMITH_NATIVE_PRIMITIVE_ADAPTER_END"
        private val PARTITIONED_CALL = Regex(
            "(?<![A-Za-z0-9_])(subgroupPartitionNV|subgroupPartitioned[A-Za-z0-9_]*NV)[\\t ]*\\(",
        )
        private val PARTITIONED_REDUCTION =
            "subgroupPartitioned(?:Inclusive|Exclusive)?(?:Add|Mul|Min|Max|And|Or|Xor)NV".toRegex()
        private val BROADCAST_CALL = Regex(
            "(?<![A-Za-z0-9_])subgroupBroadcast[\\t ]*\\([\\t ]*" +
                "([A-Za-z_][A-Za-z0-9_]*)[\\t ]*,[\\t ]*([^,()]+)[\\t ]*\\)",
        )
        private val BROADCAST_CONSTANT_ID = "[0-9]+[uU]?".toRegex()
        private val ADAPTER_FUNCTION_HEADER = Regex(
            "(?m)^[\\t ]*(?:[A-Za-z_][A-Za-z0-9_]*[\\t ]+)+" +
                Regex.escape(ADAPTER_PREFIX) + "[A-Za-z0-9_]*[\\t ]*\\([^;{}]*\\)\\s*\\{",
        )
        private val VERSION_LINE = "(?m)^[\\t ]*#version[^\\r\\n]*".toRegex()
        private val BITWISE_TYPES = listOf(
            "int", "uint", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
        )
        private val NUMERIC_TYPES = BITWISE_TYPES + listOf(
            "float", "vec2", "vec3", "vec4", "double", "dvec2", "dvec3", "dvec4",
        )
        private val BROADCAST_TYPES = NUMERIC_TYPES + listOf(
            "bool", "bvec2", "bvec3", "bvec4",
        )
    }
}
