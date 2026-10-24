package com.mobileailocal

import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.historyForRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRetryTest {
    private fun u(s: String = "u") = ChatMessage(Role.User, s)
    private fun a(s: String = "a") = ChatMessage(Role.Assistant, s)

    @Test fun userAssistant() = assertEquals(listOf(u()), historyForRetry(listOf(u(), a())))

    @Test fun twoTurns() {
        val m = listOf(u("1"), a("1"), u("2"), a("2"))
        assertEquals(m.take(3), historyForRetry(m))
    }

    @Test fun userOnly() = assertEquals(listOf(u()), historyForRetry(listOf(u())))
    @Test fun emptyAssistant() = assertEquals(listOf(u()), historyForRetry(listOf(u(), a(""))))
    @Test fun empty() = assertNull(historyForRetry(emptyList()))
    @Test fun assistantOnly() = assertNull(historyForRetry(listOf(a())))

    @Test fun endsWithUser() {
        val m = listOf(u(), a(), u("2"))
        assertEquals(m, historyForRetry(m))
    }
}
