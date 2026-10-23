package com.mobileailocal

import com.mobileailocal.data.ResumeAction
import com.mobileailocal.data.downloadTotal
import com.mobileailocal.data.resumeAction
import com.mobileailocal.domain.NativeError
import com.mobileailocal.data.sha256Hex
import com.mobileailocal.data.shouldDiscard
import com.mobileailocal.data.updateDigest
import com.mobileailocal.domain.ReportBody
import com.mobileailocal.domain.ReportLabels
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class DownloadReportTest {
    // --- ダウンロード再開の判定 ---
    @Test fun noPartRequiresPlain200() {
        assertEquals(ResumeAction.Fresh, resumeAction(0, 200, 1000))
        assertNull(resumeAction(0, 404, 1000))
        assertNull(resumeAction(0, 416, 1000)) // .part が無いのに 416 は異常
    }
    @Test fun partResumesOn206() {
        assertEquals(ResumeAction.Resume, resumeAction(500, 206, 1000))
    }
    @Test fun rangeIgnoredRestarts() {
        assertEquals(ResumeAction.Fresh, resumeAction(500, 200, 1000))
    }
    @Test fun fullPartIsComplete() {
        assertEquals(ResumeAction.Complete, resumeAction(1000, 416, 1000))
        assertEquals(ResumeAction.Complete, resumeAction(1000, 416, 0)) // 期待サイズ不明なら完成扱い
    }
    @Test fun mismatchedPartOn416Restarts() {
        assertEquals(ResumeAction.Fresh, resumeAction(1200, 416, 1000))
    }
    @Test fun serverErrorsFail() {
        assertNull(resumeAction(500, 500, 1000))
        assertNull(resumeAction(500, 403, 1000))
    }

    // --- SHA-256 ---
    private fun ref(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 1回に少しずつしか返さない入力(分割して読んでも結果が同じことの確認用)。 */
    private class Dribble(val data: ByteArray, val step: Int) : InputStream() {
        var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, step, data.size - pos)
            System.arraycopy(data, pos, b, off, n); pos += n
            return n
        }
    }

    @Test fun sha256OfStreamMatchesReference() {
        val data = ByteArray(700_000) { (it * 31).toByte() }
        assertEquals(ref(data), sha256Hex(ByteArrayInputStream(data)))
        assertEquals(ref(data), sha256Hex(Dribble(data, 1000)))
        assertEquals(ref("".toByteArray()), sha256Hex(ByteArrayInputStream(ByteArray(0))))
    }
    @Test fun partialDigestThenContinue() {
        // 再開: 先頭 300 バイトを通してから残りを足しても、全体と同じ
        val data = ByteArray(1000) { it.toByte() }
        val d = MessageDigest.getInstance("SHA-256")
        assertEquals(300L, updateDigest(d, ByteArrayInputStream(data), 300))
        d.update(data, 300, 700)
        assertEquals(ref(data), d.digest().joinToString("") { "%02x".format(it) })
    }
    @Test fun discardDecision() {
        val h = ref("abc".toByteArray())
        assertFalse(shouldDiscard(h, h))
        assertFalse(shouldDiscard(null, h))
        assertTrue(shouldDiscard("0".repeat(64), h))
    }

    // --- 報告メールの本文 ---
    private val labels = ReportLabels("INTRO", "RESPONSE:", "App: 1.0", "Model: m", "Android: 15")

    @Test fun reportBodyHasOnlyExpectedParts() {
        val body = ReportBody.build(labels, "bad answer")
        assertEquals("INTRO\n\nRESPONSE:\nbad answer\n\nApp: 1.0\nModel: m\nAndroid: 15", body)
    }
    @Test fun reportBodyTruncatesLongAnswer() {
        val body = ReportBody.build(labels, "a".repeat(5000))
        assertTrue(body.contains("a".repeat(ReportBody.MAX_ANSWER_CHARS) + "…"))
        assertFalse(body.contains("a".repeat(ReportBody.MAX_ANSWER_CHARS + 1)))
    }
    @Test fun reportBodyDoesNotSplitSurrogatePair() {
        val cat = "🐱" // 🐱
        val text = "a".repeat(ReportBody.MAX_ANSWER_CHARS - 1) + cat
        val body = ReportBody.build(labels, text)
        assertFalse(body.contains('\uD83D') && !body.contains(cat))
        assertTrue(body.contains("a".repeat(ReportBody.MAX_ANSWER_CHARS - 1) + "…"))
    }

    // --- 総量が不明なとき / ネイティブのエラーコード ---
    @Test fun unknownContentLengthGivesZeroTotal() {
        assertEquals(0L, downloadTotal(0, -1))
        assertEquals(0L, downloadTotal(500, -1)) // 再開でも不明なら不確定表示
        assertEquals(1500L, downloadTotal(500, 1000))
        assertEquals(1000L, downloadTotal(0, 1000))
    }
    @Test fun nativeErrorCodesParse() {
        assertEquals(NativeError.LoadFile, NativeError.parse("E_LOAD_FILE"))
        assertEquals(NativeError.TooLong, NativeError.parse(" E_TOO_LONG "))
        assertEquals(NativeError.DecodeGen, NativeError.parse("E_DECODE_GEN"))
        assertNull(NativeError.parse("something else"))
        assertNull(NativeError.parse(null))
        assertEquals(NativeError.entries.size, NativeError.entries.map { it.code }.toSet().size)
    }
}
