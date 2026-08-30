package dev.luna5ama.shadesmith

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PipelinePerformanceTest {
    @Test
    fun externalProcessGateEnforcesPermitCount() {
        val gate = ExternalProcessGate(2)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 6).map {
                executor.submit {
                    gate.run {
                        val current = active.incrementAndGet()
                        peak.accumulateAndGet(current, ::maxOf)
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        active.decrementAndGet()
                    }
                }
            }
            entered.await(5, TimeUnit.SECONDS)
            assertEquals(2, active.get())
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        assertEquals(2, peak.get())
        assertEquals(2, gate.peakConcurrency)
    }

    @Test
    fun externalProcessGateReleasesPermitAfterFailure() {
        val gate = ExternalProcessGate(1)

        assertFailsWith<IllegalStateException> {
            gate.run { error("expected") }
        }
        assertEquals("ok", gate.run { "ok" })
        assertEquals(1, gate.peakConcurrency)
    }
}
