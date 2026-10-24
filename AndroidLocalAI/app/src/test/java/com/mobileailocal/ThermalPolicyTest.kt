package com.mobileailocal

import com.mobileailocal.domain.StopArbiter
import com.mobileailocal.domain.StopReason
import com.mobileailocal.domain.ThermalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ThermalPolicyTest {
    @Test fun isHot() {
        listOf(0, 1).forEach { assertFalse(ThermalPolicy.isHot(it)) }
        listOf(2, 3, 4, 5, 6).forEach { assertTrue(ThermalPolicy.isHot(it)) }
    }

    @Test fun shouldStop() {
        listOf(0, 1, 2).forEach { assertFalse(ThermalPolicy.shouldStop(it)) }
        listOf(3, 4, 5, 6).forEach { assertTrue(ThermalPolicy.shouldStop(it)) }
    }

    @Test fun arbiterFirstWins() {
        val a = StopArbiter()
        assertNull(a.reason)
        assertTrue(a.request(StopReason.Thermal))
        assertFalse(a.request(StopReason.User))
        assertEquals(StopReason.Thermal, a.reason)
        val b = StopArbiter()
        assertTrue(b.request(StopReason.User))
        assertFalse(b.request(StopReason.Thermal))
        assertEquals(StopReason.User, b.reason)
    }

    @Test fun arbiterSameReasonTwice() {
        val a = StopArbiter()
        assertTrue(a.request(StopReason.User))
        assertFalse(a.request(StopReason.User))
    }

    @Test fun arbiterConcurrentExactlyOneWinner() {
        repeat(100) {
            val a = StopArbiter()
            val wins = AtomicInteger()
            val start = CountDownLatch(1)
            val threads = StopReason.entries.map { r ->
                Thread { start.await(); if (a.request(r)) wins.incrementAndGet() }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, wins.get())
        }
    }
}
