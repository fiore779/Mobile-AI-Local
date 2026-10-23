package com.mobileailocal

import com.mobileailocal.domain.JsHandoff
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunStatus
import org.junit.Assert.*
import org.junit.Test

class JsHandoffTest {
    private fun result(
        status: JsRunStatus = JsRunStatus.Ok,
        returnValue: String? = null,
        console: String = "",
        error: String? = null,
        elapsedMs: Long = 123L,
        consoleTruncated: Boolean = false,
    ) = JsRunResult(1, status, returnValue, console, error, elapsedMs, consoleTruncated, null)

    @Test fun budgetChars() {
        assertEquals(512, JsHandoff.budgetChars(2048))
        assertEquals(1024, JsHandoff.budgetChars(4096))
        assertEquals(3000, JsHandoff.budgetChars(1_000_000))
        assertEquals(512, JsHandoff.budgetChars(100)) // 下限
    }

    @Test fun format() {
        val (text, cut) = JsHandoff.format(result(returnValue = "42", console = "hello"), 1024)
        assertEquals(
            "[JavaScript execution result]\nstatus: ok\ntime: 123 ms\nreturn value:\n42\nconsole:\nhello",
            text,
        )
        assertFalse(cut)
    }

    @Test fun emptySectionsAreOmitted() {
        val (text, cut) = JsHandoff.format(result(), 1024)
        assertEquals("[JavaScript execution result]\nstatus: ok\ntime: 123 ms", text)
        assertFalse(cut)
    }

    @Test fun allSections() {
        val (text, cut) = JsHandoff.format(result(JsRunStatus.Error, "42", "log", "Error: boom"), 1024)
        assertEquals(
            "[JavaScript execution result]\nstatus: error\ntime: 123 ms\nreturn value:\n42\nconsole:\nlog\nerror:\nError: boom",
            text,
        )
        assertFalse(cut)
    }

    @Test fun truncationReportsDroppedChars() {
        // 上限は return = 256 / console = 512 / error = 256
        val r = result(
            returnValue = "x".repeat(300),
            console = "y".repeat(600),
            error = "z".repeat(300),
        )
        val (text, cut) = JsHandoff.format(r, 1024)
        assertEquals(
            listOf(
                "[JavaScript execution result]",
                "status: ok",
                "time: 123 ms",
                "return value:",
                "x".repeat(256) + "\n...[truncated 44 chars]",
                "console:",
                "y".repeat(512) + "\n...[truncated 88 chars]",
                "error:",
                "z".repeat(256) + "\n...[truncated 44 chars]",
            ).joinToString("\n"),
            text,
        )
        assertTrue(cut)
    }

    @Test fun statusLabels() {
        val cases = listOf(
            JsRunStatus.Ok to "ok",
            JsRunStatus.Error to "error",
            JsRunStatus.Timeout to "timeout",
            JsRunStatus.Crashed to "crashed",
            JsRunStatus.Killed to "cancelled",
        )
        for ((status, label) in cases) {
            val (text, _) = JsHandoff.format(result(status), 1024)
            assertEquals("status: $label", text.split("\n")[1])
        }
    }

    @Test fun consoleTruncatedAddsMarkerLine() {
        val (text, cut) = JsHandoff.format(result(console = "log", consoleTruncated = true), 1024)
        assertEquals(
            "[JavaScript execution result]\nstatus: ok\ntime: 123 ms\nconsole:\nlog\n[output truncated]",
            text,
        )
        assertFalse(cut) // consoleTruncated だけでは true にしない
    }

    @Test fun consoleTruncatedMarkerIsNotDuplicated() {
        val (text, cut) = JsHandoff.format(result(console = "log\n[output truncated]", consoleTruncated = true), 1024)
        assertEquals(
            "[JavaScript execution result]\nstatus: ok\ntime: 123 ms\nconsole:\nlog\n[output truncated]",
            text,
        )
        assertFalse(cut)
    }

    @Test fun japaneseIsCountedByChars() {
        // 上限 25 / 50 / 25 文字
        val (text, cut) = JsHandoff.format(result(returnValue = "あ".repeat(30), console = "い".repeat(50)), 100)
        assertEquals(
            listOf(
                "[JavaScript execution result]",
                "status: ok",
                "time: 123 ms",
                "return value:",
                "あ".repeat(25) + "\n...[truncated 5 chars]",
                "console:",
                "い".repeat(50),
            ).joinToString("\n"),
            text,
        )
        assertTrue(cut)
    }
}
