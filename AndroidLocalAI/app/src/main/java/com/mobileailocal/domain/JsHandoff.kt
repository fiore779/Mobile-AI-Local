package com.mobileailocal.domain

/**
 * 実行結果を、次のターンでモデルに渡すテキストに組み立てる(設計書 4.4)。
 * ラベルは英語固定(コード内の定数。strings.xml には入れない)。切り詰めが起きたかどうかも返す。
 */
object JsHandoff {
    private const val HEADER = "[JavaScript execution result]"
    private const val LABEL_STATUS = "status:"
    private const val LABEL_TIME = "time:"
    private const val LABEL_RETURN = "return value:"
    private const val LABEL_CONSOLE = "console:"
    private const val LABEL_ERROR = "error:"
    private const val LABEL_TRUNCATED = "...[truncated"
    private const val LABEL_OUTPUT_TRUNCATED = "[output truncated]"

    /** モデルに渡してよい文字数。コンテキスト長の 1/4、512〜3000 の範囲に収める。 */
    fun budgetChars(contextLength: Int): Int = (contextLength / 4).coerceIn(512, 3000)

    /** 各セクションの本文の上限(整数除算)。 */
    private fun limit(budgetChars: Int, fourths: Int): Int = budgetChars / fourths

    /** 先頭側を残して切り詰め、末尾に切り捨てた文字数を添える。切り詰めたら true。 */
    private fun clamp(body: String, limit: Int): Pair<String, Boolean> {
        if (body.length <= limit) return body to false
        val dropped = body.length - limit
        return body.take(limit) + "\n$LABEL_TRUNCATED $dropped chars]" to true
    }

    private fun statusLabel(status: JsRunStatus): String = when (status) {
        JsRunStatus.Ok -> "ok"
        JsRunStatus.Error -> "error"
        JsRunStatus.Timeout -> "timeout"
        JsRunStatus.Crashed -> "crashed"
        JsRunStatus.Killed -> "cancelled"
    }

    /** (モデルに渡すテキスト, 切り詰めが起きたか)。無いセクションはラベルごと省略する。 */
    fun format(result: JsRunResult, budgetChars: Int): Pair<String, Boolean> {
        val lines = mutableListOf(HEADER, "$LABEL_STATUS ${statusLabel(result.status)}", "$LABEL_TIME ${result.elapsedMs} ms")
        var truncated = false

        fun section(label: String, body: String?, limit: Int) {
            if (body == null || body.isEmpty()) return
            val (clamped, cut) = clamp(body, limit)
            if (cut) truncated = true
            lines += label
            lines += clamped
        }

        // console は consoleTruncated のときだけ [output truncated] の行を足す(上限の外に足す)
        section(LABEL_RETURN, result.returnValue, limit(budgetChars, 4))
        if (result.console.isNotEmpty()) {
            val (clamped, cut) = clamp(result.console, limit(budgetChars, 2))
            if (cut) truncated = true
            var body = clamped
            if (result.consoleTruncated && !body.endsWith(LABEL_OUTPUT_TRUNCATED)) body += "\n$LABEL_OUTPUT_TRUNCATED"
            lines += LABEL_CONSOLE
            lines += body
        }
        section(LABEL_ERROR, result.error, limit(budgetChars, 4))
        return lines.joinToString("\n") to truncated
    }
}
