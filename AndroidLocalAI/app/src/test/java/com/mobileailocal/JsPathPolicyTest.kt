package com.mobileailocal

import com.mobileailocal.domain.JsFsException
import com.mobileailocal.domain.JsPathPolicy
import org.junit.Assert.*
import org.junit.Test

class JsPathPolicyTest {
    private fun normalize(raw: Any?, allowEmpty: Boolean = false) = JsPathPolicy.normalize(raw, allowEmpty)

    private fun rejected(code: String, raw: Any?, allowEmpty: Boolean = false) {
        try {
            normalize(raw, allowEmpty)
            fail("expected JsFsException($code) for '$raw'")
        } catch (e: JsFsException) {
            assertEquals(code, e.code)
        }
    }

    @Test fun allowedPaths() {
        assertEquals("a.txt", normalize("a.txt"))
        assertEquals("sub/a.txt", normalize("sub/a.txt"))
        assertEquals("a.txt", normalize("./a.txt"))
        assertEquals("a/b", normalize("a//b"))
    }

    @Test fun rootAndEmptyWhenAllowed() {
        assertEquals("", normalize("", allowEmpty = true))
        assertEquals("", normalize(".", allowEmpty = true))
    }

    @Test fun escapesAreEperm() {
        rejected("EPERM", "../x")
        rejected("EPERM", "sub/../x")
        rejected("EPERM", "..")
        rejected("EPERM", "/etc/hosts")
    }

    @Test fun invalidStringsAreEinval() {
        rejected("EINVAL", "\\a")
        rejected("EINVAL", "C:\\x")
        rejected("EINVAL", "a\\b")
        rejected("EINVAL", "a\u0000b")
        rejected("EINVAL", "a\u0001b")
        rejected("EINVAL", "")
        rejected("EINVAL", 123)
        rejected("EINVAL", null)
    }

    @Test fun tooLongIsEnametoolong() {
        rejected("ENAMETOOLONG", "a".repeat(513))
        rejected("ENAMETOOLONG", "sub/" + "a".repeat(101))
        rejected("ENAMETOOLONG", "a/a/a/a/a/a/a/a/a") // 階層 9
        assertEquals("a/a/a/a/a/a/a/a", normalize("a/a/a/a/a/a/a/a")) // 階層 8 は許可
    }
}
