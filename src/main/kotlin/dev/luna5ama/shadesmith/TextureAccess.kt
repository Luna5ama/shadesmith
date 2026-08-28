package dev.luna5ama.shadesmith

internal data class TextureAccess(
    val reads: Set<String> = emptySet(),
    val writes: Set<String> = emptySet(),
) {
    operator fun plus(other: TextureAccess): TextureAccess {
        return TextureAccess(reads + other.reads, writes + other.writes)
    }
}

internal enum class TextureMarkerAccess {
    READ,
    WRITE,
    READ_WRITE,
}

internal enum class TextureMarkerKind {
    SAMPLER,
    IMAGE,
}

internal data class TextureResourceMarker(
    val identifier: String,
    val sourceIdentifier: String,
    val texture: String,
    val access: TextureMarkerAccess,
    val kind: TextureMarkerKind,
)

internal data class TextureAccessProbe(
    val source: String,
    val markers: List<TextureResourceMarker>,
    val conservativeAccess: TextureAccess,
)

internal object TextureAccessAnalyzer {
    fun createProbe(source: String, config: Config): TextureAccessProbe {
        var probeSource = source
        val calls = logicalCalls(source)
        val markers = linkedMapOf<Triple<String, TextureMarkerAccess, TextureMarkerKind>, TextureResourceMarker>()
        val instrumentedCalls = mutableSetOf<LogicalTextureCall>()

        calls.sortedWith(compareBy(LogicalTextureCall::texture, LogicalTextureCall::operation)).forEach { call ->
            val format = config.screen[call.texture] ?: config.fixed[call.texture]?.format ?: return@forEach
            val sourceIdentifier = sourceResourceIdentifier(probeSource, call) ?: return@forEach
            val access = call.operation.markerAccess
            val key = Triple(call.texture, access, call.operation.markerKind)
            val marker = markers.getOrPut(key) {
                val baseIdentifier = "shadesmith_resource_${access.name.lowercase()}_" +
                    "${call.operation.markerKind.name.lowercase()}_${call.texture}"
                TextureResourceMarker(
                    identifier = uniqueIdentifier(baseIdentifier, source, markers.values),
                    sourceIdentifier = sourceIdentifier,
                    texture = call.texture,
                    access = access,
                    kind = call.operation.markerKind,
                )
            }
            val replacement = replaceMacroResource(probeSource, call, marker.identifier)
            if (replacement != null) {
                probeSource = replacement
                instrumentedCalls += call
            }
        }

        val usedMarkers = markers.values.filter { marker ->
            instrumentedCalls.any {
                it.texture == marker.texture &&
                    it.operation.markerAccess == marker.access &&
                    it.operation.markerKind == marker.kind
            }
        }
        if (usedMarkers.isNotEmpty()) {
            val declarations = usedMarkers
                .sortedBy { it.identifier }
                .joinToString("\n") { marker -> marker.declaration(config) }
            probeSource = insertAfterVersion(probeSource, "$declarations\n")
        }

        val conservativeCalls = calls - instrumentedCalls
        return TextureAccessProbe(
            source = probeSource,
            markers = usedMarkers,
            conservativeAccess = TextureAccess(
                reads = conservativeCalls.filter { it.operation.reads }.mapTo(linkedSetOf()) { it.texture },
                writes = conservativeCalls.filter { it.operation.writes }.mapTo(linkedSetOf()) { it.texture },
            ),
        )
    }

    fun fromOptimizedSource(
        source: String,
        markers: List<TextureResourceMarker> = emptyList(),
    ): TextureAccess {
        val markerByIdentifier = markers.associateBy { it.identifier }
        val reads = linkedSetOf<String>()
        val writes = linkedSetOf<String>()

        RESOURCE_OPERATION_REGEX.findAll(source).forEach { match ->
            val operation = match.groupValues[1]
            val identifier = match.groupValues[2]
            val marker = markerByIdentifier[identifier]
            val texture = marker?.texture ?: identifier.takeIf { LOGICAL_TEXTURE_NAME.matches(it) } ?: return@forEach
            val access = marker?.access ?: operation.markerAccess
            if (access != TextureMarkerAccess.WRITE) reads += texture
            if (access != TextureMarkerAccess.READ) writes += texture
        }

        val logical = logicalCalls(source)
        reads += logical.filter { it.operation.reads }.map { it.texture }
        writes += logical.filter { it.operation.writes }.map { it.texture }
        return TextureAccess(reads, writes)
    }

