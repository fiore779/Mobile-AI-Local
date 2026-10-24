package com.mobileailocal.domain

import com.mobileailocal.R
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Messages(/v1/messages)。 */
class AnthropicProvider : CloudProvider {
    companion object {
        /** 実装時に公式ドキュメントの最新の例で確認する。 */
        const val ANTHROPIC_VERSION = "2023-06-01"
        private const val SPEND_LIMIT_CODE = "enforced_spend_limit_reached"
    }

    override val type = ProviderType.Anthropic
    override val defaultBaseUrl = "https://api.anthropic.com/v1"
    // 正確なキー画面の URL は未確認(設計書 O9)。確認できるまで Console のトップを開く
    override val keyIssueUrl: String? = "https://platform.claude.com/"
    override val helpTextRes = R.string.cloud_help_anthropic

    private fun headers(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
        "content-type" to "application/json",
    )

    override fun buildStreamRequest(req: CloudRequest): HttpRequestSpec {
        val system = req.messages.filter { it.role == Role.System }.joinToString("\n\n") { it.content }
        val msgs = JSONArray()
        for (m in req.messages) {
            when (m.role) {
                Role.User -> msgs.put(JSONObject().put("role", "user").put("content", m.content))
                Role.Assistant -> msgs.put(JSONObject().put("role", "assistant").put("content", m.content))
                Role.System -> {}
            }
        }
        val body = JSONObject().put("model", req.model).put("max_tokens", req.params.maxTokens)
            .put("stream", true).put("messages", msgs)
        if (system.isNotEmpty()) body.put("system", system)
        // 温度は 0.0〜1.0 に収める。top_p は送らない(温度との併用の可否が未確認。設計書 9 章)
        req.params.temperature?.let { body.put("temperature", it.coerceIn(0f, 1f).toDouble()) }
        return HttpRequestSpec(
            url = req.baseUrl.trimEnd('/') + "/messages",
            method = "POST",
            headers = headers(req.apiKey) + ("accept" to "text/event-stream"),
            body = body.toString(),
        )
    }

    override fun parseEvent(event: String?, data: String): StreamEvent? {
        val json = try { JSONObject(data) } catch (_: Exception) { return StreamEvent.Unreadable }
        return when (event ?: json.optString("type")) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return null
                if (delta.optString("type") == "text_delta") delta.optString("text").takeIf { it.isNotEmpty() }
                    ?.let { StreamEvent.Text(it) } else null // thinking_delta・input_json_delta は捨てる
            }
            "message_delta" -> when (json.optJSONObject("delta")?.optString("stop_reason")) {
                "refusal" -> StreamEvent.Failure(ApiError(ApiErrorKind.ContentBlocked, providerCode = "refusal"))
                "max_tokens" -> StreamEvent.MaxTokens
                else -> null
            }
            "message_stop" -> StreamEvent.Done
            "error" -> StreamEvent.Failure(classify(0, emptyMap(), data))
            else -> null // ping・message_start・未知のイベントは無視
        }
    }

    override fun buildListModelsRequest(baseUrl: String, apiKey: String) = HttpRequestSpec(
        url = baseUrl.trimEnd('/') + "/models", method = "GET",
        headers = headers(apiKey) - "content-type", body = null,
    )

    override fun parseModelList(body: String): List<String> = try {
        val a = JSONObject(body).optJSONArray("data")
        val out = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(out::add)
        out
    } catch (_: Exception) {
        emptyList()
    }

    override fun classify(status: Int, headers: Map<String, String>, body: String): ApiError {
        val p = ApiErrorClassifier.parseBody(body)
        val errorCode = p.details?.optString("error_code")?.takeIf { it.isNotEmpty() }
        val msg = p.message?.lowercase().orEmpty()
        val resetAt = p.details?.keys()?.asSequence()?.firstOrNull { it.contains("reset", true) }
            ?.let { p.details.optString(it) }?.takeIf { it.isNotEmpty() }
        val spendByMessage = "spend" in msg || "usage limit" in msg || "usage_limit" in msg
        val kind = when {
            errorCode == SPEND_LIMIT_CODE -> ApiErrorKind.SpendLimit
            status == 429 && ApiErrorClassifier.retryAfterSec(headers) == null && "spend" in msg -> ApiErrorKind.SpendLimit
            // 設定した使用上限に達したときは 400(公式)。文言は未確認なので語で判定する
            status == 400 && spendByMessage -> ApiErrorKind.SpendLimit
            status == 400 && ("prompt is too long" in msg || "too long" in msg) -> ApiErrorKind.ContextTooLong
            status == 0 -> when (p.type?.lowercase() ?: p.code?.lowercase()) {
                "overloaded_error" -> ApiErrorKind.Overloaded
                "rate_limit_error" -> ApiErrorKind.RateLimited
                "api_error" -> ApiErrorKind.ServerError
                "timeout_error" -> ApiErrorKind.Timeout
                "authentication_error" -> ApiErrorKind.AuthInvalid
                "billing_error" -> ApiErrorKind.BillingRequired
                "permission_error" -> ApiErrorKind.PermissionDenied
                "invalid_request_error" -> ApiErrorKind.InvalidRequest
                else -> ApiErrorKind.Unknown
            }
            else -> ApiErrorClassifier.byStatus(status)
        }
        return ApiErrorClassifier.build(kind, status, headers, p, resetAt = resetAt)
    }
}
