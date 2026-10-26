package com.mobileailocal.domain

/** ツール結果の発言の形式と判定(設計書 context-and-workspace 5.3、6.5)。英語固定。 */
object ToolResult {
    const val PREFIX = "[File tool result]\n"
    const val CLOUD_OMITTED = "[File tool result omitted: not sent to the cloud]"

    fun isResult(content: String): Boolean = content.startsWith(PREFIX)

    fun opOf(call: ToolCall): String = when (call) {
        is ToolCall.List -> "list"
        is ToolCall.Read -> "read"
        is ToolCall.Write -> "write"
    }

    fun pathOf(call: ToolCall): String = when (call) {
        is ToolCall.List -> call.path.ifEmpty { "." }
        is ToolCall.Read -> call.path
        is ToolCall.Write -> call.path
    }

    /** 結果の発言(user 行)の本文。 */
    fun format(call: ToolCall, outcome: ToolOutcome): String = when (outcome) {
        is ToolOutcome.Ok -> header(opOf(call), pathOf(call)) + "status: ok\n" + outcome.text
        is ToolOutcome.Failed ->
            header(opOf(call), pathOf(call)) + "status: error\ncode: ${outcome.code}\n" + outcome.message
        ToolOutcome.Denied -> header(opOf(call), pathOf(call)) + "status: denied by user"
    }

    /** 形式の誤り(タグが読めない)の結果。hint は直し方の 1 行。 */
    fun formatError(hint: String): String = PREFIX + "status: error\ncode: FORMAT\n" + hint

    private fun header(op: String, path: String) = PREFIX + "op: $op\npath: $path\n"

    /** 結果の状態(カード表示用)。 */
    enum class Status { Ok, Error, Denied }

    fun statusOf(content: String): Status {
        if (!isResult(content)) return Status.Error
        val line = content.lineSequence().firstOrNull { it.startsWith("status:") }?.removePrefix("status:")?.trim()
        return when {
            line == "ok" -> Status.Ok
            line != null && line.startsWith("denied") -> Status.Denied
            else -> Status.Error
        }
    }
}

object ToolResultFormat {
    /** クラウドへ送る履歴のツール結果は、本文を 1 行に置き換える(DB と画面は変えない)。 */
    fun forCloud(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { if (it.role == Role.User && ToolResult.isResult(it.content)) it.copy(content = ToolResult.CLOUD_OMITTED) else it }
}
