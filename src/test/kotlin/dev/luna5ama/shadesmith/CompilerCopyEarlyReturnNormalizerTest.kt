package dev.luna5ama.shadesmith

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCopyEarlyReturnNormalizerTest {
    @Test
    fun lowersMultipleNestedAndVoidReturnsWithoutRepeatingReturnExpressions() {
        val source = """
            #version 460 core
            int sideEffect();
            int twoReturns(bool first) {
                if (first) {
                    return sideEffect();
                }
                return 2;
            }
            int multipleReturns(int mode) {
                if (mode == 0) return 0;
                if (mode == 1) return 1;
                return 2;
            }
            int nestedReturns(bool outer, bool inner) {
                if (outer) {
                    if (inner) return 3;
                    return 4;
                }
                return 5;
            }
            void voidReturn(bool skip) {
                if (skip) return;
                sideEffect();
            }
            void nestedVoidReturn(bool enabled, bool invalid) {
                if (enabled) {
                    int value = sideEffect();
                    if (invalid) return;
                    value += 1;
                    if (value == 2) return;
                    sideEffect();
                }
            }
        """.trimIndent()

        val result = CompilerCopyEarlyReturnNormalizer.normalize(source)

        assertEquals(
            listOf("twoReturns@3", "multipleReturns@9", "nestedReturns@14", "voidReturn@21", "nestedVoidReturn@25"),
            result.transformedFunctions,
        )
        assertTrue(result.rejectedFunctions.isEmpty())
        assertEquals(1, "\\bsideEffect\\s*\\(\\s*\\)".toRegex().findAll(functionBody(result.source, "twoReturns")).count())
        listOf("twoReturns", "multipleReturns", "nestedReturns", "voidReturn", "nestedVoidReturn").forEach { name ->
            assertEquals(1, "\\breturn\\b".toRegex().findAll(functionBody(result.source, name)).count(), name)
        }
        assertContains(functionBody(result.source, "multipleReturns"), "else {")
        assertContains(functionBody(result.source, "nestedReturns"), "if (inner)")
        assertContains(functionBody(result.source, "voidReturn"), "return;")
        assertFalse(result.source.contains("while (false)"))
    }

    @Test
    fun rejectsUnsafeControlEffectsStructuralBoundariesAndLeakingLocalScopes() {
        val fixtures = listOf(
            "loop" to """
                int candidate(bool stop) {
                    for (int i = 0; i < 4; ++i) {
                        if (stop) return i;
                    }
                    return 4;
                }
            """.trimIndent(),
            "switch" to """
                int candidate(int mode) {
                    switch (mode) {
                        case 0: return 0;
                        default: return 1;
                    }
                }
            """.trimIndent(),
            "barrier" to """
                int candidate(bool stop) {
                    if (stop) {
                        barrier();
                        return 0;
                    }
                    return 1;
                }
            """.trimIndent(),
            "memory barrier" to """
                int candidate(bool stop) {
                    if (stop) {
                        memoryBarrierShared();
                        return 0;
                    }
                    return 1;
                }
            """.trimIndent(),
            "discard" to """
                void candidate(bool stop) {
                    if (stop) return;
                    discard;
                }
            """.trimIndent(),
            "directive" to """
                int candidate(bool stop) {
                    #if ENABLED
                    if (stop) return 0;
                    #endif
                    return 1;
                }
            """.trimIndent(),
            "local scope" to """
                int candidate(bool outer, bool inner) {
                    if (outer) {
                        int local = 1;
                        if (inner) return local;
                        local += 1;
                    }
                    return 0;
                }
            """.trimIndent(),
        )

        fixtures.forEach { (name, source) ->
            val result = CompilerCopyEarlyReturnNormalizer.normalize(source)
            assertEquals(source, result.source, name)
            assertEquals(1, result.rejectedFunctions.size, name)
            assertEquals("candidate", result.rejectedFunctions.single().function, name)
        }
    }

    @Test
    fun preservesNativeSubgroupOperationsWithoutIntroducingAlgorithmSubstitutes() {
        val source = """
            #version 460 core
            uint shuffleCandidate(uint value, bool invalid) {
                uint shuffled = subgroupShuffleXor(value, 1u);
                if (invalid) return shuffled;
                return value;
            }
        """.trimIndent()

        val result = CompilerCopyEarlyReturnNormalizer.normalize(source)
        val body = functionBody(result.source, "shuffleCandidate")

        assertEquals(listOf("shuffleCandidate@2"), result.transformedFunctions)
        assertEquals(1, "\\bsubgroupShuffleXor\\b".toRegex().findAll(body).count())
        assertFalse(body.contains("shared_lane"))
        assertFalse(body.contains("barrier"))
        assertEquals(1, "\\breturn\\b".toRegex().findAll(body).count())
    }

    private fun functionBody(source: String, name: String): String {
        val header = Regex("\\b${Regex.escape(name)}\\s*\\([^)]*\\)\\s*\\{").find(source)
            ?: error("Missing function $name")
        val open = source.indexOf('{', header.range.first)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        error("Unterminated function $name")
    }
}
