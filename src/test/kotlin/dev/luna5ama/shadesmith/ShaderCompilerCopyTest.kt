package dev.luna5ama.shadesmith

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun removesEmptySettingRecognitionConditionalsFromCompilerCopy() {
        val source = buildString {
            appendLine("#version 460 compatibility")
            repeat(25) { index ->
                appendLine("//#define SETTING_$index")
                appendLine("#ifdef SETTING_$index")
                appendLine("#endif")
            }
            appendLine("void main() {}")
        }

        val plan = ShaderCompilerCopyPlanner.plan(source, "empty-settings.csh")

        assertTrue(plan.structuralBlockers.isEmpty())
        assertEquals(
            25,
            plan.conditionals.count { it.disposition == ShaderConditionalDisposition.COMPILER_NO_OP },
        )
        val compiler = assertNotNull(plan.compilerSource)
        assertFalse("#ifdef SETTING_" in compiler)
        assertEquals(25, "layout\\(constant_id".toRegex().findAll(compiler).count())
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
    fun modelsHostPresenceAndIrisOptionsAsDistinctBaseControls() {
        val source = """
            #version 460 compatibility
            #define SETTING_PBR_MATERIAL 1 //[0 1 2]
            void main() {
            #if defined(MC_TEXTURE_FORMAT_LAB_PBR) && (SETTING_PBR_MATERIAL == 1 || SETTING_PBR_MATERIAL == 2)
                int value = 1;
            #else
                int value = 0;
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "host-presence.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val host = plan.settings.single { it.name == "MC_TEXTURE_FORMAT_LAB_PBR" }
        val option = plan.settings.single { it.name == "SETTING_PBR_MATERIAL" }
        assertEquals(ShaderControlKind.HOST_PRESENCE, host.controlKind)
        assertEquals(ShaderControlKind.IRIS_SCALAR, option.controlKind)
        assertEquals(0, host.specializationId)
        assertEquals(1, option.specializationId)
        assertEquals("SM_HOST_MC_TEXTURE_FORMAT_LAB_PBR", host.compilerName)
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "layout(constant_id = 0) const bool SM_HOST_MC_TEXTURE_FORMAT_LAB_PBR = false;")
        assertContains(compiler, "layout(constant_id = 1) const int SM_SETTING_PBR_MATERIAL = 1;")
        assertContains(compiler, "SM_HOST_MC_TEXTURE_FORMAT_LAB_PBR")
        assertFalse("defined(MC_TEXTURE_FORMAT_LAB_PBR)" in compiler)
    }

    @Test
    fun convertsPreprocessorIntegerTruthValuesWithoutChangingComparisonOperands() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1]
            #define SETTING_FLAG
            void main() {
            #if 0 && defined(SETTING_FLAG)
                int unreachable = 1;
            #endif
            #if SETTING_MODE == 0 && defined(SETTING_FLAG)
                int value = 1;
            #else
                int value = 0;
            #endif
            #if SETTING_MODE && SETTING_MODE == 1
                int mixed = 1;
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "logical-integer.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "SM_SETTING_MODE == 0")
        assertContains(compiler, "SM_SETTING_MODE != 0")
        assertFalse("unreachable" in compiler)
        assertFalse("SM_SETTING_MODE == false" in compiler)
    }

    @Test
    fun lowersDerivedPresenceAndScalarMacrosWithoutAllocatingExtraIds() {
        val source = """
            #version 460 compatibility
            #define SETTING_TBN_PACKING 1 //[0 1]
            #if SETTING_TBN_PACKING == 1
            #define GBUFFER_USE_TBN_PACKING
            #else
            // Explicitly absent.
            #endif
            #if SETTING_TBN_PACKING == 0
            #define TBN_WEIGHT 2
            #else
            #define TBN_WEIGHT 4
            #endif
            void main() {
            #ifdef GBUFFER_USE_TBN_PACKING
                int value = TBN_WEIGHT;
            #else
                int value = 0;
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "derived-controls.csh")

        assertTrue(plan.structuralBlockers.isEmpty(), plan.structuralBlockers.toString())
        assertEquals(1, plan.settings.size)
        assertEquals(
            setOf(ShaderDerivedControlKind.PRESENCE, ShaderDerivedControlKind.SCALAR),
            plan.derivedControls.mapTo(hashSetOf()) { it.kind },
        )
        val compiler = assertNotNull(plan.compilerSource)
        assertEquals(1, "layout\\(constant_id".toRegex().findAll(compiler).count())
        assertFalse("#define GBUFFER_USE_TBN_PACKING" in compiler)
        assertFalse("#define TBN_WEIGHT" in compiler)
        assertFalse("#ifdef GBUFFER_USE_TBN_PACKING" in compiler)
        assertContains(compiler, "SM_SETTING_TBN_PACKING == 1")
        assertContains(compiler, "? (2) : (4)")
    }

    @Test
    fun rewritesDerivedPresenceExpressionsInPreservedDirectivesAsIfConditions() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 0 //[0 1 2]
            #define HOST_PASS a
            #ifdef HOST_PASS
            #if SETTING_MODE == 0
            #elif SETTING_MODE == 1
            #define FEATURE_ENABLED
            #elif SETTING_MODE == 2
            #define FEATURE_ENABLED
            #endif
            #ifdef FEATURE_ENABLED
            layout(rgba16f, binding = 0) uniform image2D target;
            #endif
            #endif
            void main() {}
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "derived-structural.csh")
        val compiler = plan.compilerCandidateSource

        PreprocessorProtection.protect(compiler, "derived-structural.csh")
        assertFalse("#ifdef (" in compiler)
        assertContains(compiler, "#if (")
        assertContains(compiler, "SM_SETTING_MODE == 1")
    }

    @Test
    fun allocatesCompilerControlNamesWithoutCollidingWithShaderIdentifiers() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 2 //[1 2]
            int SM_SETTING_MODE = 7;
            // SETTING_MODE remains recognizable in source comments.
            void main() {
                int value = SETTING_MODE + SM_SETTING_MODE;
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "hygienic-name.csh")

        val setting = plan.settings.single()
        assertEquals("SM_SETTING_MODE_", setting.compilerName)
        val compiler = assertNotNull(plan.compilerSource)
        assertContains(compiler, "const int SM_SETTING_MODE_ = 2;")
        assertContains(compiler, "int SM_SETTING_MODE = 7;")
        assertContains(compiler, "int value = SM_SETTING_MODE_ + SM_SETTING_MODE;")
        assertContains(compiler, "// SETTING_MODE remains recognizable in source comments.")
    }

    @Test
    fun renamesDynamicGlobalConstantWithoutCapturingLocalShadow() {
        val source = """
            #version 460 compatibility
            #define SETTING_ALBEDO 1 //[1 2]
            const vec3 GROUND_ALBEDO_BASE = vec3(SETTING_ALBEDO);
            vec3 passthrough(vec3 GROUND_ALBEDO_BASE) {
                return GROUND_ALBEDO_BASE;
            }
            void main() {
                vec3 before = GROUND_ALBEDO_BASE;
                {
                    const vec3 GROUND_ALBEDO_BASE = vec3(0.0);
                    vec3 local = GROUND_ALBEDO_BASE;
                }
                vec3 after = GROUND_ALBEDO_BASE;
            }
        """.trimIndent()

        val compiler = assertNotNull(ShaderCompilerCopyPlanner.plan(source, "begin3.csh").compilerSource)

        assertContains(compiler, "#define SM_DYNAMIC_GROUND_ALBEDO_BASE (vec3(SM_SETTING_ALBEDO))")
        assertContains(compiler, "vec3 passthrough(vec3 GROUND_ALBEDO_BASE)")
        assertContains(compiler, "return GROUND_ALBEDO_BASE;")
        assertContains(compiler, "vec3 before = SM_DYNAMIC_GROUND_ALBEDO_BASE;")
        assertContains(compiler, "const vec3 GROUND_ALBEDO_BASE = vec3(0.0);")
        assertContains(compiler, "vec3 local = GROUND_ALBEDO_BASE;")
        assertContains(compiler, "vec3 after = SM_DYNAMIC_GROUND_ALBEDO_BASE;")
        assertFalse("#define GROUND_ALBEDO_BASE" in compiler)
    }

    @Test
    fun relaxesOnlyAggregateGlobalConstantsThatDependOnSpecializationValues() {
        val source = """
            #version 460 compatibility
            #define SETTING_GAIN 1.0 //[1.0 2.0]
            #define SETTING_SIZE 2 //[2 4]
            #define BRACED_MACRO(value) { value; }
            #define MAKE_TINT(value) vec3(value * SETTING_GAIN)
            layout(constant_id = 7) const float SM_SETTING_EXTERNAL = 1.0;
            const vec3 crossTint = vec3(SM_SETTING_EXTERNAL);
            const float crossTintX = crossTint.x;
            const int SM_IRIS_HOST_EXTERNAL = int(SM_SETTING_EXTERNAL);
            const vec2 hostSize = vec2(float(SM_IRIS_HOST_EXTERNAL));
            const float scalarGain = SETTING_GAIN;
            const float inverseGain = inversesqrt(SETTING_GAIN);
            const int scalarSize = SETTING_SIZE;
            const vec3 tint = vec3(SETTING_GAIN);
            const vec2 offsets[2] = vec2[2](vec2(0.0), vec2(SETTING_GAIN));
            const vec2[3] typedOffsets = vec2[3](vec2(0.0), vec2(SETTING_GAIN), vec2(2.0));
            const vec2[3] braceOffsets = { vec2(0.0), vec2(SETTING_GAIN), vec2(2.0) };
            const highp vec2[3] preciseTypedOffsets = vec2[3](vec2(0.0), vec2(SETTING_GAIN), vec2(2.0));
            const vec3 indirectTint = MAKE_TINT(1.0);
            float values[SETTING_SIZE];
            void main() {
                values[0] = tint.x + offsets[1].x + typedOffsets[1].x + preciseTypedOffsets[1].x + indirectTint.x + scalarGain + float(scalarSize);
            }
        """.trimIndent()

        val compiler = assertNotNull(ShaderCompilerCopyPlanner.plan(source, "aggregate-const.csh").compilerSource)

        assertContains(compiler, "layout(constant_id = 0) const float SM_SETTING_GAIN = 1.0;")
        assertContains(compiler, "layout(constant_id = 1) const int SM_SETTING_SIZE = 2;")
        assertContains(compiler, "#define SM_DYNAMIC_scalarGain (SM_SETTING_GAIN)")
        assertContains(compiler, "#define SM_DYNAMIC_inverseGain (inversesqrt(SM_SETTING_GAIN))")
        assertContains(compiler, "#define SM_DYNAMIC_scalarSize (SM_SETTING_SIZE)")
        assertContains(compiler, "#define SM_DYNAMIC_tint (vec3(SM_SETTING_GAIN))")
        assertContains(compiler, "      vec2 offsets[2] = vec2[2](vec2(0.0), vec2(SM_SETTING_GAIN));")
        assertContains(compiler, "      vec2[3] typedOffsets")
        assertContains(compiler, "      vec2[3] braceOffsets = { vec2(0.0), vec2(SM_SETTING_GAIN), vec2(2.0) };")
        assertContains(compiler, "      highp vec2[3] preciseTypedOffsets")
        assertContains(compiler, "      vec3 indirectTint = MAKE_TINT(1.0);")
        assertContains(compiler, "      vec3 crossTint = vec3(SM_SETTING_EXTERNAL);")
        assertContains(compiler, "      float crossTintX = crossTint.x;")
        assertContains(compiler, "      vec2 hostSize = vec2(float(SM_IRIS_HOST_EXTERNAL));")
        assertContains(compiler, "float values[SM_SETTING_SIZE];")
        assertContains(compiler, "SM_DYNAMIC_tint.x")
        assertContains(compiler, "SM_DYNAMIC_scalarGain")
        assertContains(compiler, "float(SM_DYNAMIC_scalarSize)")
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
    fun mergesFunctionBundlesAndSynthesizesAnUnreachableDefaultBranch() {
        val plan = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                #define SETTING_MODE 0 //[0 1 2]
                #if SETTING_MODE == 1
                float evaluate(float value) { return value + 1.0; }
                vec3 evaluate(vec3 value) { return value + 1.0; }
                #elif SETTING_MODE == 2
                float evaluate(float value) { return value + 2.0; }
                vec3 evaluate(vec3 value) { return value + 2.0; }
                #endif
                void main() {
                    if (SETTING_MODE != 0) {
                        float value = evaluate(1.0);
                    }
                }
            """.trimIndent(),
            "function-bundle.csh",
        )

        assertEquals(ShaderConditionalDisposition.CONTROL_FLOW_FUNCTION, plan.conditionals.single().disposition)
        val compiler = assertNotNull(plan.compilerSource)
        assertEquals(2, "\\b(?:float|vec3)\\s+evaluate\\s*\\(".toRegex().findAll(compiler).count())
        assertContains(compiler, "if (SM_SETTING_MODE == 1)")
        assertContains(compiler, "else {\n return value + 1.0;")
    }

    @Test
    fun lowersInactiveByDefaultLocalDeclarationsButRejectsCrossBranchLeakage() {
        val inactive = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_DEBUG
                void main() {
                #ifdef SETTING_DEBUG
                    float sampleValue = 1.0;
                    if (sampleValue > 0.0) {}
                #endif
                }
            """.trimIndent(),
            "inactive-local.csh",
        )
        assertEquals(ShaderConditionalDisposition.CONTROL_FLOW_STATEMENT, inactive.conditionals.single().disposition)
        assertNotNull(inactive.compilerSource)

        val leaked = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_FAST
                void main() {
                #ifdef SETTING_FAST
                    float sampleValue = 1.0;
                #else
                    float sampleValue = 2.0;
                #endif
                    float result = sampleValue;
                }
            """.trimIndent(),
            "leaked-local.csh",
        )
        assertNull(leaked.compilerSource)
        assertContains(leaked.structuralBlockers.single().reason, "leak past the conditional")

        val leakedAcrossSiblingConditions = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_FAST
                void consume(float value) {}
                void main() {
                #ifdef SETTING_FAST
                    float sampleValue = 1.0;
                #endif
                #ifdef SETTING_FAST
                    consume(sampleValue);
                #endif
                }
            """.trimIndent(),
            "sibling-leaked-local.csh",
        )
        assertNull(leakedAcrossSiblingConditions.compilerSource)
        assertTrue(leakedAcrossSiblingConditions.structuralBlockers.any { "leak past" in it.reason })
    }

    @Test
    fun removesDerivedBooleanBranchComparedOutsideItsDomain() {
        val plan = ShaderCompilerCopyPlanner.plan(
            """
                #version 460 compatibility
                //#define SETTING_REFERENCE
                #ifdef SETTING_REFERENCE
                #define USE_REFERENCE 1
                #else
                #define USE_REFERENCE 0
                #endif
                void main() {
                #if USE_REFERENCE == 0
                    int mode = 0;
                #elif USE_REFERENCE == 1
                    int mode = 1;
                #elif USE_REFERENCE == 2
                    consumeUndefinedName();
                #endif
                }
            """.trimIndent(),
            "derived-presence-domain.csh",
        )

        val compiler = assertNotNull(plan.compilerSource)
        assertFalse("consumeUndefinedName" in compiler)
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
    fun lowersNestedSettingControlFlowAcrossAnUnrelatedHostConditional() {
        val source = """
            #version 460 compatibility
            //#define SETTING_OUTER
            //#define SETTING_INNER
            void main() {
                int value = 0;
            #ifdef SETTING_OUTER
                value += 1;
                #if HOST_FEATURE
                    #ifdef SETTING_INNER
                    value += 2;
                    #else
                    value -= 2;
                    #endif
                #endif
            #else
                value -= 1;
            #endif
            }
        """.trimIndent()

        val plan = ShaderCompilerCopyPlanner.plan(source, "nested-host.csh")
        val compiler = assertNotNull(plan.compilerSource)

        assertContains(compiler, "if (SM_SETTING_OUTER)")
        assertContains(compiler, "if (SM_SETTING_INNER)")
        assertContains(compiler, "#if HOST_FEATURE")
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
    fun compilerCopyUsesBoundedArtifactPathsAndReportsProcessStartupFailures() = withWorkspace { workspace ->
        val source = "#version 460 compatibility\nvoid main() {}\n"
        val sourceName = "gbuffers_particles_translucent_".repeat(8) + ".fsh"
        val exception = assertFailsWith<ShaderCompilerCopyException> {
            ShaderCompilerCopyMaterializer(
                workspace,
                processRunner = ClangProcessRunner { _, _, _, _ -> throw IOException("path too long") },
            ).materialize(
                sourceName,
                ShaderStage.FRAGMENT,
                ShaderCompilerCopyPlanner.plan(source, sourceName),
                TextureAccessProbe(source, emptyList(), TextureAccess()),
                "final-structural-validation-module-with-a-long-name",
            )
        }

        assertTrue(exception.artifactDirectory.fileName.toString().length <= 20)
        assertContains(exception.message.orEmpty(), "unable to start clang: path too long")
        assertIs<IOException>(exception.cause)
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
    fun preservesSpirvCrossDirectiveOnlyCapabilityDispatchForGlslang() = withWorkspace { workspace ->
        val source = """
            #version 460 core
            #if defined(GL_KHR_shader_subgroup_basic)
            #extension GL_KHR_shader_subgroup_basic : require
            #else
            #error No extensions available to emulate requested subgroup feature.
            #endif
            #define VALUE 3
            void main() { int value = VALUE; }
        """.trimIndent()

        val materialized = ShaderCompilerCopyMaterializer(workspace).materializeSource(
            "cross-capability.csh",
            ShaderStage.COMPUTE,
            source,
            "final-validation",
        )

        assertContains(materialized, "#if defined(GL_KHR_shader_subgroup_basic)")
        assertContains(materialized, "#error No extensions available to emulate requested subgroup feature.")
        assertContains(materialized, "int value = 3;")
    }

    @Test
    fun batchesCompilerCopiesAcrossIndependentTranslationUnits() = withWorkspace { workspace ->
        val metrics = PipelineMetrics()
        val copyRunner = ClangProcessRunner { command, _, stdout, _ ->
            val inputs = command.filter { it.endsWith("clang-input.glsl") }.map(Path::of)
            stdout.writeText(inputs.joinToString("\n") { it.readText() })
            0
        }
        val materializer = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            metrics = metrics,
        )
        val requests = (0 until 45).map { index ->
            val source = "#version 460 compatibility\n#define VALUE $index\nvoid main() { int value = VALUE; }\n"
            ShaderCompilerCopyMaterializationRequest(
                "batch-$index.csh",
                ShaderStage.COMPUTE,
                ShaderCompilerCopyPlanner.plan(source, "batch-$index.csh"),
                TextureAccessProbe(source, emptyList(), TextureAccess()),
            )
        }

        val results = materializer.materializeBatch(requests)

        assertEquals(45, results.size)
        assertTrue(results.all { it is ShaderCompilerCopyMaterialization.Success })
        assertEquals(45, metrics.snapshot().compilerModules)
        assertEquals(3, metrics.snapshot().clangProcesses)
    }

    @Test
    fun reusesIdenticalCompilerCopyAcrossRootBatches() = withWorkspace { workspace ->
        val metrics = PipelineMetrics()
        var executions = 0
        val copyRunner = ClangProcessRunner { command, _, stdout, _ ->
            executions++
            stdout.writeText(Path.of(command.last()).readText())
            0
        }
        val source = "#version 460 compatibility\nvoid main() {}\n"
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        val materializer = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            metrics = metrics,
        )

        val first = materializer.materialize(
            "first.csh",
            ShaderStage.COMPUTE,
            ShaderCompilerCopyPlanner.plan(source, "first.csh"),
            probe,
        )
        val second = materializer.materialize(
            "second.csh",
            ShaderStage.COMPUTE,
            ShaderCompilerCopyPlanner.plan(source, "second.csh"),
            probe,
        )

        assertEquals(first.source, second.source)
        assertEquals(1, executions)
        assertEquals(1, metrics.snapshot().compilerModules)
        assertEquals(1, metrics.snapshot().clangProcesses)
    }

    @Test
    fun deduplicatesIdenticalCompilerCopiesInsideOneBatch() = withWorkspace { workspace ->
        val metrics = PipelineMetrics()
        val copyRunner = ClangProcessRunner { command, _, stdout, _ ->
            val inputs = command.filter { it.endsWith("clang-input.glsl") }.map(Path::of)
            stdout.writeText(inputs.joinToString("\n") { it.readText() })
            0
        }
        val source = "#version 460 compatibility\n#define VALUE 3\nvoid main() { int value = VALUE; }\n"
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        val requests = (0 until 45).map { index ->
            ShaderCompilerCopyMaterializationRequest(
                "duplicate-$index.csh",
                ShaderStage.COMPUTE,
                ShaderCompilerCopyPlanner.plan(source, "duplicate-$index.csh"),
                probe,
            )
        }
        val results = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            metrics = metrics,
        ).materializeBatch(requests)

        assertEquals(45, results.size)
        assertTrue(results.all { it is ShaderCompilerCopyMaterialization.Success })
        assertEquals(1, metrics.snapshot().compilerModules)
        assertEquals(1, metrics.snapshot().clangProcesses)
    }

    @Test
    fun boundsCompilerCopyCacheWithoutChangingResults() = withWorkspace { workspace ->
        var executions = 0
        val copyRunner = ClangProcessRunner { command, _, stdout, _ ->
            executions++
            stdout.writeText(Path.of(command.last()).readText())
            0
        }
        val source = "#version 460 compatibility\nvoid main() {}\n"
        val probe = TextureAccessProbe(source, emptyList(), TextureAccess())
        val materializer = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            cacheCharacterBudget = 0,
        )

        val first = materializer.materialize(
            "first.csh",
            ShaderStage.COMPUTE,
            ShaderCompilerCopyPlanner.plan(source, "first.csh"),
            probe,
        )
        val second = materializer.materialize(
            "second.csh",
            ShaderStage.COMPUTE,
            ShaderCompilerCopyPlanner.plan(source, "second.csh"),
            probe,
        )

        assertEquals(first.source, second.source)
        assertEquals(2, executions)
    }

    @Test
    fun retriesFailedBatchAsSinglesAndAttributesTheExactCompilerCopy() = withWorkspace { workspace ->
        val metrics = PipelineMetrics()
        val copyRunner = ClangProcessRunner { command, _, stdout, stderr ->
            val inputs = command.filter { it.endsWith("clang-input.glsl") }.map(Path::of)
            if (inputs.size > 1) {
                stderr.writeText("batch failed")
                7
            } else {
                val source = inputs.single().readText()
                if ("FAIL_THIS" in source) {
                    stdout.writeText("failed compiler copy stdout")
                    stderr.writeText("failed compiler copy stderr")
                    9
                } else {
                    stdout.writeText(source)
                    0
                }
            }
        }
        val materializer = ShaderCompilerCopyMaterializer(
            workspace,
            processRunner = copyRunner,
            metrics = metrics,
        )
        val requests = listOf("OK", "FAIL_THIS").mapIndexed { index, value ->
            val source = "#version 460 compatibility\n#define VALUE $value\nvoid main() {}\n"
            ShaderCompilerCopyMaterializationRequest(
                "failure-$index.csh",
                ShaderStage.COMPUTE,
                ShaderCompilerCopyPlanner.plan(source, "failure-$index.csh"),
                TextureAccessProbe(source, emptyList(), TextureAccess()),
            )
        }

        val results = materializer.materializeBatch(requests)
        val failure = assertIs<ShaderCompilerCopyMaterialization.Failure>(results[1]).exception

        assertIs<ShaderCompilerCopyMaterialization.Success>(results[0])
        assertContains(failure.message.orEmpty(), "failure-1.csh")
        assertContains(failure.message.orEmpty(), "clang exited with code 9")
        assertEquals("failed compiler copy stdout", failure.artifactDirectory.resolve("clang.stdout.log").readText())
        assertEquals("failed compiler copy stderr", failure.artifactDirectory.resolve("clang.stderr.log").readText())
        assertTrue(
            workspace.resolve("batches").toFile().walkTopDown()
                .filter { it.name == "clang.stderr.log" }
                .any { it.readText() == "batch failed" },
        )
        assertEquals(3, metrics.snapshot().clangProcesses)
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
