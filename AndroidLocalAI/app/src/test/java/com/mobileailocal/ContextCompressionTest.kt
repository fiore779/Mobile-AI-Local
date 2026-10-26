package com.mobileailocal

import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CompressionBackoff
import com.mobileailocal.domain.ContextBudget
import com.mobileailocal.domain.Prompts
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.SummaryOutcome
import com.mobileailocal.domain.planCompression
import com.mobileailocal.domain.summarizeAndValidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionTest {
    /** 1 発言の大きさ = 文字数(テストしやすいよう、sizeOf は length)。 */
    private val sizeOf: (ChatMessage) -> Int = { it.content.length }
    private fun u(n: Int) = ChatMessage(Role.User, "u".repeat(n))
    private fun a(n: Int) = ChatMessage(Role.Assistant, "a".repeat(n))

    /** User, Assistant を pairs 組並べ、最後に User を 1 件足す。各 size 文字。 */
    private fun convo(pairs: Int, size: Int): List<ChatMessage> =
        (0 until pairs).flatMap { listOf(u(size), a(size)) } + u(size)

    private fun plan(h: List<ChatMessage>, sc: Int = 0, fixed: Int = 0, trigger: Int = 1000, keep: Int = 400, input: Int = 10_000) =
        planCompression(h, sc, fixed, sizeOf, trigger, keep, input)

    @Test fun belowTriggerReturnsNull() {
        assertNull(plan(convo(2, 100), trigger = 1000)) // 500
        assertNull(plan(convo(2, 100), fixed = 500, trigger = 1000)) // ちょうど 1000 は null
    }

    @Test fun aboveTriggerAdvancesAndKeepsWithinLimit() {
        val h = convo(10, 100) // 21 件 2100
        val p = plan(h)!!
        val kept = h.drop(p.newSummaryCount)
        assertTrue(kept.sumOf(sizeOf) <= 400)
        assertEquals(Role.User, kept.first().role)
        assertEquals(h.take(p.newSummaryCount), p.toSummarize)
    }

    @Test fun newestUserKeptEvenIfOverKeep() {
        val h = listOf(u(100), a(100), u(900)) // 最新だけで keep(400) 超え
        val p = plan(h)!!
        assertEquals(2, p.newSummaryCount)
        assertEquals(listOf(h[0], h[1]), p.toSummarize)
    }

    @Test fun singleMessageReturnsNull() {
        assertNull(plan(listOf(u(5000))))
    }

    @Test fun remainingNeverStartsWithAssistant() {
        val h = listOf(u(100), a(100), u(100), a(100), u(100), a(100), u(100), a(100), u(100), a(100), u(100))
        for (keep in 100..700 step 50) {
            val p = plan(h, trigger = 500, keep = keep) ?: continue
            assertEquals("keep=$keep", Role.User, h[p.newSummaryCount].role)
        }
    }

    @Test fun smallInputBudgetStopsAtUserBoundary() {
        val h = convo(10, 100)
        // 350 → 3 件入る。h[3] は Assistant なので 2 件に戻る
        assertEquals(2, plan(h, input = 350)!!.newSummaryCount)
        // 450 → 4 件。h[4] は User
        assertEquals(4, plan(h, input = 450)!!.newSummaryCount)
        for (input in 200..2000 step 70) {
            val p = plan(h, input = input) ?: continue
            assertEquals("input=$input", Role.User, h[p.newSummaryCount].role)
        }
    }

    @Test fun firstMessageTooBigStillTakesOne() {
        val h = listOf(u(900), a(100), u(100), a(100), u(100))
        val p = plan(h, trigger = 500, keep = 250, input = 10)!!
        assertEquals(1, p.newSummaryCount)
        assertEquals(listOf(h[0]), p.toSummarize)
    }

    @Test fun summaryCountOffsetsActiveRange() {
        val h = convo(10, 100)
        val p = plan(h, sc = 6)!!
        assertTrue(p.newSummaryCount > 6)
        assertEquals(h.subList(6, p.newSummaryCount), p.toSummarize)
    }

    @Test fun repeatedPlansAreMonotonic() {
        var h = convo(10, 100)
        var sc = 0
        var called = 0
        repeat(3) {
            h = h + a(100) + u(100) + a(100) + u(100)
            val p = plan(h, sc = sc)
            if (p != null) {
                assertTrue(p.newSummaryCount > sc)
                sc = p.newSummaryCount
                called++
            }
        }
        assertTrue(called > 0)
    }

    @Test fun retryHistoryNeverCoversLastUser() {
        val h = convo(10, 100)
        val p = plan(h)!!
        assertTrue(p.newSummaryCount <= h.lastIndex)
    }

    @Test fun cloudCharsSameProperties() {
        val h = convo(30, 2_000) // 61 件 122,000 文字
        // 要約の入力に制限がなければ、残りは keep 以内
        val full = planCompression(
            h, 0, 500, sizeOf, ContextBudget.CLOUD_TRIGGER_CHARS, ContextBudget.CLOUD_KEEP_CHARS, 1_000_000,
        )!!
        val kept = h.drop(full.newSummaryCount)
        assertTrue(kept.sumOf(sizeOf) <= ContextBudget.CLOUD_KEEP_CHARS)
        assertEquals(Role.User, kept.first().role)
        // 入力の上限(45,000)があれば、要約する分はその中に収まる
        val capped = planCompression(
            h, 0, 500, sizeOf, ContextBudget.CLOUD_TRIGGER_CHARS, ContextBudget.CLOUD_KEEP_CHARS,
            ContextBudget.CLOUD_TRIGGER_CHARS,
        )!!
        assertTrue(capped.toSummarize.sumOf(sizeOf) <= ContextBudget.CLOUD_TRIGGER_CHARS)
        assertEquals(Role.User, h[capped.newSummaryCount].role)
    }

    // ---- summarizeAndValidate(R5) ----

    private fun run(maxSize: Int = 100, replaced: Int = 1000, gen: suspend (List<ChatMessage>) -> String) = runBlocking {
        summarizeAndValidate(listOf(u(1)), maxSize, replaced, { it.length }, gen)
    }

    @Test fun validSummaryIsDone() {
        assertEquals(SummaryOutcome.Done("要点"), run { "  要点\n" })
    }

    @Test fun thinkingIsStripped() {
        assertEquals(SummaryOutcome.Done("sum"), run { "<think>hmm</think>sum" })
    }

    @Test fun exceptionIsFailed() {
        assertEquals(SummaryOutcome.Failed, run { throw IllegalStateException("x") })
    }

    @Test fun blankIsFailed() {
        assertEquals(SummaryOutcome.Failed, run { "   " })
    }

    @Test fun tooLongIsFailed() {
        assertEquals(SummaryOutcome.Failed, run(maxSize = 10) { "x".repeat(11) })
    }

    @Test fun notSmallerThanOriginalIsFailed() {
        assertEquals(SummaryOutcome.Failed, run(replaced = 5) { "x".repeat(5) })
    }

    @Test fun cancellationIsRethrown() {
        var thrown = false
        try {
            run { throw CancellationException("stop") }
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    // ---- 抑止 ----

    @Test fun backoffSkipsUntilFourMoreMessages() {
        val b = CompressionBackoff()
        assertFalse(b.shouldSkip(10))
        b.markFailed(10)
        assertTrue(b.shouldSkip(10))
        assertTrue(b.shouldSkip(13))
        assertFalse(b.shouldSkip(14))
        b.markFailed(14)
        b.reset()
        assertFalse(b.shouldSkip(14))
    }

    // ---- Prompts ----

    @Test fun summarizeMessagesOmitsEmptyPreviousSummary() {
        val m = Prompts.summarizeMessages("", listOf(u(3), a(3)), 100)
        assertEquals(Role.System, m[0].role)
        assertEquals(Prompts.SUMMARIZE_SYSTEM, m[0].content)
        assertEquals("[Conversation to add]\nUser: uuu\nAssistant: aaa", m[1].content)
    }

    @Test fun summarizeMessagesIncludesPreviousAndCutsLines() {
        val m = Prompts.summarizeMessages("old", listOf(u(10)), 4)
        assertEquals("[Previous summary]\nold\n\n[Conversation to add]\nUser: uuuu …[cut]", m[1].content)
    }

    @Test fun toolResultsAreCutAt400() {
        val body = com.mobileailocal.domain.ToolResult.PREFIX + "z".repeat(1000)
        val m = Prompts.summarizeMessages("", listOf(ChatMessage(Role.User, body)), 2000)
        assertEquals("[Conversation to add]\nUser: " + body.take(400) + " …[cut]", m[1].content)
    }

    @Test fun summaryBlockAndSystemFor() {
        assertEquals("", Prompts.summaryBlock(""))
        assertEquals("", Prompts.summaryBlock("  "))
        val block = Prompts.summaryBlock("S")
        assertTrue(block.endsWith("\nS"))
        assertEquals("base", Prompts.systemFor("base", false, false, "", false))
        assertEquals("base\n\n" + block, Prompts.systemFor("base", false, false, "S", false))
        assertEquals("base\n\n" + Prompts.JS_ADDENDUM + "\n\n" + block, Prompts.systemFor("base", true, false, "S", false))
        // クラウドは JS を足さないが、要約は足す
        assertEquals("base\n\n" + block, Prompts.systemFor("base", true, false, "S", true))
    }
}
