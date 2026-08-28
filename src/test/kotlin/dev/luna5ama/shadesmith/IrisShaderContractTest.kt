package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrisShaderContractTest {
    @Test
    fun clearVoxelDataRestoresHostDispatchBytesWithoutVariants() {
        val hostContract = """
            #if VOXEL_POOL_SIZE == 256
            #define _VOXEL_CLEAR_WG 1024
            #elif VOXEL_POOL_SIZE == 512
            #define _VOXEL_CLEAR_WG 2048
            #endif

            const ivec3 workGroups = ivec3(_VOXEL_CLEAR_WG, 1, 1);
        """.trimIndent() + "\n"
        val source = buildString {
            appendLine("#version 460 compatibility")
            appendLine("layout(local_size_x = 256) in;")
            append(hostContract)
            appendLine("void main() {}")
        }

        val plan = ShaderCompilerCopyPlanner.plan(source, "ClearVoxelData.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertEquals(1, plan.compilerModuleCount)
        assertEquals(listOf(LocalSizeAbiSignature(256, 1, 1)), plan.irisContracts.localSize?.signatures)
        val compiler = assertNotNull(plan.compilerSource)
        assertFalse("_VOXEL_CLEAR_WG" in compiler)
        assertFalse("workGroups =" in compiler)
        val restored = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restore("#version 460 core\nvoid main()\n{\n}\n"),
        ).source
        assertContains(restored, hostContract)
        assertEquals(1, "const ivec3 workGroups".toRegex().findAll(restored).count())
    }

    @Test
    fun referencedIrisHostConstantUsesCompilerSurrogateAndRestoresExactDeclaration() {
        val declaration = "const int shadowMapResolution = 2048; // Iris host contract\n"
        val source = buildString {
            appendLine("#version 460 compatibility")
            append(declaration)
            appendLine("layout(local_size_x = 1) in;")
            appendLine("void main() { int value = shadowMapResolution; }")
        }

        val plan = ShaderCompilerCopyPlanner.plan(source, "referenced-host.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "const int SM_IRIS_HOST_shadowMapResolution = 2048;")
        assertContains(compiler, "int value = SM_IRIS_HOST_shadowMapResolution;")
        assertFalse("const int shadowMapResolution" in compiler)
        val restored = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restore(
                "#version 460 core\nconst int SM_IRIS_HOST_shadowMapResolution = 2048;\nvoid main() {}\n",
            ),
        ).source
        assertContains(restored, declaration)
        assertFalse("SM_IRIS_HOST_" in restored)
    }

    @Test
    fun settingControlledReferencedHostConstantUsesOneSpecializedSurrogate() {
        val source = """
            #version 460 compatibility
            #ifndef INCLUDE_BASE
            #define INCLUDE_BASE
            #define SETTING_SHADOW_MAP_RESOLUTION 2048 //[1024 2048 3072 4096]
            #define usam_main colortex0
            #if SETTING_SHADOW_MAP_RESOLUTION == 1024
            #define SHADOW_MAP_SIZE_D16 64
            const int shadowMapResolution = 1024;
            #elif SETTING_SHADOW_MAP_RESOLUTION == 2048
            #define SHADOW_MAP_SIZE_D16 128
            const int shadowMapResolution = 2048;
            #elif SETTING_SHADOW_MAP_RESOLUTION == 3072
            #define SHADOW_MAP_SIZE_D16 192
            const int shadowMapResolution = 3072;
            #else
            #define SHADOW_MAP_SIZE_D16 256
            const int shadowMapResolution = 4096;
            #endif
            uniform sampler2D usam_main;
            layout(local_size_x = 1) in;
            void main() { int value = shadowMapResolution + textureSize(usam_main, 0).x; }
            #endif
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "conditional-host.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertEquals(1, "const int SM_IRIS_HOST_shadowMapResolution".toRegex().findAll(compiler).count())
        assertContains(compiler, "SM_SETTING_SHADOW_MAP_RESOLUTION == 1024")
        assertContains(compiler, "SM_SETTING_SHADOW_MAP_RESOLUTION == 3072")
        assertContains(compiler, "#define usam_main colortex0")
        assertFalse("SM_DERIVED_usam_main" in compiler)
        assertContains(compiler, "int value = SM_IRIS_HOST_shadowMapResolution + textureSize(usam_main, 0).x;")
        assertFalse("SHADOW_MAP_SIZE_D16" in compiler)
        assertFalse("#if SETTING_SHADOW_MAP_RESOLUTION" in compiler)
        assertFalse("const int shadowMapResolution" in compiler)
    }

    @Test
    fun restoredSettingDeclarationsPrecedeDependentHostSurrogates() {
        val source = """
            #version 460 compatibility
            #define SETTING_SHADOW_MAP_RESOLUTION 2048 //[1024 2048]
            #if SETTING_SHADOW_MAP_RESOLUTION == 1024
            const int shadowMapResolution = 1024;
            #else
            const int shadowMapResolution = 2048;
            #endif
            layout(local_size_x = 1) in;
            void main() { int value = shadowMapResolution; }
        """.trimIndent()
        val plan = ShaderCompilerCopyPlanner.plan(source, "shadow-validation.csh")
        val setting = plan.settings.single()
        val restored = source
            .replace(
                "#version 460 compatibility\n",
                "#version 460 compatibility\n" +
                    "layout(constant_id = ${setting.specializationId}) const int ${setting.compilerName} = 2048;\n",
            )
            .replace(
                "void main() { int value = shadowMapResolution; }",
                "void main() { int value = SM_IRIS_HOST_shadowMapResolution; }",
            )

        val compiler = plan.irisContracts.prepareCompilerSource(restored)
        val settingOffset = compiler.indexOf("layout(constant_id = ${setting.specializationId})")
        val hostOffset = compiler.indexOf("const int SM_IRIS_HOST_shadowMapResolution")

        assertTrue(settingOffset >= 0)
        assertTrue(hostOffset > settingOffset, compiler)
        assertEquals(1, "const int ${setting.compilerName}".toRegex().findAll(compiler).count())
    }

    @Test
    fun specializationDependentTopLevelConstantsBecomeCompilerOnlyMacros() {
        val source = """
            #version 460 compatibility
            #define SETTING_SHADOW_MAP_RESOLUTION 2048 //[1024 2048]
            #if SETTING_SHADOW_MAP_RESOLUTION == 1024
            const int shadowMapResolution = 1024;
            #else
            const int shadowMapResolution = 2048;
            #endif
            const float SHADOW_TEXEL_SIZE = 1.0 / float(shadowMapResolution);
            const vec2 SHADOW_MAP_SIZE = vec2(float(shadowMapResolution), SHADOW_TEXEL_SIZE);
            layout(local_size_x = 1) in;
            void main() { float value = SHADOW_MAP_SIZE.x + SHADOW_TEXEL_SIZE; }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "dynamic-top-level-const.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(
            compiler,
            "#define SHADOW_TEXEL_SIZE (1.0 / float(SM_IRIS_HOST_shadowMapResolution))",
        )
        assertContains(
            compiler,
            "#define SHADOW_MAP_SIZE (vec2(float(SM_IRIS_HOST_shadowMapResolution), SHADOW_TEXEL_SIZE))",
        )
        assertFalse("const float SHADOW_TEXEL_SIZE" in compiler)
        assertFalse("const vec2 SHADOW_MAP_SIZE" in compiler)
    }

    @Test
    fun epipolarUsesOneLocalSizeIdModuleAndKeepsLoopCountSpecialized() {
        val source = epipolarSource()

        val plan = ShaderCompilerCopyPlanner.plan(source, "EpipolarScattering.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertEquals(1, plan.compilerModuleCount)
        assertEquals(listOf("128", "256", "512", "1024"), plan.settings.single().domain)
        val localSize = assertNotNull(plan.irisContracts.localSize)
        assertEquals(
            listOf(LocalSizeAbiSignature(1, 128, 1), LocalSizeAbiSignature(1, 256, 1)),
            localSize.signatures,
        )
        assertEquals(mapOf('y' to 1), localSize.specializationIds)
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "layout(local_size_y_id = 1) in;")
        assertContains(compiler, "int(gl_WorkGroupSize.y)")
        assertContains(compiler, "SM_DERIVED_LOOP_COUNT")
        assertFalse("#define LOOP_COUNT" in compiler)
        assertFalse("#if SETTING_SLICE_SAMPLES" in compiler)
    }

    @Test
    fun failedLocalSizeIdProbeExposesOnlyDeduplicatedWorkgroupAbi() {
        val plan = ShaderCompilerCopyPlanner.plan(
            epipolarSource(),
            "EpipolarScattering.csh",
            localSizeIdSupported = false,
            localSizeProbeDiagnostic = "forced probe failure",
        )

        assertEquals(0, plan.compilerModuleCount)
        assertEquals(1, plan.settings.size)
        assertEquals(
            listOf(LocalSizeAbiSignature(1, 128, 1), LocalSizeAbiSignature(1, 256, 1)),
            plan.irisContracts.localSize?.signatures,
        )
        assertTrue(plan.irisContracts.localSize?.specializationIds.orEmpty().isEmpty())
        assertContains(plan.irisContracts.structuralReason.orEmpty(), "forced probe failure")
        assertContains(plan.irisContracts.structuralReason.orEmpty(), "local-size ABI requires structural signatures")
    }

    @Test
    fun finalGlobalDataUpdateSplitsDirectiveContractFromMergedMain() {
        val source = """
            #version 460 compatibility
            //#define SETTING_DEBUG_AE
            #ifdef SETTING_DEBUG_AE
            layout(local_size_x = 256) in;
            const ivec3 workGroups = ivec3(1, 1, 1);
            void main() { int value = int(gl_LocalInvocationIndex); }
            #else
            layout(local_size_x = 1) in;
            const ivec3 workGroups = ivec3(1, 1, 1);
            void main() { int value = 0; }
            #endif
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "FinalGlobalDataUpdate.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertEquals(1, plan.conditionals.count {
            it.disposition == ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION
        })
        assertEquals(1, "\\bvoid\\s+main\\s*\\(".toRegex().findAll(assertNotNull(plan.compilerSource)).count())
        assertEquals(
            listOf(LocalSizeAbiSignature(1, 1, 1), LocalSizeAbiSignature(256, 1, 1)),
            plan.irisContracts.localSize?.signatures,
        )
        val contract = plan.irisContracts.contracts.single {
            it.kind == IrisSourceContractKind.CONDITIONAL_CONTRACT && "workGroups" in it.exactText
        }.exactText
        assertContains(contract, "#ifdef SETTING_DEBUG_AE")
        assertContains(contract, "layout(local_size_x = 256) in;")
        assertContains(contract, "#else")
        assertContains(contract, "layout(local_size_x = 1) in;")
        assertFalse("void main" in contract)
    }

    @Test
    fun splitsHelperMacrosFromMixedConditionalWithoutDiscardingMain() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 1 //[0 1]
            #if SETTING_MODE == 0
            #define WORK_GROUP_SIZE 4
            #define LOOP_COUNT 1
            void main() { int value = LOOP_COUNT + WORK_GROUP_SIZE; }
            #else
            #define WORK_GROUP_SIZE 8
            #define LOOP_COUNT 2
            void main() { int value = LOOP_COUNT + WORK_GROUP_SIZE; }
            #endif
            layout(local_size_x = WORK_GROUP_SIZE) in;
            const ivec3 workGroups = ivec3(1, 1, 1);
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "mixed-contract.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertEquals(1, "\\bvoid\\s+main\\s*\\(".toRegex().findAll(compiler).count())
        assertContains(compiler, "SM_DERIVED_LOOP_COUNT")
        assertContains(compiler, "int(gl_WorkGroupSize.x)")
        assertFalse("#define LOOP_COUNT" in compiler)
        val contract = plan.irisContracts.contracts.single().exactText
        assertContains(contract, "#define WORK_GROUP_SIZE 4")
        assertContains(contract, "#define LOOP_COUNT 2")
        assertFalse("void main" in contract)
    }

    @Test
    fun restoresExtensionBeforeAbiDeclarationsAndPreservesContractOrder() {
        val source = """
            #version 460 compatibility
            #extension GL_ARB_gpu_shader_int64 : require
            //#define SETTING_DEBUG
            /* RENDERTARGETS:0 */
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
        """.trimIndent() + "\n"
        val plan = ShaderCompilerCopyPlanner.plan(source, "ordered.fsh")
        val optimized = """
            #version 460 core
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
        """.trimIndent() + "\n"

        val restored = assertIs<IrisContractRestoration.Restored>(plan.irisContracts.restore(optimized)).source

        val extension = restored.indexOf("#extension GL_ARB_gpu_shader_int64 : require")
        val option = restored.indexOf("//#define SETTING_DEBUG")
        val directive = restored.indexOf("/* RENDERTARGETS:0 */")
        val declaration = restored.indexOf("layout(location = 0) out vec4 color;")
        assertTrue(extension in 0 until option)
        assertTrue(option in 0 until directive)
        assertTrue(directive in 0 until declaration)
    }

    @Test
    fun numericDefinedPredicateIsTrueEvenWhenItsValueIsZeroAndIdsDoNotCollide() {
        val source = """
            #version 460 compatibility
            layout(constant_id = 0) const int existing = 1;
            #define SETTING_MODE 0 //[0 1]
            #if defined(SETTING_MODE)
            #define WORK_GROUP_SIZE (SETTING_MODE + 8)
            #else
            #define WORK_GROUP_SIZE 4
            #endif
            layout(local_size_x = WORK_GROUP_SIZE) in;
            void main() { int value = WORK_GROUP_SIZE; }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "defined-zero.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertEquals(1, plan.settings.single().specializationId)
        assertEquals(mapOf('x' to 2), plan.irisContracts.localSize?.specializationIds)
        assertEquals(
            listOf(LocalSizeAbiSignature(8, 1, 1), LocalSizeAbiSignature(9, 1, 1)),
            plan.irisContracts.localSize?.signatures,
        )
        assertContains(assertNotNull(plan.compilerSource), "layout(local_size_x_id = 2) in;")
    }

    @Test
    fun unsupportedSourceLocalSizeIdsAndNestedCommentAnchorsFailClosed() {
        val sourceLocalSizeId = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                layout(local_size_x = 8, local_size_x_id = 9) in;
                void main() {}
            """.trimIndent(),
            "source-local-size-id.csh",
        )
        assertContains(
            sourceLocalSizeId.structuralBlockers.joinToString { it.reason },
            "source local_size_*_id declarations require structural preservation",
        )

        val nestedComment = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                layout(location = 0) out vec4 color;
                void main() {
                    /* RENDERTARGETS:0 */
                    color = vec4(1.0);
                }
            """.trimIndent(),
            "nested-comment.fsh",
        )
        assertContains(
            nestedComment.structuralBlockers.joinToString { it.reason },
            "nested Iris comment directive has no stable top-level anchor",
        )
    }

    @Test
    fun epipolarCompilerCopyCompletesRealOpenGlRoundTrip() = withWorkspace { workspace ->
        val source = epipolarSource()
        val plan = ShaderCompilerCopyPlanner.plan(source, "EpipolarScattering.csh")
        val module = ShaderCompilerCopyMaterializer(workspace.resolve("compiler-copy")).materialize(
            "EpipolarScattering.csh",
            ShaderStage.COMPUTE,
            plan,
            TextureAccessProbe(source, emptyList(), TextureAccess()),
        )

        val result = SpirvOptimizer(workspace.resolve("optimizer")).optimize(
            SpirvOptimizationRequest(
                "EpipolarScattering.csh",
                ShaderStage.COMPUTE,
                source,
                listOf(module),
            ),
        )
        val optimized = result.modules.single()

        assertTrue(optimized.validationSpirv.toFile().isFile)
        assertContains(optimized.source, "#if SETTING_SLICE_SAMPLES == 128")
        assertContains(optimized.source, "layout(local_size_x = 1, local_size_y = WORK_GROUP_SIZE) in;")
        assertContains(optimized.source, "const ivec3 workGroups = ivec3(8, 1, 1);")
        assertFalse("local_size_y_id" in optimized.source)
        assertFalse(COMPILER_MARKER in optimized.source)
    }

    @Test
    fun restoresSettingSpecializationsAsFinalMacroBridges() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            //#define SETTING_BOOL
            #define SETTING_MODE 2 //[1 2]
            layout(location = 0) out vec4 color;
            void main() {
            #ifdef SETTING_BOOL
                color = vec4(float(SETTING_MODE));
            #else
                color = vec4(0.0);
            #endif
            }
        """.trimIndent()
        val plan = ShaderCompilerCopyPlanner.plan(source, "settings.fsh")
        val module = ShaderCompilerCopyMaterializer(workspace.resolve("compiler-copy")).materialize(
            "settings.fsh",
            ShaderStage.FRAGMENT,
            plan,
            TextureAccessProbe(source, emptyList(), TextureAccess()),
        )

        val result = SpirvOptimizer(workspace.resolve("optimizer")).optimize(
            SpirvOptimizationRequest("settings.fsh", ShaderStage.FRAGMENT, source, listOf(module)),
        )

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode)
        assertContains(result.source, "//#define SETTING_BOOL")
        assertContains(result.source, "#ifdef SETTING_BOOL")
        assertContains(result.source, "#define SM_SETTING_BOOL true")
        assertContains(result.source, "#define SM_SETTING_MODE SETTING_MODE")
        assertContains(result.source, "if (SM_SETTING_BOOL)")
        assertTrue(result.source.indexOf("//#define SETTING_BOOL") < result.source.indexOf("#ifdef SETTING_BOOL"))
        assertFalse("SPIRV_CROSS_CONSTANT_ID_" in result.source)
        assertFalse("constant_id" in result.source)
    }

    @Test
    fun preservesContractBytesAndReportsMissingOrAmbiguousAnchors() {
        val exactOption = "//#define SETTING_EXACT\r\n"
        val source = "#version 460 compatibility\r\n$exactOption/* RENDERTARGETS:0 */\r\nvoid main() {}\r\n"
        val plan = ShaderCompilerCopyPlanner.plan(source, "exact.fsh")

        val restored = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restore("#version 460 core\nvoid main()\n{\n}\n"),
        ).source
        assertContains(restored, exactOption)
        assertContains(restored, "/* RENDERTARGETS:0 */")
        assertIs<IrisContractRestoration.StructuralPreservation>(
            plan.irisContracts.restore("#version 460 core\nvoid helper() {}\n"),
        )
        val ambiguous = assertIs<IrisContractRestoration.StructuralPreservation>(
            plan.irisContracts.restore("#version 460 core\nvoid main() {}\nvoid main() {}\n"),
        )
        assertContains(ambiguous.reason, "ambiguous")
    }

    @Test
    fun capabilityProbeCachesSuccessAndFailureByToolIdentity() = withWorkspace { workspace ->
        val successCount = AtomicInteger()
        val successRunner = SpirvProcessRunner { invocation, _, _, _ ->
            successCount.incrementAndGet()
            invocation.output.writeBytes(byteArrayOf(3, 2, 35, 7))
            0
        }
        val first = OpenGlSpirvCapabilityProbe(
            workspace.resolve("success-a"),
            processRunner = successRunner,
            identityProvider = { "test-success-identity" },
            memoryCache = ConcurrentHashMap(),
        ).probe()
        val second = OpenGlSpirvCapabilityProbe(
            workspace.resolve("success-a"),
            processRunner = successRunner,
            identityProvider = { "test-success-identity" },
            memoryCache = ConcurrentHashMap(),
        ).probe()
        assertTrue(first.localSizeId)
        assertEquals(first, second)
        assertEquals(4, successCount.get())

        val failureCount = AtomicInteger()
        val failure = OpenGlSpirvCapabilityProbe(
            workspace.resolve("failure"),
            processRunner = SpirvProcessRunner { _, _, _, _ -> failureCount.incrementAndGet(); 2 },
            identityProvider = { "test-failure-identity" },
        ).probe()
        assertFalse(failure.localSizeId)
        assertNotNull(failure.diagnostic)
        assertEquals(1, failureCount.get())
    }

    @Test
    fun installedOpenGlToolchainSupportsLocalSizeId() = withWorkspace { workspace ->
        val capabilities = OpenGlSpirvCapabilityProbe(workspace).probe()

        assertTrue(capabilities.localSizeId, capabilities.diagnostic)
        assertTrue(Files.isRegularFile(capabilities.probeArtifactDirectory.resolve("local-size-id.spv")))
        assertTrue(Files.isRegularFile(capabilities.probeArtifactDirectory.resolve("local-size-id-validation.spv")))
    }

    private fun epipolarSource(): String = """
        #version 460 compatibility
        #define SETTING_SLICE_SAMPLES 256 //[128 256 512 1024]
        #if SETTING_SLICE_SAMPLES == 128
        #define WORK_GROUP_SIZE 128
        #define LOOP_COUNT 1
        #elif SETTING_SLICE_SAMPLES == 256
        #define WORK_GROUP_SIZE 256
        #define LOOP_COUNT 1
        #elif SETTING_SLICE_SAMPLES == 512
        #define WORK_GROUP_SIZE 256
        #define LOOP_COUNT 2
        #elif SETTING_SLICE_SAMPLES == 1024
        #define WORK_GROUP_SIZE 256
        #define LOOP_COUNT 4
        #endif
        layout(local_size_x = 1, local_size_y = WORK_GROUP_SIZE) in;
        const ivec3 workGroups = ivec3(8, 1, 1);
        void main() {
            for (int i = 0; i < LOOP_COUNT; i++) {
                int index = int(gl_LocalInvocationID.y) + i * WORK_GROUP_SIZE;
            }
        }
    """.trimIndent()

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith iris contract test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
