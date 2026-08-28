package dev.luna5ama.shadesmith

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.LongAdder

internal class ExternalProcessGate(permits: Int) {
    private val semaphore = Semaphore(permits)

    fun <T> run(block: () -> T): T {
        semaphore.acquire()
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }
}

internal class PipelineMetrics {
    private val compilerModules = LongAdder()
    private val clangProcesses = LongAdder()
    private val glslangProcesses = LongAdder()
    private val spirvOptProcesses = LongAdder()
    private val spirvCrossProcesses = LongAdder()

    fun recordCompilerModules(count: Int) = compilerModules.add(count.toLong())

    fun recordClangProcess() = clangProcesses.increment()

    fun recordToolProcess(tool: SpirvTool) {
        when (tool) {
            SpirvTool.GLSLANG -> glslangProcesses.increment()
            SpirvTool.SPIRV_OPT -> spirvOptProcesses.increment()
            SpirvTool.SPIRV_CROSS -> spirvCrossProcesses.increment()
        }
    }

    fun snapshot(): PipelineMetricsSnapshot {
        return PipelineMetricsSnapshot(
            compilerModules = compilerModules.sum(),
            clangProcesses = clangProcesses.sum(),
            glslangProcesses = glslangProcesses.sum(),
            spirvOptProcesses = spirvOptProcesses.sum(),
            spirvCrossProcesses = spirvCrossProcesses.sum(),
        )
    }
}

internal data class PipelineMetricsSnapshot(
    val compilerModules: Long,
    val clangProcesses: Long,
    val glslangProcesses: Long,
    val spirvOptProcesses: Long,
    val spirvCrossProcesses: Long,
) {
    val externalProcesses: Long
        get() = clangProcesses + glslangProcesses + spirvOptProcesses + spirvCrossProcesses
}
