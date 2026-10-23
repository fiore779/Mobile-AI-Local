package com.mobileailocal

import com.mobileailocal.domain.CodeBlockParser
import com.mobileailocal.domain.MessageSegment
import org.junit.Assert.*
import org.junit.Test

class CodeBlockParserTest {
    private fun parse(s: String) = CodeBlockParser.parse(s)
    private fun codes(s: String) = parse(s).filterIsInstance<MessageSegment.Code>()

    @Test fun jsBlockIsRunnable() {
        assertEquals(
            listOf(
                MessageSegment.Text("Text"),
                MessageSegment.Code("js", "let x = 1;", true),
                MessageSegment.Text("Tail"),
            ),
            parse("Text\n```js\nlet x = 1;\n```\nTail"),
        )
        assertTrue(codes("Text\n```js\nlet x = 1;\n```\nTail")[0].runnable)
    }

    @Test fun javascriptLangIsRunnable() {
        val c = codes("```javascript\n1+1\n```")[0]
        assertEquals("javascript", c.lang); assertTrue(c.closed); assertTrue(c.runnable)
    }

    @Test fun upperCaseLangIsLowercased() {
        val c = codes("```JS\n1+1\n```")[0]
        assertEquals("js", c.lang); assertTrue(c.runnable)
    }

    @Test fun pythonIsNotRunnable() {
        val c = codes("```python\nprint(1)\n```")[0]
        assertEquals("python", c.lang); assertTrue(c.closed); assertFalse(c.runnable)
    }

    @Test fun noInfoStringIsNotRunnable() {
        val c = codes("```\n1+1\n```")[0]
        assertEquals("", c.lang); assertTrue(c.closed); assertFalse(c.runnable)
    }

    @Test fun unclosedBlock() {
        val c = codes("```js\n1+1\n")[0]
        assertEquals("js", c.lang); assertEquals("1+1", c.code); assertFalse(c.closed); assertFalse(c.runnable)
    }

    @Test fun textAndCodeAlternate() {
        assertEquals(
            listOf(
                MessageSegment.Text("A"),
                MessageSegment.Code("js", "x", true),
                MessageSegment.Text("mid"),
                MessageSegment.Code("js", "y", true),
                MessageSegment.Text("B"),
            ),
            parse("A\n```js\nx\n```\nmid\n```js\ny\n```\nB"),
        )
    }

    @Test fun fourBacktickFence() {
        val c = codes("````js\n```\n1+1\n````")[0]
        assertEquals("js", c.lang); assertEquals("```\n1+1", c.code); assertTrue(c.closed); assertTrue(c.runnable)
    }

    @Test fun indentUpToThreeSpaces() {
        val c = codes("   ```js\n1+1\n   ```")[0]
        assertEquals("js", c.lang); assertEquals("1+1", c.code); assertTrue(c.closed)
    }

    @Test fun fourSpacesIndentIsText() {
        assertEquals(
            listOf(MessageSegment.Text("    ```js\n1+1\n    ```")),
            parse("    ```js\n1+1\n    ```"),
        )
    }

    @Test fun crlfInput() {
        assertEquals(
            listOf(
                MessageSegment.Text("Text"),
                MessageSegment.Code("js", "1+1", true),
                MessageSegment.Text("Tail"),
            ),
            parse("Text\r\n```js\r\n1+1\r\n```\r\nTail"),
        )
    }

    @Test fun emptyCodeBlock() {
        val c = codes("```js\n```")[0]
        assertEquals("js", c.lang); assertEquals("", c.code); assertTrue(c.closed); assertFalse(c.runnable)
    }

    @Test fun multipleBlocks() {
        assertEquals(
            listOf(
                MessageSegment.Code("js", "a", true),
                MessageSegment.Text("text"),
                MessageSegment.Code("js", "b", true),
            ),
            parse("```js\na\n```\ntext\n```js\nb\n```"),
        )
    }

    @Test fun infoStringWithExtraWords() {
        val c = codes("```js title=\"x\"\n1+1\n```")[0]
        assertEquals("js", c.lang); assertTrue(c.runnable)
    }

    @Test fun backticksInsideSentenceAreText() {
        assertEquals(
            listOf(MessageSegment.Text("Use ```js``` inline")),
            parse("Use ```js``` inline"),
        )
        assertEquals(
            listOf(MessageSegment.Text("```a```")),
            parse("```a```"),
        )
    }

    @Test fun blankTextIsDropped() {
        assertEquals(
            listOf(MessageSegment.Code("js", "1+1", true)),
            parse("   \n```js\n1+1\n```"),
        )
    }

    @Test fun plainTextOnly() {
        assertEquals(listOf(MessageSegment.Text("Hello\nworld")), parse("Hello\nworld"))
    }

    @Test fun blankLinesInsideCodeAreKept() {
        assertEquals(
            listOf(MessageSegment.Code("js", "a\n\nb", true)),
            parse("```js\na\n\nb\n```"),
        )
    }

    @Test fun textAfterCodeHasNoLeadingNewline() {
        assertEquals(
            listOf(MessageSegment.Code("js", "x", true), MessageSegment.Text("after")),
            parse("```js\nx\n```\nafter"),
        )
    }

    @Test fun unclosedCodeDropsTrailingNewline() {
        assertEquals(
            listOf(MessageSegment.Code("js", "x", false)),
            parse("```js\nx\n"),
        )
    }
}
