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
    fun tokenPasteFailsClosedWhenFloatArgumentsCannotBeSeparated() {
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
        val preserved = assertIs<ShaderStructuralPlanningResult.Preserved>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
        )

        assertTrue("floating-point structural settings" in preserved.reason, preserved.reason)
        assertTrue("SETTING_GAIN" in preserved.reason)
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
    fun restoredStructuralEntityKeepsWholeMultilineMacroDependency() = withWorkspace { workspace ->
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
        assertContains(result.source, "#define OPTIONAL_VALUE(value) (\\\n    (value) + 1.0)")
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
        assertContains(result.source, exactIsland)
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
        assertContains(result.source, "uniform float unusedAbi;")
        assertTrue(result.source.indexOf("#define unusedAbiAlias unusedAbi") < result.source.indexOf("uniform float unusedAbi;"))
        assertTrue(result.source.indexOf("uniform float unusedAbi;") < result.source.indexOf("void main"))
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
        assertContains(result.source, "uniform float unusedAbi;")
        val prologue = result.source.indexOf("SHADESMITH_BRANCH_OWNED_PROLOGUE_BEGIN")
        assertTrue(prologue < 0 || result.source.indexOf("uniform float unusedAbi;") < prologue)
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
        assertTrue(result.modules.all { Files.isRegularFile(it.validationSpirv) })
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

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith structural planner test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
