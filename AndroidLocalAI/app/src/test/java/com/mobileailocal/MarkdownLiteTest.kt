package com.mobileailocal

import com.mobileailocal.domain.MarkdownLite
import com.mobileailocal.domain.MdBlock
import com.mobileailocal.domain.MdInline
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLiteTest {
    @Test fun headings() {
        val b = MarkdownLite.parse("# Title\n### Sub\n####### seven")
        assertEquals(MdBlock.Heading(1, listOf(MdInline.Plain("Title"))), b[0])
        assertEquals(MdBlock.Heading(3, listOf(MdInline.Plain("Sub"))), b[1])
        // 7 個以上の # は見出しにしない
        assertEquals(MdBlock.Para(listOf(MdInline.Plain("####### seven"))), b[2])
    }

    @Test fun bullets() {
        val b = MarkdownLite.parse("- a\n* b\n  - nested\n1. one\n2) two")
        assertEquals(MdBlock.Bullet("•", 0, listOf(MdInline.Plain("a"))), b[0])
        assertEquals(MdBlock.Bullet("•", 0, listOf(MdInline.Plain("b"))), b[1])
        assertEquals(MdBlock.Bullet("•", 1, listOf(MdInline.Plain("nested"))), b[2])
        assertEquals(MdBlock.Bullet("1.", 0, listOf(MdInline.Plain("one"))), b[3])
        assertEquals(MdBlock.Bullet("2)", 0, listOf(MdInline.Plain("two"))), b[4])
    }

    @Test fun inlineBoldAndCode() {
        assertEquals(
            listOf(MdInline.Plain("a "), MdInline.Bold("bold"), MdInline.Plain(" and "), MdInline.Code("x+1"), MdInline.Plain(".")),
            MarkdownLite.inline("a **bold** and `x+1`."),
        )
    }

    @Test fun unclosedMarkersStayLiteral() {
        // 生成の途中で印が閉じていなくても崩れない
        assertEquals(listOf(MdInline.Plain("a **half")), MarkdownLite.inline("a **half"))
        assertEquals(listOf(MdInline.Plain("a `half")), MarkdownLite.inline("a `half"))
        assertEquals(listOf(MdInline.Plain("**** `` x")), MarkdownLite.inline("**** `` x"))
    }

    @Test fun blankLinesAndPlainText() {
        val b = MarkdownLite.parse("line1\n\nline2\r\nline3")
        assertEquals(listOf("P", "B", "P", "P"), b.map { if (it is MdBlock.Para) "P" else "B" })
        assertEquals(MdBlock.Blank, b[1])
        // 箇条書きの印の後ろに空白が無ければ本文(例: *強調*、-1 などは箇条書きにしない)
        assertEquals(MdBlock.Para(listOf(MdInline.Plain("-1 is negative"))), MarkdownLite.parse("-1 is negative")[0])
    }
}
