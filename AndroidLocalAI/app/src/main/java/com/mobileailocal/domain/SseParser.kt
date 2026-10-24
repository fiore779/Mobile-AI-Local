package com.mobileailocal.domain

/** SSE の 1 イベント。event は名前つきのときだけ。data は複数行を改行で連結したもの。 */
data class SseEvent(val event: String?, val data: String)

/**
 * SSE を解釈する(純粋なロジック)。行ごとに line() に渡すか、受信した断片を feed() に渡す。
 * 空行でイベントが確定する。`:` で始まる行(コメント)は捨てる。CRLF は行末の CR を除く。
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private val pending = StringBuilder()

    /** 1 行(改行なし)を渡す。イベントが確定したら返す。 */
    fun line(raw: String): SseEvent? {
        val l = raw.removeSuffix("\r")
        if (l.isEmpty()) return flush()
        if (l.startsWith(":")) return null
        val i = l.indexOf(':')
        val field = if (i < 0) l else l.substring(0, i)
        var value = if (i < 0) "" else l.substring(i + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** 受信した文字列の断片(行の途中で切れていてもよい)を渡す。確定したイベントを順に返す。 */
    fun feed(chunk: String): List<SseEvent> {
        pending.append(chunk)
        val out = mutableListOf<SseEvent>()
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0) break
            val line = pending.substring(0, nl)
            pending.delete(0, nl + 1)
            line(line)?.let { out += it }
        }
        return out
    }

    /** ストリームの終わり。確定していない最後のイベントがあれば返す。 */
    fun finish(): SseEvent? {
        if (pending.isNotEmpty()) {
            val l = pending.toString()
            pending.setLength(0)
            line(l)?.let { return it }
        }
        return flush()
    }

    private fun flush(): SseEvent? {
        val ev = if (hasData) SseEvent(event, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return ev
    }
}
