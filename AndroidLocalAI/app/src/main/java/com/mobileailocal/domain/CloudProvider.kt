package com.mobileailocal.domain

import androidx.annotation.StringRes

interface CloudProvider {
    val type: ProviderType
    val defaultBaseUrl: String
    /** ブラウザで開くキー発行ページ。null なら開く操作を出さない(Custom)。 */
    val keyIssueUrl: String?
    /** 画面に出す短い説明(キーの作り方・課金の注意)の文字列リソース id。 */
    @get:StringRes val helpTextRes: Int

    /** ストリーミング呼び出しのリクエスト(URL・ヘッダー・本文)を作る。純粋関数。 */
    fun buildStreamRequest(req: CloudRequest): HttpRequestSpec
    /** SSE の 1 イベントを解釈する。無視するイベントは null。純粋関数。 */
    fun parseEvent(event: String?, data: String): StreamEvent?
    /** 接続テストと、モデル一覧取得のリクエスト。 */
    fun buildListModelsRequest(baseUrl: String, apiKey: String): HttpRequestSpec
    fun parseModelList(body: String): List<String>
    /** 失敗した HTTP 応答を分類する。純粋関数。 */
    fun classify(status: Int, headers: Map<String, String>, body: String): ApiError
}

/** 種別 → 実装のレジストリ。プロバイダーを足すときはここに登録する。 */
object CloudProviders {
    private val map: Map<ProviderType, CloudProvider> = listOf(
        AnthropicProvider(),
        OpenAiCompatProvider.openAi(),
        OpenAiCompatProvider.gemini(),
        OpenAiCompatProvider.custom(),
    ).associateBy { it.type }

    fun of(type: ProviderType): CloudProvider = map.getValue(type)
    fun all(): List<CloudProvider> = ProviderType.entries.mapNotNull { map[it] }
}
