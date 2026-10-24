package com.mobileailocal.domain

/**
 * モデル一覧から、チャット(文章生成)に使えそうにないモデルを見分ける(名前による判定)。
 * 名前を英数字の区切り(- _ . : / 空白)で分け、下の語のどれかに一致するものを除く。
 * 部分一致にしないのは、別の語の一部(例: "olive")を誤って除かないため。
 */
object ModelFilter {
    private val NON_CHAT_TOKENS = setOf(
        "tts", "speech", "audio", "transcribe", "whisper", "realtime", "live",
        "image", "imagen", "dall", "veo", "sora",
        "embedding", "embeddings", "embed", "aqa", "moderation", "rerank",
    )
    private val splitter = Regex("[^a-z0-9]+")

    /** チャットに使えそうなモデル名か。迷うものは true(除外しすぎない)。 */
    fun isChatModel(name: String): Boolean {
        val tokens = name.lowercase().split(splitter).filter { it.isNotEmpty() }
        return tokens.none { it in NON_CHAT_TOKENS }
    }

    /** 初期の選択肢にするモデル。showAll なら全部、そうでなければチャット向けと、すでに選んだもの。 */
    fun visible(all: List<String>, selected: Set<String>, showAll: Boolean): List<String> =
        if (showAll) all else all.filter { isChatModel(it) || it in selected }
}
