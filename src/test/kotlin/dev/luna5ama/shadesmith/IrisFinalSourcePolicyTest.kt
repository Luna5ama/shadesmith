package dev.luna5ama.shadesmith

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IrisFinalSourcePolicyTest {
    @Test
    fun scansMultilineHostRegistryDeclarationWithoutParsingUnrelatedEntities() {
        val source = """
            #version 460 compatibility
            const int colortex0Format =
                RGBA16F; // exact multiline registry text
            float unrelated(float value) { return value * 2.0; }
            void main() {}
        """.trimIndent() + "\n"

        val declarations = irisHostRegistryDeclarations(source, "final.fsh")

        assertEquals(listOf("colortex0Format"), declarations.map { it.name })
        assertEquals(
            "const int colortex0Format =\n    RGBA16F; // exact multiline registry text\n",
            source.substring(declarations.single().range),
        )
    }

    @Test
    fun scansOnlyCompleteCommentWrappedHostFormatRegistries() {
        val exactBlock = """
            /*
            const int colortex0Format = RGBA16F; // exact main format
            const int shadowcolor0Format = R16F; // exact shadow format
            */
        """.trimIndent() + "\n"
        val source = "#version 460 compatibility\n$exactBlock\nvoid main() {}\n"

        val blocks = irisCommentHostRegistryBlocks(source, "final.fsh")

        assertEquals(1, blocks.size)
        assertEquals(listOf("colortex0Format", "shadowcolor0Format"), blocks.single().names)
        assertEquals(exactBlock, source.substring(blocks.single().range))

        val documentation = """
            #version 460 compatibility
            /* Example only:
            const int colortex0Format = RGBA8;
            */
            void main() {}
        """.trimIndent() + "\n"
        assertTrue(irisCommentHostRegistryBlocks(documentation, "documented.fsh").isEmpty())
    }

    @Test
    fun buildsUniqueFinalSinkWithExactSettingAndGlobalBundles() {
        val common = """
            #version 460 compatibility
            //#define SETTING_FEATURE
            const int colortex0Format = RGBA16F; // exact registry text
            const ivec3 workGroups = ivec3(2, 1, 1);
            void main() {}
        """.trimIndent() + "\n"
        val policies = IrisCorpusMetadataRegistry.plan(
            listOf(
                ShaderFile(Path.of("final.fsh"), common),
                ShaderFile(Path.of("composite.csh"), common),
            ),
        )

        assertTrue(policies.getValue("final.fsh").globalSink)
        assertFalse(policies.getValue("composite.csh").globalSink)
        assertEquals(
            listOf("//#define SETTING_FEATURE\n"),
            policies.getValue("final.fsh").settings.getValue("SETTING_FEATURE").map { it.exactText },
        )
        assertEquals(
            listOf("const int colortex0Format = RGBA16F; // exact registry text\n"),
            policies.getValue("final.fsh").packGlobals.getValue("colortex0Format").map { it.exactText },
        )
        assertFalse("workGroups" in policies.getValue("final.fsh").packGlobals)
    }

    @Test
    fun buildsOneExactGlobalSliceForACommentWrappedFormatBundle() {
        val exactBlock = """
            /*
            const int colortex0Format = RGBA16F; // exact main format
            const int shadowcolor0Format = R16F; // exact shadow format
            */
        """.trimIndent() + "\n"
        val source = "#version 460 compatibility\n$exactBlock\nvoid main() {}\n"
        val policies = IrisCorpusMetadataRegistry.plan(
            listOf(
                ShaderFile(Path.of("final.fsh"), source),
                ShaderFile(Path.of("composite.csh"), source),
            ),
        )

        val sink = policies.getValue("final.fsh")
        assertEquals(exactBlock, sink.packGlobals.getValue("colortex0Format").single().exactText)
        assertEquals(exactBlock, sink.packGlobals.getValue("shadowcolor0Format").single().exactText)

        val emitted = "#version 460 compatibility\nvoid main() {}\n"
        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("final.fsh", ShaderStage.FRAGMENT, source, finalSourcePolicy = sink),
            emitted,
            emptyList(),
        )
        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertEquals(1, Regex.escape(exactBlock).toRegex().findAll(processed.source).count())

        val changedSource = source.replace("RGBA16F", "RGBA32F")
        val changedPolicy = IrisCorpusMetadataRegistry.plan(
            listOf(
                ShaderFile(Path.of("final.fsh"), changedSource),
                ShaderFile(Path.of("composite.csh"), changedSource),
            ),
        ).getValue("final.fsh")
        assertFalse(sink.cacheContract == changedPolicy.cacheContract)
    }

    @Test
    fun rejectsConflictingCommentWrappedFormatBundles() {
        fun source(format: String) = """
            #version 460 compatibility
            /*
            const int colortex0Format = $format;
            */
            void main() {}
        """.trimIndent() + "\n"

        val conflict = assertFailsWith<IllegalArgumentException> {
            IrisCorpusMetadataRegistry.plan(
                listOf(
                    ShaderFile(Path.of("final.fsh"), source("RGBA16F")),
                    ShaderFile(Path.of("composite.csh"), source("RGBA8")),
                ),
            )
        }

        assertContains(conflict.message.orEmpty(), "Conflicting Iris PACK_GLOBAL registry definition")
        assertContains(conflict.message.orEmpty(), "colortex0Format")
    }

    @Test
    fun rejectsMissingNonUniqueAndConflictingRegistryOwnership() {
        val setting = "#version 460 compatibility\n//#define SETTING_FEATURE\nvoid main() {}\n"
        val missing = assertFailsWith<IllegalArgumentException> {
            IrisCorpusMetadataRegistry.plan(listOf(ShaderFile(Path.of("composite.csh"), setting)))
        }
        assertContains(missing.message.orEmpty(), "exactly one final.fsh")

        val duplicate = assertFailsWith<IllegalArgumentException> {
            IrisCorpusMetadataRegistry.plan(
                listOf(
                    ShaderFile(Path.of("a/final.fsh"), "#version 460 compatibility\nvoid main() {}\n"),
                    ShaderFile(Path.of("b/final.fsh"), "#version 460 compatibility\nvoid main() {}\n"),
                ),
            )
        }
        assertContains(duplicate.message.orEmpty(), "multiple final.fsh")

        val conflict = assertFailsWith<IllegalArgumentException> {
            IrisCorpusMetadataRegistry.plan(
                listOf(
                    ShaderFile(Path.of("final.fsh"), setting),
                    ShaderFile(
                        Path.of("composite.csh"),
                        "#version 460 compatibility\n#define SETTING_FEATURE\nvoid main() {}\n",
                    ),
                ),
            )
        }
        assertContains(conflict.message.orEmpty(), "Conflicting Iris SETTING registry definition")
        assertContains(conflict.message.orEmpty(), "SETTING_FEATURE")
    }

    @Test
    fun retainsLocalSettingReferencedOnlyByRestoredDirectives() {
        val source = """
            #version 460 compatibility
            #define SETTING_MODE 1 //[0 1]
            #if SETTING_MODE == 1
            #define ACTIVE_MODE 1
            #endif
            void main() {}
        """.trimIndent() + "\n"
        val policies = IrisCorpusMetadataRegistry.plan(
            listOf(
                ShaderFile(Path.of("final.fsh"), source),
                ShaderFile(Path.of("composite.csh"), source),
            ),
        )

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest(
                sourceName = "composite.csh",
                stage = ShaderStage.COMPUTE,
                source = source,
                finalSourcePolicy = policies.getValue("composite.csh"),
            ),
            source,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertContains(processed.source, "#define SETTING_MODE 1 //[0 1]")
        assertContains(processed.source, "#if SETTING_MODE == 1")
    }

    @Test
    fun retainsForeignSettingReferencedByAnotherRetainedDefinition() {
        val source = """
            #version 460 compatibility
            #define SETTING_BASE 2 //[1 2]
            #define SETTING_DERIVED SETTING_BASE
            void main() { int value = SETTING_DERIVED; }
        """.trimIndent() + "\n"
        val policies = IrisCorpusMetadataRegistry.plan(
            listOf(
                ShaderFile(Path.of("final.fsh"), source),
                ShaderFile(Path.of("composite.csh"), source),
            ),
        )

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest(
                sourceName = "composite.csh",
                stage = ShaderStage.COMPUTE,
                source = source,
                finalSourcePolicy = policies.getValue("composite.csh"),
            ),
            source,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertContains(processed.source, "#define SETTING_BASE 2 //[1 2]")
        assertContains(processed.source, "#define SETTING_DERIVED SETTING_BASE")
    }

    @Test
    fun bridgesExhaustiveDerivedMacroAfterInactiveExpandedIncludeGuard() {
        val source = """
            #version 460 compatibility
            #define SETTING_UPSCALE 1 //[0 1 2]
            #define SM_SETTING_UPSCALE SETTING_UPSCALE
            #define INCLUDE_COMMON a
            #ifndef INCLUDE_COMMON
            #if SETTING_UPSCALE == 0
            #define UPSCALE 1.0
            #elif SETTING_UPSCALE == 1
            #define UPSCALE 1.5
            #elif SETTING_UPSCALE == 2
            #define UPSCALE 2.0
            #endif
            #endif
            void main() { float value = UPSCALE; }
        """.trimIndent() + "\n"

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("derived-guard.csh", ShaderStage.COMPUTE, source),
            source,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertContains(processed.source, "#ifndef UPSCALE\n#define UPSCALE")
        assertContains(processed.source, "SM_SETTING_UPSCALE == 0")
        assertTrue(processed.source.indexOf("#ifndef UPSCALE") < processed.source.indexOf("void main"))
    }

    @Test
    fun doesNotBridgeDerivedMacroFromActiveSettingBranch() {
        val source = """
            #version 460 compatibility
            #define SETTING_SAMPLES 256 //[128 256 512]
            #define SM_SETTING_SAMPLES SETTING_SAMPLES
            #if SETTING_SAMPLES == 128
            #define LOOP_COUNT 1
            #elif SETTING_SAMPLES == 256
            #define LOOP_COUNT 2
            #elif SETTING_SAMPLES == 512
            #define LOOP_COUNT 4
            #endif
            void main() { int value = LOOP_COUNT; }
        """.trimIndent() + "\n"

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("active-derived.csh", ShaderStage.COMPUTE, source),
            source,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertEquals(3, "#define LOOP_COUNT".toRegex().findAll(processed.source).count())
        assertFalse("#ifndef LOOP_COUNT" in processed.source)
    }

    @Test
    fun removesRepeatedDominatedDerivedMacroConditional() {
        val block = """
            #if SETTING_SAMPLES == 128
            #define LOOP_COUNT 1
            #elif SETTING_SAMPLES == 256
            #define LOOP_COUNT 2
            #endif
        """.trimIndent()
        val source = """
            #version 460 compatibility
            #define SETTING_SAMPLES 256 //[128 256]
            $block
            layout(local_size_x = 1) in;
            $block
            void main() { int value = LOOP_COUNT; }
        """.trimIndent() + "\n"

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("duplicate-derived.csh", ShaderStage.COMPUTE, source),
            source,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertEquals(1, "#if SETTING_SAMPLES == 128".toRegex().findAll(processed.source).count())
        assertEquals(1, "#define LOOP_COUNT 1".toRegex().findAll(processed.source).count())
    }

    @Test
    fun hoistsExistingLateResourceBeforeRestoredHelper() {
        val original = """
            #version 460 compatibility
            layout(rgba16f) restrict uniform image2D colorimg0;
            vec4 loadValue(ivec2 texel) { return imageLoad(colorimg0, texel); }
            void main() { imageStore(colorimg0, ivec2(0), loadValue(ivec2(0))); }
        """.trimIndent() + "\n"
        val emitted = """
            #version 460 compatibility
            vec4 loadValue(ivec2 texel) { return imageLoad(colorimg0, texel); }
            layout(rgba16f) restrict uniform image2D colorimg0;
            void main() { imageStore(colorimg0, ivec2(0), loadValue(ivec2(0))); }
        """.trimIndent() + "\n"

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("late-resource.csh", ShaderStage.COMPUTE, original),
            emitted,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertTrue(processed.source.indexOf("uniform image2D colorimg0") < processed.source.indexOf("vec4 loadValue"))
    }

    @Test
    fun restoresSourceStructReferencedByOptimizedFunctionSignature() {
        val original = """
            #version 460 compatibility
            struct DispatchParameters { vec2 offset; };
            void writeValue(DispatchParameters parameters) {}
            void main() { writeValue(DispatchParameters(vec2(1.0))); }
        """.trimIndent() + "\n"
        val emitted = """
            #version 460 compatibility
            void writeValue(DispatchParameters parameters) {}
            void main() { writeValue(DispatchParameters(vec2(1.0))); }
        """.trimIndent() + "\n"

        val processing = IrisFinalSourceProcessor.process(
            SpirvOptimizationRequest("struct-signature.csh", ShaderStage.COMPUTE, original),
            emitted,
            emptyList(),
        )

        val processed = assertIs<IrisFinalSourceProcessing.Processed>(processing, processing.toString())
        assertContains(processed.source, "struct DispatchParameters { vec2 offset; };")
        assertTrue(processed.source.indexOf("struct DispatchParameters") < processed.source.indexOf("void writeValue"))
    }

    @Test
    fun restoresRuntimeEntryBeforeRemovingCompilerOnlyCompatibilityBranch() {
        val original = """
            #version 460 compatibility
            #ifdef __clang__
            in vec3 shadesmith_position;
            in vec3 shadesmith_normal;
            in vec4 shadesmith_color;
            in vec2 shadesmith_texCoord;
            in ivec2 shadesmith_lmCoord;
            in int shadesmith_dhMaterialId;
            vec4 vertexCompat_clipPosition() {
                return projectionMatrix * modelViewMatrix * vec4(shadesmith_position, 1.0);
            }
            vec3 vertexCompat_viewDirection(vec3 value) { return normalMatrix * value; }
            vec3 vertexCompat_normal() { return shadesmith_normal; }
            vec4 vertexCompat_color() { return shadesmith_color; }
            vec2 vertexCompat_texCoord() { return (textureMatrix * vec4(shadesmith_texCoord, 0.0, 1.0)).xy; }
            vec2 vertexCompat_lmCoord() { return (vec2(shadesmith_lmCoord) + 8.0) * (1.0 / 256.0); }
            int vertexCompat_dhMaterialId() { return shadesmith_dhMaterialId; }
            #else
            vec4 vertexCompat_clipPosition() { return ftransform(); }
            vec3 vertexCompat_viewDirection(vec3 value) { return gl_NormalMatrix * value; }
            vec3 vertexCompat_normal() { return gl_Normal.xyz; }
            vec4 vertexCompat_color() { return gl_Color; }
            vec2 vertexCompat_texCoord() { return (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; }
            vec2 vertexCompat_lmCoord() { return (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy; }
            int vertexCompat_dhMaterialId() { return dhMaterialId; }
            #endif
            void main() { gl_Position = vertexCompat_clipPosition(); }
        """.trimIndent() + "\n"
        val emitted = """
            #version 460 compatibility
            in vec3 shadesmith_position;
            in vec3 shadesmith_normal;
            in vec4 shadesmith_color;
            in vec2 shadesmith_texCoord;
            in ivec2 shadesmith_lmCoord;
            flat in int shadesmith_dhMaterialId;
            #ifdef __clang__
            vec4 vertexCompat_clipPosition() {
                return projectionMatrix * modelViewMatrix * vec4(shadesmith_position, 1.0);
            }
            #else
            vec4 vertexCompat_clipPosition() { return ftransform(); }
            #endif
            void main() {
                gl_Position = (projectionMatrix * modelViewMatrix) * vec4(shadesmith_position, 1.0);
                vec3 direction = normalMatrix * normalize(shadesmith_normal);
                vec4 color = shadesmith_color;
                vec2 texCoord = (textureMatrix * vec4(shadesmith_texCoord, 0.0, 1.0)).xy;
                vec2 lightCoord = (vec2(shadesmith_lmCoord) + vec2(8.0)) * 0.00390625;
                int materialId = shadesmith_dhMaterialId;
            }
        """.trimIndent() + "\n"

        val restoration = IrisFinalSourceProcessor.restoreRuntimeCompilerBranches(original, emitted)
        val restored = assertIs<IrisFinalSourceProcessing.Processed>(restoration, restoration.toString())
        assertContains(restored.source, "gl_Position = ftransform();")
        assertContains(restored.source, "return ftransform();")
        assertContains(restored.source, "gl_NormalMatrix * normalize(gl_Normal.xyz)")
        assertContains(restored.source, "vec4 color = gl_Color;")
        assertContains(restored.source, "gl_TextureMatrix[0] * vec4(gl_MultiTexCoord0.xy")
        assertContains(restored.source, "(gl_TextureMatrix[1] * gl_MultiTexCoord1).xy")
        assertContains(restored.source, "int materialId = dhMaterialId;")
        assertFalse("shadesmith_" in restored.source)
        assertFalse("__clang__" in restored.source)
        val compiler = IrisFinalSourceProcessor.prepareCompatibilityCompilerSource(original, restored.source)
        assertContains(compiler, "in vec3 shadesmith_position;")
        assertContains(compiler, "in ivec2 shadesmith_lmCoord;")
        assertContains(compiler, "in int shadesmith_dhMaterialId;")
        assertContains(compiler, "uniform mat4 projectionMatrix;")
        assertContains(compiler, "uniform mat4 modelViewMatrix;")
        assertContains(compiler, "uniform mat3 normalMatrix;")
        assertContains(compiler, "uniform mat4 textureMatrix;")
        assertContains(compiler, "projectionMatrix * modelViewMatrix")
        assertContains(compiler, "normalMatrix * normalize(shadesmith_normal)")
        assertContains(compiler, "textureMatrix * vec4(shadesmith_texCoord")
        assertContains(compiler, "vec2(shadesmith_lmCoord)")
        assertFalse("ftransform" in compiler)
        assertFalse("gl_NormalMatrix" in compiler)
        assertFalse("gl_TextureMatrix" in compiler)
        assertFalse("gl_MultiTexCoord" in compiler)
        assertEquals(
            restored,
            IrisFinalSourceProcessor.restoreRuntimeCompilerBranches(original, restored.source),
        )
    }
}
