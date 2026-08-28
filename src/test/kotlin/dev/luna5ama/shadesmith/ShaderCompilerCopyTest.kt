package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderCompilerCopyTest {
    @Test
    fun lowersTwentyFiveIndependentBooleanSettingsIntoOneCompilerModule() {
        val source = buildString {
            appendLine("#version 460 compatibility")
            repeat(25) { index -> appendLine("//#define SETTING_$index") }
            appendLine("void main() {")
            appendLine("    int value = 0;")
            repeat(25) { index ->
                appendLine("    #ifdef SETTING_$index")
                appendLine("    value += $index;")
                appendLine("    #else")
                appendLine("    value -= $index;")
                appendLine("    #endif")
            }
            appendLine("}")
        }

        val plan = ShaderCompilerCopyPlanner.plan(source, "settings.csh")

        assertEquals(1, plan.compilerModuleCount)
        assertTrue(plan.structuralBlockers.isEmpty())
        assertEquals((0 until 25).toList(), plan.settings.map { it.specializationId })
        assertTrue(plan.settings.all { it.type == ShaderSettingType.BOOL && it.presenceToggle })
        assertEquals(
            25,
            plan.conditionals.count { it.disposition == ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT },
        )
        val compiler = assertNotNull(plan.compilerSource)
        assertEquals(25, "layout\\(constant_id".toRegex().findAll(compiler).count())
        assertFalse("#ifdef SETTING_" in compiler)
        assertFalse("#define SETTING_" in compiler)
        assertContains(compiler, "if (SM_SETTING_0)")
    }

    @Test
    fun preservesNumericSettingsInArraysConstantsAndConditionsWithoutFreezing() {
        val source = """
            #version 460 compatibility
            layout(constant_id = 0) const int existing = 1;
            #define SETTING_MODE 2 //[1 2 4]
            const int itemCount = SETTING_MODE;
            float values[SETTING_MODE];
            void main() {
            #if SETTING_MODE == 2
                values[0] = 1.0;
            #else
                values[0] = 0.0;
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "numeric.csh")

        val setting = plan.settings.single()
        assertEquals(ShaderSettingType.INT, setting.type)
        assertEquals("2", setting.defaultValue)
        assertEquals(listOf("1", "2", "4"), setting.domain)
        assertEquals(1, setting.specializationId)
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "layout(constant_id = 1) const int SM_SETTING_MODE = 2;")
        assertContains(compiler, "float values[SM_SETTING_MODE];")
        assertContains(compiler, "if (SM_SETTING_MODE == 2)")
        assertFalse("#define SETTING_MODE" in compiler)
    }

    @Test
    fun lowersACompleteConditionalExpressionToATernary() {
        val source = """
            #version 460 compatibility
            //#define SETTING_FAST
            float fastValue() { return 1.0; }
            float slowValue() { return 0.0; }
            void main() {
                float value =
            #if defined(SETTING_FAST)
                    fastValue()
            #else
                    slowValue()
            #endif
                ;
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "expression.fsh")

        assertEquals(ShaderConditionalDisposition.CONTROL_FLOW_EXPRESSION, plan.conditionals.single().disposition)
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "SM_SETTING_FAST")
        assertContains(compiler, "? (fastValue()) : (slowValue())")
    }

    @Test
    fun mergesSameSignatureFunctionsButRejectsFunctionAbiChanges() {
        val sameSignature = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_MAIN
                #ifdef SETTING_MAIN
                void main() {
                    int value = 1;
                }
                #else
                void main() {
                    int value = 2;
                }
                #endif
            """.trimIndent(),
            "main-merge.csh",
        )

        assertEquals(ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION, sameSignature.conditionals.single().disposition)
        val compiler = assertNotNull(sameSignature.compilerSource)
        assertEquals(1, "\\bvoid\\s+main\\s*\\(".toRegex().findAll(compiler).count())
        assertContains(compiler, "if (SM_SETTING_MAIN)")

        val changedSignature = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_TYPE
                #ifdef SETTING_TYPE
                float evaluate(float value) { return value; }
                #else
                int evaluate(int value) { return value; }
                #endif
                void main() {}
            """.trimIndent(),
            "function-abi.csh",
        )
        assertNull(changedSignature.compilerSource)
        assertTrue(changedSignature.structuralBlockers.any { "ABI shape" in it.reason })
    }

    @Test
    fun recordsConditionalTreeMacroDependenciesAndExactSlices() {
        val source = """
            #version 460 compatibility
            //#define SETTING_OUTER
            #define DERIVED_SETTING SETTING_OUTER
            void main() {
            #if DERIVED_SETTING
                int value = 1;
                #if defined(SETTING_OUTER)
                value += 1;
                #endif
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "tree.csh")

        val macro = plan.macros.single { it.name == "DERIVED_SETTING" }
        assertEquals(setOf("SETTING_OUTER"), macro.settingDependencies)
        assertEquals(2, plan.conditionals.size)
        assertEquals(plan.conditionals.first().id, plan.conditionals.last().parentId)
        assertTrue(plan.conditionals.all { it.exactSlice.startsWith("#if") || it.exactSlice.startsWith("    #if") })
        assertTrue(plan.sourceRegions.any { it.kind == ShaderSourceRegionKind.FUNCTION && it.name == "main" })
    }

    @Test
    fun rejectsStructuralAndUnsafeSettingUsesWithSpecificReasons() {
        val topLevel = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_FORMAT
                #ifdef SETTING_FORMAT
                layout(rgba16f) uniform image2D target;
                #else
                layout(r32ui) uniform uimage2D target;
                #endif
                void main() {}
            """.trimIndent(),
            "resource.csh",
        )
        assertNull(topLevel.compilerSource)
        assertTrue(topLevel.structuralBlockers.any { "ABI shape" in it.reason })

        val leakedLocal = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_LOCAL
                void consume(int value) {}
                void main() {
                #ifdef SETTING_LOCAL
                    int leaked = 1;
                #else
                    int leaked = 2;
                #endif
                    consume(leaked);
                }
            """.trimIndent(),
            "leaked.csh",
        )
        assertNull(leakedLocal.compilerSource)
        assertTrue(leakedLocal.structuralBlockers.any { "leak past" in it.reason })

        val caseCut = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_CASE
                void main() {
                    switch (1) {
                #ifdef SETTING_CASE
                    case 0: break;
                #else
                    default: break;
                #endif
                    }
                }
            """.trimIndent(),
            "case.csh",
        )
        assertNull(caseCut.compilerSource)
        assertTrue(caseCut.structuralBlockers.any { "case/default" in it.reason })

        val tokenCut = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_VECTOR
                void main() {
                    vec
                #ifdef SETTING_VECTOR
                    2
                #else
                    3
                #endif
                    value;
                }
            """.trimIndent(),
            "token-cut.csh",
        )
        assertNull(tokenCut.compilerSource)
        assertTrue(tokenCut.structuralBlockers.any { "surrounding expression tokens" in it.reason })

        val incompatibleExpression = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_TYPE
                void main() {
                    int value =
                #ifdef SETTING_TYPE
                    true
                #else
                    1
                #endif
                    ;
                }
            """.trimIndent(),
            "expression-type.csh",
        )
        assertNull(incompatibleExpression.compilerSource)
        assertTrue(incompatibleExpression.structuralBlockers.any { "incompatible scalar types" in it.reason })

        val unbalancedScope = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_SCOPE
                void main() {
                #ifdef SETTING_SCOPE
                    if (true) {
                #else
                    }
                #endif
                }
            """.trimIndent(),
            "scope-cut.csh",
        )
        assertNull(unbalancedScope.compilerSource)
        assertTrue(unbalancedScope.structuralBlockers.any { "unbalanced lexical scope" in it.reason })
    }

    @Test
    fun rejectsFunctionLikeStringAndUnresolvedSettingDefinitions() {
        val functionLike = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                #define SETTING_CALL(x) (x)
                void main() {
                #if SETTING_CALL(1)
                    int value = 1;
                #endif
                }
            """.trimIndent(),
            "function-setting.csh",
        )
        assertNull(functionLike.compilerSource)
        assertTrue(functionLike.structuralBlockers.any { "function-like" in it.reason })

        val string = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                #define SETTING_NAME "fast" //["fast" "slow"]
                void main() {
                #if SETTING_NAME
                    int value = 1;
                #endif
                }
            """.trimIndent(),
            "string-setting.csh",
        )
        assertNull(string.compilerSource)
        assertTrue(string.structuralBlockers.any { "provable" in it.reason })
    }

    @Test
    fun materializesExactlyOneCompilerCopyAndRetainsFailureLogs() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            //#define SETTING_FAST
            void main() {
            #ifdef SETTING_FAST
                int value = 1;
            #else
                int value = 0;
            #endif
            }
        """.trimIndent()
        val plan = ShaderCompilerCopyPlanner.plan(source, "single.csh")
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        val metrics = PipelineMetrics()
        val copyRunner = ClangProcessRunner { command, _, stdout, _ ->
            stdout.writeText(Path.of(command.last()).readText())
            0
        }

        val module = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            metrics = metrics,
        ).materialize("single.csh", ShaderStage.COMPUTE, plan, probe)

        assertEquals("compiler-copy", module.name)
        assertContains(module.source, "layout(constant_id = 0)")
        assertEquals(1, metrics.snapshot().compilerModules)
        assertEquals(1, metrics.snapshot().clangProcesses)

        val failingRunner = ClangProcessRunner { _, _, stdout, stderr ->
            stdout.writeText("retained stdout")
            stderr.writeText("retained stderr")
            7
        }
        val exception = assertFailsWith<ShaderCompilerCopyException> {
            ShaderCompilerCopyMaterializer(workspace.resolve("failure"), processRunner = failingRunner)
                .materialize("single.csh", ShaderStage.COMPUTE, plan, probe)
        }
        assertContains(exception.message.orEmpty(), "clang exited with code 7")
        assertEquals("retained stdout", exception.artifactDirectory.resolve("clang.stdout.log").readText())
        assertEquals("retained stderr", exception.artifactDirectory.resolve("clang.stderr.log").readText())
    }

    @Test
    fun materializesHostConditionalsAndUnconditionalTokenPasteOnce() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            #ifndef SKIP_HOST_UNIFORMS
            uniform float farPlane;
            #endif
            #define FUNCTION_INDEX 0
            #define CALL_IMPL(index, value) function_ ## index ##(value)
            #define CALL(index, value) CALL_IMPL(index, value)
            int function_0(int value) { return value; }
            void main() { int result = CALL(FUNCTION_INDEX, 3); }
        """.trimIndent()
        val plan = ShaderCompilerCopyPlanner.plan(source, "host-macros.csh")
        val metrics = PipelineMetrics()

        val module = ShaderCompilerCopyMaterializer(workspace, metrics = metrics).materialize(
            "host-macros.csh",
            ShaderStage.COMPUTE,
            plan,
            TextureAccessProbe(source, emptyList(), TextureAccess()),
        )

        assertContains(module.source, "uniform float farPlane;")
        assertTrue("\\bfunction_0\\s*\\(\\s*3\\s*\\)".toRegex().containsMatchIn(module.source))
        assertFalse("CALL(" in module.source)
        assertEquals(1, metrics.snapshot().compilerModules)
        assertEquals(1, metrics.snapshot().clangProcesses)
    }

    @Test
    fun includeExpansionPreservesSettingDefinitionsAndDirectiveText() = withWorkspace { workspace ->
        val input = workspace.resolve("input")
        val output = workspace.resolve("output")
        Files.createDirectories(input.resolve("lib"))
        input.resolve("root.csh").writeText(
            """
                #version 460 compatibility
                #include "/options.glsl"
                #include "lib/guarded.glsl"
                #include "lib/guarded.glsl"
                void main() {}
            """.trimIndent(),
        )
        input.resolve("options.glsl").writeText(
            "//#define SETTING_PRESERVED\n#define SETTING_QUALITY 2 //[1 2 4]\n",
        )
        input.resolve("lib/guarded.glsl").writeText(
            "#ifndef INCLUDE_guarded\n#define INCLUDE_guarded\n// exact contract comment\n#endif\n",
        )
        val ioContext = IOContext(input, output)

        val expanded = context(ioContext) {
            resolveIncludes(listOf(requireNotNull(ioContext.readInputRoot("root.csh")))).single().code
        }

        assertContains(expanded, "//#define SETTING_PRESERVED")
        assertContains(expanded, "#define SETTING_QUALITY 2 //[1 2 4]")
        assertContains(expanded, "// exact contract comment")
        assertEquals(1, "#define INCLUDE_guarded".toRegex().findAll(expanded).count())
        assertFalse("#include" in expanded)
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith compiler copy test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
