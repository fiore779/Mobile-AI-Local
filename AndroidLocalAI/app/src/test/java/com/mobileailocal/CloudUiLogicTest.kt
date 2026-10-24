package com.mobileailocal

import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CLOUD_MAX_INPUT_CHARS
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.MessageSegment
import com.mobileailocal.domain.Origin
import com.mobileailocal.domain.Prompts
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.halvedLimit
import com.mobileailocal.domain.trimForCloud
import com.mobileailocal.ui.CloudErrorAction
import com.mobileailocal.ui.RunButtonState
import com.mobileailocal.ui.cloudErrorDetail
import com.mobileailocal.ui.cloudErrorUi
import com.mobileailocal.ui.runButtonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloudUiLogicTest {
    private fun m(role: Role, n: Int) = ChatMessage(role, "x".repeat(n))

    // --- 履歴の切り詰め ---
    @Test fun noTrimWhenSmall() {
        val list = listOf(m(Role.System, 10), m(Role.User, 10), m(Role.Assistant, 10), m(Role.User, 10))
        val (out, trimmed) = trimForCloud(list, 1000)
        assertEquals(list, out)
        assertFalse(trimmed)
    }

    @Test fun dropsOldestKeepsSystemAndLatest() {
        val list = listOf(
            m(Role.System, 100), m(Role.User, 400), m(Role.Assistant, 400),
            m(Role.User, 400), m(Role.Assistant, 400), m(Role.User, 300),
        )
        val (out, trimmed) = trimForCloud(list, 1200)
        assertTrue(trimmed)
        assertEquals(Role.System, out.first().role)
        assertEquals(list.last(), out.last())
        assertTrue(out.sumOf { it.content.length } <= 1200)
        // 先頭の System の次は User から始まる
        assertEquals(Role.User, out[1].role)
    }

    @Test fun latestIsKeptEvenIfHuge() {
        val list = listOf(m(Role.System, 10), m(Role.User, 50), m(Role.Assistant, 50), m(Role.User, 5000))
        val (out, trimmed) = trimForCloud(list, 100)
        assertTrue(trimmed)
        assertEquals(listOf(list[0], list[3]), out)
    }

    @Test fun trimEdgeCases() {
        assertEquals(emptyList<ChatMessage>() to false, trimForCloud(emptyList(), 10))
        val onlySystem = listOf(m(Role.System, 100))
        assertEquals(onlySystem to false, trimForCloud(onlySystem, 10))
    }

    @Test fun halvedLimit() {
        assertEquals(30_000, halvedLimit(CLOUD_MAX_INPUT_CHARS))
        assertEquals(2000, halvedLimit(3000))
    }

    // --- システムプロンプト ---
    @Test fun jsAddendumOnlyForLocal() {
        assertTrue(Prompts.JS_ADDENDUM in Prompts.systemFor("base", jsEnabled = true, cloud = false))
        assertFalse(Prompts.JS_ADDENDUM in Prompts.systemFor("base", jsEnabled = true, cloud = true))
        assertEquals("base", Prompts.systemFor("base", jsEnabled = false, cloud = false))
    }

    // --- 実行ボタン ---
    private val closedJs = MessageSegment.Code("js", "1+1", closed = true)

    @Test fun cloudRepliesNeverShowRunButton() {
        assertEquals(RunButtonState.Hidden, runButtonState(closedJs, true, false, JsRunState.Idle, Origin.Cloud))
        assertEquals(RunButtonState.Hidden, runButtonState(MessageSegment.Code("js", "1", false), true, false, JsRunState.Idle, Origin.Cloud))
        assertEquals(RunButtonState.Hidden, runButtonState(MessageSegment.Code("js", "x".repeat(100_000), true), true, false, JsRunState.Idle, Origin.Cloud))
    }

    @Test fun localRepliesKeepExistingBehavior() {
        assertEquals(RunButtonState.Enabled, runButtonState(closedJs, true, false, JsRunState.Idle, Origin.Local))
        assertEquals(RunButtonState.Enabled, runButtonState(closedJs, true, false, JsRunState.Idle))
        assertEquals(RunButtonState.Hidden, runButtonState(closedJs, false, false, JsRunState.Idle, Origin.Local))
        assertEquals(RunButtonState.Disabled, runButtonState(closedJs, true, true, JsRunState.Idle, Origin.Local))
    }

    // --- エラー表示 ---
    @Test fun everyKindHasUi() {
        for (k in ApiErrorKind.entries) assertNotNull(k.name, cloudErrorUi(k))
    }

    @Test fun billingKindsHaveNoRetry() {
        for (k in listOf(ApiErrorKind.BillingRequired, ApiErrorKind.SpendLimit, ApiErrorKind.QuotaExceeded)) {
            val a = cloudErrorUi(k).actions
            assertFalse(k.name, CloudErrorAction.Retry in a)
            assertTrue(CloudErrorAction.OpenSettings in a)
            assertTrue(CloudErrorAction.OpenProviderConsole in a)
        }
    }

    @Test fun retriableKindsHaveRetryAndAuthHasReEnter() {
        for (k in listOf(ApiErrorKind.RateLimited, ApiErrorKind.Overloaded, ApiErrorKind.ServerError, ApiErrorKind.Timeout, ApiErrorKind.Network, ApiErrorKind.StreamCut)) {
            assertTrue(k.name, CloudErrorAction.Retry in cloudErrorUi(k).actions)
        }
        assertTrue(CloudErrorAction.SwitchToLocal in cloudErrorUi(ApiErrorKind.Network).actions)
        assertEquals(listOf(CloudErrorAction.ReEnterKey), cloudErrorUi(ApiErrorKind.AuthInvalid).actions)
        assertEquals(listOf(CloudErrorAction.ReselectModel), cloudErrorUi(ApiErrorKind.ModelNotFound).actions)
    }

    @Test fun detailShowsProviderInfo() {
        val e = ApiError(ApiErrorKind.SpendLimit, 429, "enforced_spend_limit_reached", "limit", null, "2026-11-01", "req_1")
        val d = cloudErrorDetail(e)!!
        assertTrue("HTTP 429" in d && "enforced_spend_limit_reached" in d && "2026-11-01" in d && "req_1" in d)
        assertNull(cloudErrorDetail(ApiError(ApiErrorKind.Network)))
    }

    // --- 入力の検証 ---
    @Test fun keyCleaning() {
        assertEquals("sk-abc", CloudInputs.cleanKey("  sk-abc\n"))
        assertTrue(CloudInputs.looksLikeKey("sk-abcd"))
        assertFalse(CloudInputs.looksLikeKey("abc"))
        assertFalse(CloudInputs.looksLikeKey("two words"))
        assertFalse(CloudInputs.looksLikeKey("a".repeat(401)))
        assertFalse(CloudInputs.looksLikeKey(null))
    }

    @Test fun modelNames() {
        assertEquals("gpt-4o", CloudInputs.cleanModelName(" gpt-4o "))
        assertEquals("models/gemini:flash@1.0_x", CloudInputs.cleanModelName("models/gemini:flash@1.0_x"))
        assertNull(CloudInputs.cleanModelName(""))
        assertNull(CloudInputs.cleanModelName("a b"))
        assertNull(CloudInputs.cleanModelName("日本語"))
        assertNull(CloudInputs.cleanModelName("a".repeat(201)))
        assertEquals("a".repeat(200), CloudInputs.cleanModelName("a".repeat(200)))
    }

    @Test fun displayNames() {
        assertEquals("x", CloudInputs.cleanDisplayName(" x "))
        assertNull(CloudInputs.cleanDisplayName("  "))
        assertNull(CloudInputs.cleanDisplayName("a".repeat(41)))
    }

    @Test fun baseUrlRules() {
        val ok = CloudInputs.UrlCheck.Ok
        val notAllowed = CloudInputs.UrlCheck.HttpNotAllowed
        assertEquals(ok, CloudInputs.checkBaseUrl("https://api.example.com/v1"))
        assertEquals(ok, CloudInputs.checkBaseUrl("https://192.168.0.5:8443/v1"))
        // 平文の http:// は、LAN・localhost でも許可しない
        for (u in listOf("http://192.168.0.5:8080/v1", "http://10.0.0.2/v1", "http://localhost:11434/v1",
            "http://mypc.local:8080", "http://[::1]:8080/v1", "http://example.com/v1", "http://8.8.8.8/v1")) {
            assertEquals(u, notAllowed, CloudInputs.checkBaseUrl(u))
        }
        assertEquals(CloudInputs.UrlCheck.Invalid, CloudInputs.checkBaseUrl("ftp://x"))
        assertEquals(CloudInputs.UrlCheck.Invalid, CloudInputs.checkBaseUrl("https://user:pw@x.com/v1"))
        assertEquals(CloudInputs.UrlCheck.Invalid, CloudInputs.checkBaseUrl("https://x.com/v1?key=1"))
        assertEquals(CloudInputs.UrlCheck.Invalid, CloudInputs.checkBaseUrl(""))
    }

    @Test fun syncableUrls() {
        assertTrue(CloudInputs.syncable("https://api.example.com/v1"))
        assertFalse(CloudInputs.syncable("http://192.168.0.5/v1"))
        assertFalse(CloudInputs.syncable("https://api.example.com/v1?k=1"))
        assertFalse(CloudInputs.syncable("https://u:p@api.example.com/v1"))
    }

    // --- 4 ロケールの文字列キー ---
    @Test fun localesHaveTheSameKeys() {
        fun keys(dir: String): Set<String> =
            Regex("""<string name="([^"]+)"""").findAll(File("src/main/res/$dir/strings.xml").readText())
                .map { it.groupValues[1] }.toSet()
        val en = keys("values")
        for (d in listOf("values-ja", "values-zh-rCN", "values-zh-rTW")) {
            assertEquals("$d: missing", emptySet<String>(), en - keys(d))
            assertEquals("$d: extra", emptySet<String>(), keys(d) - en)
        }
        assertTrue(en.count { it.startsWith("cloud_err_") } >= ApiErrorKind.entries.size)
    }
}
