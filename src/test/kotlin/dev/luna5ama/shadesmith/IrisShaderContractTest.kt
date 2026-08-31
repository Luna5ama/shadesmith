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
    fun staticHostHelperSharedWithShaderCodeRemainsInCompilerCopy() {
        val source = """
            #version 460 compatibility
            #define DISPATCH_OFFSET ivec2(8, 1)
            #define SHADER_OFFSET() DISPATCH_OFFSET
            const ivec3 workGroups = ivec3(DISPATCH_OFFSET, 1);
            layout(local_size_x = 1) in;
            void main() { ivec2 value = SHADER_OFFSET(); }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "shared-host-helper.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "#define DISPATCH_OFFSET ivec2(8, 1)")
        assertContains(compiler, "#define SHADER_OFFSET() DISPATCH_OFFSET")
        assertContains(compiler, "ivec2 value = SHADER_OFFSET();")
        assertFalse("workGroups =" in compiler)
    }

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
                "#version 460 core\nconst int SM_IRIS_HOST_shadowMapResolution = 2048;\n" +
                    "void main() { int value = SM_IRIS_HOST_shadowMapResolution; }\n",
            ),
        ).source.let { restoredContracts ->
            assertIs<IrisContractRestoration.Restored>(
                plan.irisContracts.restoreSourceReferences(restoredContracts),
            ).source
        }
        assertContains(restored, declaration)
        assertContains(restored, "int value = shadowMapResolution;")
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

        val replanned = plan.irisContracts.restoreRequiredCompilerPrelude(
            "#version 460 core\n$COMPILER_MARKER\n" +
                "layout(constant_id = ${setting.specializationId}) const int ${setting.compilerName} = 2048;\n" +
                "void main() { int value = SM_IRIS_HOST_shadowMapResolution; }\n",
        )
        assertTrue(
            replanned.indexOf("const int SM_IRIS_HOST_shadowMapResolution") >
                replanned.indexOf("const int ${setting.compilerName}"),
            replanned,
        )
    }

    @Test
    fun floatDerivedHostMacrosUseFloatCompilerConstants() {
        val source = """
            #version 460 compatibility
            #define SETTING_SCALE 0 //[0 1]
            #if SETTING_SCALE == 0
            #define RENDER_MULTIPLIER 1.0
            #else
            #define RENDER_MULTIPLIER 0.5
            #endif
            const vec2 workGroupsRender = vec2(RENDER_MULTIPLIER);
            layout(local_size_x = 1) in;
            void main() { float scale = RENDER_MULTIPLIER; }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "float-derived-host.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "const float SM_DERIVED_RENDER_MULTIPLIER")
        assertContains(compiler, "SM_SETTING_SCALE == 0")
        assertContains(compiler, "float scale = SM_DERIVED_RENDER_MULTIPLIER;")
    }

    @Test
    fun booleanPredicateCanSelectNumericSettingInDerivedScalar() {
        val source = """
            #version 460 compatibility
            #define SETTING_REAL_SUN_TEMPERATURE
            #define SETTING_SUN_TEMPERATURE 5700 //[1000 5700]
            #ifdef SETTING_REAL_SUN_TEMPERATURE
            #define SUN_TEMPERATURE 5772.0
            #else
            #define SUN_TEMPERATURE SETTING_SUN_TEMPERATURE
            #endif
            layout(local_size_x = 1) in;
            void main() { float temperature = SUN_TEMPERATURE; }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "real-sun-temperature.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "SM_SETTING_REAL_SUN_TEMPERATURE")
        assertContains(compiler, "SM_SETTING_SUN_TEMPERATURE")
        assertContains(compiler, "? (5772.0) : (SM_SETTING_SUN_TEMPERATURE)")
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
            "#define SM_DYNAMIC_SHADOW_TEXEL_SIZE (1.0 / float(SM_IRIS_HOST_shadowMapResolution))",
        )
        assertContains(
            compiler,
            "#define SM_DYNAMIC_SHADOW_MAP_SIZE " +
                "(vec2(float(SM_IRIS_HOST_shadowMapResolution), SM_DYNAMIC_SHADOW_TEXEL_SIZE))",
        )
        assertContains(
            compiler,
            "float value = SM_DYNAMIC_SHADOW_MAP_SIZE.x + SM_DYNAMIC_SHADOW_TEXEL_SIZE;",
        )
        assertEquals(
            "const vec2 size = vec2(float(SM_IRIS_HOST_shadowMapResolution));",
            plan.irisContracts.replaceHostReferences(
                "const vec2 size = vec2(float(shadowMapResolution));",
            ),
        )
        assertEquals(
            "float value = SM_DYNAMIC_SHADOW_MAP_SIZE.x + SM_DYNAMIC_SHADOW_TEXEL_SIZE;",
            plan.irisContracts.replaceHostReferences(
                "float value = SHADOW_MAP_SIZE.x + SHADOW_TEXEL_SIZE;",
            ),
        )
        assertFalse("const float SHADOW_TEXEL_SIZE" in compiler)
        assertFalse("const vec2 SHADOW_MAP_SIZE" in compiler)
    }

    @Test
    fun finalSourceReferencesRestoreConditionalHostsAndDerivedConstants() {
        val source = dynamicShadowHostSource()
        val plan = ShaderCompilerCopyPlanner.plan(source, "final-shadow-host.csh")
        val emitted = """
            #version 460 core
            #define SM_DYNAMIC_SHADOW_TEXEL_SIZE (1.0 / float(SM_IRIS_HOST_shadowMapResolution))
            #define SM_DYNAMIC_SHADOW_MAP_SIZE (vec2(float(SM_IRIS_HOST_shadowMapResolution), SM_DYNAMIC_SHADOW_TEXEL_SIZE))
            #define SM_DYNAMIC_SHADOW_MAP_SIZE (vec2(float(SM_IRIS_HOST_shadowMapResolution), SM_DYNAMIC_SHADOW_TEXEL_SIZE))
            const int SM_IRIS_HOST_shadowMapResolution = 2048;
            float nestedValue()
            {
                return SM_DYNAMIC_SHADOW_MAP_SIZE.x + SM_DYNAMIC_SHADOW_TEXEL_SIZE;
            }
            void main()
            {
                float value = nestedValue() + float(SM_IRIS_HOST_shadowMapResolution);
            }
        """.trimIndent() + "\n"

        val restoredContracts = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restore(emitted),
        ).source
        val restored = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restoreSourceReferences(restoredContracts),
        ).source

        assertContains(restored, "#if SETTING_SHADOW_MAP_RESOLUTION == 1024")
        assertContains(restored, "const int shadowMapResolution = 1024;")
        assertContains(restored, "const vec2 SHADOW_MAP_SIZE = vec2(float(shadowMapResolution), SHADOW_TEXEL_SIZE);")
        assertContains(restored, "return SHADOW_MAP_SIZE.x + SHADOW_TEXEL_SIZE;")
        assertContains(restored, "float value = nestedValue() + float(shadowMapResolution);")
        assertFalse("SM_IRIS_HOST_" in restored)
        assertFalse("SM_DYNAMIC_" in restored)
    }

    @Test
    fun mutuallyExclusiveDynamicDeclarationsRestoreAsOneConditionalContract() {
        val source = """
            #version 460 compatibility
            #define SETTING_SCALE 1 //[1 2]
            #if SETTING_SCALE == 1
            const int shadowMapResolution = 1024;
            #else
            const int shadowMapResolution = 2048;
            #endif
            const float OUTER_SCALE = float(shadowMapResolution);
            #define MATERIAL_TRANSLUCENT
            #ifdef MATERIAL_TRANSLUCENT
            const float MATERIAL_SCALE = float(shadowMapResolution);
            #else
            const float MATERIAL_SCALE = 1.0 / float(shadowMapResolution);
            #endif
            layout(std430, binding = 0) buffer OutputBuffer { float outputValue; };
            layout(local_size_x = 1) in;
            void main() { outputValue = OUTER_SCALE + MATERIAL_SCALE; }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "conditional-dynamic-host.csh")
        val dynamicName = assertNotNull(
            "#define (SM_DYNAMIC_MATERIAL_SCALE_*)".toRegex()
                .find(assertNotNull(plan.compilerSource)),
        ).groupValues[1]
        val outerName = assertNotNull(
            "#define (SM_DYNAMIC_OUTER_SCALE_*)".toRegex()
                .find(assertNotNull(plan.compilerSource)),
        ).groupValues[1]
        val restoredContracts = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restore(
                """
                    #version 460 core
                    #define $outerName (float(SM_IRIS_HOST_shadowMapResolution))
                    #define $dynamicName (float(SM_IRIS_HOST_shadowMapResolution))
                    const int SM_IRIS_HOST_shadowMapResolution = 1024;
                    layout(std430, binding = 0) buffer OutputBuffer { float outputValue; };
                    layout(local_size_x = 1) in;
                    void main() { outputValue = $outerName + $dynamicName; }
                """.trimIndent() + "\n",
            ),
        ).source
        val restoration = plan.irisContracts.restoreSourceReferences(restoredContracts)
        val restored = assertIs<IrisContractRestoration.Restored>(restoration, restoration.toString()).source

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertContains(restored, "#ifdef MATERIAL_TRANSLUCENT")
        assertFalse(";#ifdef MATERIAL_TRANSLUCENT" in restored)
        assertContains(restored, "const float MATERIAL_SCALE = float(shadowMapResolution);")
        assertContains(restored, "const float MATERIAL_SCALE = 1.0 / float(shadowMapResolution);")
        assertContains(restored, "outputValue = OUTER_SCALE + MATERIAL_SCALE;")
        assertTrue(restored.indexOf("const int shadowMapResolution") < restored.indexOf("const float OUTER_SCALE"))
        assertTrue(restored.indexOf("const float OUTER_SCALE") < restored.indexOf("#ifdef MATERIAL_TRANSLUCENT"))
        assertTrue(restored.indexOf("#ifdef MATERIAL_TRANSLUCENT") < restored.indexOf("void main()"))
        assertFalse(dynamicName in restored)
        assertFalse(outerName in restored)
    }

    @Test
    fun conflictingFinalCompilerHostDefinitionsFailClosed() {
        val plan = ShaderCompilerCopyPlanner.plan(dynamicShadowHostSource(), "conflicting-shadow-host.csh")
        val emitted = """
            #version 460 core
            #define SM_DYNAMIC_SHADOW_MAP_SIZE vec2(1024.0)
            #define SM_DYNAMIC_SHADOW_MAP_SIZE vec2(2048.0)
            void main() { vec2 value = SM_DYNAMIC_SHADOW_MAP_SIZE; }
        """.trimIndent() + "\n"

        val result = assertIs<IrisContractRestoration.StructuralPreservation>(
            plan.irisContracts.restoreSourceReferences(emitted),
        )

        assertContains(result.reason, "conflicting final definitions")
        assertContains(result.reason, "SM_DYNAMIC_SHADOW_MAP_SIZE")
    }

    @Test
    fun unknownCompilerHostReferencesFailBeforeValidationRelowering() {
        val plan = ShaderCompilerCopyPlanner.plan(dynamicShadowHostSource(), "missing-shadow-host.csh")
        val emitted = """
            #version 460 core
            void main() { int value = SM_IRIS_HOST_missingMapping; }
        """.trimIndent() + "\n"

        val result = assertIs<IrisContractRestoration.StructuralPreservation>(
            plan.irisContracts.restoreSourceReferences(emitted),
        )

        assertContains(result.reason, "compiler-only Iris host references remain")
        assertContains(result.reason, "SM_IRIS_HOST_missingMapping")
        assertNotNull(plan.irisContracts.finalSourceReferenceIssue(emitted))
    }

    @Test
    fun sourceOwnedPrivatePrefixNamesDoNotCollideWithCompilerAliases() {
        val source = """
            #version 460 compatibility
            const int SM_IRIS_HOST_shadowMapResolution = 17;
            const int shadowMapResolution = 2048;
            layout(local_size_x = 1) in;
            void main() {
                int value = SM_IRIS_HOST_shadowMapResolution + shadowMapResolution;
            }
        """.trimIndent()
        val plan = ShaderCompilerCopyPlanner.plan(source, "host-name-collision.csh")
        val compiler = assertNotNull(plan.compilerSource)

        assertContains(compiler, "const int SM_IRIS_HOST_shadowMapResolution_ = 2048;")
        val restored = assertIs<IrisContractRestoration.Restored>(
            plan.irisContracts.restoreSourceReferences(
                """
                    #version 460 core
                    const int SM_IRIS_HOST_shadowMapResolution = 17;
                    const int SM_IRIS_HOST_shadowMapResolution_ = 2048;
                    void main() {
                        int value = SM_IRIS_HOST_shadowMapResolution + SM_IRIS_HOST_shadowMapResolution_;
                    }
                """.trimIndent() + "\n",
            ),
        ).source

        assertContains(restored, "const int SM_IRIS_HOST_shadowMapResolution = 17;")
        assertContains(restored, "SM_IRIS_HOST_shadowMapResolution + shadowMapResolution")
        assertFalse("SM_IRIS_HOST_shadowMapResolution_" in restored)
        assertNull(plan.irisContracts.finalSourceReferenceIssue(restored))
    }

    @Test
    fun optimizerPublishesOnlySourceFacingHostReferences() = withWorkspace { workspace ->
        val source = dynamicShadowHostSource().replace(
            "layout(local_size_x = 1) in;\n" +
                "void main() { float value = SHADOW_MAP_SIZE.x + SHADOW_TEXEL_SIZE; }",
            "layout(std430, binding = 0) buffer OutputBuffer { float outputValue; };\n" +
                "layout(local_size_x = 1) in;\n" +
                "void main() { outputValue = SHADOW_MAP_SIZE.x + SHADOW_TEXEL_SIZE; }",
        )
        val plan = ShaderCompilerCopyPlanner.plan(source, "published-shadow-host.csh")
        val module = ShaderCompilerCopyMaterializer(workspace.resolve("compiler-copy")).materialize(
            "published-shadow-host.csh",
            ShaderStage.COMPUTE,
            plan,
            TextureAccessProbe(source, emptyList(), TextureAccess()),
        )

        val result = SpirvOptimizer(workspace.resolve("optimizer")).optimize(
            SpirvOptimizationRequest("published-shadow-host.csh", ShaderStage.COMPUTE, source, listOf(module)),
        )

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode)
        assertContains(result.source, "const int shadowMapResolution = 1024;")
        assertContains(result.source, "float(shadowMapResolution)")
        assertFalse("SM_IRIS_HOST_" in result.source)
        assertFalse("SM_DYNAMIC_" in result.source)
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
        assertContains(compiler, "#define WORK_GROUP_SIZE int(gl_WorkGroupSize.y)")
        assertContains(compiler, "int(gl_WorkGroupSize.y)")
        assertContains(compiler, "SM_DERIVED_LOOP_COUNT")
        assertFalse("#define LOOP_COUNT" in compiler)
        assertFalse("#if SETTING_SLICE_SAMPLES" in compiler)
        val validation = plan.irisContracts.prepareCompilerSource(
            "#version 460 compatibility\nconst int SM_DERIVED_LOOP_COUNT = 1;\n" +
                "void main() { int value = SM_DERIVED_LOOP_COUNT; }\n",
        )
        assertEquals(1, "\\bconst\\s+int\\s+SM_DERIVED_LOOP_COUNT\\b".toRegex().findAll(validation).count())
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
    fun preservesSpirvCrossCapabilityPrologueWhileRestoringSourceExtension() {
        val source = """
            #version 460 compatibility
            #extension GL_ARB_gpu_shader_int64 : require
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
        """.trimIndent() + "\n"
        val optimized = """
            #version 460 core
            #extension GL_NV_shader_thread_group : require
            #extension GL_KHR_shader_subgroup_ballot : require
            #if defined(GL_KHR_shader_subgroup_ballot)
            #extension GL_KHR_shader_subgroup_ballot : require
            #elif defined(GL_ARB_shader_ballot)
            #extension GL_ARB_shader_ballot : require
            #else
            #error No subgroup ballot extension available
            #endif
            layout(location = 0) out vec4 color;
            void main() { color = vec4(1.0); }
        """.trimIndent() + "\n"
        val plan = ShaderCompilerCopyPlanner.plan(source, "capability-prologue.fsh").irisContracts

        val restored = assertIs<IrisContractRestoration.Restored>(plan.restore(optimized)).source

        assertContains(restored, "#extension GL_ARB_gpu_shader_int64 : require")
        assertContains(restored, "#extension GL_KHR_shader_subgroup_ballot : require")
        assertContains(restored, "#extension GL_ARB_shader_ballot : require")
        assertContains(restored, "#error No subgroup ballot extension available")
        assertFalse("GL_NV_shader_thread_group" in restored)
        assertTrue(restored.indexOf("#version") < restored.indexOf("#extension GL_ARB_gpu_shader_int64"))
        assertTrue(restored.indexOf("#extension GL_ARB_gpu_shader_int64") < restored.indexOf("#if defined(GL_KHR_shader_subgroup_ballot)"))

        val compiler = plan.prepareCompilerSource(restored)
        assertContains(compiler, "#extension GL_KHR_shader_subgroup_ballot : require")
        assertContains(compiler, "#extension GL_ARB_shader_ballot : require")
        assertFalse("GL_NV_shader_thread_group" in compiler)
        assertEquals(1, "#extension GL_ARB_gpu_shader_int64 : require".toRegex().findAll(compiler).count())
    }

    @Test
    fun finalValidationReusesOnlyExtensionsActiveInTheMaterializedCompilerModule() {
        val source = """
            #version 460 compatibility
            #ifdef SETTING_VENDOR
            #extension GL_NV_shader_thread_group : require
            #else
            #extension GL_KHR_shader_subgroup_basic : require
            #endif
            const int shadowMapResolution = 2048;
            layout(local_size_x = 1) in;
            void main() { int value = shadowMapResolution; }
        """.trimIndent() + "\n"
        val materialized = """
            #version 460 compatibility
            #extension GL_KHR_shader_subgroup_basic : require
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent() + "\n"
        val contracts = ShaderCompilerCopyPlanner.plan(source, "materialized-capability.csh").irisContracts
            .withMaterializedCompilerSource(materialized)

        val compiler = contracts.prepareCompilerSource("#version 460 core\nvoid main() {}\n")

        assertContains(compiler, "#extension GL_KHR_shader_subgroup_basic : require")
        assertFalse("GL_NV_shader_thread_group" in compiler)

        val replanned = contracts.restoreRequiredCompilerPrelude(
            "#version 460 core\n#extension GL_NV_shader_thread_group : require\n$COMPILER_MARKER\n" +
                "void main() { int value = SM_IRIS_HOST_shadowMapResolution; }\n",
        )
        assertContains(replanned, "#extension GL_KHR_shader_subgroup_basic : require")
        assertFalse("GL_NV_shader_thread_group" in replanned)
        assertContains(replanned, "const int SM_IRIS_HOST_shadowMapResolution = 2048;")
        assertContains(replanned, "layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;")
        assertEquals(1, "layout\\s*\\([^)]*local_size_".toRegex().findAll(replanned).count())
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
    fun conditionalCommentContractKeepsDirectiveLineBoundariesWhenAbiBodiesAreRemoved() {
        val source = """
            #version 460 compatibility
            #ifdef SETTING_TRANSLUCENT
            /* RENDERTARGETS:0,2 */
            layout(location = 0) out float depth;
            layout(location = 1) out vec4 color;
            #else
            /* RENDERTARGETS:0 */
            layout(location = 0) out float depth;
            #endif
            void main() { depth = 1.0; }
        """.trimIndent() + "\n"

        val plan = ShaderCompilerCopyPlanner.plan(source, "conditional-targets.fsh")
        val contract = plan.irisContracts.contracts.single {
            it.kind == IrisSourceContractKind.CONDITIONAL_CONTRACT
        }.exactText

        PreprocessorProtection.protect(contract, "conditional-targets-contract")
        assertContains(contract, "/* RENDERTARGETS:0,2 */\n#else")
        assertContains(contract, "/* RENDERTARGETS:0 */\n#endif")
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

    @Test
    fun branchOwnedMainRegionIsTheAuthoritativeStableAnchor() {
        val source = """
            #version 460 compatibility
            void main() {}
            // SHADESMITH_BRANCH_OWNED_MAIN_BEGIN
            #ifdef SETTING_BRANCH
            void main() {}
            #else
            void main() {}
            #endif
            // SHADESMITH_BRANCH_OWNED_MAIN_END
        """.trimIndent()

        val mainAnchors = findStableAnchors(source).filter {
            it.anchor == IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
        }

        assertEquals(1, mainAnchors.size)
        assertContains(source.substring(mainAnchors.single().range), "SHADESMITH_BRANCH_OWNED_MAIN_BEGIN")
    }

    @Test
    fun settingConditionalMainArmsAreOneMutuallyExclusiveStableAnchor() {
        val source = """
            #version 460 compatibility
            #ifdef SETTING_BRANCH
            void main() {}
            #else
            void main() {}
            #endif
        """.trimIndent()

        val mainAnchors = findStableAnchors(source).filter {
            it.anchor == IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
        }

        assertEquals(1, mainAnchors.size)
        assertContains(source.substring(mainAnchors.single().range), "#ifdef SETTING_BRANCH")
        assertContains(source.substring(mainAnchors.single().range), "#else")
    }

    private fun dynamicShadowHostSource(): String = """
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
        #define SHADOW_SAMPLE_COUNT (WORK_GROUP_SIZE * 2)
        shared float shadowSamples[SHADOW_SAMPLE_COUNT];
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
