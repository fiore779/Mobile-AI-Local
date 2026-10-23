package com.mobileailocal

import com.mobileailocal.data.RemoteFile
import com.mobileailocal.data.sha256Matches
import com.mobileailocal.data.shouldBlockForMetered
import com.mobileailocal.data.toHex
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DataLogicTest {
    private fun q(name: String) = RemoteFile("a/b", name, 1).quant

    @Test fun quantDetected() {
        assertEquals("Q4_K_M", q("Qwen3-0.6B-Q4_K_M.gguf"))
        assertEquals("IQ3_S", q("model-IQ3_S.gguf"))
        assertEquals("F16", q("model-f16.gguf"))
        assertEquals("Q8_0", q("Qwen3-0.6B-Q8_0.gguf"))
        assertEquals("BF16", q("model-BF16.gguf"))
    }
    @Test fun quantUnknown() { assertEquals("?", q("model.gguf")) }

    @Test fun sha256() {
        val actual = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).toHex()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", actual)
        assertTrue(sha256Matches(actual, actual))
        assertTrue(sha256Matches(actual.uppercase(), actual))
        assertFalse(sha256Matches("0".repeat(64), actual))
        assertTrue(sha256Matches(null, actual))
    }

    @Test fun wifiOnlyJudgement() {
        assertTrue(shouldBlockForMetered(wifiOnly = true, metered = true))
        assertFalse(shouldBlockForMetered(wifiOnly = true, metered = false))
        assertFalse(shouldBlockForMetered(wifiOnly = false, metered = true))
        assertFalse(shouldBlockForMetered(wifiOnly = false, metered = false))
    }
}
