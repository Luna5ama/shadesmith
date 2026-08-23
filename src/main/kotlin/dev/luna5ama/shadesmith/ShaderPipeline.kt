package dev.luna5ama.shadesmith

import java.nio.file.Path
import kotlin.io.path.createDirectories

internal data class OptimizedShaderFile(
    val file: ShaderFile,
    val textureAccess: TextureAccess,
)

internal class ShaderPipeline(
    artifactDirectory: Path,
) {
    val artifactDirectory: Path = artifactDirectory.toAbsolutePath().normalize()
    private val optimizer = SpirvOptimizer(this.artifactDirectory.resolve("round-trip"))
    private val materializer = ShaderVariantMaterializer(this.artifactDirectory.resolve("materialized"))

    init {
        this.artifactDirectory.createDirectories()
    }

    context(ioContext: IOContext)
    fun optimize(inputFiles: List<ShaderFile>): List<OptimizedShaderFile> {
        return inputFiles.map { file ->
            val sourceName = file.path.toString().replace('\\', '/')
            val stage = ShaderStage.fromEntryPoint(file.path, file.code)
            val protection = PreprocessorProtection.protect(file.code, sourceName)
            val variants = if (protection.compilerBlockers.isEmpty()) {
                emptyList()
            } else {
                val probe = TextureAccessAnalyzer.createProbe(file.code, ioContext.config)
                materializer.materialize(sourceName, stage, protection, probe)
            }
            val result = optimizer.optimize(
                SpirvOptimizationRequest(
                    sourceName = sourceName,
                    stage = stage,
                    source = file.code,
                    variants = variants,
                ),
            )
            OptimizedShaderFile(
                file = file.copy(code = result.source),
                textureAccess = result.variants
                    .map { it.textureAccess }
                    .fold(TextureAccess(), TextureAccess::plus),
            )
        }
    }
}
