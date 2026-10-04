package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class OpenGlSpirvCapabilities(
    val toolIdentity: String,
    val localSizeId: Boolean,
    val probeArtifactDirectory: Path,
    val diagnostic: String? = null,
)

internal class OpenGlSpirvCapabilityProbe(
    workingDirectory: Path,
    private val executables: SpirvExecutables = SpirvExecutables(),
    private val processRunner: SpirvProcessRunner? = null,
    private val processGate: ExternalProcessGate? = null,
    private val metrics: PipelineMetrics? = null,
    private val identityProvider: (String) -> String = ::completeExecutableIdentity,
    private val memoryCache: ConcurrentHashMap<String, OpenGlSpirvCapabilities> = sharedCache,
) {
    private val workingDirectory = workingDirectory.toAbsolutePath().normalize()

    init {
        this.workingDirectory.createDirectories()
    }

    fun probe(): OpenGlSpirvCapabilities {
        val identity = buildString {
            append(identityProvider(executables.glslang))
            append('\u0000')
            append(identityProvider(executables.spirvOpt))
            append('\u0000')
            append(identityProvider(executables.spirvCross))
            append('\u0000')
            append(PROBE_CONTRACT)
        }
        return memoryCache.computeIfAbsent("$identity\u0000$workingDirectory") {
            runProbe(identity)
        }
    }

    private fun runProbe(identity: String): OpenGlSpirvCapabilities {
        val artifactDirectory = workingDirectory.resolve(sha256(identity).take(24))
        artifactDirectory.createDirectories()
        val resultFile = artifactDirectory.resolve("result.txt")
        readCachedResult(identity, artifactDirectory, resultFile)?.let { return it }
        val source = artifactDirectory.resolve("local-size-id.comp")
        val input = artifactDirectory.resolve("local-size-id.spv")
        val optimized = artifactDirectory.resolve("local-size-id-opt.spv")
        val decompiled = artifactDirectory.resolve("local-size-id-opt.glsl")
        val validation = artifactDirectory.resolve("local-size-id-validation.spv")
        source.writeText(PROBE_SOURCE)
        val toolchain = if (processRunner == null) {
            SpirvToolchain(artifactDirectory, executables, processGate = processGate, metrics = metrics)
        } else {
            SpirvToolchain(artifactDirectory, executables, processRunner, processGate, metrics)
        }
        return try {
            toolchain.execute(toolchain.compileInvocation(ShaderStage.COMPUTE, source, input))
            toolchain.execute(toolchain.optimizeInvocation(ShaderStage.COMPUTE, input, optimized))
            toolchain.execute(toolchain.decompileInvocation(ShaderStage.COMPUTE, optimized, decompiled))
            toolchain.execute(toolchain.compileInvocation(ShaderStage.COMPUTE, decompiled, validation))
            OpenGlSpirvCapabilities(identity, true, artifactDirectory).also {
                resultFile.writeText("true\n")
            }
        } catch (exception: SpirvToolException) {
            val diagnostic = "LocalSizeId OpenGL round-trip probe failed in ${exception.invocation.tool.displayName}; " +
                "stderr=${exception.stderrPath.absolutePathString()}"
            OpenGlSpirvCapabilities(
                identity,
                false,
                artifactDirectory,
                diagnostic,
            ).also { resultFile.writeText("false\n$diagnostic\n") }
        }
    }

    private fun readCachedResult(
        identity: String,
        artifactDirectory: Path,
        resultFile: Path,
    ): OpenGlSpirvCapabilities? {
        if (!resultFile.isRegularFile()) return null
        val lines = resultFile.readText().lineSequence().toList()
        val supported = lines.firstOrNull()?.toBooleanStrictOrNull() ?: return null
        val diagnostic = lines.drop(1).joinToString("\n").takeIf { it.isNotBlank() }
        val requiredArtifacts = if (supported) {
            listOf("local-size-id.spv", "local-size-id-opt.spv", "local-size-id-opt.glsl", "local-size-id-validation.spv")
        } else {
            listOf("local-size-id.comp")
        }
        if (requiredArtifacts.any { !artifactDirectory.resolve(it).isRegularFile() }) return null
        return OpenGlSpirvCapabilities(identity, supported, artifactDirectory, diagnostic)
    }

    companion object {
        private val sharedCache = ConcurrentHashMap<String, OpenGlSpirvCapabilities>()
        private val PROBE_CONTRACT = buildList {
            addAll(listOf("--target-env", "opengl", "--target-env", "spirv1.3", "-S", "comp"))
            addAll(SpirvToolchain.OPTIMIZER_PASSES)
            addAll(
                listOf(
                    "--no-es",
                    "--version",
                    "460",
                    "--glsl-force-flattened-io-blocks",
                    "--combined-samplers-inherit-bindings",
                    "--remove-unused-variables",
                    "OpenGL validation recompile",
                    "local_size_y_id=100",
                    "gl_WorkGroupSize.y array",
                ),
            )
        }.joinToString("\u0000")
        private val PROBE_SOURCE = """
            #version 460 core
            layout(constant_id = 7) const int probeMode = 1;
            const int probeCount = probeMode == 0 ? 1 : 2;
            layout(local_size_x = 1, local_size_y = 4, local_size_z = 1) in;
            layout(local_size_y_id = 100) in;
            shared uint probeValues[gl_WorkGroupSize.y];
            void main() {
                probeValues[gl_LocalInvocationID.y] = gl_WorkGroupSize.y * uint(probeCount);
            }
        """.trimIndent() + "\n"

    }
}

private fun completeExecutableIdentity(command: String): String {
    val executable = resolveExecutable(command)
        ?: throw IllegalStateException("Cannot resolve executable identity for $command")
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(executable).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return buildString {
        append(executable.absolutePathString())
        append('\u0000')
        append(Files.size(executable))
        append('\u0000')
        append(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}

private fun resolveExecutable(command: String): Path? {
    val direct = Path.of(command)
    if ((direct.isAbsolute || direct.parent != null) && direct.isRegularFile()) {
        return direct.toAbsolutePath().normalize()
    }
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val extensions = if (windows && direct.fileName.toString().substringAfterLast('.', "").isEmpty()) {
        System.getenv("PATHEXT")?.split(';')?.filter { it.isNotBlank() }.orEmpty().ifEmpty {
            listOf(".COM", ".EXE", ".BAT", ".CMD")
        }
    } else {
        listOf("")
    }
    return System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        .asSequence()
        .filter { it.isNotBlank() }
        .flatMap { directory -> extensions.asSequence().map { Path.of(directory).resolve(command + it) } }
        .firstOrNull { it.isRegularFile() }
        ?.toAbsolutePath()
        ?.normalize()
}

private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
