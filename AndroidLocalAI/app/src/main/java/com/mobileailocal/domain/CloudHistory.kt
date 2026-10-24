package com.mobileailocal.domain

/** クラウドへ送る入力の文字数の目安(トークン換算はしない。設計書 6.2-5、O3)。 */
const val CLOUD_MAX_INPUT_CHARS = 60_000

/**
 * 送る履歴を maxChars 文字以下に絞る。
 * System(先頭)と最新の発言は必ず残し、古い発言から落とす。落としたかどうかを second で返す。
 * 落とした後は、先頭が Assistant にならないように、さらに 1 件落とす(User から始める)。
 */
fun trimForCloud(messages: List<ChatMessage>, maxChars: Int): Pair<List<ChatMessage>, Boolean> {
    if (messages.isEmpty()) return messages to false
    val system = messages.takeWhile { it.role == Role.System }
    val rest = messages.drop(system.size)
    if (rest.isEmpty()) return messages to false
    var total = messages.sumOf { it.content.length }
    if (total <= maxChars) return messages to false
    var start = 0
    // 最新の 1 件は必ず残す
    while (start < rest.size - 1 && total > maxChars) {
        total -= rest[start].content.length
        start++
    }
    while (start < rest.size - 1 && rest[start].role == Role.Assistant) {
        start++
    }
    return (system + rest.drop(start)) to (start > 0)
}

/** 履歴の上限を半分にする(ContextTooLong のとき 1 回だけ使う)。下限は 2000 文字。 */
fun halvedLimit(maxChars: Int): Int = (maxChars / 2).coerceAtLeast(2000)

/**
 * クラウドへ送る前の整形。System 以外の空(空白のみ)のメッセージを除き、同じ役割が続いたら改行 2 つで連結する
 * (失敗した返答が空のまま履歴に残っていると、同じ役割が連続して拒否されるため)。
 */
fun normalizeForCloud(messages: List<ChatMessage>): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    for (m in messages) {
        if (m.role != Role.System && m.content.isBlank()) continue
        val last = out.lastOrNull()
        if (last != null && last.role == m.role && m.role != Role.System) {
            out[out.lastIndex] = last.copy(content = last.content + "\n\n" + m.content)
        } else {
            out += m
        }
    }
    return out
}

/**
 * システム指示(System ロール)を受け付けないモデル向けの整形。System を取り除き、最初の User メッセージの前置きに畳み込む。
 * 最初のやり取りが Assistant から始まるとき(古い発言を落とした後など)は、システム文だけの User を先頭に足す。
 * User/Assistant の交互は崩さない(normalizeForCloud の後に呼ぶこと)。
 */
fun foldSystemIntoUser(messages: List<ChatMessage>): List<ChatMessage> {
    val system = messages.filter { it.role == Role.System }.map { it.content }.filter { it.isNotBlank() }
    if (system.isEmpty()) return messages.filter { it.role != Role.System }
    val prefix = system.joinToString("\n\n")
    val rest = messages.filter { it.role != Role.System }
    val first = rest.firstOrNull()
    return when {
        first == null -> listOf(ChatMessage(Role.User, prefix))
        first.role == Role.User -> listOf(first.copy(content = prefix + "\n\n" + first.content)) + rest.drop(1)
        else -> listOf(ChatMessage(Role.User, prefix)) + rest
    }
}

private val SYSTEM_WORDS = listOf("developer instruction", "system instruction", "system role", "system message", "system prompt")
private val REJECT_WORDS = listOf("not enabled", "not supported", "unsupported", "not allowed", "does not support", "doesn't support", "invalid")

/** 提供側のエラー文が「システム(developer)指示を受け付けないモデル」を示しているか。 */
fun rejectsSystemRole(providerMessage: String?): Boolean {
    val m = providerMessage?.lowercase() ?: return false
    return SYSTEM_WORDS.any { it in m } && REJECT_WORDS.any { it in m }
}
