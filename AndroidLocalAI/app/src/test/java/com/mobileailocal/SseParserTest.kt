package com.mobileailocal

import com.mobileailocal.domain.SseEvent
import com.mobileailocal.domain.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test fun multipleEvents() {
        val p = SseParser()
        val out = p.feed("data: a\n\nevent: x\ndata: b\n\n")
        assertEquals(listOf(SseEvent(null, "a"), SseEvent("x", "b")), out)
    }

    @Test fun multiLineData() {
        assertEquals(listOf(SseEvent(null, "l1\nl2")), SseParser().feed("data: l1\ndata: l2\n\n"))
    }

    @Test fun commentsAndBlankLinesAreIgnored() {
        assertEquals(listOf(SseEvent(null, "a")), SseParser().feed(": ping\n\n\ndata: a\n: c\n\n"))
    }

    @Test fun chunkBoundaryInsideLine() {
        val p = SseParser()
        assertEquals(emptyList<SseEvent>(), p.feed("da"))
        assertEquals(emptyList<SseEvent>(), p.feed("ta: hel"))
        assertEquals(listOf(SseEvent(null, "hello")), p.feed("lo\n\n"))
    }

    @Test fun crlf() {
        assertEquals(listOf(SseEvent("e", "x")), SseParser().feed("event: e\r\ndata: x\r\n\r\n"))
    }

    @Test fun japaneseText() {
        val s = "data: こんにちは😀\n\n"
        // 1 文字ずつ渡しても、デコード後の文字列なので分断されない
        val p = SseParser()
        val out = s.map { p.feed(it.toString()) }.flatten()
        assertEquals(listOf(SseEvent(null, "こんにちは😀")), out)
    }

    @Test fun lineApiAndFinish() {
        val p = SseParser()
        assertNull(p.line("data: z"))
        assertEquals(SseEvent(null, "z"), p.finish())
        assertNull(p.finish())
    }

    @Test fun dataWithoutSpaceAndEmptyValue() {
        assertEquals(listOf(SseEvent(null, "a")), SseParser().feed("data:a\n\n"))
        assertEquals(listOf(SseEvent(null, "")), SseParser().feed("data:\n\n"))
    }
}
