package com.mobileailocal.domain

/** 回答を構成する断片。Text はそのまま表示し、Code は実行できるものだけ実行対象にする。 */
sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment

    data class Code(val lang: String, val code: String, val closed: Boolean) : MessageSegment {
        /** 閉じていて、言語が js/javascript で、中身が空白だけでないものだけ実行する */
        val runnable: Boolean get() = closed && lang in setOf("js", "javascript") && code.isNotBlank()
    }
}

/**
 * AI の回答からコードブロックを取り出す(設計書 4.3 の規則 1〜6)。
 * 行頭 0〜3 個の空白 + バッククォート 3 個以上が開き、同数以上のバッククォートだけの行が閉じる。
 * 情報文字列にバッククォートを含む行(文中の ```js``` など)は開きフェンスにしない。
 */
object CodeBlockParser {
    private const val MAX_FENCE_INDENT = 3
    private val WHITESPACE = Regex("\\s+")
    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /** 行頭 0〜3 個の空白の後にバッククォート 3 個以上。条件に合わなければ null。返す: (個数, 残り) */
    private fun fenceMarks(line: String): Pair<Int, String>? {
        var i = 0
        while (i < line.length && line[i] == ' ' && i < MAX_FENCE_INDENT) i++
        var count = 0
        var j = i
        while (j < line.length && line[j] == '`') { count++; j++ }
        if (count < 3) return null
        return count to line.substring(j)
    }

    fun parse(answer: String): List<MessageSegment> {
        val out = mutableListOf<MessageSegment>()
        val textLines = mutableListOf<String>()
        var codeLines: MutableList<String>? = null // null の間だけ文章モード
        var lang = ""
        var openCount = 0

        fun flushText() {
            val s = textLines.joinToString("\n")
            textLines.clear()
            if (s.isNotBlank()) out += MessageSegment.Text(s) // 空・空白だけの Text は出さない
        }

        for (line in answer.split(LINE_BREAK)) {
            val marks = fenceMarks(line)
            val code = codeLines
            if (code != null) {
                // 閉じ: 開きと同数以上のバッククォートだけの行(後ろは空白のみ可)
                if (marks != null && marks.second.isBlank() && marks.first >= openCount) {
                    out += MessageSegment.Code(lang, code.joinToString("\n"), true)
                    codeLines = null
                } else {
                    code += line
                }
                continue
            }
            // 開き: 情報文字列(同じ行の残り)にバッククォートを含む行は文章
            val info = marks?.second?.trim()
            if (marks == null || info == null || '`' in info) {
                textLines += line
                continue
            }
            flushText()
            openCount = marks.first
            lang = info.split(WHITESPACE).firstOrNull { it.isNotEmpty() }.orEmpty().lowercase()
            codeLines = mutableListOf()
        }
        codeLines?.let { cl ->
            if (cl.lastOrNull() == "") cl.removeAt(cl.size - 1) // 生成途中の末尾の改行は含めない
            out += MessageSegment.Code(lang, cl.joinToString("\n"), false)
        }
        flushText()
        return out
    }
}
