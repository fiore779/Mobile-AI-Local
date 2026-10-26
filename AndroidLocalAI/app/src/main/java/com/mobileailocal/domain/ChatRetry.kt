package com.mobileailocal.domain

/**
 * 再試行で残す履歴。messages(System を除く、表示順)のうち、最後の(本物の)User 発言までを返す。
 * User 発言が1つも無ければ null。
 */
fun historyForRetry(messages: List<ChatMessage>): List<ChatMessage>? {
    // ツール結果の user 行は本物の利用者の発言ではない(再試行は本物の最後の発言から)
    val i = messages.indexOfLast { it.role == Role.User && !ToolResult.isResult(it.content) }
    return if (i < 0) null else messages.subList(0, i + 1).toList()
}
