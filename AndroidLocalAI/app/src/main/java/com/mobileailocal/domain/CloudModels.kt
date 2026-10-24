package com.mobileailocal.domain

/** プロバイダーの種類。増やすときは、ここに値を足し、CloudProviders に実装を登録する。 */
enum class ProviderType { OpenAi, Anthropic, Gemini, OpenAiCompatCustom }

/** 1 つの連携(接続先)。キーは含まない(キーは CloudKeyStore)。 */
data class CloudIntegration(
    val id: String,                  // UUID 文字列。'/' と ':' を含まない
    val type: ProviderType,
    val displayName: String,         // 1〜40 文字
    val baseUrl: String,             // 末尾の '/' は保存時に除く
    val enabledModels: List<String>, // 1 件以上、最大 10 件
    val lastModel: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 送る生成設定。null の項目はリクエストに含めない。 */
data class CloudParams(
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int,
    /** OpenAI 互換で max_tokens を拒否されたとき、max_completion_tokens の名前で送る。 */
    val useMaxCompletionTokens: Boolean = false,
)

/** 送る内容のスナップショット。 */
data class CloudRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>, // 先頭に Role.System が 0 or 1 件。以降は User/Assistant が交互
    val params: CloudParams,
) {
    /** キーと本文をログに出さない。 */
    override fun toString(): String =
        "CloudRequest(baseUrl=$baseUrl, apiKey=••••, model=$model, messages=[${messages.size} 件], params=$params)"
}

data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
) {
    /** ヘッダー(キーを含む)と本文をログに出さない。 */
    override fun toString(): String = "HttpRequestSpec($method $url, headers=[${headers.size} 件], body=${body?.length ?: 0} 文字)"
}

/** ストリームから取り出した 1 イベント。 */
sealed interface StreamEvent {
    data class Text(val piece: String) : StreamEvent
    data object Done : StreamEvent
    /** 最大トークン数で打ち切られた(完了として扱う。Done が続く)。 */
    data object MaxTokens : StreamEvent
    /** JSON として読めない行(連続して多いと異常として扱う)。 */
    data object Unreadable : StreamEvent
    /** 200 応答の途中で届くエラー。 */
    data class Failure(val error: ApiError) : StreamEvent
}

enum class ApiErrorKind {
    AuthInvalid,
    PermissionDenied,
    BillingRequired,
    SpendLimit,
    QuotaExceeded,
    RateLimited,
    Overloaded,
    ModelNotFound,
    ContextTooLong,
    InvalidRequest,
    ContentBlocked,
    ServerError,
    Timeout,
    Network,
    StreamCut,
    Unknown,
}

class ApiError(
    val kind: ApiErrorKind,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    /** 提供側の message 原文(「詳細」でのみ表示する)。 */
    val providerMessage: String? = null,
    val retryAfterSec: Long? = null,
    val resetAt: String? = null,
    val requestId: String? = null,
) : Exception("ApiError($kind, status=$httpStatus, code=$providerCode)") {
    override fun toString(): String = message ?: "ApiError($kind)"
}

/** クラウドモデルの参照(連携 ID + モデル名)。 */
data class CloudModelRef(val integrationId: String, val model: String)

sealed interface ModelSelection {
    data class Local(val modelId: String) : ModelSelection
    data class Cloud(val ref: CloudModelRef) : ModelSelection
}

private const val CLOUD_PREFIX = "cloud:"

/** Local → modelId そのまま(後方互換)。Cloud → "cloud:" + integrationId + "/" + model。 */
fun ModelSelection.encode(): String = when (this) {
    is ModelSelection.Local -> modelId
    is ModelSelection.Cloud -> CLOUD_PREFIX + ref.integrationId + "/" + ref.model
}

/** "cloud:" で始まれば Cloud(最初の '/' で分割)。それ以外は Local。null/空、および不正な cloud: は null。 */
fun decodeModelSelection(s: String?): ModelSelection? {
    if (s.isNullOrEmpty()) return null
    if (!s.startsWith(CLOUD_PREFIX)) return ModelSelection.Local(s)
    val rest = s.removePrefix(CLOUD_PREFIX)
    val i = rest.indexOf('/')
    if (i <= 0 || i == rest.lastIndex) return null
    return ModelSelection.Cloud(CloudModelRef(rest.substring(0, i), rest.substring(i + 1)))
}
