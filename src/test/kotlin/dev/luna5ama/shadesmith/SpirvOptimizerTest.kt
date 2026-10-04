package dev.luna5ama.shadesmith

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpirvOptimizerTest {
    @Test
    fun restoresLiveHostSamplerDeclarationsAfterAliasExpansion() = withWorkspace { workspace ->
        listOf("colortex10", "shadowcolor5").forEach { sampler ->
            val source = """
                #version 460 compatibility
                #define sourceSampler $sampler
                uniform sampler2D sourceSampler;
                layout(rgba16f, binding = 0) uniform image2D target;
                layout(local_size_x = 1) in;
                void main() { imageStore(target, ivec2(0), texelFetch(sourceSampler, ivec2(0), 0)); }
            """.trimIndent() + "\n"
            val request = SpirvOptimizationRequest("$sampler.csh", ShaderStage.COMPUTE, source)
            val nativeSource = source.replace("#define sourceSampler $sampler\n", "").replace("sourceSampler", sampler)
            val result = SpirvOptimizer(workspace.resolve(sampler)).optimize(request.copy(source = nativeSource))
            val emitted = result.source.replace(Regex("(?m)^uniform sampler2D $sampler;\\s*"), "")
            val processed = IrisFinalSourceProcessor.process(request, emitted, result.modules)
            assertTrue(processed is IrisFinalSourceProcessing.Processed, processed.toString())
            assertContains(processed.source, "uniform sampler2D $sampler;")
        }
    }

    @Test
    fun preservesUnreferencedIrisDispatchMetadataInFinalSource() = withWorkspace { workspace ->
        val cases = listOf(
            "const ivec3 workGroups = ivec3(5120, 1, 1);",
            "const vec2 workGroupsRender = vec2(0.25, 0.25);",
        )

        cases.forEachIndexed { index, declaration ->
            val source = """
                #version 460 compatibility
                layout(local_size_x = 1) in;
                $declaration
                void main() {}
            """.trimIndent() + "\n"
            val result = SpirvOptimizer(workspace.resolve("dispatch-$index")).optimize(
                SpirvOptimizationRequest("dispatch-$index.csh", ShaderStage.COMPUTE, source),
            )

            assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
            assertContains(result.source, declaration)
            assertEquals(1, Regex.escape(declaration).toRegex().findAll(result.source).count())
        }
    }

    @Test
    fun roundTripsRepresentativeComputeVertexFragmentAndGeometryShaders() = withWorkspace { workspace ->
        val fixtures = listOf(
            "dead-code.csh" to ShaderStage.COMPUTE,
            "basic.vsh" to ShaderStage.VERTEX,
            "basic.fsh" to ShaderStage.FRAGMENT,
            "basic.gsh" to ShaderStage.GEOMETRY,
        )

        fixtures.forEach { (name, stage) ->
            val result = SpirvOptimizer(workspace.resolve(stage.glslangName)).optimize(
                SpirvOptimizationRequest(name, stage, fixture(name)),
            )
            val module = result.modules.single()

            assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, name)
            assertEquals(
                listOf(SpirvTool.GLSLANG, SpirvTool.SPIRV_OPT, SpirvTool.SPIRV_CROSS, SpirvTool.GLSLANG),
                (module.invocations + result.finalValidationInvocations).map { it.tool },
                name,
            )
            assertEquals(SpirvToolchain.OPTIMIZER_PASSES, module.invocations[1].command.drop(1).dropLast(3), name)
            assertContains(
                module.invocations[0].command.joinToString(" "),
                "--target-env opengl",
                message = name,
            )
            val crossCommand = module.invocations[2].command
            assertTrue(crossCommand.windowed(3).contains(listOf("--no-es", "--version", "460")), name)
            assertTrue("--glsl-force-flattened-io-blocks" in crossCommand, name)
            assertTrue("--combined-samplers-inherit-bindings" in crossCommand, name)
            assertTrue("--remove-unused-variables" in crossCommand, name)
            assertTrue(module.validationSpirv.isRegularFile(), name)
            assertTrue(module.validationSpirv.fileSize() > 0, name)
            assertContains(module.source, "#version 460 compatibility", message = name)
            assertContains(module.source, "void main()", message = name)
        }
    }

    @Test
    fun compilesSubgroupOperationsWithOpenGlSemanticsAtSpirv13() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("subgroup.csh", ShaderStage.COMPUTE, fixture("subgroup.csh")),
        )
        val module = result.modules.single()

        assertTrue(
            module.invocations.first().command.windowed(4).contains(
                listOf("--target-env", "opengl", "--target-env", "spirv1.3"),
            ),
        )
        assertTrue("--vulkan-semantics" in module.invocations[2].command)
        assertContains(result.source, "#extension GL_KHR_shader_subgroup_arithmetic : require")
        assertContains(result.source, "subgroupAdd(")
        assertContains(result.source, "subgroupShuffleXor(")
        assertContains(result.source, "subgroupBroadcast(")
        assertContains(result.source, "subgroupClusteredXor(")
        assertContains(result.source, "subgroupQuadSwapHorizontal(")
        assertContains(result.source, "subgroupExclusiveAdd(")
        assertContains(result.source, "subgroupMin(")
        assertContains(result.source, "subgroupMax(")
        assertContains(result.source, "subgroupOr(")
        assertFalse("No extensions available to emulate requested subgroup feature" in result.source)
        assertFalse("SM_SPIRV_CROSS_NATIVE_" in result.source)
        assertFalse("shared_lane" in result.source)
        val optimizedInventory = SpirvBinaryInventory.read(module.optimizedSpirv)
        val validationInventory = SpirvBinaryInventory.read(module.validationSpirv)
        assertTrue(optimizedInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE_XOR) > 0)
        assertTrue(validationInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE_XOR) > 0)
        assertTrue(optimizedInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE) > 0)
        assertTrue(validationInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE) > 0)
        assertTrue(67 in optimizedInventory.capabilities)
        assertTrue(68 in optimizedInventory.capabilities)
        assertContains(module.artifactDirectory.resolve("native-primitives.txt").readText(), "vulkan-subgroup")
        assertContains(
            module.artifactDirectory.resolve("compiler.glsl").readText(),
            "SM_SPIRV_CROSS_NATIVE_subgroupBroadcast",
        )
        assertContains(
            module.artifactDirectory.resolve("decompiled-native.glsl").readText(),
            "subgroupBroadcast",
        )
        assertTrue(module.validationSpirv.isRegularFile())
    }

    @Test
    fun roundTripsPartitionedNvOperationsThroughReversibleCrossAdapter() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("partitioned.csh", ShaderStage.COMPUTE, fixture("partitioned.csh")),
        )
        val module = result.modules.single()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertEquals(
            listOf(
                SpirvTool.GLSLANG,
                SpirvTool.SPIRV_OPT,
                SpirvTool.GLSLANG,
                SpirvTool.SPIRV_OPT,
                SpirvTool.SPIRV_CROSS,
                SpirvTool.GLSLANG,
            ),
            (module.invocations + result.finalValidationInvocations).map(SpirvInvocation::tool),
        )
        assertTrue("--vulkan-semantics" in module.invocations[4].command)
        assertFalse("--inline-entry-points-exhaustive" in module.invocations[3].command)
        assertContains(result.source, "subgroupPartitionNV(")
        assertContains(result.source, "subgroupPartitionedAddNV(")
        assertContains(result.source, "subgroupPartitionedMaxNV(")
        assertFalse("SM_SPIRV_CROSS_NATIVE_" in result.source)
        assertFalse("shared_lane" in result.source)
        assertFalse("barrier(" in result.source)
        assertFalse("atomic" in result.source)
        val optimizedInventory = SpirvBinaryInventory.read(module.optimizedSpirv)
        val validationInventory = SpirvBinaryInventory.read(module.validationSpirv)
        assertTrue(5297 in optimizedInventory.capabilities)
        assertTrue(5297 in validationInventory.capabilities)
        assertTrue(
            optimizedInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_PARTITION_NV) > 0,
        )
        assertTrue(
            validationInventory.opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_PARTITION_NV) > 0,
        )
        assertContains(
            module.artifactDirectory.resolve("cross-compiler.glsl").readText(),
            "SM_SPIRV_CROSS_NATIVE_subgroupPartitionNV",
        )
        assertContains(
            module.artifactDirectory.resolve("decompiled.glsl").readText(),
            "SM_SPIRV_CROSS_NATIVE_subgroupPartitionedAddNV",
        )
        assertContains(
            module.artifactDirectory.resolve("decompiled-native.glsl").readText(),
            "subgroupPartitionedAddNV",
        )
        assertContains(
            module.artifactDirectory.resolve("native-primitives.txt").readText(),
            "subgroupPartitionedAddNV",
        )
    }

    @Test
    fun roundTripsHybridRaySortWithoutReplacingItsSubgroupPhase() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("ray-sort.csh", ShaderStage.COMPUTE, fixture("ray-sort.csh")),
        )
        val module = result.modules.single()

        assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
        assertContains(result.source, "subgroupShuffleXor(")
        assertContains(result.source, "shared uint temp[2][128]")
        assertContains(result.source, "barrier()")
        assertFalse("SM_SPIRV_CROSS_NATIVE_" in result.source)
        assertFalse("shared_lane" in result.source)
        assertTrue(
            SpirvBinaryInventory.read(module.optimizedSpirv)
                .opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE_XOR) > 0,
        )
        assertTrue(
            SpirvBinaryInventory.read(module.validationSpirv)
                .opcodeCount(SpirvBinaryInventory.OP_GROUP_NON_UNIFORM_SHUFFLE_XOR) > 0,
        )
    }

    @Test
    fun restoresExactHostConditionalOwnerWhenOptimizationDropsItsNativePrimitiveBranch() {
        val original = """
            #version 460 compatibility
            void main() {
            #if defined(MC_GL_VENDOR_NVIDIA)
                uint partition = subgroupPartitionedAddNV(1u, subgroupPartitionNV(0u));
            #else
                uint partition = 1u;
            #endif
            }
        """.trimIndent()
        val optimized = """
            #version 460 compatibility
            void main()
            {
                uint partition = 1u;
            }
        """.trimIndent()

        val restoration = SpirvFinalEmitter.restoreConditionalNativePrimitiveFunctions(original, optimized)
            as ConditionalNativePrimitiveRestoration.Restored

        assertEquals(1, restoration.restoredFunctions)
        assertContains(restoration.source, "#if defined(MC_GL_VENDOR_NVIDIA)")
        assertContains(restoration.source, "subgroupPartitionNV(")
        assertContains(restoration.source, "subgroupPartitionedAddNV(")
        assertContains(restoration.diagnostics.single(), "function:main()")
    }

    @Test
    fun restoresVulkanCrossLayoutSpecializationDeclarationsToSettingBridges() {
        val setting = ShaderSetting(
            name = "SETTING_MODE",
            type = ShaderSettingType.INT,
            defaultValue = "2",
            domain = listOf("1", "2", "3"),
            presenceToggle = false,
            specializationId = 7,
            sourceSlices = listOf("#define SETTING_MODE 2 //[1 2 3]\n"),
        )
        val source = """
            #version 460
            layout(constant_id = 7) const int SM_SETTING_MODE = 2;
            int selectedMode() { return SM_SETTING_MODE; }
        """.trimIndent() + "\n"

        val restored = SpirvSettingBridge.restoreCrossOutput(source, listOf(setting), emptySet())
            as SpirvSettingBridgeRestoration.Restored

        assertEquals(listOf(setting), restored.settings)
        assertContains(restored.source, "#define SM_SETTING_MODE SETTING_MODE")
        assertContains(restored.source, "return SM_SETTING_MODE;")
        assertFalse("constant_id" in restored.source)
    }

    @Test
    fun restoresVulkanCrossSpecializationByIdWhenCrossUsesASourceAlias() {
        val setting = ShaderSetting(
            name = "SETTING_MODE",
            type = ShaderSettingType.INT,
            defaultValue = "2",
            domain = listOf("1", "2", "3"),
            presenceToggle = false,
            specializationId = 7,
            sourceSlices = listOf("#define SETTING_MODE 2 //[1 2 3]\n"),
        )
        val source = """
            #version 460
            layout(constant_id = 7) const int MODE = 2;
            int selectedMode() { return MODE; }
        """.trimIndent() + "\n"

        val restored = SpirvSettingBridge.restoreCrossOutput(source, listOf(setting), emptySet())
            as SpirvSettingBridgeRestoration.Restored

        assertEquals(listOf(setting), restored.settings)
        assertContains(restored.source, "#define SM_SETTING_MODE SETTING_MODE")
        assertContains(restored.source, "return SM_SETTING_MODE;")
        assertFalse("constant_id" in restored.source)
    }

    @Test
    fun removesRealDeadFunctionAndDeadBranchFromComputeSpirv() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("dead-code.csh", ShaderStage.COMPUTE, fixture("dead-code.csh")),
        )
        val module = result.modules.single()

        assertTrue(module.optimizedSpirvSize < module.originalSpirvSize)
        assertFalse(module.source.contains("deadHelper"))
        assertFalse(module.source.contains("if (false)"))
        assertFalse(module.source.contains("gl_WorkGroupSize"))
        assertContains(module.source, "inputTexture")
        assertFalse(module.source.contains("unusedTexture"), module.coreSource)
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("unusedTexture"))
        assertFalse(module.source.contains("deadReferencedUniform"), module.coreSource)
        assertFalse(module.artifactDirectory.resolve("decompiled.glsl").readText().contains("deadReferencedUniform"))
        assertFalse(module.source.contains("struct DeadRecord"), module.source)
        assertFalse(module.source.contains("readonly buffer DeadBuffer"))
        assertFalse(module.source.contains("DeadRecord deadValues[];"))
        assertContains(module.source, "outputImage")
        assertContains(module.source, "exposure")
        assertContains(module.source, "readonly buffer DataBuffer")
        assertContains(module.source, "readonly buffer FoldedArrayBuffer")
        assertContains(module.source, "float foldedWeights[8 * 4];")
        assertContains(module.source, "uniform Params")
        assertContains(module.source, "float weights[];")
        assertContains(module.source, "vec4 tint;")
        assertContains(module.source, "layout(local_size_x = 8, local_size_y = 4) in;")
        assertContains(
            module.source,
            "const ivec3 workGroups = ivec3(32, 18, 1); // Iris dispatch contract",
        )
        assertContains(module.source, "#extension GL_ARB_shader_image_load_store : require")
        assertContains(module.source, "#pragma optimize(on)")
        assertContains(module.source, "//#define FIXTURE_DEBUG")
        assertFalse(module.source.contains("binding ="))
        assertFalse(module.source.contains("location ="))
    }

    @Test
    fun producesDeterministicSourceSpirvAndArtifactPaths() = withWorkspace { workspace ->
        val request = SpirvOptimizationRequest("basic.fsh", ShaderStage.FRAGMENT, fixture("basic.fsh"))
        val optimizer = SpirvOptimizer(workspace)

        val first = optimizer.optimize(request)
        val restored = first.source
        val firstModule = first.modules.single()
        val firstSpirv = firstModule.optimizedSpirv.readBytes()
        val second = optimizer.optimize(request)

        assertFalse(firstModule.artifactDirectory.resolve("compiler.glsl").readText().contains("colortex3Format"))
        assertFalse(firstModule.artifactDirectory.resolve("validation.glsl").readText().contains("colortex3Format"))
        assertFalse(firstModule.artifactDirectory.resolve("compiler.glsl").readText().contains("colortex4Format"))
        assertFalse(firstModule.artifactDirectory.resolve("validation.glsl").readText().contains("colortex4Format"))
        assertContains(restored, "/* RENDERTARGETS:3 */")
        assertContains(restored, "const int noiseTextureResolution = 256;")
        assertContains(restored, "const float sunPathRotation = -20.0; //[-90.0 -20.0 0.0 20.0 90.0]")
        assertContains(restored, "const int colortex3Format = RGBA16F; // Iris string directive")
        assertContains(restored, "const bool colortex3Clear = false;")
        assertContains(restored, "const vec4 colortex3ClearColor = vec4(0.25, 0.5, 0.75, 1.0);")
        assertContains(restored, "const int colortex4Format = RGBA32F;")
        assertEquals(first.source, second.source)
        assertEquals(first.artifactDirectory, second.artifactDirectory)
        assertEquals(first.modules.single().artifactDirectory, second.modules.single().artifactDirectory)
        assertTrue(firstSpirv.contentEquals(second.modules.single().optimizedSpirv.readBytes()))
    }

    @Test
    fun restoresCommentWrappedHostFormatsWithoutSendingThemToCompilers() = withWorkspace { workspace ->
        val exactBlock = """
            /*
            const int colortex0Format = RGBA16F; // exact main format
            const int shadowcolor0Format = R16F; // exact shadow format
            */
        """.trimIndent() + "\n"
        val source = """
            #version 460 compatibility
            $exactBlock
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
        """.trimIndent() + "\n"

        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("comment-format.fsh", ShaderStage.FRAGMENT, source),
        )
        val module = result.modules.single()

        assertFalse(module.artifactDirectory.resolve("compiler.glsl").readText().contains("colortex0Format"))
        assertFalse(module.artifactDirectory.resolve("validation.glsl").readText().contains("colortex0Format"))
        assertContains(result.source, exactBlock)
        assertEquals(1, Regex.escape(exactBlock).toRegex().findAll(result.source).count())
    }

    @Test
    fun preservesExplicitResourceBindingLocationAndInterpolationContracts() = withWorkspace { workspace ->
        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest(
                "explicit-contract.fsh",
                ShaderStage.FRAGMENT,
                fixture("explicit-contract.fsh"),
            ),
        )
        val source = result.source

        assertContains(source, "layout(location = 3) flat in highp vec2 texCoord;")
        assertContains(source, "layout(location = 1) out vec4 fragColor;")
        assertContains(source, "layout(binding = 5) uniform sampler2D colorTexture;")
        assertTrue(result.modules.single().validationSpirv.isRegularFile())
    }

    @Test
    fun reusesCompilerOnlySamplerBindingsAfterPortableLimitAndSeparatesImages() = withWorkspace { workspace ->
        val source = buildString {
            appendLine("#version 460 compatibility")
            appendLine("layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;")
            repeat(81) { appendLine("uniform sampler2D sampler$it;") }
            appendLine("layout(rgba32ui) uniform writeonly uimage2D outputImage;")
            appendLine("void main() {")
            appendLine("    vec4 value = texture(sampler80, vec2(0.5));")
            appendLine("    imageStore(outputImage, ivec2(0), uvec4(value));")
            appendLine("}")
        }
        val protected = PreprocessorProtection.protect(source, "binding-namespaces.csh")
        val patch = OpenGlShaderPatcher().patch(protected, ShaderStage.COMPUTE)
        val generated = patch.generatedLayouts.associateBy { it.key }

        assertEquals(79, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "sampler79")).value)
        assertEquals(0, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "sampler80")).value)
        assertEquals(0, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "outputImage")).value)

        val result = SpirvOptimizer(workspace).optimize(
            SpirvOptimizationRequest("binding-namespaces.csh", ShaderStage.COMPUTE, source),
        )
        assertTrue(result.modules.single().validationSpirv.isRegularFile())
        assertContains(result.source, "uniform sampler2D sampler80;")
        assertContains(result.source, "layout(rgba32ui) uniform writeonly uimage2D outputImage;")
    }

    @Test
    fun reservesPreferredUniformLocationsBeforeAllocatingRestoredUniforms() {
        val source = """
            #version 460 compatibility
            uniform float restoredUniform;
            uniform int heldItemId;
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()
        val preferred = listOf(
            GeneratedShaderLayout(
                ShaderAbiKey(ShaderAbiKind.UNIFORM, "heldItemId"),
                "location",
                0,
            ),
        )
        val patch = OpenGlShaderPatcher().patch(
            PreprocessorProtection.protect(source, "preferred-location.csh"),
            ShaderStage.COMPUTE,
            preferred,
        )
        val generated = patch.generatedLayouts.associateBy(GeneratedShaderLayout::key)

        assertEquals(0, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "heldItemId")).value)
        assertEquals(1, generated.getValue(ShaderAbiKey(ShaderAbiKind.UNIFORM, "restoredUniform")).value)
    }

    @Test
    fun acceptsMutuallyExclusiveStructDefinitionsDuringRestoredSourceValidation() {
        val source = """
            #version 460 compatibility
            #if HOST_LAYOUT == 0
            struct Payload { float value; };
            #else
            struct Payload { vec2 value; };
            #endif
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()

        val patch = OpenGlShaderPatcher().patch(
            PreprocessorProtection.protectGeneratedCompilerSource(source, "conditional-struct.csh"),
            ShaderStage.COMPUTE,
        )

        assertFalse("Payload" in patch.restorableTypeDeclarations)
    }

    @Test
    fun acceptsMutuallyExclusiveAbiDeclarationsBeforeStructuralMaterialization() {
        val source = """
            #version 460 compatibility
            #if HOST_LAYOUT == 0
            uniform sampler2D payload;
            #else
            uniform usampler2D payload;
            #endif
            layout(local_size_x = 1) in;
            void main() {}
        """.trimIndent()

        val patch = OpenGlShaderPatcher().patch(
            PreprocessorProtection.protectGeneratedCompilerSource(source, "conditional-abi.csh"),
            ShaderStage.COMPUTE,
        )

        assertEquals(1, patch.originalContract.entries.size)
        assertEquals(1, patch.generatedLayouts.size)
        val generated = patch.generatedLayouts.single()
        assertEquals(
            2,
            "${generated.qualifier} = ${generated.value}".toRegex().findAll(patch.compilerSource).count(),
        )
    }

    @Test
    fun restoresLiveAnonymousBlocksAndLinksStagesAfterOptimization() = withWorkspace { workspace ->
        val optimizer = SpirvOptimizer(workspace.resolve("optimizer"))
        val vertex = optimizer.optimize(
            SpirvOptimizationRequest(
                "anonymous-block.vsh",
                ShaderStage.VERTEX,
                fixture("anonymous-block.vsh"),
            ),
        ).source
        val fragment = optimizer.optimize(
            SpirvOptimizationRequest(
                "anonymous-block.fsh",
                ShaderStage.FRAGMENT,
                fixture("anonymous-block.fsh"),
            ),
        ).source

        assertContains(vertex, "readonly buffer GlobalData")
        assertContains(vertex, "sharedCoord = globalValue.xy;")
        assertFalse(Regex("""\b_[0-9]+\s*\.""").containsMatchIn(vertex))
        assertFalse("readonly buffer GlobalData" in fragment)
        assertFalse(Regex("""}\s+_[0-9]+\s*;""").containsMatchIn(fragment))

        val linkDirectory = workspace.resolve("linked")
        Files.createDirectories(linkDirectory)
        val vertexPath = linkDirectory.resolve("anonymous-block.vert")
        val fragmentPath = linkDirectory.resolve("anonymous-block.frag")
        Files.writeString(vertexPath, vertex.replaceFirst("#version 460 compatibility", "#version 460 core"))
        Files.writeString(fragmentPath, fragment.replaceFirst("#version 460 compatibility", "#version 460 core"))
        val output = linkDirectory.resolve("anonymous-block.spv")
        val command = listOf(
            "glslang",
            "--target-env",
            "opengl",
            "--target-env",
            "spirv1.3",
            "--auto-map-bindings",
            "--auto-map-locations",
            "-l",
            vertexPath.toString(),
            fragmentPath.toString(),
            "-o",
            output.toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, log)
        assertTrue(output.isRegularFile())
    }

    @Test
    fun preservesEveryRasterStageInterfaceOrderWithoutRetainingWholeSourceShell() = withWorkspace { workspace ->
        val fixtures = listOf(
            Triple(
                "raster-order.vsh",
                ShaderStage.VERTEX,
                Pair(
                    """
                        #version 460 compatibility
                        layout(location = 0) in vec3 position;
                        out vec2 vertexUv;
                        out vec3 vertexNormal;
                        out vec4 vertexExtra;
                        const int removedShellMarker = 7;
                        void main() {
                            vertexUv = position.xy;
                            vertexNormal = position;
                            vertexExtra = vec4(position, 1.0);
                            gl_Position = vertexExtra;
                            if (false) gl_Position.x += float(removedShellMarker);
                        }
                    """.trimIndent() + "\n",
                    listOf("out vec2 vertexUv;", "out vec3 vertexNormal;", "out vec4 vertexExtra;"),
                ),
            ),
            Triple(
                "raster-order.tcs",
                ShaderStage.TESSELLATION_CONTROL,
                Pair(
                    """
                        #version 460 compatibility
                        layout(vertices = 3) out;
                        in vec2 vertexUv[];
                        in vec3 vertexNormal[];
                        in vec4 vertexExtra[];
                        out vec2 controlUv[];
                        out vec3 controlNormal[];
                        out vec4 controlExtra[];
                        const int removedShellMarker = 7;
                        void main() {
                            controlUv[gl_InvocationID] = vertexUv[gl_InvocationID];
                            controlNormal[gl_InvocationID] = vertexNormal[gl_InvocationID];
                            controlExtra[gl_InvocationID] = vertexExtra[gl_InvocationID];
                            gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                            if (gl_InvocationID == 0) {
                                gl_TessLevelOuter[0] = 1.0;
                                gl_TessLevelOuter[1] = 1.0;
                                gl_TessLevelOuter[2] = 1.0;
                                gl_TessLevelInner[0] = 1.0;
                            }
                            if (false) gl_out[gl_InvocationID].gl_Position.x += float(removedShellMarker);
                        }
                    """.trimIndent() + "\n",
                    listOf(
                        "in vec2 vertexUv[];",
                        "in vec3 vertexNormal[];",
                        "in vec4 vertexExtra[];",
                        "out vec2 controlUv[];",
                        "out vec3 controlNormal[];",
                        "out vec4 controlExtra[];",
                    ),
                ),
            ),
            Triple(
                "raster-order.tes",
                ShaderStage.TESSELLATION_EVALUATION,
                Pair(
                    """
                        #version 460 compatibility
                        layout(triangles, equal_spacing, ccw) in;
                        in vec2 controlUv[];
                        in vec3 controlNormal[];
                        in vec4 controlExtra[];
                        out vec2 evalUv;
                        out vec3 evalNormal;
                        out vec4 evalExtra;
                        const int removedShellMarker = 7;
                        void main() {
                            evalUv = controlUv[0] * gl_TessCoord.x + controlUv[1] * gl_TessCoord.y + controlUv[2] * gl_TessCoord.z;
                            evalNormal = controlNormal[0] * gl_TessCoord.x + controlNormal[1] * gl_TessCoord.y + controlNormal[2] * gl_TessCoord.z;
                            evalExtra = controlExtra[0] * gl_TessCoord.x + controlExtra[1] * gl_TessCoord.y + controlExtra[2] * gl_TessCoord.z;
                            gl_Position = gl_in[0].gl_Position * gl_TessCoord.x + gl_in[1].gl_Position * gl_TessCoord.y + gl_in[2].gl_Position * gl_TessCoord.z;
                            if (false) gl_Position.x += float(removedShellMarker);
                        }
                    """.trimIndent() + "\n",
                    listOf(
                        "in vec2 controlUv[];",
                        "in vec3 controlNormal[];",
                        "in vec4 controlExtra[];",
                        "out vec2 evalUv;",
                        "out vec3 evalNormal;",
                        "out vec4 evalExtra;",
                    ),
                ),
            ),
            Triple(
                "raster-order.gsh",
                ShaderStage.GEOMETRY,
                Pair(
                    """
                        #version 460 compatibility
                        layout(points) in;
                        layout(points, max_vertices = 1) out;
                        in vec2 evalUv[];
                        in vec3 evalNormal[];
                        in vec4 evalExtra[];
                        out vec2 fragUv;
                        out vec3 fragNormal;
                        out vec4 fragExtra;
                        const int removedShellMarker = 7;
                        void main() {
                            fragUv = evalUv[0];
                            fragNormal = evalNormal[0];
                            fragExtra = evalExtra[0];
                            gl_Position = gl_in[0].gl_Position;
                            if (false) gl_Position.x += float(removedShellMarker);
                            EmitVertex();
                            EndPrimitive();
                        }
                    """.trimIndent() + "\n",
                    listOf(
                        "in vec2 evalUv[];",
                        "in vec3 evalNormal[];",
                        "in vec4 evalExtra[];",
                        "out vec2 fragUv;",
                        "out vec3 fragNormal;",
                        "out vec4 fragExtra;",
                    ),
                ),
            ),
            Triple(
                "raster-order.fsh",
                ShaderStage.FRAGMENT,
                Pair(
                    """
                        #version 460 compatibility
                        uniform sampler2D removedShellSampler;
                        in vec2 fragUv;
                        in vec3 fragNormal;
                        in vec4 fragExtra;
                        layout(location = 3) out vec4 lateTarget;
                        layout(location = 0) out vec4 earlyTarget;
                        void main() {
                            vec4 color = vec4(fragUv, fragNormal.x, fragExtra.x);
                            if (false) color += texture(removedShellSampler, vec2(0.0));
                            earlyTarget = color;
                            lateTarget = color;
                        }
                    """.trimIndent() + "\n",
                    listOf("in vec2 fragUv;", "in vec3 fragNormal;", "in vec4 fragExtra;"),
                ),
            ),
        )

        fixtures.forEach { (name, stage, fixture) ->
            val (source, declarations) = fixture
            val result = SpirvOptimizer(workspace.resolve(stage.glslangName)).optimize(
                SpirvOptimizationRequest(name, stage, source),
            )

            assertEquals(SpirvEmissionMode.OPTIMIZED, result.emissionMode, result.fallbackReason)
            assertFalse("removedShell" in result.source)
            assertOrdered(result.source, *declarations.toTypedArray())
            assertContains(result.source, "void main()\n{")
        }
    }

    @Test
    fun preservesStageInterfaceOrderWithinPreprocessorOwnershipBoundaries() {
        val original = """
            #version 460 compatibility
            out vec2 alwaysFirst;
            #if defined(SETTING_BRANCH)
            out vec3 branchFirst;
            out vec4 branchSecond;
            #else
            out float elseValue;
            #endif
            out uint alwaysLast;
            void main() {}
        """.trimIndent() + "\n"
        val decompiled = """
            #version 460 compatibility
            out uint alwaysLast;
            #if defined(SETTING_BRANCH)
            out vec4 branchSecond;
            out vec3 branchFirst;
            #else
            out float elseValue;
            #endif
            out vec2 alwaysFirst;
            void main() {}
        """.trimIndent() + "\n"

        val restored = SpirvFinalEmitter.restoreSourceStageInterfaceOrder(original, decompiled)

        assertOrdered(restored, "out vec2 alwaysFirst;", "out uint alwaysLast;")
        assertOrdered(restored, "out vec3 branchFirst;", "out vec4 branchSecond;")
        assertContains(
            restored,
            "#if defined(SETTING_BRANCH)\nout vec3 branchFirst;\nout vec4 branchSecond;\n#else\nout float elseValue;\n#endif",
        )
    }

    @Test
    fun preservesSourceAndOptimizesExplicitCompilerModulesConcurrently() = withWorkspace { workspace ->
        val original = fixture("macro-heavy.fsh")
        val executor = Executors.newFixedThreadPool(2)
        val firstCompiles = CountDownLatch(2)
        val activeProcesses = AtomicInteger()
        val maximumProcesses = AtomicInteger()
        val runner = SpirvProcessRunner { invocation, workingDirectory, stdoutPath, stderrPath ->
            val active = activeProcesses.incrementAndGet()
            maximumProcesses.accumulateAndGet(active, ::maxOf)
            try {
                if (invocation.tool == SpirvTool.GLSLANG && invocation.input.fileName.toString() == "compiler.glsl") {
                    firstCompiles.countDown()
                    check(firstCompiles.await(30, TimeUnit.SECONDS)) { "Shader compiler modules did not compile concurrently" }
                }
                ProcessBuilder(invocation.command)
                    .directory(workingDirectory.toFile())
                    .redirectOutput(stdoutPath.toFile())
                    .redirectError(stderrPath.toFile())
                    .start()
                    .waitFor()
            } finally {
                activeProcesses.decrementAndGet()
            }
        }
        val result = try {
            SpirvOptimizer(workspace, processRunner = runner, moduleExecutor = executor).optimize(
                SpirvOptimizationRequest(
                    sourceName = "macro-heavy.fsh",
                    stage = ShaderStage.FRAGMENT,
                    source = original,
                    compilerModules = listOf(
                        SpirvCompilerModule(
                            "tint-on",
                            fixture("macro-heavy-on.fsh"),
                        ),
                        SpirvCompilerModule(
                            "tint-off",
                            fixture("macro-heavy-off.fsh"),
                        ),
                    ),
                ),
            )
        } finally {
            executor.shutdownNow()
        }

        assertEquals(SpirvEmissionMode.PRESERVED_SOURCE, result.emissionMode)
        assertEquals(original, result.source)
        assertContains(result.fallbackReason.orEmpty(), "multiple compiler modules have no structural restoration plan")
        assertContains(result.source, "#if defined(SETTING_TINT)")
        assertContains(result.source, "#define APPLY_TINT(value)")
        assertContains(result.source, "//#define SETTING_TINT")
        assertEquals(listOf("tint-on", "tint-off"), result.modules.map { it.name })
        assertTrue(maximumProcesses.get() >= 2)
        assertTrue(result.modules.all { it.optimizedSpirv.isRegularFile() && it.optimizedSpirv.fileSize() > 0 })
        assertTrue(result.finalValidationInvocations.isEmpty())
        assertTrue(result.modules.all { "APPLY_TINT" !in it.source })
    }

    @Test
    fun refusesBlockedSourceWithoutACompilerCopyBeforeInvokingAnyTool() = withWorkspace { workspace ->
        var invoked = false
        val optimizer = SpirvOptimizer(
            workspace,
            processRunner = SpirvProcessRunner { _, _, _, _ ->
                invoked = true
                error("tool must not run")
            },
        )

        val exception = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                ),
            )
        }

        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, exception.phase)
        assertContains(exception.message.orEmpty(), "no compiler-copy module")
        assertFalse(invoked)
        assertTrue(exception.artifactDirectory.resolve("original.glsl").isRegularFile())
        assertTrue(exception.artifactDirectory.listDirectoryEntries("*.spv").isEmpty())
    }

    @Test
    fun refusesBlankOrDuplicateCompilerModuleNames() = withWorkspace { workspace ->
        val optimizer = SpirvOptimizer(workspace)
        val duplicate = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                    compilerModules = listOf(
                        SpirvCompilerModule("same", fixture("macro-heavy-off.fsh")),
                        SpirvCompilerModule("same", fixture("macro-heavy-on.fsh")),
                    ),
                ),
            )
        }
        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, duplicate.phase)
        assertContains(duplicate.message.orEmpty(), "duplicates=[same]")

        val blank = assertFailsWith<SpirvRoundTripException> {
            optimizer.optimize(
                SpirvOptimizationRequest(
                    "macro-heavy.fsh",
                    ShaderStage.FRAGMENT,
                    fixture("macro-heavy.fsh"),
                    compilerModules = listOf(
                        SpirvCompilerModule("", fixture("macro-heavy-off.fsh")),
                    ),
                ),
            )
        }
        assertEquals(SpirvRoundTripPhase.COMPILER_COPY, blank.phase)
        assertContains(blank.message.orEmpty(), "non-blank")
    }

    @Test
    fun resourceAndGeneratedLayoutMismatchesFailLoudly() {
        val source = fixture("basic.fsh")
        val protected = PreprocessorProtection.protect(source, "basic.fsh")
        val patcher = OpenGlShaderPatcher()
        val patch = patcher.patch(protected, ShaderStage.FRAGMENT)

        val missingResource = patch.compilerSource.replace(
            Regex("(?m)^layout\\([^\n]+colorTexture;\\n?"),
            "",
        )
        val restoredMissingResource = patcher.restore(missingResource, patch)
        assertContains(restoredMissingResource, "uniform sampler2D colorTexture;")

        val samplerLayout = patch.generatedLayouts.single { it.key.name == "colorTexture" }
        val changedBinding = patch.compilerSource.replace(
            "${samplerLayout.qualifier} = ${samplerLayout.value}",
            "${samplerLayout.qualifier} = ${samplerLayout.value + 7}",
        )
        val bindingException = assertFailsWith<OpenGlShaderPatchException> {
            patcher.restore(changedBinding, patch)
        }
        assertContains(bindingException.reason, "changed from")

        val computePatch = patcher.patch(
            PreprocessorProtection.protect(fixture("dead-code.csh"), "dead-code.csh"),
            ShaderStage.COMPUTE,
        )
        val changedBlock = computePatch.compilerSource.replace("float weights[];", "vec2 weights[];")
        val blockException = assertFailsWith<OpenGlShaderPatchException> {
            patcher.restore(changedBlock, computePatch)
        }
        assertContains(blockException.reason, "DataBuffer")
        assertContains(blockException.reason, "block declaration changed")

        val crossStyleBlock = computePatch.compilerSource.replace(
            "    float weights[];\n};",
            "    layout(offset = 0) float weights[];\n} _123;",
        )
        val restoredBlock = patcher.restore(crossStyleBlock, computePatch)
        assertContains(restoredBlock, "layout(std430) readonly buffer DataBuffer {\n    float weights[];\n};")
        assertFalse("layout(offset = 0)" in restoredBlock)
        assertFalse("_123" in restoredBlock)
    }

    @Test
    fun blockArrayMacroExtentsCompareByResolvedIntegerValue() {
        val source = """
            #version 460 compatibility
            #define GRID_SIDE 4
            #define GRID_VOLUME (GRID_SIDE * GRID_SIDE * GRID_SIDE)
            layout(std430) buffer DataBuffer { uint values[GRID_VOLUME]; };
            layout(local_size_x = 1) in;
            void main() { values[0] = 1u; }
        """.trimIndent()
        val patcher = OpenGlShaderPatcher()
        val patch = patcher.patch(PreprocessorProtection.protect(source, "macro-block.csh"), ShaderStage.COMPUTE)
        val crossStyle = patch.compilerSource
            .replace("#define GRID_SIDE 4\n", "")
            .replace("#define GRID_VOLUME (GRID_SIDE * GRID_SIDE * GRID_SIDE)\n", "")
            .replace("values[GRID_VOLUME]", "values[64]")
        val symbolicCompilerCopy = crossStyle.replace("values[64]", "values[GRID_VOLUME]")
        val materializedPatch = patcher.patch(
            PreprocessorProtection.protect(symbolicCompilerCopy, "macro-block-final.csh"),
            ShaderStage.COMPUTE,
            expectedContract = patch.originalContract,
        )

        val restored = patcher.restore(crossStyle, patch.copy(integerMacros = emptyMap()))

        assertContains(materializedPatch.compilerSource, "uint values[64];")
        assertContains(restored, "uint values[GRID_VOLUME];")
    }

    @Test
    fun finalCompilerCopyBridgesMissingConditionalIntegerMacro() {
        val contractSource = """
            #version 460 compatibility
            #define SM_STRUCT_SETTING_GRID_SIZE 64
            #define SM_SETTING_MODE 2
            #if defined(DISTANT_HORIZONS)
            #define GRID_SIZE SM_STRUCT_SETTING_GRID_SIZE
            #define usam_data colortex8
            #endif
            #define UPSCALE_FACTOR 2.5
            uniform sampler2D colortex8;
            float readGridSize() { return float(GRID_SIZE + textureSize(usam_data, 0).x) * UPSCALE_FACTOR; }
        """.trimIndent()
        val preprocessed = """
            #version 460 compatibility
            layout(constant_id = 0) const int SM_SETTING_MODE = 2;
            uniform sampler2D colortex8;
            float readGridSize() { return float(GRID_SIZE + textureSize(usam_data, 0).x) * UPSCALE_FACTOR; }
        """.trimIndent()

        val result = restoreMissingCompilerScalarMacros(
            restoreMissingCompilerMacros(preprocessed, contractSource),
            contractSource,
        )

        assertContains(result, "#define GRID_SIZE 64")
        assertContains(result, "#define usam_data colortex8")
        assertContains(result, "#define UPSCALE_FACTOR 2.5")
        assertFalse("#define SM_SETTING_MODE" in result)
        assertContains(result, "float(GRID_SIZE + textureSize(usam_data, 0).x) * UPSCALE_FACTOR")
    }

    @Test
    fun finalCompilerCopyBridgesMissingDerivedScalarMacro() {
        val contractSource = """
            #version 460 compatibility
            #define SETTING_UPSCALE_FACTOR 2 //[0 1 2]
            #if SETTING_UPSCALE_FACTOR == 0
            #define UPSCALE_FACTOR 1.0
            #elif SETTING_UPSCALE_FACTOR == 1
            #define UPSCALE_FACTOR 1.5
            #else
            #define UPSCALE_FACTOR 2.0
            #endif
            float readValue() { return UPSCALE_FACTOR; }
        """.trimIndent()
        val preprocessed = """
            #version 460 compatibility
            const int SM_SETTING_UPSCALE_FACTOR = 2;
            float readValue() { return UPSCALE_FACTOR; }
        """.trimIndent()

        val result = restoreMissingCompilerDerivedMacros(
            preprocessed,
            compilerDerivedScalarExpressions(ShaderCompilerCopyPlanner.plan(contractSource)),
        )

        assertContains(result, "#define UPSCALE_FACTOR")
        assertContains(result, "SM_SETTING_UPSCALE_FACTOR == 0")
        assertContains(result, "SM_SETTING_UPSCALE_FACTOR == 1")
    }

    @Test
    fun restoresBridgeForSettingReintroducedByAnIrisContract() {
        val setting = ShaderSetting(
            name = "SETTING_SHADOW_MAP_RESOLUTION",
            type = ShaderSettingType.INT,
            defaultValue = "2048",
            domain = listOf("1024", "2048", "3072"),
            presenceToggle = false,
            specializationId = 7,
            sourceSlices = listOf("#define SETTING_SHADOW_MAP_RESOLUTION 2048 //[1024 2048 3072]\n"),
        )
        val source = """
            #version 460 compatibility
            #define SETTING_SHADOW_MAP_RESOLUTION 2048 //[1024 2048 3072]
            const int shadowMapResolution = SM_SETTING_SHADOW_MAP_RESOLUTION;
            void main() {}
        """.trimIndent() + "\n"

        val restoration = SpirvSettingBridge.completeRestoredSettings(source, emptyList(), listOf(setting))
        val restored = restoration as SpirvSettingBridgeRestoration.Restored

        assertEquals(listOf(setting), restored.settings)
        assertContains(restored.source, "#define SM_SETTING_SHADOW_MAP_RESOLUTION SETTING_SHADOW_MAP_RESOLUTION")
    }

    @Test
    fun refusesUsingAnIrisFormatDirectiveAsShaderCode() {
        val source = """
            #version 460 compatibility
            const int colortex0Format = RGBA16F;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(float(colortex0Format));
            }
        """.trimIndent()

        val exception = assertFailsWith<OpenGlShaderPatchException> {
            OpenGlShaderPatcher().patch(
                PreprocessorProtection.protect(source, "referenced-format.fsh"),
                ShaderStage.FRAGMENT,
            )
        }

        assertEquals(2, exception.sourceLine)
        assertContains(exception.reason, "colortex0Format")
        assertContains(exception.reason, "referenced by shader code")
    }

    @Test
    fun refusesUnparsedResourceDeclarations() {
        val source = """
            #version 460 compatibility
            uniform sampler2D firstTexture, secondTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(firstTexture, vec2(0.5));
            }
        """.trimIndent()

        val exception = assertFailsWith<OpenGlShaderPatchException> {
            OpenGlShaderPatcher().patch(
                PreprocessorProtection.protect(source, "comma-resource.fsh"),
                ShaderStage.FRAGMENT,
            )
        }

        assertEquals(2, exception.sourceLine)
        assertContains(exception.reason, "unsupported top-level")
    }

    @Test
    fun compileFailureKeepsInputsAndToolLogsWithPhaseContext() = withWorkspace { workspace ->
        val invalid = """
            #version 460 compatibility
            layout(local_size_x = 1) in;
            void main() {
                missingSymbol = 1;
            }
        """.trimIndent()

        val exception = assertFailsWith<SpirvRoundTripException> {
            SpirvOptimizer(workspace).optimize(
                SpirvOptimizationRequest("invalid.csh", ShaderStage.COMPUTE, invalid),
            )
        }

        assertEquals(SpirvRoundTripPhase.COMPILE, exception.phase)
        assertContains(exception.message.orEmpty(), "invalid.csh")
        assertContains(exception.message.orEmpty(), "[comp]")
        assertTrue(exception.artifactDirectory.resolve("input.glsl").isRegularFile())
        assertTrue(exception.artifactDirectory.resolve("compiler.glsl").isRegularFile())
        val logs = exception.artifactDirectory.resolve("logs")
        assertTrue(logs.exists())
        assertTrue(
            logs.listDirectoryEntries("*.log").any { it.fileSize() > 0 },
            "glslang diagnostics should be retained in stdout or stderr",
        )
    }

    private fun assertOrdered(source: String, vararg declarations: String) {
        var previous = -1
        declarations.forEach { declaration ->
            val offset = source.indexOf(declaration)
            assertTrue(offset > previous, "Expected '$declaration' after offset $previous in:\n$source")
            previous = offset
        }
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/spirv/$name")) {
            "Missing SPIR-V fixture $name"
        }.readText()
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("shadesmith spirv optimizer test ")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
