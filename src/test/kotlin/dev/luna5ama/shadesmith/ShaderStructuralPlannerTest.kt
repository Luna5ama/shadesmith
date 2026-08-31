package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderStructuralPlannerTest {
    @Test
    fun independentStructuralComponentsUseCoverageRowsInsteadOfCartesianProduct() = withWorkspace { workspace ->
        val base = ShaderCompilerCopyPlanner.plan(independentResources(), "independent.csh")

        val result = ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE)
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            result,
            (result as? ShaderStructuralPlanningResult.Preserved)?.reason,
        ).plan

        assertEquals(2, planned.graph.components.size)
        assertEquals(
            listOf(listOf("SETTING_A"), listOf("SETTING_B")),
            planned.graph.components.map { it.settings },
        )
        assertEquals(3, planned.rows.size)
        assertEquals(
            setOf(
                mapOf("SETTING_A" to "false", "SETTING_B" to "false"),
                mapOf("SETTING_A" to "true", "SETTING_B" to "false"),
                mapOf("SETTING_A" to "false", "SETTING_B" to "true"),
            ),
            planned.rows.map { it.assignment }.toSet(),
        )
        planned.rows.forEach { row ->
            val compiler = requireNotNull(row.compilerPlan.compilerSource)
            assertContains(compiler, "layout(constant_id")
            assertContains(compiler, "SM_SETTING_RUNTIME")
            assertTrue("SM_SETTING_A" !in compiler)
            assertTrue("SM_SETTING_B" !in compiler)
        }
        val materialized = materialize(workspace, independentResources(), planned)
        val finalized = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialized),
        ).modules
        assertEquals(3, finalized.size)
        optimizeAll(workspace, independentResources(), ShaderStage.COMPUTE, finalized.map { it.module })
    }

    @Test
    fun compactStructuralModulesRetainEveryCoveredAssignment() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            #define SETTING_FORMAT 0 //[0 1 2]
            #if SETTING_FORMAT == 1
            layout(r32ui, binding = 0) uniform uimage2D target;
            #else
            layout(rgba16f, binding = 0) uniform image2D target;
            #endif
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "covered-assignments.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan
        val modules = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialize(workspace, source, planned)),
        ).modules

        assertEquals(2, modules.size)
        assertEquals(
            planned.rows.map(ShaderStructuralCoverageRow::assignment).toSet(),
            modules.flatMap { it.module.structuralAssignments }.toSet(),
        )
        assertTrue(modules.any { it.module.structuralAssignments.size == 2 })
    }

    @Test
    fun identicalLocalsInDifferentFunctionsDoNotCoupleStructuralComponents() {
        val source = buildString {
            appendLine("#version 460 compatibility")
            repeat(8) { appendLine("//#define SETTING_$it") }
            repeat(8) { index ->
                appendLine("float evaluate$index() {")
                appendLine("#ifdef SETTING_$index")
                appendLine("    float value = 1.0;")
                appendLine("#else")
                appendLine("    float value = 0.0;")
                appendLine("#endif")
                appendLine("    return value;")
                appendLine("}")
            }
            appendLine("layout(local_size_x = 1) in;")
            appendLine("void main() { float value = evaluate0(); }")
        }
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "independent-function-locals.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        assertEquals(8, planned.graph.components.size)
        assertEquals(9, planned.rows.size)
    }

    @Test
    fun compileFeedbackMergesOnlyTheImplicatedComponentsAndReplans() = withWorkspace { workspace ->
        val base = ShaderCompilerCopyPlanner.plan(independentResources(), "hidden.csh")
        val feedbackCalls = AtomicInteger()
        val feedback = ShaderStructuralCompileFeedback {
            if (feedbackCalls.getAndIncrement() == 0) setOf("SETTING_A", "SETTING_B") else null
        }

        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE, feedback),
        ).plan

        assertEquals(2, feedbackCalls.get())
        assertEquals(1, planned.graph.components.size)
        assertEquals(listOf("SETTING_A", "SETTING_B"), planned.graph.components.single().settings)
        assertEquals(4, planned.rows.size)
        assertEquals(4, planned.rows.map { it.assignment }.toSet().size)
        val materialized = materialize(workspace, independentResources(), planned)
        val finalized = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialized),
        ).modules
        assertEquals(4, finalized.size)
        optimizeAll(workspace, independentResources(), ShaderStage.COMPUTE, finalized.map { it.module })
    }

    @Test
    fun ordinarySettingsDoNotRequestStructuralPlanning() {
        val source = buildString {
            appendLine("#version 460 compatibility")
            repeat(25) { appendLine("//#define SETTING_$it") }
            appendLine("void main() {")
            repeat(25) {
                appendLine("#ifdef SETTING_$it")
                appendLine("int value_$it = 1;")
                appendLine("#else")
                appendLine("int value_$it = 0;")
                appendLine("#endif")
            }
            appendLine("}")
        }
        val base = ShaderCompilerCopyPlanner.plan(source, "ordinary.csh")

        assertEquals(1, base.compilerModuleCount)
        assertTrue(!ShaderStructuralPlanner.requiresPlanning(base))
    }

    @Test
    fun tokenPasteSelectionDoesNotStructuralizeUnrelatedFloatSettings() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define SETTING_GAIN 0.5 //[0.5 1.0]
            #define SELECTED_MODE SETTING_MODE
            #define PASTE_IMPL(a, b) a ## b
            #define PASTE(a, b) PASTE_IMPL(a, b)
            #define APPLY(value) PASTE(mode, SELECTED_MODE)(value * SETTING_GAIN)
            float mode0(float value) { return value; }
            float mode1(float value) { return value + 1.0; }
            layout(local_size_x = 1) in;
            void main() { float value = APPLY(2.0); }
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "token-paste-float.csh")
        assertEquals(1, base.compilerModuleCount, base.structuralBlockers.joinToString { it.reason })
        assertTrue(base.structuralBlockers.isEmpty())
        assertFalse(ShaderStructuralPlanner.requiresPlanning(base))
        assertTrue(base.settings.single { it.name == "SETTING_GAIN" }.type == ShaderSettingType.FLOAT)
        assertFalse("##" in assertNotNull(base.compilerSource))
    }

    @Test
    fun capabilitiesDoNotEvaluateLaterUnrelatedFloatControlFlow() {
        val source = """
            #version 460 compatibility
            #define SETTING_EXTENSION
            #ifdef SETTING_EXTENSION
            #extension GL_ARB_gpu_shader_int64 : enable
            #endif
            #define SETTING_LAYOUT 0 //[0 1]
            #define SETTING_GAIN 0.5 //[0.0 0.5 1.0]
            #if SETTING_LAYOUT == 0
            layout(r32f) uniform image2D target;
            #else
            layout(r32ui) uniform uimage2D target;
            #endif
            layout(local_size_x = 1) in;
            void main() {
            #if SETTING_GAIN > 0.0
                float value = SETTING_GAIN;
            #endif
            }
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "unconditional-capability.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan

        assertEquals(3, planned.rows.size)
        assertTrue(planned.rows.any { it.requiredCapabilities == listOf("GL_ARB_gpu_shader_int64:enable") })
        assertTrue(planned.rows.any { it.requiredCapabilities.isEmpty() })
    }

    @Test
    fun structuralSelectionDoesNotEvaluateUnrelatedFloatControlFlow() {
        val source = """
            #version 460 compatibility
            #define SETTING_LAYOUT 0 //[0 1]
            #define SETTING_GAIN 0.5 //[0.0 0.5 1.0]
            #if SETTING_LAYOUT == 0
            layout(r32f) uniform image2D target;
            #else
            layout(r32ui) uniform uimage2D target;
            #endif
            layout(local_size_x = 1) in;
            void main() {
            #if SETTING_LAYOUT == 0
                imageStore(target, ivec2(0), vec4(1.0));
            #else
                imageStore(target, ivec2(0), uvec4(1u));
            #endif
            #if SETTING_GAIN > 0.0
                float value = SETTING_GAIN;
            #endif
            }
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "evaluation-failure.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan

        assertEquals(2, planned.rows.size)
        planned.rows.forEach { row ->
            assertContains(requireNotNull(row.compilerPlan.compilerSource), "SM_SETTING_GAIN")
        }
    }

    @Test
    fun multilineFunctionBodiesDoNotBecomeAbiBlocks() {
        val source = """
            #version 460 compatibility
            #define SETTING_GAIN 0.5 //[0.0 0.5 1.0]
            float evaluate(
                float value
            ) {
                return value * SETTING_GAIN;
            }
            layout(local_size_x = 1) in;
            void main() { float value = evaluate(1.0); }
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "multiline-function-body.csh")

        assertTrue(!ShaderStructuralPlanner.requiresPlanning(base), base.structuralBlockers.toString())
    }

    @Test
    fun multilineFunctionSignaturesRemainStructural() {
        val source = """
            #version 460 compatibility
            #define SETTING_COUNT 2 //[2 4]
            float evaluate(
                float value[SETTING_COUNT]
            ) {
                return value[0];
            }
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "multiline-function-signature.csh")

        assertTrue(ShaderStructuralPlanner.requiresPlanning(base), base.structuralBlockers.toString())
    }

    @Test
    fun commentsDoNotTurnFunctionPrototypesIntoResources() {
        val signature = ShaderStructuralSignatureExtractor.extract(
            ShaderStage.COMPUTE,
            """
                #version 460 core
                // Uniform buffer compatibility helper.
                float ffxSqrt(float value);
                layout(local_size_x = 1) in;
                void main() {}
            """.trimIndent(),
            emptyList(),
            null,
        )

        assertTrue(signature.resources.isEmpty(), signature.resources.toString())
        assertEquals(listOf("float ffxSqrt(float value);"), signature.functionAbi)
    }

    @Test
    fun sharedBindingNumberDoesNotMergeOtherwiseIndependentStructuralSettings() {
        val source = """
            #version 460 compatibility
            //#define SETTING_A
            //#define SETTING_B
            #ifdef SETTING_A
            layout(rgba16f, binding = 0) uniform image2D targetA;
            #else
            layout(r32f, binding = 0) uniform image2D targetA;
            #endif
            #ifdef SETTING_B
            layout(rgba8, binding = 0) uniform image2D targetB;
            #else
            layout(r8, binding = 0) uniform image2D targetB;
            #endif
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "shared-binding.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        assertEquals(2, planned.graph.components.size)
        assertEquals(
            listOf(listOf("SETTING_A"), listOf("SETTING_B")),
            planned.graph.components.map { it.settings },
        )
        assertEquals(3, planned.rows.size)
    }

    @Test
    fun settingSizedStorageBlockMembersUseFiniteStructuralModules() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            #define SETTING_SIZE 2 //[2 4]
            #define DATA_QUALIFIER buffer
            #define DATA_SIZE SETTING_SIZE
            layout(std430, binding = 0) DATA_QUALIFIER Data {
                uint head;
                uint values[DATA_SIZE];
                uint tail;
            };
            layout(local_size_x = 1) in;
            void main() { values[0] = tail; }
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "storage-array.csh")
        assertContains(base.structuralBlockers.single().reason, "resource block member")
        val result = ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE)
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            result,
            (result as? ShaderStructuralPlanningResult.Preserved)?.reason,
        ).plan

        assertEquals(null, planned.restorationPlan.issue)
        assertEquals(1, planned.restorationPlan.islands.size)
        val island = planned.restorationPlan.islands.single()
        assertContains(island.exactText, "layout(std430, binding = 0)")
        assertContains(island.exactText, "uint tail;")
        assertEquals(IrisSourceAnchor(IrisAnchorKind.VERSION, "version"), island.beforeAnchor)
        assertEquals(IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main"), island.afterAnchor)
        assertEquals(2, planned.materializationRows().size)
        val arraySizes = planned.materializationRows().map { row ->
            val compilerSource = requireNotNull(row.compilerPlan.compilerSource)
            requireNotNull("uint values\\[([0-9]+)".toRegex().find(compilerSource)).groupValues[1]
        }.toSet()
        assertEquals(setOf("2", "4"), arraySizes)
        val modules = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialize(workspace, source, planned)),
        ).modules
        assertEquals(2, modules.size)
        optimizeAll(workspace, source, ShaderStage.COMPUTE, modules.map { it.module })
    }

    @Test
    fun structuralStructMembersRestoreTheOwningTopLevelDeclaration() {
        val source = """
            #version 460 compatibility
            //#define SETTING_EXTRA
            #define BRACED_MACRO(value) { value; }
            struct Payload {
                uint value;
            #ifdef SETTING_EXTRA
                uint extra;
            #endif
            };
            void main() {}
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "struct-member.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan

        assertEquals(null, planned.restorationPlan.issue)
        assertEquals(1, planned.restorationPlan.islands.size)
        assertContains(planned.restorationPlan.islands.single().exactText, "struct Payload")
    }

    @Test
    fun structuralFunctionParameterConditionRestoresTheWholeFunction() {
        val source = """
            #version 460 compatibility
            //#define SETTING_ALPHA
            void filter(
                out float red,
            #ifdef SETTING_ALPHA
                out float alpha,
            #endif
                ivec2 position) {
                red = float(position.x);
            #ifdef SETTING_ALPHA
                alpha = 1.0;
            #endif
            }
            void main() {}
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "conditional-parameter.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        assertEquals(null, planned.restorationPlan.issue)
        val island = planned.restorationPlan.islands.single()
        assertEquals(ShaderStructuralEntitySlotKind.FUNCTION, island.kind)
        assertContains(island.exactText, "void filter(")
        assertTrue(island.exactText.trimEnd().endsWith("}"), island.exactText)
        assertContains(island.exactText, "out float alpha")
        assertContains(island.exactText, "red = float(position.x)")
    }

    @Test
    fun structuralMaterializationPreservesHostOnlyAncestorDirectives() {
        val source = """
            #version 460 compatibility
            //#define SETTING_WIDE
            #ifndef INCLUDE_DATA
            #define INCLUDE_DATA
            #define DATA_OFFSET ivec2(4, 8)
            #ifdef SETTING_WIDE
            layout(rgba16f, binding = 0) uniform image2D target;
            #else
            layout(r32f, binding = 0) uniform image2D target;
            #endif
            #endif
            #ifdef SETTING_WIDE
            #ifndef INCLUDE_DATA
            #define INCLUDE_DATA
            #endif
            #endif
            layout(local_size_x = 1) in;
            void main() { ivec2 offset = DATA_OFFSET; }
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "host-ancestor.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        planned.materializationRows().forEach { row ->
            val compiler = assertNotNull(row.compilerPlan.compilerSource)
            assertContains(compiler, "#ifndef INCLUDE_DATA")
            assertContains(compiler, "#define DATA_OFFSET ivec2(4, 8)")
        }
    }

    @Test
    fun promotedEntityUsesItsDerivedMacroStructuralOwner() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #if SETTING_MODE == 1
            #define USE_OPTIONAL_RESOURCE
            #endif
            #ifdef USE_OPTIONAL_RESOURCE
            layout(rgba16f, binding = 0) uniform image2D target;
            float optionalValue() { return imageLoad(target, ivec2(0)).x; }
            #endif
            #if defined(HOST_EXTENSION)
            uniform float hostValue;
            #endif
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "derived-owner.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan
        val entityStart = source.indexOf("float optionalValue()")
        val entityEnd = source.indexOf('}', entityStart)
        val owner = assertNotNull(planned.restorationPlan.structuralOwnerRange(source, entityStart..entityEnd))
        val restored = source.substring(owner)

        assertTrue(restored.startsWith("#ifdef USE_OPTIONAL_RESOURCE"), restored)
        assertContains(restored, "uniform image2D target")
        assertContains(restored, "float optionalValue()")
        assertTrue(restored.trimEnd().endsWith("#endif"), restored)
        val hostStart = source.indexOf("uniform float hostValue")
        val hostEnd = source.indexOf(';', hostStart)
        val hostOwner = assertNotNull(planned.restorationPlan.structuralOwnerRange(source, hostStart..hostEnd))
        assertEquals(
            "#if defined(HOST_EXTENSION)\nuniform float hostValue;\n#endif",
            source.substring(hostOwner).trimEnd(),
        )
    }

    @Test
    fun optimizedAwayStructuralEntityDropsItsMultilineMacroDependency() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1 2]
            #define OPTIONAL_VALUE(value) (\
                (value) + 1.0)
            #define ENABLE_TARGET a
            #ifdef ENABLE_TARGET
            #if SETTING_MODE == 1
            #define USE_FLOAT_TARGET
            #elif SETTING_MODE == 2
            #define USE_FLOAT_TARGET
            #endif
            #define UNUSED_CALLBACK unusedCallback
            #endif
            float unusedCallback() { return 0.0; }
            #ifdef USE_FLOAT_TARGET
            layout(r32f, binding = 0) uniform image2D target;
            float optionalValue() { return OPTIONAL_VALUE(1.0); }
            #else
            layout(r32ui, binding = 0) uniform uimage2D target;
            uint optionalValue() { return 1u; }
            #endif
            layout(local_size_x = 1) in;
            void main() {
            #ifdef USE_FLOAT_TARGET
                imageStore(target, ivec2(0), vec4(optionalValue()));
            #else
                imageStore(target, ivec2(0), uvec4(optionalValue()));
            #endif
            }
        """.trimIndent()
        val result = optimizeStructural(workspace, "multiline-macro.csh", source, ShaderStage.COMPUTE)

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertFalse(result.source.contains("#define OPTIONAL_VALUE"))
        assertFalse(result.source.contains("float unusedCallback()"), result.source)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun settingMutationFailsClosedInsteadOfBeingMaterialized() {
        val source = """
            #version 460 compatibility
            //#define SETTING_FORMAT
            #ifdef SETTING_FORMAT
            layout(rgba16f, binding = 0) uniform image2D target;
            #else
            layout(r32f, binding = 0) uniform image2D target;
            #endif
            #undef SETTING_FORMAT
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val preserved = assertIs<ShaderStructuralPlanningResult.Preserved>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "mutation.csh"),
                ShaderStage.COMPUTE,
            ),
        )

        assertContains(preserved.reason, "SETTING_FORMAT is mutated with #undef")
    }

    @Test
    fun epipolarFallbackDeduplicatesFourValuesIntoTwoWorkgroupAbiModules() = withWorkspace { workspace ->
        val source = epipolarSource()
        val base = ShaderCompilerCopyPlanner.plan(
            source,
            "EpipolarScattering.csh",
            localSizeIdSupported = false,
            localSizeProbeDiagnostic = "forced test fallback",
        )
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan

        assertEquals(4, planned.rows.size)
        assertEquals(2, planned.materializationRows().size)
        assertEquals(1, planned.graph.components.size)
        val materialized = materialize(workspace, source, planned)
        val finalized = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialized),
        ).modules

        assertEquals(2, finalized.size)
        assertEquals(
            setOf(LocalSizeAbiSignature(1, 128, 1), LocalSizeAbiSignature(1, 256, 1)),
            finalized.mapNotNull { it.signature.localSizeFallback }.toSet(),
        )
        finalized.forEach { structural ->
            assertContains(structural.module.source, "SM_SETTING_SLICE_SAMPLES")
            assertContains(structural.module.source, "SM_DERIVED_LOOP_COUNT")
        }
        val result = SpirvOptimizer(workspace.resolve("spirv")).optimize(
            SpirvOptimizationRequest(
                "EpipolarScattering.csh",
                ShaderStage.COMPUTE,
                source,
                finalized.map { it.module },
                planned,
            ),
        )
        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, "layout(local_size_x = 1, local_size_y = WORK_GROUP_SIZE) in;")
        assertFalse("local_size_y_id" in result.source)
        assertFalse("SPIRV_CROSS_CONSTANT_ID_" in result.source)
        assertFalse("constant_id" in result.source)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun programActivationPrunesOnlyStructuralRowsProvenDisabled() {
        val source = """
            #version 460 compatibility
            #define SETTING_GRID_SIZE 64 //[16 32 64]
            layout(std430, binding = 0) buffer Values { uint values[SETTING_GRID_SIZE]; };
            layout(local_size_x = 1) in;
            void main() { values[0] = 1u; }
        """.trimIndent()
        val properties = """
            #if SETTING_GRID_SIZE == 64
            program.prepare5.enabled=true
            #else
            program.prepare5.enabled=false
            #endif
            #if defined(UNKNOWN_HOST_CAPABILITY)
            program.unknown.enabled=true
            #else
            program.unknown.enabled=false
            #endif
        """.trimIndent()
        val base = ShaderCompilerCopyPlanner.plan(source, "prepare5.csh")
        val index = ProgramActivationIndex.parse(properties)

        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                base,
                ShaderStage.COMPUTE,
                activation = index.contractFor("prepare5"),
            ),
        ).plan

        assertEquals(listOf(mapOf("SETTING_GRID_SIZE" to "64")), planned.rows.map { it.assignment })
        assertFalse(
            index.contractFor("unknown").isProvenDisabled(
                mapOf("SETTING_GRID_SIZE" to "16"),
                base.settings.associateBy { it.name },
            ),
        )
        assertFalse(index.contractFor("prepare5").cacheContract == index.contractFor("unknown").cacheContract)
    }

    @Test
    fun equalStructuralBodiesRestoreExactIslandAndRecompileEverySignature() = withWorkspace { workspace ->
        val source = equalStructuralBodies()
        val result = optimizeStructural(workspace, "equal.csh", source, ShaderStage.COMPUTE)
        val exactIsland = """
            #ifdef SETTING_WIDE
            layout(rgba16f, binding = 0) uniform image2D target;
            #else
            layout(r32f, binding = 0) uniform image2D target;
            #endif
        """.trimIndent()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, exactIsland)
        assertContains(result.source, "//#define SETTING_WIDE")
        assertContains(result.source, "const ivec3 workGroups = ivec3(1, 1, 1);")
        assertFalse("SPIRV_CROSS_CONSTANT_ID_" in result.source)
        assertFalse("constant_id" in result.source)
        assertEquals(2, result.structuralSignatures.size)
        assertEquals(2, result.finalValidationInvocations.size)
        assertTrue(result.optimizedEntities > 0)
        assertTrue(result.restoredEntities > 0)
        assertTrue(result.restoredBytes >= exactIsland.encodeToByteArray().size)
    }

    @Test
    fun optimizedAwayStructuralFunctionDoesNotForceSourcePreservation() = withWorkspace { workspace ->
        val source = optimizedAwayStructuralFunction()
        val result = optimizeStructural(workspace, "dead-function.csh", source, ShaderStage.COMPUTE)
        val exactIsland = """
            #ifdef SETTING_FLOAT
            float unusedStructural(float value) { return value; }
            #else
            int unusedStructural(int value) { return value; }
            #endif
        """.trimIndent()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertFalse(result.source.contains(exactIsland))
        assertFalse(result.source.contains("unusedStructural"))
        assertEquals(2, result.structuralSignatures.size)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun optimizedAwayCommonFunctionDoesNotChangeStructuralAbi() = withWorkspace { workspace ->
        val source = equalStructuralBodies().replace(
            "layout(local_size_x = 1) in;",
            "float unusedCommon(float value) { return value; }\nlayout(local_size_x = 1) in;",
        )
        val result = optimizeStructural(workspace, "dead-common.csh", source, ShaderStage.COMPUTE)

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertFalse("unusedCommon" in result.source)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun optimizedAwayCommonAbiDeclarationIsRestoredBeforeStructuralValidation() = withWorkspace { workspace ->
        val source = equalStructuralBodies().replace(
            "layout(local_size_x = 1) in;",
            "#ifndef UNUSED_ABI_GLSL\n#define UNUSED_ABI_GLSL\n" +
                "#define unusedAbiAlias unusedAbi\nuniform float unusedAbiAlias;\n#endif\n" +
                "layout(local_size_x = 1) in;",
        )
        val result = optimizeStructural(workspace, "dead-abi.csh", source, ShaderStage.COMPUTE)

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertFalse(result.source.contains("uniform float unusedAbi;"))
        assertFalse(result.source.contains("#define unusedAbiAlias unusedAbi"))
        assertFalse("SHADESMITH_RESTORED_ABI" in result.source)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun repeatedEquivalentGuardedAbiDeclarationIsDeduplicated() {
        val source = """
            #version 460 compatibility
            #ifndef SKIP_UNIFORMS
            uniform sampler2D shadowtex0;
            #endif
            #ifndef SKIP_UNIFORMS
            uniform sampler2D shadowtex0;
            #endif
            void main() {}
        """.trimIndent()

        val result = SpirvFinalEmitter.deduplicateDominatedAbiLines(source)

        assertEquals(1, Regex("\\buniform\\s+sampler2D\\s+shadowtex0\\s*;").findAll(result).count())
    }

    @Test
    fun conditionalAbiVariantsWinOverAnOptimizedUnconditionalDuplicate() {
        val source = """
            #version 460 compatibility
            #ifdef VOXEL_MATERIAL_VEC4
            layout(std430, binding = 4) readonly buffer VoxelMaterialData {
                uvec4 voxel_materials_v4[];
            };
            #else
            layout(std430, binding = 4) readonly buffer VoxelMaterialData {
                uint voxel_materials[];
            };
            #endif
            layout(std430, binding = 4) readonly buffer VoxelMaterialData {
                uint voxel_materials[];
            };
            void main() {}
        """.trimIndent()

        val result = SpirvFinalEmitter.deduplicateUnconditionalDeclarations(source)

        assertContains(result, "uvec4 voxel_materials_v4[];")
        assertContains(result, "uint voxel_materials[];")
        assertEquals(2, Regex("\\bbuffer VoxelMaterialData\\b").findAll(result).count())
        assertEquals(1, Regex("\\buint voxel_materials\\[\\]").findAll(result).count())
    }

    @Test
    fun sourceFunctionSignatureReplacesCrossStorageQualifierDuplicate() {
        val original = """
            #version 460 compatibility
            vec3 interpolateTurbo(float x) {
                x *= 255.0;
                return vec3(x);
            }
        """.trimIndent()
        val restored = """
            #version 460 compatibility
            vec3 interpolateTurbo(inout float x) {
                x *= 255.0;
                return vec3(x);
            }
            vec3 interpolateTurbo(float x) {
                x *= 255.0;
                return vec3(x);
            }
        """.trimIndent()

        val result = SpirvFinalEmitter.deduplicateRelaxedFunctionDefinitions(original, restored)

        assertFalse("inout float x" in result)
        assertEquals(1, Regex("vec3 interpolateTurbo\\(").findAll(result).count())
        assertContains(result, "vec3 interpolateTurbo(float x)")
    }

    @Test
    fun alreadyOrderedMacroDependentConstantIsNotHoistedWithAnUnrelatedLateConstant() {
        val source = """
            #version 460 compatibility
            float useLate() { return LATE_VALUE; }
            #define SAMPLE_COUNT 4
            const float BASE_WEIGHT = 1.0 / SAMPLE_COUNT;
            float useBase() { return BASE_WEIGHT; }
            const float LATE_VALUE = 2.0;
        """.trimIndent()

        val result = SpirvFinalEmitter.hoistLateReferencedConstants(source)

        assertTrue(result.indexOf("#define SAMPLE_COUNT 4") < result.indexOf("const float BASE_WEIGHT"))
        assertTrue(result.indexOf("const float BASE_WEIGHT") < result.indexOf("float useBase"))
        assertTrue(result.indexOf("const float LATE_VALUE") < result.indexOf("float useLate"))
    }

    @Test
    fun restoredDirectiveMacroClosurePrecedesLocalSizeHostAndArrayUses() {
        val original = """
            #version 460 compatibility
            #define BASE_COUNT 32
            #define SAMPLE_COUNT (BASE_COUNT * 2)
            layout(local_size_x = SAMPLE_COUNT) in;
            const ivec3 workGroups = ivec3(SAMPLE_COUNT, 1, 1);
            float weights[SAMPLE_COUNT];
            void main() { weights[0] = 1.0; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(local_size_x = SAMPLE_COUNT) in;
            const ivec3 workGroups = ivec3(SAMPLE_COUNT, 1, 1);
            float weights[SAMPLE_COUNT];
            void main() { weights[0] = 1.0; }
            #define BASE_COUNT 32
            #define SAMPLE_COUNT (BASE_COUNT * 2)
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        val base = result.indexOf("#define BASE_COUNT 32")
        val count = result.indexOf("#define SAMPLE_COUNT (BASE_COUNT * 2)")
        val layout = result.indexOf("layout(local_size_x = SAMPLE_COUNT)")
        val host = result.indexOf("const ivec3 workGroups")
        val array = result.indexOf("float weights[SAMPLE_COUNT]")
        assertTrue(base in 0 until count)
        assertTrue(count in 0 until layout)
        assertTrue(layout in 0 until host)
        assertTrue(host in 0 until array)
        assertEquals(1, Regex("#define BASE_COUNT\\b").findAll(result).count())
        assertEquals(1, Regex("#define SAMPLE_COUNT\\b").findAll(result).count())
    }

    @Test
    fun liveIncludeGuardDependencyRestoresGuardDefinitionBeforeHostMetadata() {
        val original = """
            #version 460 compatibility
            #ifndef INCLUDE_clouds_ss_Common_glsl
            #define INCLUDE_clouds_ss_Common_glsl a
            #if SETTING_CLOUDS_LOW_UPSCALE_FACTOR == 0
            #define RENDER_MULTIPLIER 1.0
            #else
            #define RENDER_MULTIPLIER 0.5
            #endif
            #endif
            const vec2 workGroupsRender = vec2(RENDER_MULTIPLIER);
            void main() {}
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            #define INCLUDE_clouds_ss_Common_glsl a
            #ifndef INCLUDE_clouds_ss_Common_glsl
            #if SETTING_CLOUDS_LOW_UPSCALE_FACTOR == 0
            #define RENDER_MULTIPLIER 1.0
            #else
            #define RENDER_MULTIPLIER 0.5
            #endif
            #endif
            const vec2 workGroupsRender = vec2(RENDER_MULTIPLIER);
            void main() {}
        """.trimIndent() + "\n"

        assertNotNull(
            SpirvFinalEmitter.finalLiveIncludeGuardDependencyIssue(
                "include-guard-order.csh",
                original,
                restored,
                setOf("RENDER_MULTIPLIER"),
            ),
        )
        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        val opener = result.indexOf("#ifndef INCLUDE_clouds_ss_Common_glsl")
        val marker = result.indexOf("#define INCLUDE_clouds_ss_Common_glsl a")
        val render = result.indexOf("#define RENDER_MULTIPLIER")
        val host = result.indexOf("const vec2 workGroupsRender")
        assertTrue(opener in 0 until marker, result)
        assertTrue(marker in 0 until render, result)
        assertTrue(render in 0 until host, result)
        assertEquals(1, Regex("(?m)^#define INCLUDE_clouds_ss_Common_glsl\\b").findAll(result).count(), result)
        assertEquals(0, Regex("(?m)^#ifndef RENDER_MULTIPLIER\\b").findAll(result).count(), result)
        assertNull(
            SpirvFinalEmitter.finalLiveIncludeGuardDependencyIssue(
                "include-guard-order.csh",
                original,
                result,
                setOf("RENDER_MULTIPLIER"),
            ),
        )
        assertEquals(
            result,
            assertIs<ShaderStructuralRestoration.Restored>(
                restoreDirectiveMacroDependencies(original, result),
            ).source,
        )
    }

    @Test
    fun nestedLiveIncludeGuardDependencyRestorationIsIdempotent() {
        val original = """
            #version 460 compatibility
            #ifndef INCLUDE_OUTER
            #define INCLUDE_OUTER
            #ifndef INCLUDE_INNER
            #define INCLUDE_INNER
            #define GROUP_SIZE 8
            #endif
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            #ifndef INCLUDE_OUTER
            #define INCLUDE_OUTER
            #define INCLUDE_INNER
            #ifndef INCLUDE_INNER
            #define GROUP_SIZE 8
            #endif
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        assertTrue(result.indexOf("#ifndef INCLUDE_INNER") < result.indexOf("#define INCLUDE_INNER"), result)
        assertTrue(result.indexOf("#define INCLUDE_INNER") < result.indexOf("#define GROUP_SIZE"), result)
        assertEquals(
            result,
            assertIs<ShaderStructuralRestoration.Restored>(
                restoreDirectiveMacroDependencies(original, result),
            ).source,
        )
    }

    @Test
    fun ambiguousAndConflictingLiveIncludeGuardDependenciesFailClosed() {
        val ambiguous = """
            #version 460 compatibility
            #ifndef INCLUDE_A
            #define INCLUDE_A
            #define GROUP_SIZE 8
            #endif
            #ifndef INCLUDE_B
            #define INCLUDE_B
            #define GROUP_SIZE 8
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"
        val ambiguousRestored = """
            #version 460 compatibility
            #define INCLUDE_A
            #ifndef INCLUDE_A
            #define GROUP_SIZE 8
            #endif
            #define INCLUDE_B
            #ifndef INCLUDE_B
            #define GROUP_SIZE 8
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"
        val ambiguousResult = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(ambiguous, ambiguousRestored),
        )
        assertContains(ambiguousResult.reason, "ownership is ambiguous")

        val original = """
            #version 460 compatibility
            #ifndef INCLUDE_COMMON
            #define INCLUDE_COMMON 1
            #define GROUP_SIZE 8
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"
        val conflicting = """
            #version 460 compatibility
            #define INCLUDE_COMMON 2
            #ifndef INCLUDE_COMMON
            #define GROUP_SIZE 8
            #endif
            layout(local_size_x = GROUP_SIZE) in;
            void main() {}
        """.trimIndent() + "\n"
        val conflictingResult = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(original, conflicting),
        )
        assertContains(conflictingResult.reason, "conflicting external definition")
    }

    @Test
    fun absentOverlappingContractDoesNotHideStructuralDeclarationDependencies() {
        val declaration = "shared uint spreadLut[VOXEL_GRID_SIZE * VOXEL_BRICK_SIZE];"
        val original = """
            #version 460 compatibility
            #define SETTING_VOXEL_GRID_SIZE 64 //[16 32 64]
            const int VOXEL_BRICK_SIZE = 16;
            #define VOXEL_GRID_SIZE SETTING_VOXEL_GRID_SIZE
            $declaration
            void main() { spreadLut[0] = 0u; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            const int VOXEL_BRICK_SIZE = 16;
            $declaration
            void main() { spreadLut[0] = 0u; }
            #define SETTING_VOXEL_GRID_SIZE 64 //[16 32 64]
            #define VOXEL_GRID_SIZE SETTING_VOXEL_GRID_SIZE
        """.trimIndent() + "\n"
        val base = ShaderCompilerCopyPlanner.plan(original, "structural-macro-order.csh")
        val declarationStart = original.indexOf(declaration)
        val absentOverlap = IrisSourceContractSlice(
            ordinal = 0,
            kind = IrisSourceContractKind.HOST_DECLARATION,
            exactText = "// intentionally absent contract\n",
            sourceLine = original.take(declarationStart).count { it == '\n' } + 1,
            sourceRange = declarationStart until declarationStart + declaration.length,
            beforeAnchor = null,
            afterAnchor = null,
            placement = IrisAnchorPlacement.AFTER_BEFORE,
        )
        val restorationPlan = ShaderStructuralRestorationPlan(
            base.sourceName,
            base.settings,
            emptySet(),
            emptyList(),
            listOf(absentOverlap),
            null,
        )

        assertNotNull(
            SpirvFinalEmitter.finalDirectiveMacroDependencyIssue(
                base.sourceName,
                original,
                restored,
                listOf(absentOverlap),
                restorationPlan,
            ),
        )
        val result = assertIs<ShaderStructuralRestoration.Restored>(
            SpirvFinalEmitter.restoreDirectiveMacroDependencies(
                base.sourceName,
                original,
                restored,
                listOf(absentOverlap),
                restorationPlan,
            ),
        ).source

        assertTrue(result.indexOf("#define SETTING_VOXEL_GRID_SIZE") < result.indexOf("#define VOXEL_GRID_SIZE"))
        assertTrue(result.indexOf("#define VOXEL_GRID_SIZE") < result.indexOf(declaration))
        assertEquals(1, Regex("#define SETTING_VOXEL_GRID_SIZE\\b").findAll(result).count())
        assertEquals(1, Regex("#define VOXEL_GRID_SIZE\\b").findAll(result).count())
        assertNull(
            SpirvFinalEmitter.finalDirectiveMacroDependencyIssue(
                base.sourceName,
                original,
                result,
                listOf(absentOverlap),
                restorationPlan,
            ),
        )
    }

    @Test
    fun expandedStructuralDeclarationRestoresCompleteMacroClosure() {
        val original = """
            #version 460 compatibility
            #define SETTING_VOXEL_GRID_SIZE 32 //[16 32 64]
            #define VOXEL_GRID_SIZE SETTING_VOXEL_GRID_SIZE
            #define VOXEL_GRID_BRICKS (VOXEL_GRID_SIZE * VOXEL_GRID_SIZE * VOXEL_GRID_SIZE)
            #define NUM_DIST_BUCKETS 1024
            #define VOXEL_BRICK_DATA_MODIFIER restrict readonly buffer
            #define VOXEL_ELEMENT_TYPE uint
            layout(std430, binding = 3) VOXEL_BRICK_DATA_MODIFIER VoxelBrickData {
                VOXEL_ELEMENT_TYPE voxel_brickOccupancy[VOXEL_GRID_BRICKS];
                VOXEL_ELEMENT_TYPE voxel_bucketCounts[NUM_DIST_BUCKETS];
            };
            void main() { uint value = voxel_brickOccupancy[0]; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(binding = 3, std430) restrict readonly buffer VoxelBrickData {
                uint voxel_brickOccupancy[VOXEL_GRID_BRICKS];
                uint voxel_bucketCounts[NUM_DIST_BUCKETS];
            };
            void main() { uint value = voxel_brickOccupancy[0]; }
            #define SETTING_VOXEL_GRID_SIZE 32 //[16 32 64]
            #define VOXEL_GRID_SIZE SETTING_VOXEL_GRID_SIZE
            #define VOXEL_GRID_BRICKS (VOXEL_GRID_SIZE * VOXEL_GRID_SIZE * VOXEL_GRID_SIZE)
            #define NUM_DIST_BUCKETS 1024
            #define VOXEL_BRICK_DATA_MODIFIER restrict readonly buffer
            #define VOXEL_ELEMENT_TYPE uint
        """.trimIndent() + "\n"
        val base = ShaderCompilerCopyPlanner.plan(original, "expanded-declaration-order.csh")
        val restorationPlan = ShaderStructuralRestorationPlan(
            base.sourceName,
            base.settings,
            emptySet(),
            emptyList(),
            base.irisContracts.contracts,
            null,
        )

        assertNotNull(
            SpirvFinalEmitter.finalDirectiveMacroDependencyIssue(
                base.sourceName,
                original,
                restored,
                base.irisContracts.contracts,
                restorationPlan,
            ),
        )
        val result = assertIs<ShaderStructuralRestoration.Restored>(
            SpirvFinalEmitter.restoreDirectiveMacroDependencies(
                base.sourceName,
                original,
                restored,
                base.irisContracts.contracts,
                restorationPlan,
            ),
        ).source

        val declaration = result.indexOf("buffer VoxelBrickData")
        listOf(
            "#define SETTING_VOXEL_GRID_SIZE",
            "#define VOXEL_GRID_SIZE",
            "#define VOXEL_GRID_BRICKS",
            "#define NUM_DIST_BUCKETS",
            "#define VOXEL_BRICK_DATA_MODIFIER",
            "#define VOXEL_ELEMENT_TYPE",
        ).forEach { definition ->
            assertTrue(result.indexOf(definition) in 0 until declaration, result)
            assertEquals(1, Regex("(?m)^${Regex.escape(definition)}\\b").findAll(result).count(), result)
        }
        assertEquals(
            result,
            assertIs<ShaderStructuralRestoration.Restored>(
                SpirvFinalEmitter.restoreDirectiveMacroDependencies(
                    base.sourceName,
                    original,
                    result,
                    base.irisContracts.contracts,
                    restorationPlan,
                ),
            ).source,
        )
        assertNull(
            SpirvFinalEmitter.finalDirectiveMacroDependencyIssue(
                base.sourceName,
                original,
                result,
                base.irisContracts.contracts,
                restorationPlan,
            ),
        )
    }

    @Test
    fun ambiguousExpandedStructuralDeclarationMappingFailsClosed() {
        val original = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define ARRAY_COUNT 4
            #define BLOCK_MODIFIER readonly buffer
            #if SETTING_MODE == 0
            layout(std430, binding = 0) BLOCK_MODIFIER Data { uint values[ARRAY_COUNT]; };
            #else
            layout(std430, binding = 0) BLOCK_MODIFIER Data { uint values[ARRAY_COUNT * 2]; };
            #endif
            void main() { uint value = values[0]; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(binding = 0, std430) readonly buffer Data { uint values[ARRAY_COUNT]; };
            void main() { uint value = values[0]; }
            #define SETTING_MODE 0 //[0 1]
            #define ARRAY_COUNT 4
            #define BLOCK_MODIFIER readonly buffer
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(original, restored),
        )

        assertContains(result.reason, "expanded declaration Data source mapping is ambiguous")
    }

    @Test
    fun expandedDeclarationIgnoresLaterMacroOverrides() {
        val original = """
            #version 460 compatibility
            #ifndef GLOBAL_DATA_MODIFIER
            #define GLOBAL_DATA_MODIFIER restrict readonly buffer
            #endif
            layout(std430, binding = 0) GLOBAL_DATA_MODIFIER GlobalData { uint values[4]; };
            #ifdef DEBUG_PASS
            #define GLOBAL_DATA_MODIFIER buffer
            #endif
            void main() { uint value = values[0]; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(binding = 0, std430) restrict readonly buffer GlobalData { uint values[4]; };
            #ifdef DEBUG_PASS
            #define GLOBAL_DATA_MODIFIER buffer
            #endif
            void main() { uint value = values[0]; }
            #ifndef GLOBAL_DATA_MODIFIER
            #define GLOBAL_DATA_MODIFIER restrict readonly buffer
            #endif
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        val declaration = result.indexOf("buffer GlobalData")
        val defaultDefinition = result.indexOf("#define GLOBAL_DATA_MODIFIER restrict readonly buffer")
        val debugDefinition = result.indexOf("#define GLOBAL_DATA_MODIFIER buffer")
        assertTrue(defaultDefinition in 0 until declaration, result)
        assertTrue(debugDefinition > declaration, result)
        assertEquals(2, Regex("(?m)^#define GLOBAL_DATA_MODIFIER\\b").findAll(result).count(), result)
        assertEquals(
            result,
            assertIs<ShaderStructuralRestoration.Restored>(
                restoreDirectiveMacroDependencies(original, result),
            ).source,
        )
    }

    @Test
    fun expandedDeclarationPrefersEarlierNestedDefaultOverShallowerOverride() {
        val original = """
            #version 460 compatibility
            #ifdef PROGRAM_ENABLED
            #ifndef GLOBAL_DATA_MODIFIER
            #define GLOBAL_DATA_MODIFIER restrict readonly buffer
            #endif
            layout(std430, binding = 0) GLOBAL_DATA_MODIFIER GlobalData { uint values[4]; };
            #endif
            #ifdef DEBUG_PASS
            #define GLOBAL_DATA_MODIFIER buffer
            #endif
            void main() { uint value = values[0]; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(binding = 0, std430) restrict readonly buffer GlobalData { uint values[4]; };
            #ifdef DEBUG_PASS
            #define GLOBAL_DATA_MODIFIER buffer
            #endif
            void main() { uint value = values[0]; }
            #ifdef PROGRAM_ENABLED
            #ifndef GLOBAL_DATA_MODIFIER
            #define GLOBAL_DATA_MODIFIER restrict readonly buffer
            #endif
            #endif
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        val declaration = result.indexOf("buffer GlobalData")
        val defaultDefinition = result.indexOf("#define GLOBAL_DATA_MODIFIER restrict readonly buffer")
        val debugDefinition = result.indexOf("#define GLOBAL_DATA_MODIFIER buffer")
        assertTrue(defaultDefinition in 0 until declaration, result)
        assertTrue(debugDefinition > declaration, result)
        assertEquals(
            result,
            assertIs<ShaderStructuralRestoration.Restored>(
                restoreDirectiveMacroDependencies(original, result),
            ).source,
        )
    }

    @Test
    fun functionMacroTypeAndArrayClosurePrecedesStructuralBlock() {
        val original = """
            #version 460 compatibility
            #define TYPE_IMPL(value) value
            #define VALUE_TYPE TYPE_IMPL(float)
            #define ARRAY_COUNT 4
            layout(std430, binding = 0) buffer Data { VALUE_TYPE values[ARRAY_COUNT]; };
            void main() { values[0] = 1.0; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            layout(std430, binding = 0) buffer Data { VALUE_TYPE values[ARRAY_COUNT]; };
            void main() { values[0] = 1.0; }
            #define TYPE_IMPL(value) value
            #define VALUE_TYPE TYPE_IMPL(float)
            #define ARRAY_COUNT 4
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        assertTrue(result.indexOf("#define TYPE_IMPL") < result.indexOf("#define VALUE_TYPE"))
        assertTrue(result.indexOf("#define VALUE_TYPE") < result.indexOf("buffer Data"))
        assertTrue(result.indexOf("#define ARRAY_COUNT") < result.indexOf("buffer Data"))
    }

    @Test
    fun restoredConditionalMacroOwnerAndCapabilityDependencyPrecedeUses() {
        val original = """
            #version 460 compatibility
            #define USE_LARGE 1
            #if USE_LARGE
            #define SAMPLE_COUNT 64
            #else
            #define SAMPLE_COUNT 32
            #endif
            #define ENABLE_INT64 1
            #if ENABLE_INT64
            #extension GL_ARB_gpu_shader_int64 : require
            #endif
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            #if ENABLE_INT64
            #extension GL_ARB_gpu_shader_int64 : require
            #endif
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
            #define USE_LARGE 1
            #if USE_LARGE
            #define SAMPLE_COUNT 64
            #else
            #define SAMPLE_COUNT 32
            #endif
            #define ENABLE_INT64 1
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        assertTrue(result.indexOf("#define USE_LARGE 1") < result.indexOf("#if USE_LARGE"))
        assertTrue(result.indexOf("#if USE_LARGE") < result.indexOf("layout(local_size_x = SAMPLE_COUNT)"))
        assertTrue(result.indexOf("#define ENABLE_INT64 1") < result.indexOf("#if ENABLE_INT64"))
        assertEquals(1, Regex("#define SAMPLE_COUNT 64").findAll(result).count())
        assertEquals(1, Regex("#define SAMPLE_COUNT 32").findAll(result).count())
    }

    @Test
    fun contractContainedHostDeclarationAndCommentedMacroExampleRemainOrdered() {
        val original = """
            #version 460 compatibility
            #define GROUP_SIZE 8
            #define DISPATCH_SIZE 32
            float stableAnchor() { return 1.0; }
            layout(local_size_x = GROUP_SIZE) in;
            const ivec3 workGroups = ivec3(DISPATCH_SIZE, 1, 1);
            // #define DISPATCH_SIZE 64
            void main() {}
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            float stableAnchor() { return 1.0; }
            layout(local_size_x = GROUP_SIZE) in;
            const ivec3 workGroups = ivec3(DISPATCH_SIZE, 1, 1);
            // #define DISPATCH_SIZE 64
            void main() {}
            #define GROUP_SIZE 8
            #define DISPATCH_SIZE 32
        """.trimIndent() + "\n"

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, restored),
        ).source

        val group = result.indexOf("#define GROUP_SIZE 8")
        val dispatch = result.indexOf("#define DISPATCH_SIZE 32")
        val layout = result.indexOf("layout(local_size_x = GROUP_SIZE)")
        val host = result.indexOf("const ivec3 workGroups")
        assertTrue(group in 0 until layout, result)
        assertTrue(dispatch in 0 until layout, result)
        assertTrue(layout in 0 until host, result)
        assertEquals(1, Regex("(?m)^#define DISPATCH_SIZE 32$").findAll(result).count())
        assertEquals(1, Regex("(?m)^// #define DISPATCH_SIZE 64$").findAll(result).count())
    }

    @Test
    fun directiveMacroCyclesConflictsAndAmbiguousTargetsFailClosed() {
        val cyclic = """
            #version 460 compatibility
            #define COUNT_A COUNT_B
            #define COUNT_B COUNT_A
            float stableAnchor() { return 1.0; }
            layout(local_size_x = COUNT_A) in;
            void main() {}
        """.trimIndent() + "\n"
        val cyclicRestored = """
            #version 460 compatibility
            float stableAnchor() { return 1.0; }
            layout(local_size_x = COUNT_A) in;
            void main() {}
            #define COUNT_A COUNT_B
            #define COUNT_B COUNT_A
        """.trimIndent() + "\n"
        val cycle = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(cyclic, cyclicRestored),
        )
        assertContains(cycle.reason, "cyclic directive macro dependency")
        assertContains(cycle.reason, "COUNT_A -> COUNT_B -> COUNT_A")

        val conflicting = """
            #version 460 compatibility
            #define SAMPLE_COUNT 32
            #define SAMPLE_COUNT 64
            float stableAnchor() { return 1.0; }
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
        """.trimIndent() + "\n"
        val conflictingRestored = """
            #version 460 compatibility
            float stableAnchor() { return 1.0; }
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
            #define SAMPLE_COUNT 32
            #define SAMPLE_COUNT 64
        """.trimIndent() + "\n"
        val conflict = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(conflicting, conflictingRestored),
        )
        assertContains(conflict.reason, "conflicting source definitions")
        assertContains(conflict.reason, "SAMPLE_COUNT")

        val original = """
            #version 460 compatibility
            #define SAMPLE_COUNT 64
            float stableAnchor() { return 1.0; }
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
        """.trimIndent() + "\n"
        val duplicatedDependency = """
            #version 460 compatibility
            float stableAnchor() { return 1.0; }
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
            #define SAMPLE_COUNT 64
            #define SAMPLE_COUNT 64
        """.trimIndent() + "\n"
        val duplicateDependency = assertIs<ShaderStructuralRestoration.Restored>(
            restoreDirectiveMacroDependencies(original, duplicatedDependency),
        ).source
        assertEquals(1, Regex("#define SAMPLE_COUNT 64").findAll(duplicateDependency).count())
        assertTrue(duplicateDependency.indexOf("#define SAMPLE_COUNT 64") < duplicateDependency.indexOf("layout(local_size_x"))

        val ambiguous = """
            #version 460 compatibility
            float stableAnchor() { return 1.0; }
            layout(local_size_x = SAMPLE_COUNT) in;
            layout(local_size_x = SAMPLE_COUNT) in;
            void main() {}
            #define SAMPLE_COUNT 64
        """.trimIndent() + "\n"
        val duplicate = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreDirectiveMacroDependencies(original, ambiguous),
        )
        assertContains(duplicate.reason, "LOCAL_SIZE contract is ambiguous (2 restored matches)")
    }

    @Test
    fun restoredTokenPasteFunctionReusesSurvivingCompilerHelper() {
        val helper = ShaderCompilerCopyPlanner.tokenPasteHelperName("APPLY_IMPL")
        val original = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define APPLY_IMPL(mode, value) function_ ## mode(value)
            #define APPLY(mode, value) APPLY_IMPL(mode, value)
            float function_0(float value) { return value; }
            float function_1(float value) { return value + 1.0; }
            float restored(float value) { return APPLY(SETTING_MODE, value); }
            layout(local_size_x = 1) in;
            void main() { float value = restored(1.0); }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define APPLY_IMPL(mode, value) function_ ## mode(value)
            #define APPLY(mode, value) APPLY_IMPL(mode, value)
            float $helper(int mode, float value) {
                if (mode == 0) return value;
                return value + 1.0;
            }
            float restored(float value) { return APPLY(SETTING_MODE, value); }
            layout(local_size_x = 1) in;
            void main() { float value = restored(1.0); }
        """.trimIndent() + "\n"
        val plan = ShaderCompilerCopyPlanner.plan(original, "token-paste-restored-function.csh")

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreTokenPasteSourceDependencies(original, restored, plan.tokenPasteLowerings),
        ).source

        assertContains(result, "#define APPLY_IMPL(mode, value) $helper(mode, value)")
        assertFalse("##" in result)
        assertFalse("function_0" in result)
        assertFalse("function_1" in result)
        assertEquals(1, Regex("float ${Regex.escape(helper)}\\([^)]*\\)\\s*\\{").findAll(result).count())
    }

    @Test
    fun restoredTokenPasteAggregateRecoversOptimizedAwayValueDeclarations() {
        val original = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define MATRIX_IMPL(mode) matrix_ ## mode
            #define MATRIX(mode) MATRIX_IMPL(mode)
            const mat3 matrix_0 = mat3(1.0);
            const mat3 matrix_1 = mat3(2.0);
            const vec3 restoredValue = MATRIX(SETTING_MODE) * vec3(1.0);
            layout(local_size_x = 1) in;
            void main() { vec3 value = restoredValue; }
        """.trimIndent() + "\n"
        val restored = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define MATRIX_IMPL(mode) matrix_ ## mode
            #define MATRIX(mode) MATRIX_IMPL(mode)
            const vec3 restoredValue = MATRIX(SETTING_MODE) * vec3(1.0);
            layout(local_size_x = 1) in;
            void main() { vec3 value = restoredValue; }
        """.trimIndent() + "\n"
        val lowering = ShaderTokenPasteLowering(
            macroName = "MATRIX_IMPL",
            sourceLine = 3,
            sourceDirective = "#define MATRIX_IMPL(mode) matrix_ ## mode\n",
            loweredDirective = "#define MATRIX_IMPL(mode) ((mode) == 0 ? matrix_0 : matrix_1)\n",
            helperPrototypes = "",
            helperNames = emptySet(),
            candidateIdentifiers = setOf("matrix_0", "matrix_1"),
            candidateCoverageComplete = true,
            settingDependencies = setOf("SETTING_MODE"),
        )

        val result = assertIs<ShaderStructuralRestoration.Restored>(
            restoreTokenPasteSourceDependencies(original, restored, listOf(lowering)),
        ).source

        assertContains(result, "#define MATRIX_IMPL(mode) ((mode) == 0 ? matrix_0 : matrix_1)")
        assertEquals(1, Regex("const mat3 matrix_0").findAll(result).count())
        assertEquals(1, Regex("const mat3 matrix_1").findAll(result).count())
        assertTrue(result.indexOf("const mat3 matrix_0") < result.indexOf("const vec3 restoredValue"), result)
        assertTrue(result.indexOf("const mat3 matrix_1") < result.indexOf("const vec3 restoredValue"), result)
        assertFalse("##" in result)
    }

    @Test
    fun tokenPasteDataDependencyFailuresAreAttributedAndClosed() {
        val baseOriginal = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define CALL_IMPL(mode, value) function_ ## mode(value)
            #define CALL(mode, value) CALL_IMPL(mode, value)
            float restored(float value) { return CALL(SETTING_MODE, value); }
            layout(local_size_x = 1) in;
            void main() { float value = restored(1.0); }
        """.trimIndent() + "\n"
        val missingHelper = ShaderTokenPasteLowering(
            macroName = "CALL_IMPL",
            sourceLine = 3,
            sourceDirective = "#define CALL_IMPL(mode, value) function_ ## mode(value)\n",
            loweredDirective = "#define CALL_IMPL(mode, value) SM_TOKEN_PASTE_MISSING(mode, value)\n",
            helperPrototypes = "float SM_TOKEN_PASTE_MISSING(int mode, float value);\n",
            helperNames = setOf("SM_TOKEN_PASTE_MISSING"),
            candidateIdentifiers = setOf("function_0"),
            candidateCoverageComplete = false,
            settingDependencies = setOf("SETTING_MODE"),
        )
        val missing = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreTokenPasteSourceDependencies(baseOriginal, baseOriginal, listOf(missingHelper)),
        )
        assertContains(missing.reason, "CALL_IMPL")
        assertContains(missing.reason, "candidate coverage is incomplete")
        assertContains(missing.reason, "SETTING_MODE")

        val conflicting = baseOriginal.replace(
            "#define CALL_IMPL(mode, value) function_ ## mode(value)",
            "#define CALL_IMPL(mode, value) other_ ## mode(value)",
        )
        val conflict = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreTokenPasteSourceDependencies(baseOriginal, conflicting, listOf(missingHelper)),
        )
        assertContains(conflict.reason, "does not match its proven compiler-copy lowering")

        val cyclicOriginal = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define SELECT_IMPL(mode) candidate_ ## mode
            #define SELECT(mode) SELECT_IMPL(mode)
            #define candidate_0 CYCLE_A
            #define CYCLE_A CYCLE_B
            #define CYCLE_B CYCLE_A
            const float restoredValue = float(SELECT(SETTING_MODE));
            layout(local_size_x = 1) in;
            void main() { float value = restoredValue; }
        """.trimIndent() + "\n"
        val cyclicLowering = ShaderTokenPasteLowering(
            macroName = "SELECT_IMPL",
            sourceLine = 3,
            sourceDirective = "#define SELECT_IMPL(mode) candidate_ ## mode\n",
            loweredDirective = "#define SELECT_IMPL(mode) candidate_0\n",
            helperPrototypes = "",
            helperNames = emptySet(),
            candidateIdentifiers = setOf("candidate_0"),
            candidateCoverageComplete = true,
            settingDependencies = setOf("SETTING_MODE"),
        )
        val cycle = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreTokenPasteSourceDependencies(cyclicOriginal, cyclicOriginal, listOf(cyclicLowering)),
        )
        assertContains(cycle.reason, "cyclic token-paste data dependency")
        assertContains(cycle.reason, "CYCLE_A -> CYCLE_B -> CYCLE_A")

        val ambiguousOriginal = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define SELECT_IMPL(mode) selected_ ## mode
            #define SELECT(mode) SELECT_IMPL(mode)
            const float selected_0 = 1.0;
            const float selected_0 = 2.0;
            const float restoredValue = SELECT(SETTING_MODE);
            layout(local_size_x = 1) in;
            void main() { float value = restoredValue; }
        """.trimIndent() + "\n"
        val ambiguousRestored = ambiguousOriginal.lineSequence()
            .filterNot { line -> line.startsWith("const float selected_0") }
            .joinToString("\n", postfix = "\n")
        val ambiguousLowering = ShaderTokenPasteLowering(
            macroName = "SELECT_IMPL",
            sourceLine = 3,
            sourceDirective = "#define SELECT_IMPL(mode) selected_ ## mode\n",
            loweredDirective = "#define SELECT_IMPL(mode) selected_0\n",
            helperPrototypes = "",
            helperNames = emptySet(),
            candidateIdentifiers = setOf("selected_0"),
            candidateCoverageComplete = true,
            settingDependencies = setOf("SETTING_MODE"),
        )
        val ambiguous = assertIs<ShaderStructuralRestoration.Preserved>(
            restoreTokenPasteSourceDependencies(
                ambiguousOriginal,
                ambiguousRestored,
                listOf(ambiguousLowering),
            ),
        )
        assertContains(ambiguous.reason, "token-paste declaration dependency selected_0 is ambiguous")
    }

    @Test
    fun optimizedCapabilityFunctionRecoversSimpleOriginalGuard() {
        val original = """
            #version 460 compatibility
            float pow2(float value) { return value * value; }
            #ifndef NO_HALF
            float16_t pow2(float16_t value) { return value * value; }
            #endif
        """.trimIndent()
        val optimized = """
            #version 460 compatibility
            float pow2(float value) { return value * value; }
            float16_t pow2(float16_t value) { return value * value; }
        """.trimIndent()

        val result = SpirvFinalEmitter.restoreSourceFunctionConditionalOwners(original, optimized)

        assertContains(result, "#ifndef NO_HALF\nfloat16_t pow2(float16_t value)")
        assertEquals(1, Regex("#ifndef NO_HALF").findAll(result).count())
    }

    @Test
    fun structuralContractsUseDurablePrologueAnchors() {
        val source = equalStructuralBodies()
        val base = ShaderCompilerCopyPlanner.plan(source, "durable-contract.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan

        planned.restorationPlan.restorationContracts.forEach { contract ->
            assertEquals(IrisSourceAnchor(IrisAnchorKind.VERSION, "version"), contract.beforeAnchor)
            assertEquals(IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main"), contract.afterAnchor)
            assertEquals(IrisAnchorPlacement.AFTER_BEFORE, contract.placement)
        }
    }

    @Test
    fun restorationTreatsMultipleStructuralMainFunctionsAsOneLogicalAnchor() {
        val mainAnchor = IrisSourceAnchor(IrisAnchorKind.FUNCTION, "main")
        val plan = ShaderStructuralRestorationPlan(
            sourceName = "multi-main.csh",
            settings = emptyList(),
            structuralSettings = emptySet(),
            islands = listOf(
                ShaderStructuralEntitySlot(
                    ordinal = 0,
                    kind = ShaderStructuralEntitySlotKind.TOP_LEVEL_REGION,
                    canonicalEntity = null,
                    exactText = "const int restored = 1;\n",
                    sourceLine = 1,
                    beforeAnchor = null,
                    afterAnchor = mainAnchor,
                    placement = IrisAnchorPlacement.BEFORE_AFTER,
                ),
            ),
            restorationContracts = emptyList(),
            issue = null,
        )
        val source = """
            #version 460 compatibility
            #ifdef SETTING_BRANCH
            void main() {}
            #else
            void main() {}
            #endif
        """.trimIndent()

        val restored = assertIs<ShaderStructuralRestoration.Restored>(plan.restore(source)).source

        assertTrue(restored.indexOf("const int restored") < restored.indexOf("void main"))
    }

    @Test
    fun structuralResourceCommentsDoNotPreventCrossOutputMatching() = withWorkspace { workspace ->
        val source = commentedStructuralResource()
        val result = optimizeStructural(workspace, "commented-resource.csh", source, ShaderStage.COMPUTE)

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, "// wide storage contract")
        assertContains(result.source, "// narrow storage contract")
        assertEquals(2, result.structuralSignatures.size)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun mixedStructuralAndOrdinaryUsesRestoreOnlyAbiIsland() = withWorkspace { workspace ->
        val source = mixedStructuralAndOrdinaryUses()
        val result = optimizeStructural(workspace, "mixed.csh", source, ShaderStage.COMPUTE)
        val exactIsland = """
            #ifdef SETTING_WIDE
            layout(rgba16f, binding = 0) uniform image2D target;
            #else
            layout(r32f, binding = 0) uniform image2D target;
            #endif
        """.trimIndent()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, exactIsland)
        assertContains(result.source, "if (SM_SETTING_WIDE)")
        assertFalse("int weight = 0;\n#ifdef SETTING_WIDE" in result.source)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun coupledAbiControlFlowMaterializesOnlyItsStructuralComponent() {
        val source = coupledAbiAndIndependentStructuralUses()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "coupled.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        assertEquals(2, planned.graph.components.size)
        assertEquals(5, planned.rows.size)
        assertEquals(3, planned.materializationRows().size)
        val materializedSettings = linkedSetOf<String>()
        planned.rows.forEach { row ->
            val compiler = requireNotNull(row.compilerPlan.compilerSource)
            val rowSettings = listOf("FORMAT", "SHAPE").filter { "SM_STRUCT_SETTING_$it" in compiler }
            assertTrue(rowSettings.size <= 1)
            materializedSettings += rowSettings
        }
        assertEquals(setOf("FORMAT"), materializedSettings)
    }

    @Test
    fun conditionalExtensionContractIsRestoredAndValidatedForEveryCapabilitySignature() = withWorkspace { workspace ->
        val source = extensionFixture()
        val result = optimizeStructural(workspace, "extension.csh", source, ShaderStage.COMPUTE)
        val exactContract = """
            #ifdef SETTING_EXTENSION
            #extension GL_KHR_shader_subgroup_basic : require
            #endif
        """.trimIndent()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, exactContract)
        assertEquals(2, result.structuralSignatures.size)
        assertEquals(2, result.finalValidationInvocations.size)
    }

    @Test
    fun divergentStructuralBodiesPreserveOriginalSourceWithDeterministicReason() = withWorkspace { workspace ->
        val source = resourceFixture()
        val first = optimizeStructural(workspace.resolve("first"), "divergent.csh", source, ShaderStage.COMPUTE)
        val second = optimizeStructural(workspace.resolve("second"), "divergent.csh", source, ShaderStage.COMPUTE)

        assertEquals(SpirvEmissionMode.PRESERVED_SOURCE, first.emissionMode)
        assertEquals(source, first.source)
        assertContains(first.fallbackReason.orEmpty(), "would leave no optimized executable entity")
        assertEquals(first.fallbackReason, second.fallbackReason)
        assertTrue(first.modules.size >= 2)
        assertTrue(first.finalValidationInvocations.isEmpty())
        assertTrue(first.modules.all { Files.notExists(it.validationSpirv) })
    }

    @Test
    fun nestedStructuralIslandRetainsSameAbiRowsAndUnionsLifecycleInOptimizedOutput() = withWorkspace { workspace ->
        val source = nestedLeakedLocal().replace(
            "layout(local_size_x = 1) in;",
            "uniform float unusedAbi;\nlayout(local_size_x = 1) in;",
        )
        val base = ShaderCompilerCopyPlanner.plan(source, "nested.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan
        val materialized = materialize(workspace, source, planned).mapIndexed { index, module ->
            module.copy(conservativeAccess = TextureAccess(reads = setOf("branch_$index")))
        }
        val structural = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialized),
        ).modules
        assertEquals(1, structural.size)
        assertEquals(1, structural.map { it.signature }.toSet().size)
        val modules = structural.map(ShaderStructuralModule::module)
        val result = SpirvOptimizer(workspace.resolve("spirv")).optimize(
            SpirvOptimizationRequest("nested.csh", ShaderStage.COMPUTE, source, modules, planned),
        )

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertFalse(result.source == source)
        assertFalse(result.source.contains("uniform float unusedAbi;"))
        val prologue = result.source.indexOf("SHADESMITH_BRANCH_OWNED_PROLOGUE_BEGIN")
        assertTrue(prologue < 0)
        assertEquals(1, result.finalValidationInvocations.size)
        assertEquals(1, result.modules.size)
        assertEquals(
            setOf("branch_0", "branch_1"),
            result.modules.map { it.textureAccess }.fold(TextureAccess(), TextureAccess::plus).reads,
        )
    }

    @Test
    fun symbolicAbiArrayRestoresItsSourceConstantDependencies() {
        val original = """
            #version 460 compatibility
            #define GRID_SIZE 64
            const int BRICK_SIZE = 16;
            shared uint spreadLut[GRID_SIZE * BRICK_SIZE];
            layout(local_size_x = 1) in;
            void main() { spreadLut[0] = 1u; }
        """.trimIndent() + "\n"
        val optimized = """
            #version 460 compatibility
            shared uint spreadLut[1024];
            layout(local_size_x = 1) in;
            void main() { spreadLut[0] = 1u; }
        """.trimIndent() + "\n"

        val restored = SpirvFinalEmitter.restoreSymbolicSourceAbiDeclarations(original, optimized)

        assertContains(restored, "const int BRICK_SIZE = 16;")
        assertContains(restored, "shared uint spreadLut[GRID_SIZE * BRICK_SIZE];")
        assertFalse("shared uint spreadLut[1024];" in restored)
    }

    @Test
    fun hoistsRestoredImageDeclarationAheadOfHelperUse() {
        val source = """
            #version 460 compatibility
            vec4 loadValue(ivec2 texel) { return imageLoad(colorimg0, texel); }
            layout(rgba16f) restrict uniform image2D colorimg0;
            void main() { imageStore(colorimg0, ivec2(0), loadValue(ivec2(0))); }
        """.trimIndent() + "\n"

        val restored = SpirvFinalEmitter.hoistLateDeclarationDependencies(source)

        assertTrue(restored.indexOf("uniform image2D colorimg0") < restored.indexOf("vec4 loadValue"))
        assertEquals(1, "uniform image2D colorimg0".toRegex().findAll(restored).count())
    }

    @Test
    fun abiCoupledExecutableUsePromotesToSourceMappableWholeFunctionSlot() {
        val source = """
            #version 460 compatibility
            #define SETTING_WIDTH 2 //[2 4]
            float evaluate(float values[SETTING_WIDTH]) { return values[0]; }
            layout(local_size_x = 1) in;
            void main() {
                float values[SETTING_WIDTH];
                values[0] = evaluate(values);
            }
        """.trimIndent()
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "function-slot.csh"),
                ShaderStage.COMPUTE,
            ),
        ).plan

        val slot = planned.restorationPlan.islands.single {
            it.kind == ShaderStructuralEntitySlotKind.FUNCTION
        }
        assertContains(slot.canonicalEntity.orEmpty(), "float evaluate(float values[SETTING_WIDTH])")
        assertContains(slot.exactText, "return values[0];")
        assertEquals(null, planned.restorationPlan.issue)
    }

    @Test
    fun fixedSignatureCoversResourceInterfaceCapabilityFunctionAndTokenPasteModules() = withWorkspace { workspace ->
        val fixtures = listOf(
            Fixture("resource.csh", ShaderStage.COMPUTE, resourceFixture()),
            Fixture("interface.vsh", ShaderStage.VERTEX, interfaceFixture()),
            Fixture("extension.csh", ShaderStage.COMPUTE, extensionFixture()),
            Fixture("function.csh", ShaderStage.COMPUTE, functionFixture()),
            Fixture("prototype.csh", ShaderStage.COMPUTE, prototypeFixture()),
            Fixture("struct.csh", ShaderStage.COMPUTE, structFixture()),
            Fixture("token-paste.csh", ShaderStage.COMPUTE, tokenPasteFixture()),
        )

        fixtures.forEach { fixture ->
            val base = ShaderCompilerCopyPlanner.plan(fixture.source, fixture.name)
            assertTrue(
                ShaderStructuralPlanner.requiresPlanning(base),
                "${fixture.name} unexpectedly became an ordinary one-module compiler copy",
            )
            val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
                ShaderStructuralPlanner.plan(base, fixture.stage),
                fixture.name,
            ).plan
            val materialized = materialize(workspace.resolve(fixture.name), fixture.source, planned)
            val finalized = assertIs<ShaderStructuralMaterializationResult.Materialized>(
                planned.deduplicate(materialized),
                fixture.name,
            ).modules

            assertEquals(2, finalized.size, fixture.name)
            assertEquals(2, finalized.map { it.signature }.toSet().size, fixture.name)
            optimizeAll(workspace.resolve("round-trip-${fixture.name}"), fixture.source, fixture.stage, finalized.map { it.module })
        }
    }

    @Test
    fun moduleCapFailsClosedWithStableGraphDomainAndSignatureDiagnostics() {
        val source = buildString {
            appendLine("#version 460 compatibility")
            repeat(6) { appendLine("//#define SETTING_$it") }
            append("#if ")
            appendLine((0 until 6).joinToString(" || ") { "defined(SETTING_$it)" })
            appendLine("layout(rgba16f, binding = 0) uniform image2D target;")
            appendLine("#else")
            appendLine("layout(r32f, binding = 0) uniform image2D target;")
            appendLine("#endif")
            appendLine("layout(local_size_x = 1) in;")
            appendLine("void main() { imageStore(target, ivec2(0), vec4(1.0)); }")
        }
        val base = ShaderCompilerCopyPlanner.plan(source, "cap.csh")
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        ).plan
        assertEquals(64, planned.rows.size)
        assertEquals(2, planned.materializationRows().size)

        val fakeModules = planned.rows.mapIndexed { index, row ->
            SpirvCompilerModule(
                row.name,
                """
                    #version 460 core
                    layout(rgba16f, binding = 0) uniform image2D target_$index;
                    layout(local_size_x = 1) in;
                    void main() { imageStore(target_$index, ivec2(0), vec4(1.0)); }
                """.trimIndent(),
            )
        }
        val first = assertIs<ShaderStructuralMaterializationResult.Preserved>(planned.deduplicate(fakeModules)).reason
        val second = assertIs<ShaderStructuralMaterializationResult.Preserved>(planned.deduplicate(fakeModules)).reason

        assertEquals(first, second)
        assertContains(first, "structural module cap exceeded: 64 > 32")
        assertContains(first, "components:")
        assertContains(first, "domains=")
        assertContains(first, "nodes:")
        assertContains(first, "coverage assignments:")
        assertContains(first, "retained structural modules:")
        assertContains(first, "compiler_sha256=")
        repeat(6) { assertContains(first, "SETTING_$it") }
    }

    @Test
    fun materializationRowCapFailsClosedBeforeMaterialization() {
        val domain = (0..64).joinToString(" ")
        val source = buildString {
            appendLine("#version 460 compatibility")
            appendLine("#define SETTING_MODE 0 //[$domain]")
            repeat(65) { appendLine("#define TYPE_$it float") }
            appendLine("#define CAT_IMPL(a, b) a ## b")
            appendLine("#define CAT(a, b) CAT_IMPL(a, b)")
            appendLine("#define TYPE(value) CAT(TYPE_, value)")
            appendLine("TYPE(SETTING_MODE) evaluate(TYPE(SETTING_MODE) value) { return value; }")
            appendLine("layout(local_size_x = 1) in;")
            appendLine("void main() {}")
        }

        val result = assertIs<ShaderStructuralPlanningResult.Preserved>(
            ShaderStructuralPlanner.plan(
                ShaderCompilerCopyPlanner.plan(source, "predicted-cap.csh"),
                ShaderStage.COMPUTE,
            ),
        )

        assertContains(result.reason, "structural materialization-row cap exceeded before materialization: 65 > 64")
        assertContains(result.reason, "components:")
        assertContains(result.reason, "SETTING_MODE=[0, 1, 2")
        assertContains(result.reason, "coverage_rows=65")
        assertContains(result.reason, "predicted compiler shapes:")
        assertContains(result.reason, "shape-064")
        assertContains(result.reason, "compiler_sha256=")
    }

    private fun materialize(
        workspace: Path,
        source: String,
        plan: ShaderStructuralCoveragePlan,
    ): List<SpirvCompilerModule> {
        val materializer = ShaderCompilerCopyMaterializer(workspace.resolve("compiler-copies"))
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        return plan.materializationRows().map { row ->
            materializer.materialize(plan.sourceName, plan.stage, row.compilerPlan, probe, row.name)
        }
    }

    private fun optimizeAll(
        workspace: Path,
        source: String,
        stage: ShaderStage,
        modules: List<SpirvCompilerModule>,
    ) {
        val result = SpirvOptimizer(workspace.resolve("spirv")).optimize(
            SpirvOptimizationRequest("fixture.${stage.glslangName}", stage, source, modules),
        )
        assertEquals(modules.size, result.modules.size)
        assertTrue(result.modules.all { Files.isRegularFile(it.optimizedSpirv) })
    }

    private fun optimizeStructural(
        workspace: Path,
        name: String,
        source: String,
        stage: ShaderStage,
    ): SpirvOptimizationResult {
        val base = ShaderCompilerCopyPlanner.plan(source, name)
        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, stage),
            name,
        ).plan
        val materialized = materialize(workspace, source, planned)
        val modules = assertIs<ShaderStructuralMaterializationResult.Materialized>(
            planned.deduplicate(materialized),
            name,
        ).modules.map { it.module }
        return SpirvOptimizer(workspace.resolve("spirv")).optimize(
            SpirvOptimizationRequest(name, stage, source, modules, planned),
        )
    }

    private fun independentResources(): String = """
        #version 460 compatibility
        //#define SETTING_A
        //#define SETTING_B
        //#define SETTING_RUNTIME
        #ifdef SETTING_A
        layout(rgba16f, binding = 0) uniform image2D targetA;
        #else
        layout(r32f, binding = 0) uniform image2D targetA;
        #endif
        #ifdef SETTING_B
        layout(rgba8, binding = 1) uniform image2D targetB;
        #else
        layout(r8, binding = 1) uniform image2D targetB;
        #endif
        layout(local_size_x = 1) in;
        void main() {
        #ifdef SETTING_RUNTIME
            int runtimeValue = 1;
        #else
            int runtimeValue = 0;
        #endif
        }
    """.trimIndent()

    private fun equalStructuralBodies(): String = """
        #version 460 compatibility
        //#define SETTING_WIDE
        #ifdef SETTING_WIDE
        layout(rgba16f, binding = 0) uniform image2D target;
        #else
        layout(r32f, binding = 0) uniform image2D target;
        #endif
        layout(local_size_x = 1) in;
        const ivec3 workGroups = ivec3(1, 1, 1);
        void main() { imageStore(target, ivec2(0), vec4(1.0)); }
    """.trimIndent()

    private fun optimizedAwayStructuralFunction(): String = """
        #version 460 compatibility
        //#define SETTING_FLOAT
        #ifdef SETTING_FLOAT
        float unusedStructural(float value) { return value; }
        #else
        int unusedStructural(int value) { return value; }
        #endif
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private fun commentedStructuralResource(): String = """
        #version 460 compatibility
        //#define SETTING_WIDE
        #ifdef SETTING_WIDE
        // wide storage contract
        layout(rgba16f, binding = 0) uniform image2D target;
        #else
        // narrow storage contract
        layout(r32f, binding = 0) uniform image2D target;
        #endif
        layout(local_size_x = 1) in;
        void main() { imageStore(target, ivec2(0), vec4(1.0)); }
    """.trimIndent()

    private fun nestedLeakedLocal(): String = """
        #version 460 compatibility
        //#define SETTING_LOCAL
        layout(local_size_x = 1) in;
        layout(std430, binding = 0) buffer Output { int value; };
        void main() {
        #ifdef SETTING_LOCAL
            int selected = 1;
        #else
            int selected = 2;
        #endif
            value = selected;
        }
    """.trimIndent()

    private fun mixedStructuralAndOrdinaryUses(): String = """
        #version 460 compatibility
        //#define SETTING_WIDE
        #ifdef SETTING_WIDE
        layout(rgba16f, binding = 0) uniform image2D target;
        #else
        layout(r32f, binding = 0) uniform image2D target;
        #endif
        layout(local_size_x = 1) in;
        void main() {
            int weight = 0;
        #ifdef SETTING_WIDE
            weight += 1;
        #else
            weight += 2;
        #endif
            imageStore(target, ivec2(0), vec4(weight));
        }
    """.trimIndent()

    private fun coupledAbiAndIndependentStructuralUses(): String = """
        #version 460 compatibility
        #define SETTING_FORMAT 0 //[0 1]
        #define SETTING_SHAPE 0 //[0 1 2 3]
        #if SETTING_FORMAT == 0
        layout(rgba16f, binding = 0) uniform image2D target;
        #else
        layout(r32ui, binding = 0) uniform uimage2D target;
        #endif
        #if SETTING_SHAPE < 2
        layout(rgba16f, binding = 1) uniform image2D auxiliary;
        #else
        layout(r32f, binding = 1) uniform image2D auxiliary;
        #endif
        layout(local_size_x = 1) in;
        void main() {
        #if SETTING_FORMAT == 0
            imageStore(target, ivec2(0), vec4(1.0));
        #else
            imageStore(target, ivec2(0), uvec4(1u));
        #endif
            int weight = 0;
        #if SETTING_SHAPE == 3
            weight += 3;
        #else
            weight += 1;
        #endif
            imageStore(auxiliary, ivec2(0), vec4(weight));
        }
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
        const ivec3 workGroups = ivec3(8, 1, 1);
        void main() {
            int value = 0;
            for (int i = 0; i < LOOP_COUNT; i++) {
                value += int(gl_LocalInvocationID.y) + i * WORK_GROUP_SIZE;
            }
        }
    """.trimIndent()

    private fun resourceFixture(): String = """
        #version 460 compatibility
        //#define SETTING_FORMAT
        layout(local_size_x = 1) in;
        #ifdef SETTING_FORMAT
        layout(rgba16f, binding = 0) uniform image2D target;
        void main() { imageStore(target, ivec2(0), vec4(1.0)); }
        #else
        layout(r32ui, binding = 0) uniform uimage2D target;
        void main() { imageStore(target, ivec2(0), uvec4(1u)); }
        #endif
    """.trimIndent()

    private fun interfaceFixture(): String = """
        #version 460 compatibility
        //#define SETTING_WIDE
        #ifdef SETTING_WIDE
        layout(location = 0) out vec4 payload;
        #else
        layout(location = 0) out vec3 payload;
        #endif
        void main() {
            gl_Position = vec4(0.0);
        #ifdef SETTING_WIDE
            payload = vec4(1.0);
        #else
            payload = vec3(1.0);
        #endif
        }
    """.trimIndent()

    private fun extensionFixture(): String = """
        #version 460 compatibility
        //#define SETTING_EXTENSION
        #ifdef SETTING_EXTENSION
        #extension GL_KHR_shader_subgroup_basic : require
        #endif
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private fun functionFixture(): String = """
        #version 460 compatibility
        //#define SETTING_FUNCTION
        #ifdef SETTING_FUNCTION
        float evaluate(float value) { return value; }
        #else
        int evaluate(int value) { return value; }
        #endif
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private fun prototypeFixture(): String = """
        #version 460 compatibility
        //#define SETTING_PROTO
        #ifdef SETTING_PROTO
        float evaluate(int value);
        #else
        float evaluate(float value);
        #endif
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private fun structFixture(): String = """
        #version 460 compatibility
        //#define SETTING_STRUCT
        #ifdef SETTING_STRUCT
        struct Payload { vec4 value; };
        #else
        struct Payload { vec3 value; };
        #endif
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private fun tokenPasteFixture(): String = """
        #version 460 compatibility
        #define SETTING_MODE 0 //[0 1]
        #define TYPE_0 float
        #define TYPE_1 int
        #define CAT_IMPL(a, b) a ## b
        #define CAT(a, b) CAT_IMPL(a, b)
        #define TYPE(value) CAT(TYPE_, value)
        #define SELECTED_TYPE SETTING_MODE
        #define SELECTED_TYPE_VALUE() TYPE(SELECTED_TYPE)
        SELECTED_TYPE_VALUE() evaluate(SELECTED_TYPE_VALUE() value) { return value; }
        layout(local_size_x = 1) in;
        void main() {}
    """.trimIndent()

    private data class Fixture(val name: String, val stage: ShaderStage, val source: String)

    private fun restoreDirectiveMacroDependencies(
        original: String,
        restored: String,
    ): ShaderStructuralRestoration {
        val base = ShaderCompilerCopyPlanner.plan(original, "directive-macro-order.csh")
        val restorationPlan = ShaderStructuralRestorationPlan(
            base.sourceName,
            base.settings,
            emptySet(),
            emptyList(),
            base.irisContracts.contracts,
            null,
        )
        return SpirvFinalEmitter.restoreDirectiveMacroDependencies(
            base.sourceName,
            original,
            restored,
            base.irisContracts.contracts,
            restorationPlan,
        )
    }

    private fun restoreTokenPasteSourceDependencies(
        original: String,
        restored: String,
        lowerings: List<ShaderTokenPasteLowering>,
    ): ShaderStructuralRestoration {
        val base = ShaderCompilerCopyPlanner.plan(original, "token-paste-source-dependencies.csh")
        val restorationPlan = ShaderStructuralRestorationPlan(
            base.sourceName,
            base.settings,
            emptySet(),
            emptyList(),
            base.irisContracts.contracts,
            null,
        )
        return SpirvFinalEmitter.restoreTokenPasteSourceDependencies(
            SpirvOptimizationRequest(base.sourceName, ShaderStage.COMPUTE, original),
            restored,
            restorationPlan,
            base.irisContracts,
            lowerings,
        )
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith structural planner test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
