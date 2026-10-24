package com.mobileailocal.domain

/** 行内の装飾。 */
sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Code(val text: String) : MdInline
}

/** 行ごとのブロック。 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    /** 箇条書き(`-` `*` `+` または `1.`)。marker は表示する印、depth は字下げの段。 */
    data class Bullet(val marker: String, val depth: Int, val inlines: List<MdInline>) : MdBlock
    data class Para(val inlines: List<MdInline>) : MdBlock
    data object Blank : MdBlock
}

/**
 * 回答に使われる最小限の Markdown(見出し・箇条書き・太字・インラインコード)を解釈する。
 * コードブロック(```)は CodeBlockParser が先に切り出すので、ここでは扱わない。
 * 表・リンク・斜体・入れ子の装飾は扱わず、そのまま文字で出す。閉じていない印も文字のまま(生成途中でも崩れない)。
 */
object MarkdownLite {
    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")

    fun parse(text: String): List<MdBlock> =
        text.split("\r\n", "\n", "\r").map { line ->
            when {
                line.isBlank() -> MdBlock.Blank
                heading.matches(line) -> heading.matchEntire(line)!!.let { MdBlock.Heading(it.groupValues[1].length, inline(it.groupValues[2].trim())) }
                bullet.matches(line) -> bullet.matchEntire(line)!!.let { m ->
                    val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                    MdBlock.Bullet(marker, (m.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(4), inline(m.groupValues[3]))
                }
                else -> MdBlock.Para(inline(line))
            }
        }

    /** `**太字**` と `` `コード` `` を取り出す。対になる印が無ければ文字のまま。 */
    fun inline(s: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += MdInline.Plain(plain.toString()); plain.setLength(0) } }
        var i = 0
        while (i < s.length) {
            if (s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) { flush(); out += MdInline.Bold(s.substring(i + 2, end)); i = end + 2; continue }
            }
            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { flush(); out += MdInline.Code(s.substring(i + 1, end)); i = end + 1; continue }
            }
            plain.append(s[i]); i++
        }
        flush()
        return out
    }
}
