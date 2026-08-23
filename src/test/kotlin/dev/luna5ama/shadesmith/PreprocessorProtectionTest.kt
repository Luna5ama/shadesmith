package dev.luna5ama.shadesmith

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreprocessorProtectionTest {
    @Test
    fun restoresEveryFixtureByteForByte() {
        FIXTURES.forEach { name ->
            val source = fixture(name)
            val protected = PreprocessorProtection.protect(source, name)

            assertEquals(source, protected.restore(), name)
            assertEquals(source, protected.originalSource, name)
        }
    }

    @Test
    fun classifiesSettingsAndNestedFunctionBranchesWithoutChoosingDefaults() {
        val protected = protectFixture("settings-and-nesting.glsl")

        val disabled = protected.directives.single { it.kind == PreprocessorDirectiveKind.DISABLED_DEFINE }
        assertEquals("SETTING_FOG", disabled.macroName)
        assertEquals(PreprocessorDisposition.RESTORED, disabled.disposition)
        assertTrue(PreprocessorFeature.DISABLED_OPTION in disabled.features)

        val numeric = protected.directives.first { it.kind == PreprocessorDirectiveKind.IF }
        assertTrue(PreprocessorFeature.SETTING in numeric.features)
        assertTrue(PreprocessorFeature.NUMERIC_EXPRESSION in numeric.features)
        assertTrue(PreprocessorFeature.DECLARATION_SHAPE in numeric.features)

        val functionBranch = protected.directives.first { it.kind == PreprocessorDirectiveKind.IFDEF }
        assertTrue(PreprocessorFeature.BLOCK_SCOPE in functionBranch.features)
        val nested = protected.directives.first {
            it.kind == PreprocessorDirectiveKind.IF && it.conditionalDepth == 1
        }
        assertTrue(PreprocessorFeature.NESTED_CONDITIONAL in nested.features)
        assertTrue(PreprocessorFeature.DEFINED_EXPRESSION in nested.features)

        assertContains(protected.compilerRepresentation, "const int quality = 0;")
        assertContains(protected.compilerRepresentation, "const int quality = 2;")
        assertFalse(protected.compilerRepresentation.contains("#if SETTING_QUALITY"))
        assertContains(protected.compilerRepresentation, "//#define SETTING_FOG")
    }

    @Test
    fun refusesToCompileStructuralBranchesUsingTheCurrentSettingValue() {
        val protected = protectFixture("structural-contracts.glsl")

        val exception = assertFailsWith<PreprocessorProtectionException> {
            protected.compilerSource()
        }

        assertEquals("structural-contracts.glsl", exception.sourceName)
        assertEquals(5, exception.sourceLine)
        assertContains(exception.reason, "declaration shape")
        assertContains(exception.reason, "materialize a variant explicitly")
        assertTrue(protected.compilerBlockers.any { it.sourceLine == 13 })
    }

    @Test
    fun classifiesStageIoWorkgroupsExtensionsAndPragmas() {
        val protected = protectFixture("structural-contracts.glsl")

        assertEquals(
            PreprocessorDisposition.RESTORED,
            protected.directives.single { it.kind == PreprocessorDirectiveKind.EXTENSION }.disposition,
        )
        assertEquals(
            PreprocessorDisposition.RESTORED,
            protected.directives.single { it.kind == PreprocessorDirectiveKind.PRAGMA }.disposition,
        )
        val qualifier = protected.directives.first { it.macroName == "IO_QUALIFIER" }
        assertTrue(PreprocessorFeature.IDENTIFIER_ALIAS in qualifier.features)
        assertTrue(PreprocessorFeature.TYPE_OR_QUALIFIER in qualifier.features)
        val workgroup = protected.directives.first { it.macroName == "WORK_GROUP_SIZE" }
        assertTrue(PreprocessorFeature.WORKGROUP_LITERAL in workgroup.features)
    }

    @Test
    fun preservesFunctionMacrosTokenPasteResourceAliasesUndefAndIncludeGuard() {
        val protected = protectFixture("macro-identifiers.glsl")

        val tokenPaste = protected.directives.single { it.macroName == "JOIN_IMPL" }
        assertEquals("a ## b", tokenPaste.macroBody)
        assertTrue(PreprocessorFeature.FUNCTION_LIKE_MACRO in tokenPaste.features)
        assertTrue(PreprocessorFeature.TOKEN_PASTE in tokenPaste.features)

        listOf("usam_main", "uimg_main").forEach { name ->
            val alias = protected.directives.single { it.macroName == name }
            assertTrue(PreprocessorFeature.IDENTIFIER_ALIAS in alias.features)
            assertTrue(PreprocessorFeature.RESOURCE_ALIAS in alias.features)
        }
        assertTrue(protected.directives.any { it.kind == PreprocessorDirectiveKind.UNDEF })

        val guard = protected.directives.filter { PreprocessorFeature.INCLUDE_GUARD in it.features }
        assertEquals(
            listOf(
                PreprocessorDirectiveKind.IFNDEF,
                PreprocessorDirectiveKind.DEFINE,
                PreprocessorDirectiveKind.ENDIF,
            ),
            guard.map { it.kind },
        )
    }

    @Test
    fun deliberatelyEvaluatesOnlyConstSpecializationDirectives() {
        val protected = protectFixture("const-specialization.glsl")

        val version = protected.directives.single { it.kind == PreprocessorDirectiveKind.VERSION }
        assertEquals(PreprocessorDisposition.RESTORED, version.disposition)
        assertTrue(
            protected.directives
                .filter { it.kind != PreprocessorDirectiveKind.VERSION }
                .all { it.disposition == PreprocessorDisposition.EVALUATED },
        )
        assertTrue(
            protected.directives
                .filter { it.kind != PreprocessorDirectiveKind.VERSION }
                .all { PreprocessorFeature.CONST_SPECIALIZATION in it.features },
        )
        assertTrue(protected.compilerBlockers.isEmpty())
        assertEquals(protected.compilerRepresentation, protected.compilerSource())
    }

    @Test
    fun evaluatesGeneratedCompilerConditionalsOnlyOnTheExplicitValidationPath() {
        val source = """
            #version 460
            #if defined(GL_KHR_shader_subgroup_basic)
            #extension GL_KHR_shader_subgroup_basic : require
            #else
            #error subgroup support is required
            #endif
            void main() {}
        """.trimIndent()

        assertTrue(PreprocessorProtection.protect(source, "generated.glsl").compilerBlockers.isNotEmpty())
        val generated = PreprocessorProtection.protectGeneratedCompilerSource(source, "generated.glsl")
        assertTrue(generated.compilerBlockers.isEmpty())
        assertTrue(generated.directives.all { it.disposition == PreprocessorDisposition.EVALUATED })
    }

    @Test
    fun allowsConstSpecializationNestedInsideAPStyleIncludeGuard() {
        val source = """
            #ifndef INCLUDE_Texture_glsl
            #define INCLUDE_Texture_glsl a
            /*const*/
            #define usam_main colortex0
            /*const*/
            uniform sampler2D usam_main;
            #endif
        """.trimIndent()

        val protected = PreprocessorProtection.protect(source, "guarded-const.glsl")

        val alias = protected.directives.single { it.macroName == "usam_main" }
        assertEquals(PreprocessorDisposition.EVALUATED, alias.disposition)
        assertTrue(PreprocessorFeature.CONST_SPECIALIZATION in alias.features)
        assertEquals(source, protected.restore())
    }

    @Test
    fun preservesContinuedFunctionMacroAsOneDirective() {
        val source = """
            #define APPLY_PAIR(value) \
                consume(value);       \
                consume((value) + 1)
            void main() {}
        """.trimIndent().replace("\n", "\r\n")

        val protected = PreprocessorProtection.protect(source, "continued.glsl")
        val directive = protected.directives.single()

        assertEquals(1, directive.sourceLine)
        assertEquals(3, directive.endLine)
        assertTrue(PreprocessorFeature.FUNCTION_LIKE_MACRO in directive.features)
        assertEquals(source, protected.restore())
    }

    @Test
    fun rejectsUnexpandedIncludesWithSourceLineAndReason() {
        val exception = assertFailsWith<PreprocessorProtectionException> {
            PreprocessorProtection.protect(
                "#version 460\n\n#include \"shared.glsl\"\nvoid main() {}\n",
                "include-test.glsl",
            )
        }

        assertEquals(3, exception.sourceLine)
        assertEquals(PreprocessorDisposition.REJECTED, exception.disposition)
        assertContains(exception.reason, "must be expanded")
        assertContains(exception.message.orEmpty(), "include-test.glsl:3")
    }

    @Test
    fun rejectsMalformedConditionalAndConstContracts() {
        val unmatched = assertFailsWith<PreprocessorProtectionException> {
            PreprocessorProtection.protect("#if FLAG\nvoid main() {}\n", "unmatched.glsl")
        }
        assertEquals(1, unmatched.sourceLine)
        assertContains(unmatched.reason, "no matching #endif")

        val duplicateElse = assertFailsWith<PreprocessorProtectionException> {
            PreprocessorProtection.protect(
                "#if FLAG\n#else\n#else\n#endif\n",
                "duplicate-else.glsl",
            )
        }
        assertEquals(3, duplicateElse.sourceLine)
        assertContains(duplicateElse.reason, "multiple #else")

        val unclosedConst = assertFailsWith<PreprocessorProtectionException> {
            PreprocessorProtection.protect("/*const*/\n#define PASS 1\n", "const.glsl")
        }
        assertEquals(1, unclosedConst.sourceLine)
        assertContains(unclosedConst.reason, "no closing marker")
    }

    @Test
    fun restorationFailsClosedWhenPlaceholderMetadataNoLongerMatches() {
        val protected = protectFixture("macro-identifiers.glsl")
        val restoration = assertNotNull(protected.restorations.firstOrNull())
        val damaged = protected.compilerRepresentation.replace(restoration.compilerText, "")

        val exception = assertFailsWith<PreprocessorProtectionException> {
            protected.restore(damaged)
        }

        assertEquals(restoration.sourceLine, exception.sourceLine)
        assertContains(exception.reason, "placeholder is missing")
    }

    private fun protectFixture(name: String): ProtectedPreprocessorSource {
        return PreprocessorProtection.protect(fixture(name), name)
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/preprocessor/$name")) {
            "Missing preprocessor fixture $name"
        }.readText()
    }

    companion object {
        private val FIXTURES = listOf(
            "settings-and-nesting.glsl",
            "structural-contracts.glsl",
            "macro-identifiers.glsl",
            "const-specialization.glsl",
        )
    }
}
