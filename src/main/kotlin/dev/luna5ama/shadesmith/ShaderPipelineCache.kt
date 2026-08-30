package dev.luna5ama.shadesmith

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

internal sealed interface ShaderPipelineCacheLookup {
    data class Hit(val shader: CachedOptimizedShader) : ShaderPipelineCacheLookup
    data object Miss : ShaderPipelineCacheLookup
    data object Invalid : ShaderPipelineCacheLookup
}

@Serializable
internal data class CachedLocalSizeSignature(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun restore(): LocalSizeAbiSignature = LocalSizeAbiSignature(x, y, z)

    companion object {
        fun from(signature: LocalSizeAbiSignature): CachedLocalSizeSignature {
            return CachedLocalSizeSignature(signature.x, signature.y, signature.z)
        }
    }
}

@Serializable
internal data class CachedStructuralSignature(
    val stage: String,
    val requiredCapabilities: List<String>,
    val localSizeFallback: CachedLocalSizeSignature?,
    val resources: List<String>,
    val stageInterfaces: List<String>,
    val functionAbi: List<String>,
) {
    fun restore(): ShaderStructuralSignature {
        return ShaderStructuralSignature(
            ShaderStage.valueOf(stage),
            requiredCapabilities,
            localSizeFallback?.restore(),
            resources,
            stageInterfaces,
            functionAbi,
        )
    }

    companion object {
        fun from(signature: ShaderStructuralSignature): CachedStructuralSignature {
            return CachedStructuralSignature(
                signature.stage.name,
                signature.requiredCapabilities,
                signature.localSizeFallback?.let(CachedLocalSizeSignature::from),
                signature.resources,
                signature.stageInterfaces,
                signature.functionAbi,
            )
        }
    }
}

@Serializable
internal data class CachedOptimizedShader(
    val cacheKey: String,
    val sourceName: String,
    val inputSourceSha256: String,
    val planSha256: String,
    val source: String,
    val stage: String,
    val processingMode: String,
    val reads: List<String>,
    val writes: List<String>,
    val moduleCount: Int,
    val moduleRowCount: Int = moduleCount,
    val fallbackReason: String? = null,
    val specializationSettings: List<String> = emptyList(),
    val structuralSignatures: List<CachedStructuralSignature> = emptyList(),
    val optimizedEntities: Int = 0,
    val restoredEntities: Int = 0,
    val restoredBytes: Int = 0,
    val activationPredicate: String = "unconstrained",
)

