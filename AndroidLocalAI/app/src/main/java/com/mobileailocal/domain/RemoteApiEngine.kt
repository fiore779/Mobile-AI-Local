package com.mobileailocal.domain

import com.mobileailocal.data.CloudHttp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.Collections

/** 連携情報とキーの取り出し口(RemoteApiEngine が使う)。テストで差し替えられるようにインターフェースにしてある。 */
interface CloudCredentials {
    suspend fun integration(id: String): CloudIntegration?
    /** キーが無い・復号できないなら null。 */
    suspend fun apiKey(integrationId: String): String?
}

/** 自動再送の待ち(ミリ秒)。attempt は 0 始まりの再送回数。Retry-After があれば優先し、上限 30 秒。 */
fun defaultRetryDelayMs(attempt: Int, retryAfterSec: Long?): Long =
    if (retryAfterSec != null) minOf(retryAfterSec, 30L) * 1000L else 2000L * (attempt + 1)

/**
 * クラウドの InferenceEngine。選択中のクラウドモデルへ HTTPS で問い合わせ、文字を流す。
 * 失敗は ApiError を投げる(CancellationException はそのまま)。
 */
class RemoteApiEngine(
    private val credentials: CloudCredentials,
    private val http: CloudHttp,
    private val retryDelayMs: (attempt: Int, retryAfterSec: Long?) -> Long = ::defaultRetryDelayMs,
    private val providerOf: (ProviderType) -> CloudProvider = CloudProviders::of,
) : InferenceEngine {

    companion object {
        const val MAX_RETRIES = 2
        const val MAX_ERROR_BODY = 64 * 1024
        const val MAX_UNREADABLE = 20
    }

    @Volatile var current: CloudModelRef? = null

    // temperature/top_p を拒否された「連携 + モデル」(プロセスの間だけ覚える。永続化しない)
    private val noSampling: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // max_tokens を拒否された(max_completion_tokens を使う)「連携 + モデル」
    private val completionTokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 直近の応答が最大トークン数で打ち切られたか(generate の開始で false に戻る)。画面の注記に使う。 */
    @Volatile var truncated: Boolean = false
        private set

    override val isLoaded: Boolean get() = current != null

    override suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit) {
        throw UnsupportedOperationException("RemoteApiEngine は load を使わない(current を設定する)")
    }

    override suspend fun unload() { current = null }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<String> = flow {
        truncated = false
        val ref = current ?: throw ApiError(ApiErrorKind.Unknown, providerMessage = "no cloud model selected")
        val integration = credentials.integration(ref.integrationId)
            ?: throw ApiError(ApiErrorKind.Unknown, providerMessage = "integration not found")
        val provider = providerOf(integration.type)
        val key = credentials.apiKey(ref.integrationId)
            ?: if (integration.type == ProviderType.OpenAiCompatCustom) "" else throw ApiError(ApiErrorKind.AuthInvalid)
        val memo = ref.integrationId + "/" + ref.model

        var received = false
        var retries = 0
        var sentSamplingRetry = false
        var sentTokensRetry = false
        while (true) {
            val req = CloudRequest(
                baseUrl = integration.baseUrl,
                apiKey = key,
                model = ref.model,
                messages = messages,
                params = CloudParams(
                    temperature = params.temperature.takeIf { memo !in noSampling },
                    topP = params.topP.takeIf { memo !in noSampling },
                    maxTokens = params.maxTokens,
                    useMaxCompletionTokens = memo in completionTokens,
                ),
            )
            try {
                streamOnce(provider, req) { received = true; emit(it) }
                return@flow
            } catch (e: ApiError) {
                // 文字を受け取った後は再送しない(二重課金を避ける)
                if (received) throw e
                when {
                    ApiErrorClassifier.isRetriable(e.kind) && retries < MAX_RETRIES -> {
                        delay(retryDelayMs(retries, e.retryAfterSec))
                        retries++
                    }
                    e.kind == ApiErrorKind.InvalidRequest && !sentSamplingRetry &&
                        memo !in noSampling && mentions(e, "temperature", "top_p") -> {
                        noSampling += memo
                        sentSamplingRetry = true
                    }
                    e.kind == ApiErrorKind.InvalidRequest && !sentTokensRetry &&
                        provider.type != ProviderType.Anthropic && mentions(e, "max_tokens", "max_completion_tokens") -> {
                        // 名前を入れ替えて 1 回だけ再送する
                        if (memo in completionTokens) completionTokens -= memo else completionTokens += memo
                        sentTokensRetry = true
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun mentions(e: ApiError, vararg words: String): Boolean {
        val m = (e.providerMessage ?: "").lowercase()
        return words.any { it in m }
    }

    /** 1 回の問い合わせ。文字を onText に渡し、完了すれば戻る。失敗は ApiError。 */
    private suspend fun streamOnce(provider: CloudProvider, req: CloudRequest, onText: suspend (String) -> Unit) {
        val spec = provider.buildStreamRequest(req)
        var midStream = false
        try {
            http.request(spec) { resp ->
                if (resp.status != 200) {
                    val body = resp.readText(MAX_ERROR_BODY)
                    throw provider.classify(resp.status, resp.headers, body)
                }
                midStream = true
                val sse = SseParser()
                var done = false
                var unreadable = 0
                suspend fun handle(ev: SseEvent) {
                    val se = provider.parseEvent(ev.event, ev.data)
                    // 読めない行が連続 20 行以上なら異常(途中の 1〜2 行は捨てる)
                    if (se == StreamEvent.Unreadable) {
                        if (++unreadable >= MAX_UNREADABLE) throw ApiError(ApiErrorKind.Unknown, providerCode = "unreadable_stream")
                    } else if (se != null) unreadable = 0
                    when (se) {
                        StreamEvent.Unreadable -> {}
                        is StreamEvent.Text -> onText(se.piece)
                        StreamEvent.Done -> done = true
                        StreamEvent.MaxTokens -> truncated = true
                        is StreamEvent.Failure -> throw se.error
                        null -> {}
                    }
                }
                while (!done) {
                    val line = resp.readLine() ?: break
                    sse.line(line)?.let { handle(it) }
                }
                if (!done) sse.finish()?.let { handle(it) }
                if (!done) {
                    // 取り消しによる切断は StreamCut ではなく、取り消しとして扱う
                    currentCoroutineContext().ensureActive()
                    throw ApiError(ApiErrorKind.StreamCut)
                }
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            // 取り消しによる切断は IOException になるので、取り消しならそのまま取り消しとして扱う
            currentCoroutineContext().ensureActive()
            throw ApiErrorClassifier.fromException(e, midStream)
        }
    }
}
