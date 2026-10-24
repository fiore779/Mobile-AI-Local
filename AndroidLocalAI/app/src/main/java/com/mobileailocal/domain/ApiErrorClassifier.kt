package com.mobileailocal.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/** エラー本文から取り出した項目(形が違っても読めるものだけ)。 */
data class ParsedErrorBody(
    val code: String?,
    val type: String?,
    val statusText: String?,   // Gemini の "RESOURCE_EXHAUSTED" など
    val message: String?,
    val requestId: String?,
    val details: JSONObject?,  // Anthropic の details(あれば)
)

/** プロバイダー共通のエラー分類の部品。純粋関数。 */
object ApiErrorClassifier {
    private const val MAX_MESSAGE = 500
    private val secretRegex = Regex("""(?:sk-|AIza|key-|xai-|gsk_)[A-Za-z0-9_\-]{16,}|[A-Za-z0-9_\-]{40,}""")

    /** 本文を読む。JSON でない・空でも落ちない(すべて null の結果)。{"error":{...}} と [{"error":{...}}] の両方に対応。 */
    fun parseBody(body: String): ParsedErrorBody {
        val empty = ParsedErrorBody(null, null, null, null, null, null)
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return empty
        return try {
            var root: JSONObject = when {
                trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0) ?: return empty
                else -> JSONObject(trimmed)
            }
            val err = root.optJSONObject("error")
            val src = err ?: root
            val details = src.optJSONObject("details") ?: root.optJSONObject("details")
            ParsedErrorBody(
                code = src.optAny("code") ?: details?.optAny("error_code"),
                type = src.optAny("type"),
                statusText = src.optAny("status"),
                message = src.optAny("message") ?: if (err == null) root.optAny("error") else null,
                requestId = root.optAny("request_id") ?: src.optAny("request_id"),
                details = details,
            )
        } catch (_: Exception) {
            empty
        }
    }

    private fun JSONObject.optAny(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is String -> v.takeIf { it.isNotEmpty() }
            is Number -> v.toString()
            else -> null
        }
    }

    /** Retry-After(秒の数値のみ)。ヘッダー名は大文字小文字を区別しない。日付形式は無視。 */
    fun retryAfterSec(headers: Map<String, String>): Long? {
        val v = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value ?: return null
        return v.trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    /** 提供側の message を画面に出せる形にする(長さ制限と、キーらしい長い文字列の伏せ字)。 */
    fun safeMessage(msg: String?): String? {
        if (msg.isNullOrBlank()) return null
        val cut = if (msg.length > MAX_MESSAGE) msg.take(MAX_MESSAGE) + "…" else msg
        return cut.replace(secretRegex, "••••")
    }

    /** HTTP 状態だけで決まる一般の分類(各社の個別コードで決まらなかったときの既定)。 */
    fun byStatus(status: Int): ApiErrorKind = when (status) {
        400 -> ApiErrorKind.InvalidRequest
        401 -> ApiErrorKind.AuthInvalid
        402 -> ApiErrorKind.BillingRequired
        403 -> ApiErrorKind.PermissionDenied
        404 -> ApiErrorKind.ModelNotFound
        408, 504 -> ApiErrorKind.Timeout
        413 -> ApiErrorKind.ContextTooLong
        429 -> ApiErrorKind.RateLimited
        503, 529 -> ApiErrorKind.Overloaded
        in 500..599 -> ApiErrorKind.ServerError
        else -> ApiErrorKind.Unknown
    }

    fun build(
        kind: ApiErrorKind,
        status: Int,
        headers: Map<String, String>,
        p: ParsedErrorBody,
        resetAt: String? = null,
    ) = ApiError(
        kind = kind,
        httpStatus = status.takeIf { it > 0 },
        providerCode = p.code ?: p.type,
        providerMessage = safeMessage(p.message),
        retryAfterSec = retryAfterSec(headers),
        resetAt = resetAt,
        requestId = p.requestId,
    )

    /** 再送してよい種別(最初の文字を受け取る前に限る)。 */
    fun isRetriable(kind: ApiErrorKind): Boolean =
        kind == ApiErrorKind.RateLimited || kind == ApiErrorKind.Overloaded || kind == ApiErrorKind.ServerError

    /**
     * 接続前後の通信例外をクライアント側の種別にする。
     * midStream が true なら、応答の途中の切断として StreamCut にする(時間切れは Timeout のまま)。
     */
    fun fromException(e: Throwable, midStream: Boolean): ApiError = when {
        e is ApiError -> e
        e is SocketTimeoutException -> ApiError(ApiErrorKind.Timeout)
        e is IOException && midStream -> ApiError(ApiErrorKind.StreamCut)
        e is IOException -> ApiError(ApiErrorKind.Network)
        else -> ApiError(ApiErrorKind.Unknown, providerMessage = e.javaClass.simpleName)
    }
}