    fun restoreProbeResources(
        source: String,
        markers: List<TextureResourceMarker>,
    ): String {
        var result = source
        markers.sortedByDescending { it.identifier.length }.forEach { marker ->
            val declaration = Regex(
                "(?m)^[\\t ]*(?:layout\\s*\\([^\\r\\n)]*\\)\\s*)?uniform\\b[^;\\r\\n]*" +
                    "\\b${Regex.escape(marker.identifier)}\\b[^;\\r\\n]*;[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)",
            )
            val declarations = declaration.findAll(result).toList()
            require(declarations.size <= 1) { "ambiguous probe declaration ${marker.identifier}" }
            declarations.singleOrNull()?.let { result = result.removeRange(it.range) }
            result = identifierRegex(marker.identifier).replace(result, marker.sourceIdentifier)
        }
        return result.trimEnd() + "\n"
    }

    private fun logicalCalls(source: String): Set<LogicalTextureCall> {
        return LOGICAL_ACCESS_REGEX.findAll(source)
            .filterNot { source.isPreprocessorLine(it.range.first) }
            .map { match ->
                LogicalTextureCall(
                    texture = match.groupValues[1],
                    operation = TextureOperation.fromName(match.groupValues[2]),
                )
            }
            .toCollection(linkedSetOf())
    }

    private fun replaceMacroResource(
        source: String,
        call: LogicalTextureCall,
        markerIdentifier: String,
    ): String? {
        val macro = Regex(
            "(?m)^([ \\t]*#[ \\t]*define[ \\t]+${Regex.escape(call.texture)}_" +
                "${Regex.escape(call.operation.sourceName)}\\([^\\r\\n]*\\)[ \\t]+" +
                "[A-Za-z_][A-Za-z0-9_]*\\([ \\t]*)([A-Za-z_][A-Za-z0-9_]*)",
        )
        val match = macro.find(source) ?: return null
        return source.replaceRange(match.range, match.groupValues[1] + markerIdentifier)
    }

    private fun sourceResourceIdentifier(source: String, call: LogicalTextureCall): String? {
        val macro = Regex(
            "(?m)^[ \\t]*#[ \\t]*define[ \\t]+${Regex.escape(call.texture)}_" +
                "${Regex.escape(call.operation.sourceName)}\\([^\\r\\n]*\\)[ \\t]+" +
                "[A-Za-z_][A-Za-z0-9_]*\\([ \\t]*([A-Za-z_][A-Za-z0-9_]*)",
        )
        return macro.find(source)?.groupValues?.get(1)
    }

    private fun identifierRegex(name: String): Regex {
        return "(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])".toRegex()
    }

    private fun TextureResourceMarker.declaration(config: Config): String {
        val format = config.screen[texture] ?: config.fixed.getValue(texture).format
        return when (kind) {
            TextureMarkerKind.SAMPLER -> "uniform ${format.samplerType} $identifier;"
            TextureMarkerKind.IMAGE -> "layout(${format.imageFormat}) uniform ${format.imageType} $identifier;"
        }
    }

    private fun insertAfterVersion(source: String, text: String): String {
        val version = VERSION_LINE.find(source)
            ?: throw IllegalArgumentException("Shader has no #version directive for texture access instrumentation")
        return source.replaceRange(version.range.last + 1, version.range.last + 1, text)
    }

    private fun uniqueIdentifier(
        base: String,
        source: String,
        existing: Collection<TextureResourceMarker>,
    ): String {
        var identifier = base
        while (
            existing.any { it.identifier == identifier } ||
            Regex("(?<![A-Za-z0-9_])${Regex.escape(identifier)}(?![A-Za-z0-9_])").containsMatchIn(source)
        ) {
            identifier += "_"
        }
        return identifier
    }

