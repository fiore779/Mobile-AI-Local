package com.mobileailocal

import com.mobileailocal.domain.ThinkingParser
import org.junit.Assert.*
import org.junit.Test

class ThinkingParserTest {
    @Test fun noThinkBlock() {
        val r = ThinkingParser.parse("Hello")
        assertNull(r.thinking); assertEquals("Hello", r.answer); assertTrue(r.thinkingDone)
    }
    @Test fun inProgress() {
        val r = ThinkingParser.parse("<think>hmm so far")
        assertEquals("hmm so far", r.thinking); assertEquals("", r.answer); assertFalse(r.thinkingDone)
    }
    @Test fun completed() {
        val r = ThinkingParser.parse("<think>\nreason\n</think>\n\nAnswer")
        assertEquals("reason", r.thinking); assertEquals("Answer", r.answer); assertTrue(r.thinkingDone)
    }
    @Test fun emptyThinking() {
        val r = ThinkingParser.parse("<think>\n\n</think>\n\nAnswer")
        assertEquals("", r.thinking); assertEquals("Answer", r.answer); assertTrue(r.thinkingDone)
    }
    @Test fun textBeforeThink() {
        val r = ThinkingParser.parse("Pre <think>x</think> Post")
        assertEquals("x", r.thinking); assertEquals("Pre  Post", r.answer)
    }
    @Test fun multipleBlocksOnlyFirstIsParsed() {
        val r = ThinkingParser.parse("<think>a</think>A<think>b</think>B")
        assertEquals("a", r.thinking); assertTrue(r.thinkingDone)
        assertTrue(r.answer.startsWith("A"))
    }
    @Test fun answerOfDropsThinking() {
        assertEquals("Answer", ThinkingParser.answerOf("<think>r</think>Answer"))
    }
}
