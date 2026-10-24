package com.mobileailocal

import com.mobileailocal.data.CloudTimeoutState
import com.mobileailocal.domain.ApiErrorClassifier
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CloudTimeoutException
import com.mobileailocal.domain.TimeoutPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException

class CloudTimeoutStateTest {
    private var now = 0L
    private fun state() = CloudTimeoutState(firstByteMs = 120_000, idleMs = 60_000, clock = { now })

    @Test fun notArmedNeverFires() {
        val s = state()
        now = 1_000_000
        assertNull(s.check())
    }

    @Test fun firstByteWaitIs120SecondsNotSixty() {
        val s = state()
        now = 0; s.arm()
        now = 61_000; assertNull(s.check()) // 推論型のモデルの待ち。60 秒では切らない
        now = 120_000; assertNull(s.check())
        now = 120_001; assertEquals(TimeoutPhase.FirstByte, s.check())
    }

    @Test fun idleIs60SecondsAfterFirstBytes() {
        val s = state()
        now = 0; s.arm()
        now = 100_000; s.onBytes()
        now = 159_000; assertNull(s.check())
        now = 160_001; assertEquals(TimeoutPhase.Idle, s.check())
        // 一度切れたら、その種別を返し続ける
        now = 999_999; assertEquals(TimeoutPhase.Idle, s.check())
    }

    @Test fun bytesKeepResettingTheIdleTimer() {
        val s = state()
        s.arm()
        for (t in listOf(50_000L, 100_000L, 150_000L, 200_000L)) { now = t; s.onBytes(); assertNull(s.check()) }
    }

    @Test fun timeoutExceptionKeepsPhaseAndSeconds() {
        val e = ApiErrorClassifier.fromException(CloudTimeoutException(TimeoutPhase.FirstByte, 120), midStream = false)
        assertEquals(ApiErrorKind.Timeout, e.kind)
        assertEquals(TimeoutPhase.FirstByte, e.timeoutPhase)
        assertEquals(120, e.timeoutSec)
        // 途中の切断でも、時間切れは StreamCut にしない
        assertEquals(ApiErrorKind.Timeout, ApiErrorClassifier.fromException(CloudTimeoutException(TimeoutPhase.Idle, 60), midStream = true).kind)
        // 従来の SocketTimeoutException は種別なしの Timeout
        val plain = ApiErrorClassifier.fromException(SocketTimeoutException(), false)
        assertEquals(ApiErrorKind.Timeout, plain.kind)
        assertNull(plain.timeoutPhase)
    }
}
