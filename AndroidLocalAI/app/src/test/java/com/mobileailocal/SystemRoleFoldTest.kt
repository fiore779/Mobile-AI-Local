package com.mobileailocal

import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.foldSystemIntoUser
import com.mobileailocal.domain.normalizeForCloud
import com.mobileailocal.domain.rejectsSystemRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRoleFoldTest {
    private fun m(role: Role, s: String) = ChatMessage(role, s)

    @Test fun foldsIntoFirstUser() {
        val out = foldSystemIntoUser(listOf(m(Role.System, "SYS"), m(Role.User, "q1"), m(Role.Assistant, "a1"), m(Role.User, "q2")))
        assertEquals(listOf(Role.User, Role.Assistant, Role.User), out.map { it.role })
        assertEquals("SYS\n\nq1", out[0].content)
        assertEquals("q2", out[2].content)
    }

    @Test fun keepsAlternationAfterNormalize() {
        val raw = listOf(m(Role.System, "SYS"), m(Role.User, "q1"), m(Role.Assistant, ""), m(Role.User, "q2"))
        val out = foldSystemIntoUser(normalizeForCloud(raw))
        assertEquals(listOf(Role.User), out.map { it.role })
        assertEquals("SYS\n\nq1\n\nq2", out[0].content)
    }

    @Test fun startsWithAssistantGetsASystemOnlyUser() {
        val out = foldSystemIntoUser(listOf(m(Role.System, "SYS"), m(Role.Assistant, "a"), m(Role.User, "q")))
        assertEquals(listOf(Role.User, Role.Assistant, Role.User), out.map { it.role })
        assertEquals("SYS", out[0].content)
    }

    @Test fun edgeCases() {
        assertEquals(listOf(m(Role.User, "SYS")), foldSystemIntoUser(listOf(m(Role.System, "SYS"))))
        // System が無い・空なら、そのまま(System だけ取り除く)
        assertEquals(listOf(m(Role.User, "q")), foldSystemIntoUser(listOf(m(Role.User, "q"))))
        assertEquals(listOf(m(Role.User, "q")), foldSystemIntoUser(listOf(m(Role.System, " "), m(Role.User, "q"))))
        // 複数の System は連結する
        assertEquals("A\n\nB\n\nq", foldSystemIntoUser(listOf(m(Role.System, "A"), m(Role.System, "B"), m(Role.User, "q")))[0].content)
    }

    @Test fun detectsRejectionMessages() {
        assertTrue(rejectsSystemRole("Developer instruction is not enabled for this model"))
        assertTrue(rejectsSystemRole("System instruction is not supported by this model"))
        assertTrue(rejectsSystemRole("The system role is unsupported"))
        assertFalse(rejectsSystemRole("Unsupported parameter: temperature"))
        assertFalse(rejectsSystemRole("Developer instruction was long"))
        assertFalse(rejectsSystemRole(null))
    }
}
