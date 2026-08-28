package dev.luna5ama.shadesmith

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

internal class ExternalProcessGate(permits: Int) {
    private val semaphore = Semaphore(permits)
    private val active = AtomicInteger()
    private val peak = AtomicInteger()

    val peakConcurrency: Int
        get() = peak.get()

    fun <T> run(block: () -> T): T {
        semaphore.acquire()
        val current = active.incrementAndGet()
        peak.accumulateAndGet(current, ::maxOf)
        return try {
            block()
        } finally {
            active.decrementAndGet()
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
    private val toolCacheHits = LongAdder()
    private val cacheHits = LongAdder()
    private val cacheMisses = LongAdder()
    private val cacheInvalidEntries = LongAdder()

    fun recordCompilerModules(count: Int) = compilerModules.add(count.toLong())

    fun recordClangProcess() = clangProcesses.increment()

    fun recordToolProcess(tool: SpirvTool) {
        when (tool) {
            SpirvTool.GLSLANG -> glslangProcesses.increment()
            SpirvTool.SPIRV_OPT -> spirvOptProcesses.increment()
            SpirvTool.SPIRV_CROSS -> spirvCrossProcesses.increment()
        }
    }

    fun recordToolCacheHit() = toolCacheHits.increment()

    fun recordCacheHit() = cacheHits.increment()

    fun recordCacheMiss() = cacheMisses.increment()

    fun recordCacheInvalidEntry() = cacheInvalidEntries.increment()

    fun snapshot(): PipelineMetricsSnapshot {
        return PipelineMetricsSnapshot(
            compilerModules = compilerModules.sum(),
            clangProcesses = clangProcesses.sum(),
            glslangProcesses = glslangProcesses.sum(),
            spirvOptProcesses = spirvOptProcesses.sum(),
            spirvCrossProcesses = spirvCrossProcesses.sum(),
            toolCacheHits = toolCacheHits.sum(),
            cacheHits = cacheHits.sum(),
            cacheMisses = cacheMisses.sum(),
            cacheInvalidEntries = cacheInvalidEntries.sum(),
        )
    }
}

internal data class PipelineMetricsSnapshot(
    val compilerModules: Long,
    val clangProcesses: Long,
    val glslangProcesses: Long,
    val spirvOptProcesses: Long,
    val spirvCrossProcesses: Long,
    val toolCacheHits: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheInvalidEntries: Long,
) {
    val externalProcesses: Long
        get() = clangProcesses + glslangProcesses + spirvOptProcesses + spirvCrossProcesses
}
