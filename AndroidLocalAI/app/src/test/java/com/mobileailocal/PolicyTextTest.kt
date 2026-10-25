package com.mobileailocal

import com.mobileailocal.domain.PolicyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicyTextTest {
    @Test fun fileNameFollowsLanguage() {
        assertEquals("privacy-policy.ja.md", PolicyText.fileName("ja"))
        assertEquals("privacy-policy.en.md", PolicyText.fileName("en", "US"))
        assertEquals("privacy-policy.en.md", PolicyText.fileName("fr"))
        assertEquals("privacy-policy.zh-CN.md", PolicyText.fileName("zh", "CN"))
        assertEquals("privacy-policy.zh-CN.md", PolicyText.fileName("zh"))
        assertEquals("privacy-policy.zh-TW.md", PolicyText.fileName("zh", "TW"))
        assertEquals("privacy-policy.zh-TW.md", PolicyText.fileName("zh", "HK"))
        assertEquals("privacy-policy.zh-TW.md", PolicyText.fileName("zh", "", "Hant"))
    }

    @Test fun fillReplacesContactPlaceholdersOnly() {
        val raw = "Date: {{effective date}} Contact: {{contact email}}\n{{連絡先メールアドレス}}"
        val out = PolicyText.fill(raw, "a@b.example")
        assertEquals("Date: {{effective date}} Contact: a@b.example\na@b.example", out)
        assertEquals(listOf("{{effective date}}"), PolicyText.unresolved(out))
        assertEquals(emptyList<String>(), PolicyText.unresolved("no placeholders {x}"))
    }

    /** 同梱元(store/privacy/)の 4 言語とも、連絡先の空欄はアプリの設定値で埋まる。日付・開発者名の空欄は公開前に本文側で埋める。 */
    @Test fun bundledPoliciesExistAndContactIsFilled() {
        for (name in listOf("ja", "en", "zh-CN", "zh-TW")) {
            val f = File("../../store/privacy/privacy-policy.$name.md")
            assertTrue("${f.path} がありません", f.exists())
            val filled = PolicyText.fill(f.readText(Charsets.UTF_8), "x@y.example")
            assertTrue(filled.contains("x@y.example"))
            assertFalse("連絡先の空欄が残っている: $name", PolicyText.unresolved(filled).any { it.contains("mail", true) || it.contains("連絡先") || it.contains("邮箱") || it.contains("信箱") })
        }
    }
}
