package com.mobileailocal.domain

/**
 * 例外を、画面の「詳細」に出せる文字列にする(次回から原因が分かるように)。
 * 例外クラス名とメッセージ、原因の連鎖(最大 3 段)を出す。ファイルパスは "<path>" に、渡された秘密(キーなど)は "••••" に置き換える。
 */
object ErrorDescribe {
    private val pathRegex = Regex("""[/\\][^\s'"]+""")
    private const val MAX_LINE = 300
    private const val MAX_DEPTH = 3

    fun describe(e: Throwable, secrets: List<String> = emptyList()): String {
        val lines = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < MAX_DEPTH) {
            val msg = cur.message?.let { scrub(it, secrets) }
            val line = cur.javaClass.name + if (msg.isNullOrBlank()) "" else ": $msg"
            lines += (if (depth == 0) "" else "caused by ") + line.take(MAX_LINE)
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        return lines.joinToString("\n")
    }

    private fun scrub(s: String, secrets: List<String>): String {
        var out = s
        for (sec in secrets) if (sec.length >= 4) out = out.replace(sec, "••••")
        return out.replace(pathRegex, "<path>").replace(Regex("\\s+"), " ").trim()
    }
}
