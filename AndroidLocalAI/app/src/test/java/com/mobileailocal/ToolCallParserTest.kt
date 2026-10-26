package com.mobileailocal

import com.mobileailocal.domain.ToolCall
import com.mobileailocal.domain.ToolCallParser
import com.mobileailocal.domain.ToolFind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {
    private fun call(s: String): ToolFind.Call = ToolCallParser.find(s) as ToolFind.Call

    @Test fun noTag() {
        assertEquals(ToolFind.NoCall, ToolCallParser.find("hello world"))
        assertEquals(ToolFind.NoCall, ToolCallParser.find(""))
    }

    @Test fun listSelfClosed() {
        val c = call("""<file_list path="."/>""")
        assertEquals(ToolCall.List("."), c.call)
        assertEquals("""<file_list path="."/>""".length, c.endIndex)
    }

    @Test fun listWithoutPathAndWithClosingTag() {
        assertEquals(ToolCall.List(""), call("<file_list/>").call)
        assertEquals(ToolCall.List("sub"), call("""<file_list path="sub"></file_list>""").call)
    }

    @Test fun readWithQuotesAndStart() {
        assertEquals(ToolCall.Read("a/b.txt", 0), call("""<file_read path="a/b.txt"/>""").call)
        assertEquals(ToolCall.Read("a b.txt", 2000), call("<file_read path='a b.txt' start=\"2000\"/>").call)
        assertEquals(ToolCall.Read("x", 0), call("""<file_read path="x"></file_read>""").call)
    }

    @Test fun writeBodyTrimsOneNewlineEachSideAndKeepsRest() {
        val c = call("<file_write path=\"a.txt\">\nline1\n\nline2\n</file_write>")
        assertEquals(ToolCall.Write("a.txt", "line1\n\nline2"), c.call)
    }

    @Test fun writeNormalizesCrlf() {
        val c = call("<file_write path=\"a.txt\">\r\nx\r\ny\r\n</file_write>")
        assertEquals(ToolCall.Write("a.txt", "x\ny"), c.call)
    }

    @Test fun writeUnwrapsFullCodeFence() {
        val c = call("<file_write path=\"a.py\">\n```python\nprint(1)\n```\n</file_write>")
        assertEquals(ToolCall.Write("a.py", "print(1)"), c.call)
    }

    @Test fun writeKeepsPartialFence() {
        val c = call("<file_write path=\"a.md\">text\n```\ncode\n```\nmore</file_write>")
        assertEquals("text\n```\ncode\n```\nmore", (c.call as ToolCall.Write).text)
    }

    @Test fun textBeforeAndAfterGivesCorrectIndices() {
        val s = "Sure.\n<file_read path=\"a\"/>\ntrailing text"
        val c = call(s)
        assertEquals("Sure.\n", s.substring(0, c.startIndex))
        assertEquals("\ntrailing text", s.substring(c.endIndex))
    }

    @Test fun onlyFirstTagIsReturned() {
        val c = call("""<file_list path="a"/><file_read path="b"/>""")
        assertEquals(ToolCall.List("a"), c.call)
    }

    @Test fun incompleteWhenNotClosed() {
        assertTrue(ToolCallParser.find("<file_write path=\"a\">abc") is ToolFind.Incomplete)
        assertTrue(ToolCallParser.find("<file_read path=\"a\"") is ToolFind.Incomplete)
        assertTrue(ToolCallParser.find("<file_read path=\"a") is ToolFind.Incomplete)
        assertTrue(ToolCallParser.find("<file_read path=\"a\">") is ToolFind.Incomplete)
        assertTrue(ToolCallParser.find("<file_li") is ToolFind.Incomplete)
    }

    @Test fun truncatedWriteIsNeverACall() {
        val f = ToolCallParser.find("<file_write path=\"a\">partial content that was cut by max tokens")
        assertEquals(ToolFind.Incomplete("file_write"), f)
    }

    @Test fun malformed() {
        assertEquals(ToolFind.Malformed("unknown tool"), ToolCallParser.find("""<file_delete path="a"/>"""))
        assertEquals(ToolFind.Malformed("unknown attribute"), ToolCallParser.find("""<file_read path="a" mode="x"/>"""))
        assertEquals(ToolFind.Malformed("unknown attribute"), ToolCallParser.find("""<file_list path="a" start="1"/>"""))
        assertEquals(ToolFind.Malformed("path required"), ToolCallParser.find("<file_read/>"))
        assertEquals(ToolFind.Malformed("path required"), ToolCallParser.find("<file_write>x</file_write>"))
        assertEquals(ToolFind.Malformed("bad start"), ToolCallParser.find("""<file_read path="a" start="-1"/>"""))
        assertEquals(ToolFind.Malformed("bad start"), ToolCallParser.find("""<file_read path="a" start="x"/>"""))
    }

    @Test fun pathIsKeptVerbatimForTheExecutorToValidate() {
        assertEquals(ToolCall.Read("../x", 0), call("""<file_read path="../x"/>""").call)
        assertEquals(ToolCall.Write("/etc/hosts", "x"), call("""<file_write path="/etc/hosts">x</file_write>""").call)
    }

    @Test fun tagInsideCodeFenceIsNotExecuted() {
        val s = "Example:\n```\n<file_write path=\"a\">x</file_write>\n```\nDone."
        assertEquals(ToolFind.NoCall, ToolCallParser.find(s))
    }

    @Test fun tagInsideUnclosedFenceIsNotExecuted() {
        assertEquals(ToolFind.NoCall, ToolCallParser.find("```\n<file_list/>"))
    }

    @Test fun tagAfterFenceIsExecuted() {
        val s = "```\nx\n```\n<file_list path=\"a\"/>"
        assertEquals(ToolCall.List("a"), call(s).call)
    }

    @Test fun otherTagsAreIgnored() {
        assertEquals(ToolFind.NoCall, ToolCallParser.find("<tool_call>{\"name\":\"x\"}</tool_call>"))
    }

    @Test fun mayCompleteNeedsGreaterThanInPieceAndMarkInAnswer() {
        assertTrue(ToolCallParser.mayComplete("<file_list path=\".\"/>", "/>"))
        assertFalse(ToolCallParser.mayComplete("<file_list path=\".\"", "\".\""))
        assertFalse(ToolCallParser.mayComplete("hello >", ">"))
    }
}