internal class ShaderPipelineCache(
    rootDirectory: Path,
    val identity: String,
) {
    private val rootDirectory = rootDirectory.toAbsolutePath().normalize().resolve(identity)

    init {
        require(HEX_SHA256.matches(identity)) { "Shader pipeline cache identity must be a SHA-256 hash" }
        this.rootDirectory.createDirectories()
    }

    fun key(
        sourceName: String,
        stage: ShaderStage,
        source: String,
        config: Config,
        planContract: String,
    ): String {
        return sha256(
            buildString {
                appendField(identity)
                appendField(sourceName)
                appendField(stage.name)
                appendField(source)
                appendField(planContract)
                config.screen.toSortedMap().forEach { (name, format) ->
                    appendField("screen")
                    appendField(name)
                    appendField(format.name)
                }
                config.fixed.toSortedMap().forEach { (name, texture) ->
                    appendField("fixed")
                    appendField(name)
                    appendField(texture.width.toString())
                    appendField(texture.height.toString())
                    appendField(texture.format.name)
                }
            },
        )
    }

    fun load(key: String): ShaderPipelineCacheLookup {
        val path = entryPath(key)
        if (!path.isRegularFile()) return ShaderPipelineCacheLookup.Miss
        return runCatching {
            val content = path.readText()
            val headerEnd = content.indexOf('\n')
            val keyEnd = content.indexOf('\n', headerEnd + 1)
            val checksumEnd = content.indexOf('\n', keyEnd + 1)
            require(headerEnd >= 0 && keyEnd > headerEnd && checksumEnd > keyEnd)
            require(content.substring(0, headerEnd) == CACHE_HEADER)
            require(content.substring(headerEnd + 1, keyEnd) == key)
            val expectedChecksum = content.substring(keyEnd + 1, checksumEnd)
            val payload = content.substring(checksumEnd + 1)
            require(sha256(payload) == expectedChecksum)
            val cached = JSON.decodeFromString<CachedOptimizedShader>(payload)
            validate(key, cached)
            ShaderPipelineCacheLookup.Hit(cached)
        }.getOrElse {
            ShaderPipelineCacheLookup.Invalid
        }
    }

    fun store(key: String, shader: CachedOptimizedShader) {
        validate(key, shader)
        val path = entryPath(key)
        path.parent.createDirectories()
        val payload = JSON.encodeToString(shader)
        val content = buildString(payload.length + 160) {
            appendLine(CACHE_HEADER)
            appendLine(key)
            appendLine(sha256(payload))
            append(payload)
        }
        val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        synchronized(ENTRY_LOCKS.computeIfAbsent(path) { Any() }) {
            try {
                Files.newByteChannel(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.write(java.nio.ByteBuffer.wrap(content.encodeToByteArray()))
                    if (channel is java.nio.channels.FileChannel) channel.force(true)
                }
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    internal fun entryPath(key: String): Path {
        require(HEX_SHA256.matches(key)) { "Shader pipeline cache key must be a SHA-256 hash" }
        return rootDirectory.resolve(key.substring(0, 2)).resolve("$key.cache")
    }

    private fun validate(key: String, cached: CachedOptimizedShader) {
        require(cached.cacheKey == key)
        require(cached.sourceName.isNotBlank())
        require(HEX_SHA256.matches(cached.inputSourceSha256))
        require(HEX_SHA256.matches(cached.planSha256))
        require(cached.source.isNotBlank())
        val stage = ShaderStage.valueOf(cached.stage)
        ShaderProcessingMode.valueOf(cached.processingMode)
        require(cached.moduleCount >= 0)
        require(cached.moduleRowCount >= cached.moduleCount)
        require(cached.reads == cached.reads.distinct().sorted())
        require(cached.writes == cached.writes.distinct().sorted())
        require(cached.specializationSettings == cached.specializationSettings.distinct().sorted())
        cached.structuralSignatures.forEach { signature ->
            require(ShaderStage.valueOf(signature.stage) == stage)
            require(signature.requiredCapabilities == signature.requiredCapabilities.distinct().sorted())
            signature.localSizeFallback?.let { require(it.x > 0 && it.y > 0 && it.z > 0) }
        }
    }

    companion object {
        private const val CACHE_HEADER = "shadesmith-structural-pipeline-cache-v3"
        private val HEX_SHA256 = "[0-9A-F]{64}".toRegex()
        private val ENTRY_LOCKS = ConcurrentHashMap<Path, Any>()
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        fun runtimeIdentity(
            executables: SpirvExecutables = SpirvExecutables(),
            clangExecutable: String = "clang",
        ): String? {
            return runCatching {
                val codeLocation = requireNotNull(ShaderPipeline::class.java.protectionDomain.codeSource?.location)
                val codePath = Path.of(codeLocation.toURI()).toAbsolutePath().normalize()
                val executablePaths = listOf(
                    clangExecutable,
                    executables.glslang,
                    executables.spirvOpt,
                    executables.spirvCross,
                ).map { command -> command to requireNotNull(resolveExecutable(command)) }
                composeIdentity(
                    codeIdentity = hashPath(codePath),
                    executableIdentities = executablePaths.map { (command, path) ->
                        "$command\u0000${path.toString().replace('\\', '/')}\u0000${hashPath(path)}"
                    },
                    argumentContracts = listOf(
                        ShaderCompilerCopyMaterializer.cacheContract(clangExecutable),
                        toolchainContract(executables),
                    ),
                )
            }.getOrNull()
        }

        internal fun composeIdentity(
            codeIdentity: String,
            executableIdentities: List<String>,
            argumentContracts: List<String>,
        ): String {
            return sha256(
                buildString {
                    appendField(CACHE_HEADER)
                    appendField(codeIdentity)
                    executableIdentities.forEach { appendField(it) }
                    argumentContracts.forEach { appendField(it) }
                },
            )
        }

        internal fun contentHash(value: String): String = sha256(value)

        private fun resolveExecutable(command: String): Path? {
            val direct = Path.of(command)
            if (direct.parent != null) return direct.toAbsolutePath().normalize().takeIf { it.isRegularFile() }
            val extensions = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                sequenceOf("") + System.getenv("PATHEXT").orEmpty().split(';').asSequence()
                    .filter { it.isNotBlank() }
                    .map { it.lowercase() }
            } else {
                sequenceOf("")
            }
            val names = extensions.map { extension ->
                if (extension.isEmpty() || command.endsWith(extension, ignoreCase = true)) command else command + extension
            }.distinct().toList()
            return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
                .filter { it.isNotBlank() }
                .flatMap { directory -> names.asSequence().map { Path.of(directory, it) } }
                .firstOrNull { it.isRegularFile() }
                ?.toAbsolutePath()
                ?.normalize()
        }

        internal fun toolchainContract(executables: SpirvExecutables): String {
            return buildList {
                add(CompilerCopyEarlyReturnNormalizer.CACHE_CONTRACT)
                add(executables.glslang)
                addAll(listOf("--target-env", "opengl", "--target-env", "spirv1.3", "-S", "<stage>", "-o", "<output>", "<input>"))
                add(executables.spirvOpt)
                addAll(SpirvToolchain.OPTIMIZER_PASSES)
                addAll(listOf("<input>", "-o", "<output>"))
                add("cross-adapter")
                addAll(SpirvToolchain.CROSS_ADAPTER_PASSES)
                add(executables.spirvCross)
                addAll(
                    listOf(
                        "--no-es",
                        "--version",
                        "460",
                        "<input>",
                        "--output",
                        "<output>",
                        "--glsl-force-flattened-io-blocks",
                        "--combined-samplers-inherit-bindings",
                        "--remove-unused-variables",
                    ),
                )
            }.joinToString("\u0000")
        }

        private fun hashPath(path: Path): String {
            if (path.isRegularFile()) return hashFile(path)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.walk(path).use { paths ->
                paths.filter { it.isRegularFile() }.sorted().forEach { file ->
                    val relative = file.relativeTo(path).toString().replace('\\', '/')
                    digest.update(relative.length.toString().encodeToByteArray())
                    digest.update(0.toByte())
                    digest.update(relative.encodeToByteArray())
                    digest.update(0.toByte())
                    updateDigest(digest, file)
                }
            }
            return digest.digest().toHex()
        }

        private fun hashFile(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            updateDigest(digest, path)
            return digest.digest().toHex()
        }

        private fun updateDigest(digest: MessageDigest, path: Path) {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }

        private fun StringBuilder.appendField(value: String) {
            append(value.length)
            append(':')
            append(value)
            append(';')
        }

        private fun sha256(value: String): String = sha256(value.encodeToByteArray())

        private fun sha256(value: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(value).toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }
}
