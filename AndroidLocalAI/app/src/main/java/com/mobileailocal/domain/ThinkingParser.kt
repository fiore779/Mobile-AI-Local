package com.mobileailocal.domain

/** AI の出力を「思考過程」と「回答」に分けたもの。thinking が null なら思考ブロックなし。 */
data class ParsedReply(
    val thinking: String?,
    val answer: String,
    /** 思考が終わった(</think> が出た)か。false の間は思考中。 */
    val thinkingDone: Boolean,
)

object ThinkingParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun parse(content: String): ParsedReply {
        val start = content.indexOf(OPEN)
        if (start < 0) return ParsedReply(null, content, true)
        val before = content.substring(0, start)
        val rest = content.substring(start + OPEN.length)
        val end = rest.indexOf(CLOSE)
        if (end < 0) return ParsedReply(rest.trim(), before.trim(), false)
        val thinking = rest.substring(0, end).trim()
        val answer = (before + rest.substring(end + CLOSE.length)).trim()
        return ParsedReply(thinking, answer, true)
    }

    /** 次の質問と一緒にモデルへ返すときは、思考過程を除いて回答だけにする。 */
    fun answerOf(content: String): String = parse(content).answer
}
