package com.mobileailocal.domain

import com.mobileailocal.R
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI 互換(/chat/completions)。OpenAI・Gemini の互換エンドポイント・カスタムで共用する。 */
class OpenAiCompatProvider(
    override val type: ProviderType,
    override val defaultBaseUrl: String,
    override val keyIssueUrl: String?,
    override val helpTextRes: Int,
) : CloudProvider {

    companion object {
        fun openAi() = OpenAiCompatProvider(
            ProviderType.OpenAi, "https://api.openai.com/v1",
            "https://platform.openai.com/settings/organization/api-keys", R.string.cloud_help_openai,
        )
        fun gemini() = OpenAiCompatProvider(
            ProviderType.Gemini, "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://aistudio.google.com/apikey", R.string.cloud_help_gemini,
        )
        fun custom() = OpenAiCompatProvider(
            ProviderType.OpenAiCompatCustom, "", null, R.string.cloud_help_custom,
        )

        private val BILLING_CODES = setOf("credit_balance_exhausted", "payment_required", "failed_precondition")
        private val SPEND_CODES = setOf(
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded",
        )
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override fun buildStreamRequest(req: CloudRequest): HttpRequestSpec {
        val msgs = JSONArray()
        for (m in req.messages) {
            val role = when (m.role) { Role.System -> "system"; Role.User -> "user"; Role.Assistant -> "assistant" }
            msgs.put(JSONObject().put("role", role).put("content", m.content))
        }
        val body = JSONObject().put("model", req.model).put("stream", true).put("messages", msgs)
        req.params.temperature?.let { body.put("temperature", it.toDouble()) }
        req.params.topP?.let { body.put("top_p", it.toDouble()) }
        // 推論量は Gemini の OpenAI 互換のプリセットだけが使う(公式で minimal / low / medium / high の対応を確認)
        req.params.reasoningEffort?.let { body.put("reasoning_effort", it) }
        body.put(if (req.params.useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", req.params.maxTokens)
        return HttpRequestSpec(
            url = req.baseUrl.trimEnd('/') + "/chat/completions",
            method = "POST",
            headers = authHeaders(req.apiKey) + mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
            body = body.toString(),
        )
    }

    override fun parseEvent(event: String?, data: String): StreamEvent? {
        val d = data.trim()
        if (d == "[DONE]") return StreamEvent.Done
        val json = try { JSONObject(d) } catch (_: Exception) { return StreamEvent.Unreadable }
        if (json.has("error") && !json.isNull("error")) {
            return StreamEvent.Failure(classify(0, emptyMap(), d))
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val text = choice.optJSONObject("delta")?.let { if (it.isNull("content")) null else it.optString("content") }
        if (!text.isNullOrEmpty()) return StreamEvent.Text(text)
        if (choice.optString("finish_reason") == "content_filter") {
            return StreamEvent.Failure(ApiError(ApiErrorKind.ContentBlocked, providerCode = "content_filter"))
        }
        if (choice.optString("finish_reason") == "length") return StreamEvent.MaxTokens
        return null
    }

    override fun buildListModelsRequest(baseUrl: String, apiKey: String) = HttpRequestSpec(
        url = baseUrl.trimEnd('/') + "/models", method = "GET",
        headers = authHeaders(apiKey) + mapOf("Accept" to "application/json"), body = null,
    )

    override fun parseModelList(body: String): List<String> = try {
        val root = JSONObject(body)
        val out = mutableListOf<String>()
        root.optJSONArray("data")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        // Gemini のネイティブ形式("models": [{"name": "models/xxx"}])にも対応
        root.optJSONArray("models")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        out.map { it.removePrefix("models/") }.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }

    /** 優先順位: 課金・上限コード → insufficient_quota/quota_exceeded → 一般の 429 → HTTP 状態。 */
    override fun classify(status: Int, headers: Map<String, String>, body: String): ApiError {
        val p = ApiErrorClassifier.parseBody(body)
        val code = p.code?.lowercase()
        val type = p.type?.lowercase()
        val msg = p.message?.lowercase().orEmpty()
        val kind = when {
            code in BILLING_CODES -> ApiErrorKind.BillingRequired
            code in SPEND_CODES -> ApiErrorKind.SpendLimit
            type == "insufficient_quota" || code == "insufficient_quota" || code == "quota_exceeded" -> ApiErrorKind.QuotaExceeded
            code == "model_not_found" -> ApiErrorKind.ModelNotFound
            code == "context_length_exceeded" -> ApiErrorKind.ContextTooLong
            status == 429 && p.statusText.equals("RESOURCE_EXHAUSTED", true) &&
                ("per day" in msg || "daily" in msg) -> ApiErrorKind.QuotaExceeded
            status == 400 && ("context length" in msg || "maximum context" in msg) -> ApiErrorKind.ContextTooLong
            status == 0 -> when {
                // 200 のストリームの途中のエラー(HTTP 状態なし)。コード・種別から決める
                code == "rate_limit_exceeded" || type == "rate_limit_error" || code == "slow_down" -> ApiErrorKind.RateLimited
                code == "server_is_overloaded" || type == "service_unavailable_error" -> ApiErrorKind.Overloaded
                type == "server_error" || code == "server_error" -> ApiErrorKind.ServerError
                else -> ApiErrorKind.Unknown
            }
            else -> ApiErrorClassifier.byStatus(status)
        }
        return ApiErrorClassifier.build(kind, status, headers, p)
    }
}
