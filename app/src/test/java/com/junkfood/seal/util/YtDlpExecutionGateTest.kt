package com.junkfood.seal.util

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class YtDlpExecutionGateTest {
    @Test
    fun checksBeforeEveryExecution() {
        val gate = YtDlpExecutionGate()
        val events = mutableListOf<String>()
        repeat(2) {
            assertEquals(
                42,
                gate.execute({ events.add("check") }) {
                    events.add("execute")
                    42
                },
            )
        }
        assertEquals(listOf("check", "execute", "check", "execute"), events)
    }

    @Test
    fun failedCheckBlocksExecutionAndNextAttemptRetries() {
        val gate = YtDlpExecutionGate()
        var executed = false
        try {
            gate.execute({ throw IOException("offline") }) { executed = true }
            fail("Expected update failure")
        } catch (expected: IOException) {
            assertEquals("offline", expected.message)
        }
        assertFalse(executed)
        gate.execute({}) { executed = true }
        assertTrue(executed)
    }

    @Test
    fun processFailureReleasesGate() {
        val gate = YtDlpExecutionGate()
        try {
            gate.execute({}) { throw IOException("process failed") }
            fail("Expected process failure")
        } catch (expected: IOException) {
            assertEquals("process failed", expected.message)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(
                7,
                executor.submit<Int> { gate.execute({}) { 7 } }.get(5, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun updateCannotRunDuringExecution() {
        val gate = YtDlpExecutionGate()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val updateAttempted = CountDownLatch(1)
        val updates = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val download =
                executor.submit {
                    gate.execute({}) {
                        running.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        assertEquals(0, updates.get())
                    }
                }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            val update =
                executor.submit {
                    updateAttempted.countDown()
                    gate.run { updates.incrementAndGet() }
                }
            assertTrue(updateAttempted.await(5, TimeUnit.SECONDS))
            assertEquals(0, updates.get())
            release.countDown()
            download.get(5, TimeUnit.SECONDS)
            update.get(5, TimeUnit.SECONDS)
            assertEquals(1, updates.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
