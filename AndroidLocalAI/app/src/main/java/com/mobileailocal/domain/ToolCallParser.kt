package com.mobileailocal.domain

/**
 * 返答から作業フォルダのタグを取り出す(設計書 context-and-workspace 5.1)。
 * ThinkingParser.answerOf 済みの文字列を渡す。コードブロック(```)の中のタグは対象にしない。
 */
object ToolCallParser {
    private const val MARK = "<file_"
    private val NAMES = listOf("file_list", "file_read", "file_write")
    private const val MAX_FENCE_INDENT = 3

    /** 生成の途中で呼ぶ軽い判定。新しい断片に '>' を含み、かつ answer に "<file_" を含むときだけ true。 */
    fun mayComplete(answer: String, lastPiece: String): Boolean = '>' in lastPiece && MARK in answer

    fun find(answer: String): ToolFind {
        val fenced = fencedRanges(answer)
        var from = 0
        while (true) {
            val i = answer.indexOf(MARK, from)
            if (i < 0) return ToolFind.NoCall
            if (fenced.any { i >= it.first && i < it.last }) {
                from = i + MARK.length
                continue
            }
            return parseAt(answer, i)
        }
    }

    private fun parseAt(s: String, start: Int): ToolFind {
        var p = start + 1 // '<' の次
        val nameStart = p
        while (p < s.length && (s[p] in 'a'..'z' || s[p] == '_')) p++
        val name = s.substring(nameStart, p)
        if (name !in NAMES) {
            // 途中で切れた名前(例: "file_li")は未完成として扱う
            if (p >= s.length && NAMES.any { it.startsWith(name) }) return ToolFind.Incomplete(name)
            return ToolFind.Malformed("unknown tool")
        }
        if (p >= s.length) return ToolFind.Incomplete(name)
        if (s[p] != ' ' && s[p] != '\t' && s[p] != '\n' && s[p] != '\r' && s[p] != '/' && s[p] != '>') {
            return ToolFind.Malformed("unknown tool")
        }
        var path: String? = null
        var startAttr: String? = null
        var selfClosed = false
        // 属性
        while (true) {
            while (p < s.length && s[p].isWhitespace()) p++
            if (p >= s.length) return ToolFind.Incomplete(name)
            val c = s[p]
            if (c == '/') {
                if (p + 1 >= s.length) return ToolFind.Incomplete(name)
                if (s[p + 1] != '>') return ToolFind.Malformed("bad tag")
                p += 2
                selfClosed = true
                break
            }
            if (c == '>') { p++; break }
            val an = p
            while (p < s.length && (s[p].isLetter() || s[p] == '_')) p++
            if (p == an) return ToolFind.Malformed("bad attribute")
            val attr = s.substring(an, p)
            while (p < s.length && s[p].isWhitespace()) p++
            if (p >= s.length) return ToolFind.Incomplete(name)
            if (s[p] != '=') return ToolFind.Malformed("bad attribute")
            p++
            while (p < s.length && s[p].isWhitespace()) p++
            if (p >= s.length) return ToolFind.Incomplete(name)
            val q = s[p]
            if (q != '"' && q != '\'') return ToolFind.Malformed("bad attribute")
            val close = s.indexOf(q, p + 1)
            if (close < 0) return ToolFind.Incomplete(name)
            val value = s.substring(p + 1, close)
            p = close + 1
            when {
                attr == "path" -> path = value
                attr == "start" && name == "file_read" -> startAttr = value
                else -> return ToolFind.Malformed("unknown attribute")
            }
        }
        val closeTag = "</$name>"
        when (name) {
            "file_list", "file_read" -> {
                var end = p
                if (!selfClosed) {
                    val rest = s.substring(p).trimStart()
                    if (rest.startsWith(closeTag)) {
                        end = s.length - rest.length + closeTag.length
                    } else if (closeTag.startsWith(rest)) {
                        return ToolFind.Incomplete(name)
                    } else {
                        return ToolFind.Malformed("tag not closed")
                    }
                }
                if (name == "file_list") return ToolFind.Call(ToolCall.List(path ?: ""), end, start)
                val pth = path ?: return ToolFind.Malformed("path required")
                val st = if (startAttr == null) 0 else (startAttr.trim().toIntOrNull() ?: return ToolFind.Malformed("bad start"))
                if (st < 0) return ToolFind.Malformed("bad start")
                return ToolFind.Call(ToolCall.Read(pth, st), end, start)
            }
            else -> { // file_write
                val pth = path ?: return ToolFind.Malformed("path required")
                if (selfClosed) return ToolFind.Malformed("body required")
                val close = s.indexOf(closeTag, p)
                if (close < 0) return ToolFind.Incomplete(name)
                val body = cleanBody(s.substring(p, close))
                return ToolFind.Call(ToolCall.Write(pth, body), close + closeTag.length, start)
            }
        }
    }

    /** 本文の整形(規則 5): \r\n 統一、先頭と末尾の改行 1 つ、全体を囲む ``` を外す。 */
    internal fun cleanBody(raw: String): String {
        var b = raw.replace("\r\n", "\n")
        if (b.startsWith("\n")) b = b.substring(1)
        if (b.endsWith("\n")) b = b.substring(0, b.length - 1)
        val lines = b.split("\n")
        if (lines.size >= 2 && lines.first().trim().startsWith("```") && lines.last().trim() == "```") {
            b = lines.subList(1, lines.size - 1).joinToString("\n")
        }
        return b
    }

    /** フェンス(```)の中の範囲。CodeBlockParser と同じ規則(行頭 0〜3 個の空白 + バッククォート 3 個以上)。閉じが無ければ末尾まで。 */
    private fun fencedRanges(s: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var pos = 0
        var openAt = -1
        var openCount = 0
        while (pos <= s.length) {
            var eol = s.indexOf('\n', pos)
            if (eol < 0) eol = s.length
            val line = s.substring(pos, eol).trimEnd('\r')
            val marks = fenceMarks(line)
            if (openAt >= 0) {
                if (marks != null && marks.second.isBlank() && marks.first >= openCount) {
                    out += openAt..(eol + 1)
                    openAt = -1
                }
            } else if (marks != null && '`' !in marks.second.trim()) {
                openAt = pos
                openCount = marks.first
            }
            pos = eol + 1
        }
        if (openAt >= 0) out += openAt..(s.length + 1)
        return out
    }

    private fun fenceMarks(line: String): Pair<Int, String>? {
        var i = 0
        while (i < line.length && line[i] == ' ' && i < MAX_FENCE_INDENT) i++
        var count = 0
        var j = i
        while (j < line.length && line[j] == '`') { count++; j++ }
        if (count < 3) return null
        return count to line.substring(j)
    }
}
