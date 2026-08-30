package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ShaderPipelineCacheTest {
    @Test
    fun toolchainIdentityContainsOrderedPassesAndEarlyReturnLoweringContract() {
        val contract = ShaderPipelineCache.toolchainContract(SpirvExecutables())

        assertContains(contract, CompilerCopyEarlyReturnNormalizer.CACHE_CONTRACT)
        assertContains(contract, SpirvToolchain.OPTIMIZER_PASSES.joinToString("\u0000"))
    }

    @Test
    fun roundTripsCompleteStructuralEntriesAndInvalidatesEverySemanticInput() = withWorkspace { workspace ->
        val identity = ShaderPipelineCache.composeIdentity(
            codeIdentity = "code-a",
            executableIdentities = listOf("clang-a", "glslang-a", "spirv-opt-a", "spirv-cross-a"),
            argumentContracts = listOf("clang-args-a", "spirv-args-a"),
        )
        val cache = ShaderPipelineCache(workspace, identity)
        val config = Config(screen = mapOf("transient_a" to TextureFormat.RGBA16F))
        val source = "#version 460\nvoid main() {}"
        val plan = "compiler-copy-plan-a"
        val key = cache.key("composite.csh", ShaderStage.COMPUTE, source, config, plan)
        val shader = cachedShader(key, "composite.csh", source, plan)

        assertEquals(ShaderPipelineCacheLookup.Miss, cache.load(key))
        cache.store(key, shader)
        assertEquals(shader, assertIs<ShaderPipelineCacheLookup.Hit>(cache.load(key)).shader)
        assertEquals(
            ShaderStructuralSignature(
                ShaderStage.COMPUTE,
                listOf("GL_ARB_shader_image_load_store"),
                LocalSizeAbiSignature(1, 128, 1),
                listOf("target:uimage2D:r32ui"),
                emptyList(),
                listOf("main()"),
            ),
            shader.structuralSignatures.single().restore(),
        )

        assertNotEquals(key, cache.key("composite1.csh", ShaderStage.COMPUTE, source, config, plan))
        assertNotEquals(key, cache.key("composite.csh", ShaderStage.FRAGMENT, source, config, plan))
        assertNotEquals(key, cache.key("composite.csh", ShaderStage.COMPUTE, "$source\n", config, plan))
        assertNotEquals(key, cache.key("composite.csh", ShaderStage.COMPUTE, source, Config(), plan))
        assertNotEquals(key, cache.key("composite.csh", ShaderStage.COMPUTE, source, config, "compiler-copy-plan-b"))

        val codeChanged = ShaderPipelineCache.composeIdentity(
            "code-b",
            listOf("clang-a", "glslang-a", "spirv-opt-a", "spirv-cross-a"),
            listOf("clang-args-a", "spirv-args-a"),
        )
        val toolChanged = ShaderPipelineCache.composeIdentity(
            "code-a",
            listOf("clang-b", "glslang-a", "spirv-opt-a", "spirv-cross-a"),
            listOf("clang-args-a", "spirv-args-a"),
        )
        val argumentsChanged = ShaderPipelineCache.composeIdentity(
            "code-a",
            listOf("clang-a", "glslang-a", "spirv-opt-a", "spirv-cross-a"),
            listOf("clang-args-b", "spirv-args-a"),
        )
        assertNotEquals(identity, codeChanged)
        assertNotEquals(identity, toolChanged)
        assertNotEquals(identity, argumentsChanged)
    }

    @Test
    fun rejectsCorruptAndIncompleteEntriesThenRecoversAtomically() = withWorkspace { workspace ->
        val cache = ShaderPipelineCache(workspace, "A".repeat(64))
        val source = "#version 460\nvoid main() {}"
        val plan = "direct-plan"
        val key = cache.key("composite.csh", ShaderStage.COMPUTE, source, Config(), plan)
        val shader = cachedShader(key, "composite.csh", source, plan)
        val entry = cache.entryPath(key)
        entry.parent.createDirectories()
        entry.resolveSibling(".${entry.fileName}.interrupted.tmp").writeText("partial")

        assertEquals(ShaderPipelineCacheLookup.Miss, cache.load(key))
        cache.store(key, shader)
        entry.writeText(entry.toFile().readText().replace("void main", "void broken"))
        assertEquals(ShaderPipelineCacheLookup.Invalid, cache.load(key))

        cache.store(key, shader)
        assertEquals(shader, assertIs<ShaderPipelineCacheLookup.Hit>(cache.load(key)).shader)
    }

    @Test
    fun concurrentPublishersLeaveOneCompleteDeterministicEntry() = withWorkspace { workspace ->
        val cache = ShaderPipelineCache(workspace, "B".repeat(64))
        val source = "#version 460\nvoid main() {}"
        val plan = "structural-plan"
        val key = cache.key("composite.csh", ShaderStage.COMPUTE, source, Config(), plan)
        val shader = cachedShader(key, "composite.csh", source, plan)
        val executor = Executors.newFixedThreadPool(8)
        try {
            executor.invokeAll((0 until 32).map { Callable { cache.store(key, shader) } }).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(shader, assertIs<ShaderPipelineCacheLookup.Hit>(cache.load(key)).shader)
    }

    private fun cachedShader(
        key: String,
        sourceName: String,
        source: String,
        plan: String,
    ): CachedOptimizedShader {
        return CachedOptimizedShader(
            cacheKey = key,
            sourceName = sourceName,
            inputSourceSha256 = ShaderPipelineCache.contentHash(source),
            planSha256 = ShaderPipelineCache.contentHash(plan),
            source = source,
            stage = ShaderStage.COMPUTE.name,
            processingMode = ShaderProcessingMode.SPIRV_ROUND_TRIP.name,
            reads = listOf("transient_a"),
            writes = listOf("transient_b"),
            moduleCount = 2,
            specializationSettings = listOf("SETTING_MODE"),
            structuralSignatures = listOf(
                CachedStructuralSignature(
                    ShaderStage.COMPUTE.name,
                    listOf("GL_ARB_shader_image_load_store"),
                    CachedLocalSizeSignature(1, 128, 1),
                    listOf("target:uimage2D:r32ui"),
                    emptyList(),
                    listOf("main()"),
                ),
            ),
        )
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith pipeline cache test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
