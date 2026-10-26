package com.mobileailocal

import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.ContextBudget
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {
    @Test fun emptyIsZero() = assertEquals(0, TokenEstimator.estimate(""))

    @Test fun asciiIsThreePointFiveCharsPerToken() {
        assertEquals(10, TokenEstimator.estimate("a".repeat(35)))
        assertEquals(1, TokenEstimator.estimate("a"))
        assertEquals(2, TokenEstimator.estimate("a".repeat(4))) // 4/3.5 を切り上げ
    }

    @Test fun nonAsciiIsOnePerChar() {
        assertEquals(10, TokenEstimator.estimate("あいうえおかきくけこ"))
        assertEquals(10 + 2, TokenEstimator.estimate("あいうえおかきくけこabcd"))
    }

    @Test fun messageAddsOverhead() {
        assertEquals(10 + TokenEstimator.PER_MESSAGE_OVERHEAD, TokenEstimator.estimate(ChatMessage(Role.User, "a".repeat(35))))
    }

    @Test fun newlinesAndEmojiDoNotThrow() {
        assertEquals(true, TokenEstimator.estimate("a\n\r\n😀😀") > 0)
    }

    @Test fun localLimitTable() {
        assertEquals(3072, ContextBudget.localLimit(4096, 1024))
        assertEquals(1024, ContextBudget.localLimit(2048, 1024))
        assertEquals(7168, ContextBudget.localLimit(8192, 1024))
        assertEquals(512, ContextBudget.localLimit(1024, 1024))
        assertEquals(1024 - 64, ContextBudget.localLimit(1024, 10)) // reserve の下限 64
    }

    @Test fun triggerKeepAndSummaryMax() {
        assertEquals(2150, ContextBudget.localTrigger(3072))
        assertEquals(716, ContextBudget.localTrigger(1024))
        assertEquals(1075, ContextBudget.localKeep(3072))
        assertEquals(358, ContextBudget.localKeep(1024))
        assertEquals(460, ContextBudget.localSummaryMaxTokens(3072))
        assertEquals(153, ContextBudget.localSummaryMaxTokens(1024))
        assertEquals(512, ContextBudget.localSummaryMaxTokens(7168)) // 上限
        assertEquals(96, ContextBudget.localSummaryMaxTokens(100)) // 下限
    }
}
