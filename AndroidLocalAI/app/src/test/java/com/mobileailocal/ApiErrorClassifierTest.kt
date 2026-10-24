package com.mobileailocal

import com.mobileailocal.domain.AnthropicProvider
import com.mobileailocal.domain.ApiErrorClassifier
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.OpenAiCompatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ApiErrorClassifierTest {
    private val anth = AnthropicProvider()
    private val oai = OpenAiCompatProvider.openAi()
    private val gem = OpenAiCompatProvider.gemini()

    private fun a(status: Int, body: String, headers: Map<String, String> = emptyMap()) = anth.classify(status, headers, body).kind
    private fun o(status: Int, body: String, headers: Map<String, String> = emptyMap()) = oai.classify(status, headers, body).kind
    private fun g(status: Int, body: String) = gem.classify(status, emptyMap(), body).kind

    private fun ae(type: String, msg: String = "m", extra: String = "") =
        """{"type":"error","error":{"type":"$type","message":"$msg"$extra},"request_id":"req_1"}"""

    // --- Anthropic(7.1 の表) ---
    @Test fun anthropicStatusTable() {
        assertEquals(ApiErrorKind.InvalidRequest, a(400, ae("invalid_request_error")))
        assertEquals(ApiErrorKind.AuthInvalid, a(401, ae("authentication_error")))
        assertEquals(ApiErrorKind.BillingRequired, a(402, ae("billing_error")))
        assertEquals(ApiErrorKind.PermissionDenied, a(403, ae("permission_error")))
        assertEquals(ApiErrorKind.ModelNotFound, a(404, ae("not_found_error")))
        assertEquals(ApiErrorKind.ContextTooLong, a(413, ae("request_too_large")))
        assertEquals(ApiErrorKind.RateLimited, a(429, ae("rate_limit_error"), mapOf("retry-after" to "10")))
        assertEquals(ApiErrorKind.ServerError, a(500, ae("api_error")))
        assertEquals(ApiErrorKind.Timeout, a(504, ae("timeout_error")))
        assertEquals(ApiErrorKind.Overloaded, a(529, ae("overloaded_error")))
    }

    @Test fun anthropicSpendLimit429() {
        val body = """{"type":"error","error":{"type":"rate_limit_error","message":"limit","details":{"error_code":"enforced_spend_limit_reached","resets_at":"2026-11-01T00:00:00Z"}}}"""
        val e = anth.classify(429, emptyMap(), body)
        assertEquals(ApiErrorKind.SpendLimit, e.kind)
        assertEquals("2026-11-01T00:00:00Z", e.resetAt)
        assertEquals("enforced_spend_limit_reached", e.providerCode)
        // retry-after 付きでも error_code があれば上限
        assertEquals(ApiErrorKind.SpendLimit, a(429, body, mapOf("retry-after" to "5")))
    }

    @Test fun anthropic429WithRetryAfterIsRateLimit() {
        val e = anth.classify(429, mapOf("Retry-After" to "7"), ae("rate_limit_error"))
        assertEquals(ApiErrorKind.RateLimited, e.kind)
        assertEquals(7L, e.retryAfterSec)
    }

    @Test fun anthropicUsageLimit400() {
        assertEquals(ApiErrorKind.SpendLimit, a(400, ae("invalid_request_error", "You have reached your workspace spend limit")))
        assertEquals(ApiErrorKind.ContextTooLong, a(400, ae("invalid_request_error", "prompt is too long: 300000 tokens")))
    }

    @Test fun anthropicRequestIdAndMessage() {
        val e = anth.classify(500, emptyMap(), ae("api_error", "boom"))
        assertEquals("req_1", e.requestId)
        assertEquals("boom", e.providerMessage)
    }

    // --- OpenAI ---
    private fun oe(code: String?, type: String? = null, msg: String = "m") =
        """{"error":{"message":"$msg","type":${type?.let { "\"$it\"" }},"code":${code?.let { "\"$it\"" }}}}"""

    @Test fun openAiBillingAndSpendCodes() {
        assertEquals(ApiErrorKind.BillingRequired, o(429, oe("credit_balance_exhausted", "insufficient_quota")))
        assertEquals(ApiErrorKind.SpendLimit, o(429, oe("organization_spend_limit_exceeded")))
        assertEquals(ApiErrorKind.SpendLimit, o(429, oe("project_spend_limit_exceeded")))
        assertEquals(ApiErrorKind.SpendLimit, o(429, oe("organization_usage_limit_exceeded")))
    }

    @Test fun openAiQuotaVsRateLimit() {
        assertEquals(ApiErrorKind.QuotaExceeded, o(429, oe("something", "insufficient_quota")))
        assertEquals(ApiErrorKind.RateLimited, o(429, oe("rate_limit_exceeded", "rate_limit_error")))
        assertEquals(ApiErrorKind.RateLimited, o(429, oe("slow_down")))
        assertEquals(ApiErrorKind.RateLimited, o(429, oe(null)))
    }

    @Test fun openAiStatuses() {
        assertEquals(ApiErrorKind.AuthInvalid, o(401, oe("invalid_api_key")))
        assertEquals(ApiErrorKind.PermissionDenied, o(403, oe(null)))
        assertEquals(ApiErrorKind.ServerError, o(500, oe(null)))
        assertEquals(ApiErrorKind.Overloaded, o(503, oe("server_is_overloaded", "service_unavailable_error")))
        assertEquals(ApiErrorKind.ModelNotFound, o(404, oe("model_not_found")))
        assertEquals(ApiErrorKind.ContextTooLong, o(400, oe("context_length_exceeded")))
        assertEquals(ApiErrorKind.InvalidRequest, o(400, oe("invalid_value")))
    }

    // --- Gemini ---
    private fun ge(code: String, msg: String = "m", status: String? = null) =
        """{"error":{"code":"$code","message":"$msg"${status?.let { ",\"status\":\"$it\"" } ?: ""}}}"""

    @Test fun geminiTable() {
        assertEquals(ApiErrorKind.BillingRequired, g(400, ge("failed_precondition")))
        assertEquals(ApiErrorKind.InvalidRequest, g(400, ge("invalid_request")))
        assertEquals(ApiErrorKind.InvalidRequest, g(400, ge("parameter_unknown")))
        assertEquals(ApiErrorKind.AuthInvalid, g(401, ge("authentication")))
        assertEquals(ApiErrorKind.BillingRequired, g(402, ge("payment_required")))
        assertEquals(ApiErrorKind.PermissionDenied, g(403, ge("permission_denied")))
        assertEquals(ApiErrorKind.ModelNotFound, g(404, ge("model_not_found")))
        assertEquals(ApiErrorKind.QuotaExceeded, g(429, ge("quota_exceeded")))
        assertEquals(ApiErrorKind.RateLimited, g(429, ge("rate_limit_exceeded")))
        assertEquals(ApiErrorKind.RateLimited, g(429, ge("too_many_requests")))
        assertEquals(ApiErrorKind.ServerError, g(500, ge("api_error")))
        assertEquals(ApiErrorKind.Overloaded, g(503, ge("service_unavailable")))
        assertEquals(ApiErrorKind.Timeout, g(504, ge("deadline_exceeded")))
    }

    @Test fun geminiResourceExhausted() {
        // 数値 code とアレイ包みの形(OpenAI 互換エンドポイントで返りうる形)
        val daily = """[{"error":{"code":429,"message":"Quota exceeded for requests per day","status":"RESOURCE_EXHAUSTED"}}]"""
        assertEquals(ApiErrorKind.QuotaExceeded, g(429, daily))
        val perMin = """[{"error":{"code":429,"message":"Too many requests","status":"RESOURCE_EXHAUSTED"}}]"""
        assertEquals(ApiErrorKind.RateLimited, g(429, perMin))
    }

    // --- 共通 ---
    @Test fun unknownCodesAndBadBodiesDoNotCrash() {
        assertEquals(ApiErrorKind.Unknown, o(418, "{}"))
        assertEquals(ApiErrorKind.ServerError, o(502, "<html>bad gateway</html>"))
        assertEquals(ApiErrorKind.AuthInvalid, o(401, ""))
        assertEquals(ApiErrorKind.Unknown, a(0, "not json"))
        assertEquals(ApiErrorKind.RateLimited, o(429, "not json"))
        assertEquals(ApiErrorKind.InvalidRequest, a(400, "[]"))
    }

    @Test fun streamErrorsWithoutStatus() {
        assertEquals(ApiErrorKind.Overloaded, anth.classify(0, emptyMap(), ae("overloaded_error")).kind)
        assertEquals(ApiErrorKind.RateLimited, oai.classify(0, emptyMap(), oe("rate_limit_exceeded")).kind)
        assertNull(anth.classify(0, emptyMap(), ae("overloaded_error")).httpStatus)
    }

    @Test fun retryAfterParsing() {
        assertEquals(12L, ApiErrorClassifier.retryAfterSec(mapOf("Retry-After" to " 12 ")))
        assertNull(ApiErrorClassifier.retryAfterSec(mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertNull(ApiErrorClassifier.retryAfterSec(emptyMap()))
    }

    @Test fun safeMessageHidesKeysAndTruncates() {
        val m = ApiErrorClassifier.safeMessage("bad key sk-abcdefghijklmnopqrstuvwxyz0123 and AIzaSyA1234567890abcdefghij")!!
        assertFalse("sk-abc" in m)
        assertFalse("AIza" in m)
        assertTrue("••••" in m)
        assertEquals(501, ApiErrorClassifier.safeMessage("a b ".repeat(300))!!.length)
        assertNull(ApiErrorClassifier.safeMessage("  "))
    }

    @Test fun clientExceptions() {
        assertEquals(ApiErrorKind.Network, ApiErrorClassifier.fromException(UnknownHostException(), false).kind)
        assertEquals(ApiErrorKind.Network, ApiErrorClassifier.fromException(SSLException("x"), false).kind)
        assertEquals(ApiErrorKind.Timeout, ApiErrorClassifier.fromException(SocketTimeoutException(), false).kind)
        assertEquals(ApiErrorKind.Timeout, ApiErrorClassifier.fromException(SocketTimeoutException(), true).kind)
        assertEquals(ApiErrorKind.StreamCut, ApiErrorClassifier.fromException(IOException(), true).kind)
        assertEquals(ApiErrorKind.Unknown, ApiErrorClassifier.fromException(IllegalStateException(), false).kind)
    }

    @Test fun retriableKinds() {
        assertTrue(ApiErrorClassifier.isRetriable(ApiErrorKind.RateLimited))
        assertTrue(ApiErrorClassifier.isRetriable(ApiErrorKind.Overloaded))
        assertTrue(ApiErrorClassifier.isRetriable(ApiErrorKind.ServerError))
        for (k in listOf(ApiErrorKind.BillingRequired, ApiErrorKind.SpendLimit, ApiErrorKind.QuotaExceeded,
            ApiErrorKind.AuthInvalid, ApiErrorKind.PermissionDenied, ApiErrorKind.InvalidRequest, ApiErrorKind.ModelNotFound)) {
            assertFalse(k.name, ApiErrorClassifier.isRetriable(k))
        }
    }
}
