package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShaderStructuralPlannerTest {
    @Test
    fun independentStructuralComponentsUseCoverageRowsInsteadOfCartesianProduct() = withWorkspace { workspace ->
        val base = ShaderCompilerCopyPlanner.plan(independentResources(), "independent.csh")

        val planned = assertIs<ShaderStructuralPlanningResult.Planned>(
            ShaderStructuralPlanner.plan(base, ShaderStage.COMPUTE),
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
    fun sharedBindingSlotMergesOtherwiseIndependentStructuralSettings() {
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

        assertEquals(1, planned.graph.components.size)
        assertEquals(listOf("SETTING_A", "SETTING_B"), planned.graph.components.single().settings)
        assertEquals(4, planned.rows.size)
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
        optimizeAll(workspace, source, ShaderStage.COMPUTE, finalized.map { it.module })
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
        assertContains(first, "distinct signatures:")
        repeat(6) { assertContains(first, "SETTING_$it") }
    }

    private fun materialize(
        workspace: Path,
        source: String,
        plan: ShaderStructuralCoveragePlan,
    ): List<SpirvCompilerModule> {
        val materializer = ShaderCompilerCopyMaterializer(workspace.resolve("compiler-copies"))
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        return plan.rows.map { row ->
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
        TYPE(SETTING_MODE) evaluate(TYPE(SETTING_MODE) value) { return value; }
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
