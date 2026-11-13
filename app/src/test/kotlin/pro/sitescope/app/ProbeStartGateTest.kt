package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class ProbeStartGateTest {
    private class Control : ProbeStartGate.Control {
        val stopped = AtomicBoolean()
        val paused = AtomicBoolean()
        override fun isCancelled() = stopped.get()
        override fun isPaused() = paused.get()
    }

    @Test fun oneClockSpacesParallelWorkers() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val gate = ProbeStartGate(100)
            val control = Control()
            val starts = (1..6).map { pool.submit<Long> { gate.awaitTurn(control); System.nanoTime() } }
                .map { it.get(3, TimeUnit.SECONDS) }.sorted()
            assertTrue("Six workers must not start as one burst", starts.last() - starts.first() >= 450_000_000L)
        } finally { pool.shutdownNow() }
    }

    @Test fun pausedWorkersWaitAndResumeWithoutRecreatingTheGate() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val control = Control().apply { paused.set(true) }
            val entered = CountDownLatch(1)
            val future = pool.submit { entered.countDown(); ProbeStartGate(0).awaitTurn(control) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try { future.get(120, TimeUnit.MILLISECONDS); fail("Started while paused") } catch (_: TimeoutException) { }
            control.paused.set(false)
            future.get(1, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    @Test fun cancellationInterruptsLongDelayWithoutWaitingForTheNextSlot() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val gate = ProbeStartGate(5000)
            val control = Control()
            gate.awaitTurn(control)
            val entered = CountDownLatch(1)
            val future = pool.submit<Boolean> {
                entered.countDown()
                try { gate.awaitTurn(control); false } catch (_: InterruptedException) { true }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            control.stopped.set(true)
            assertTrue(future.get(1, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }
}