    private fun String.isPreprocessorLine(offset: Int): Boolean {
        val lineStart = lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        return substring(lineStart, offset).trimStart().startsWith('#')
    }

    private val TextureFormat.samplerType: String
        get() = when {
            name.endsWith("UI") -> "usampler2D"
            name.endsWith("I") -> "isampler2D"
            else -> "sampler2D"
        }

    private val TextureFormat.imageType: String
        get() = when {
            name.endsWith("UI") -> "uimage2D"
            name.endsWith("I") -> "iimage2D"
            else -> "image2D"
        }

    private val TextureFormat.imageFormat: String
        get() = name.lowercase()

    private data class LogicalTextureCall(
        val texture: String,
        val operation: TextureOperation,
    )

    private enum class TextureOperation(
        val sourceName: String,
        val reads: Boolean,
        val writes: Boolean,
        val markerKind: TextureMarkerKind,
    ) {
        SAMPLE("sample", true, false, TextureMarkerKind.SAMPLER),
        GATHER("gather", true, false, TextureMarkerKind.SAMPLER),
        GATHER_TEXEL("gatherTexel", true, false, TextureMarkerKind.SAMPLER),
        FETCH("fetch", true, false, TextureMarkerKind.SAMPLER),
        LOAD("load", true, false, TextureMarkerKind.IMAGE),
        STORE("store", false, true, TextureMarkerKind.IMAGE),
        ATOMIC_ADD("atomicAdd", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_MIN("atomicMin", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_MAX("atomicMax", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_AND("atomicAnd", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_OR("atomicOr", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_XOR("atomicXor", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_EXCHANGE("atomicExchange", true, true, TextureMarkerKind.IMAGE),
        ATOMIC_COMP_SWAP("atomicCompSwap", true, true, TextureMarkerKind.IMAGE);

        val markerAccess: TextureMarkerAccess
            get() = when {
                reads && writes -> TextureMarkerAccess.READ_WRITE
                reads -> TextureMarkerAccess.READ
                else -> TextureMarkerAccess.WRITE
            }

        companion object {
            private val BY_NAME = entries.associateBy { it.sourceName }

            fun fromName(name: String): TextureOperation {
                return requireNotNull(BY_NAME[name]) { "Unknown texture operation $name" }
            }
        }
    }

    private val String.markerAccess: TextureMarkerAccess
        get() = when {
            startsWith("imageStore") -> TextureMarkerAccess.WRITE
            startsWith("imageAtomic") -> TextureMarkerAccess.READ_WRITE
            else -> TextureMarkerAccess.READ
        }

    private const val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*"
    private const val LOGICAL_TEXTURE = "(?:transient|history|persistent)_$IDENTIFIER"
    private const val ATOMIC_OPERATION = "atomic(?:Add|Min|Max|And|Or|Xor|Exchange|CompSwap)"
    private val LOGICAL_TEXTURE_NAME = LOGICAL_TEXTURE.toRegex()
    private val LOGICAL_ACCESS_REGEX =
        "\\b($LOGICAL_TEXTURE)_(sample|gather|gatherTexel|fetch|load|store|$ATOMIC_OPERATION)[ \\t]*\\(".toRegex()
    private val RESOURCE_OPERATION_REGEX = (
        "\\b(texture(?:Gather|GatherOffset|GatherOffsets|Lod|LodOffset|Offset|Proj|ProjLod|ProjLodOffset|ProjOffset)?|" +
            "texelFetch(?:Offset)?|imageLoad|imageStore|imageAtomic(?:Add|Min|Max|And|Or|Xor|Exchange|CompSwap))" +
            "[ \\t\\r\\n]*\\([ \\t\\r\\n]*($IDENTIFIER)\\b"
        ).toRegex()
    private val VERSION_LINE = "(?m)^[ \\t]*#version[^\\r\\n]*(?:\\r\\n|\\n|\\r)?".toRegex()
}
