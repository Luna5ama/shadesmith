package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderVariantMaterializerTest {
    @Test
    fun reportsOnlySettingCoverageWhenReachingThroughAHostConditional() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            //#define INTERNAL_DISABLE
            //#define SETTING_FEATURE
            #ifdef INTERNAL_DISABLE
            const int mode = 0;
            #else
                #ifdef SETTING_FEATURE
                const int mode = 1;
                #else
                const int mode = 2;
                #endif
            #endif
            void main() {}
        """.trimIndent()
        val protection = PreprocessorProtection.protect(source, "nested-setting.glsl")
        val probe = TextureAccessAnalyzer.createProbe(source, Config())

        val variants = ShaderVariantMaterializer(workspace).materialize(
            "nested-setting.glsl",
            ShaderStage.FRAGMENT,
            protection,
            probe,
        )

        assertEquals(
            setOf(
                setOf(PreprocessorBranchSelection(1, 0)),
                setOf(PreprocessorBranchSelection(1, 1)),
            ),
            variants.mapTo(hashSetOf()) { it.coveredBranches },
        )
        assertTrue(variants.all { branch -> branch.coveredBranches.all { it.conditionalId == 1 } })
    }

    @Test
    fun leavesNonSettingConditionalsInTheCurrentMacroEnvironment() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            #ifndef SKIP_HOST_UNIFORMS
            uniform float farPlane;
            #endif
            float copiedFarPlane = farPlane;
            void main() {}
        """.trimIndent()
        val protection = PreprocessorProtection.protect(source, "host-uniform.glsl")
        val probe = TextureAccessAnalyzer.createProbe(source, Config())

        assertTrue(requiredPreprocessorBranches(protection).isEmpty())
        val variant = ShaderVariantMaterializer(workspace).materialize(
            "host-uniform.glsl",
            ShaderStage.FRAGMENT,
            protection,
            probe,
        ).single()

        assertContains(variant.source, "uniform float farPlane;")
        assertContains(variant.source, "float copiedFarPlane = farPlane;")
    }

    @Test
    fun omitsImpossibleFallthroughForExhaustiveSettingDomain() {
        val source = """
            #define SETTING_MODE 1 //[0 1]
            #if SETTING_MODE == 0
            const int mode = 0;
            #elif SETTING_MODE == 1
            const int mode = 1;
            #endif
        """.trimIndent()

        assertEquals(
            setOf(
                PreprocessorBranchSelection(0, 0),
                PreprocessorBranchSelection(0, 1),
            ),
            requiredPreprocessorBranches(PreprocessorProtection.protect(source, "exhaustive-setting.glsl")),
        )
    }

    @Test
    fun retainsFallthroughForNonExhaustiveSettingDomain() {
        val source = """
            #define SETTING_MODE 1 //[0 1 2]
            #if SETTING_MODE == 0
            const int mode = 0;
            #elif 1 == SETTING_MODE
            const int mode = 1;
            #endif
        """.trimIndent()

        assertEquals(
            setOf(
                PreprocessorBranchSelection(0, 0),
                PreprocessorBranchSelection(0, 1),
                PreprocessorBranchSelection(0, 2),
            ),
            requiredPreprocessorBranches(PreprocessorProtection.protect(source, "non-exhaustive-setting.glsl")),
        )
    }

    @Test
    fun materializesPunctuationTokenPasteWithoutChangingTheProtectedInput() = withWorkspace { workspace ->
        val source = """
            #version 460 compatibility
            //#define SETTING_ALTERNATE
            #define FUNCTION_INDEX 0
            #if defined(SETTING_ALTERNATE)
            #undef FUNCTION_INDEX
            #define FUNCTION_INDEX 1
            #endif
            #define CALL_IMPL(index, value) function_ ## index ##(value)
            #define CALL(index, value) CALL_IMPL(index, value)
            int function_0(int value) { return value; }
            int function_1(int value) { return value + 1; }
            void main() { int result = CALL(FUNCTION_INDEX, 3); }
        """.trimIndent()
        val protection = PreprocessorProtection.protect(source, "token-paste.csh")
        val probe = TextureAccessAnalyzer.createProbe(source, Config())

        val variants = ShaderVariantMaterializer(workspace).materialize(
            "token-paste.csh",
            ShaderStage.COMPUTE,
            protection,
            probe,
        )

        assertTrue(variants.any { FUNCTION_0_CALL.containsMatchIn(it.source) })
        assertTrue(variants.any { FUNCTION_1_CALL.containsMatchIn(it.source) })
        val requestDirectory = workspace.listDirectoryEntries().single()
        requestDirectory.listDirectoryEntries().forEach { variantDirectory ->
            assertContains(variantDirectory.resolve("forced.glsl").readText(), "##(")
            assertFalse(variantDirectory.resolve("clang-input.glsl").readText().contains("##("))
        }
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith materializer test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    companion object {
        private val FUNCTION_0_CALL = """\bfunction_0\s*\(\s*3\s*\)""".toRegex()
        private val FUNCTION_1_CALL = """\bfunction_1\s*\(\s*3\s*\)""".toRegex()
    }
}
