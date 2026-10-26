package com.mobileailocal.domain

import kotlinx.coroutines.CancellationException

/** 上限と閾値(設計書 context-and-workspace 4.2)。 */
object ContextBudget {
    /** mal_llama.cpp の `n_ctx - reserve` と同じ式。reserve = max(64, min(maxTokens, contextLength/2)) */
    fun localLimit(contextLength: Int, maxTokens: Int): Int {
        val reserve = maxOf(64, minOf(maxTokens, contextLength / 2))
        return contextLength - reserve
    }

    const val TRIGGER_RATIO = 0.70
    const val KEEP_RATIO = 0.35
    const val MIN_LOCAL_LIMIT = 768
    fun localTrigger(limit: Int): Int = (limit * TRIGGER_RATIO).toInt()
    fun localKeep(limit: Int): Int = (limit * KEEP_RATIO).toInt()
    fun localSummaryMaxTokens(limit: Int): Int = (limit * 0.15).toInt().coerceIn(96, 512)

    const val CLOUD_TRIGGER_CHARS = 45_000
    const val CLOUD_KEEP_CHARS = 15_000
    const val CLOUD_SUMMARY_MAX_TOKENS = 800
    const val CLOUD_LINE_CAP_CHARS = 8_000
    const val LOCAL_LINE_CAP_CHARS = 2_000
    /** 要約の長さの上限(ローカル: limit のこの割合のトークン / クラウド: 文字数) */
    const val SUMMARY_MAX_RATIO = 0.30
    const val CLOUD_SUMMARY_MAX_CHARS = 6_000
    /** 要約に失敗したあと、発言がこの件数増えるまで再試行しない */
    const val BACKOFF_MESSAGES = 4
    const val MAX_PASSES_LOCAL = 2
    const val MAX_PASSES_CLOUD = 1
}

/** 圧縮の計画。 */
data class CompressionPlan(
    val newSummaryCount: Int,
    val toSummarize: List<ChatMessage>,
)

/**
 * 圧縮の計画(規則は設計書 4.2)。null なら圧縮しない。
 * history は表示順の全発言(System を除く。最後は今回の User 発言)。
 */
fun planCompression(
    history: List<ChatMessage>, summaryCount: Int, fixedSize: Int,
    sizeOf: (ChatMessage) -> Int, trigger: Int, keep: Int, inputBudget: Int,
): CompressionPlan? {
    val active = history.drop(summaryCount)
    if (active.isEmpty()) return null
    val sizes = active.map(sizeOf)
    val total = fixedSize + sizes.sum()
    if (total <= trigger) return null
    // 後ろから足して keep 以下で最大になる位置 s(最新の 1 件は必ず残す)
    var s = active.size - 1
    var acc = sizes[s]
    while (s > 0 && acc + sizes[s - 1] <= keep) {
        s--
        acc += sizes[s]
    }
    // 残す先頭が Assistant なら 1 つ後ろへ(要約する側へ)。最後の発言は越えない
    while (s < active.size - 1 && active[s].role == Role.Assistant) s++
    if (s <= 0) return null
    // 要約する範囲 active[0 until s]。inputBudget に収まる最大の c
    var c = 0
    var used = 0
    while (c < s && used + sizes[c] <= inputBudget) {
        used += sizes[c]
        c++
    }
    // active[c] が User になる位置まで手前へ戻す(c == s はそのまま User/残す側の先頭なので許す)
    while (c > 0 && c < active.size && active[c].role != Role.User) c--
    if (c == 0) c = 1 // active[0] 1 件だけを(長さ上限で切って)入れる
    return CompressionPlan(summaryCount + c, active.subList(0, c))
}

/** 要約の結果。 */
sealed interface SummaryOutcome {
    data class Done(val summary: String) : SummaryOutcome
    data object Failed : SummaryOutcome
}

/**
 * 要約を 1 回作って検証する(設計書 6.2 の 5〜6、6.3)。generate は要約用メッセージから本文を返す(呼び出し側が collect する)。
 * 例外・空・長すぎ・置き換える原文以上の大きさは Failed(保存しない)。CancellationException は再送出する。
 * @param maxSummarySize 要約に許す大きさ(sizeOf 単位)
 * @param replacedSize 置き換える原文の大きさ(sizeOf 単位)
 * @param sizeOfText 要約の大きさを測る関数(ローカル: TokenEstimator.estimate、クラウド: length)
 */
suspend fun summarizeAndValidate(
    messages: List<ChatMessage>, maxSummarySize: Int, replacedSize: Int,
    sizeOfText: (String) -> Int,
    generate: suspend (List<ChatMessage>) -> String,
): SummaryOutcome {
    val raw = try {
        generate(messages)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return SummaryOutcome.Failed
    }
    val summary = ThinkingParser.answerOf(raw).trim()
    if (summary.isEmpty()) return SummaryOutcome.Failed
    val size = sizeOfText(summary)
    if (size > maxSummarySize || size >= replacedSize) return SummaryOutcome.Failed
    return SummaryOutcome.Done(summary)
}

/** 要約失敗後の抑止(6.2-2)。メモリ上だけ、会話を切り替えるとリセットする。 */
class CompressionBackoff {
    private var failedAtCount = -1

    fun reset() { failedAtCount = -1 }
    fun markFailed(messageCount: Int) { failedAtCount = messageCount }
    fun shouldSkip(messageCount: Int): Boolean =
        failedAtCount >= 0 && messageCount - failedAtCount < ContextBudget.BACKOFF_MESSAGES
}
