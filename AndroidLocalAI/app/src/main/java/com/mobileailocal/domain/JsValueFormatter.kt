package com.mobileailocal.domain

import java.math.BigDecimal

/**
 * quickjs-kt が返す値を、モデルに見せる文字列にする(設計書 4.5)。
 * トップレベルの文字列だけそのまま返し、入れ子の文字列は JSON の文字列としてダブルクォートで囲む。
 * 組み立てる文字列には上限(MAX_CHARS)があり、超えたら打ち切る(巨大な配列で本体のメモリを使わないため)。
 * Android 固有のクラスは使わない(JVM の単体テストで動くこと)。
 */
@OptIn(ExperimentalUnsignedTypes::class)
object JsValueFormatter {
    private const val MAX_DEPTH = 8 // トップレベルのコンテナを深さ 1。これを超えた位置のコンテナは […]
    private const val CIRCULAR = "[Circular]"
    private const val TOO_DEEP = "[…]"
    private const val FORM_FEED = '\u000C'

    /** 組み立てる文字数の上限。最終的な切り詰め(MAX_RETURN_CHARS + 目印)は呼び出し側が行うので、それより少し大きくする */
    const val MAX_CHARS = JsLimits.MAX_RETURN_CHARS + 1

    /** 最後の式の値。undefined / null / 無しは null。MAX_CHARS を超える分は切り捨てる。 */
    fun formatReturn(v: Any?): String? {
        if (v == null) return null
        if (v is String) return if (v.length > MAX_CHARS) v.substring(0, MAX_CHARS) else v
        val sb = StringBuilder()
        write(sb, v, 1, emptyList())
        if (sb.length > MAX_CHARS) sb.setLength(MAX_CHARS)
        return sb.toString()
    }

    private fun full(sb: StringBuilder) = sb.length > MAX_CHARS

    /**
     * 入れ子の値を sb に書く。depth はこの値の深さ(トップレベルのコンテナは 1)。
     * ancestors は identity で比較した祖先(循環参照の検出)。sb が上限を超えたら何も書かない。
     */
    private fun write(sb: StringBuilder, v: Any?, depth: Int, ancestors: List<Any>) {
        if (full(sb)) return
        if (v == null) { sb.append("null"); return }
        val items = elements(v)
        if (items != null) {
            if (ancestors.any { it === v }) { sb.append(CIRCULAR); return }
            if (depth > MAX_DEPTH) { sb.append(TOO_DEEP); return }
            val isMap = v is Map<*, *>
            val next = ancestors + v
            sb.append(if (isMap) '{' else '[')
            var first = true
            for (item in items) {
                if (full(sb)) break
                if (!first) sb.append(',')
                first = false
                if (isMap) {
                    val (k, value) = item as Pair<*, *>
                    sb.append(jsonString(k.toString())).append(':')
                    write(sb, value, depth + 1, next)
                } else {
                    write(sb, item, depth + 1, next)
                }
            }
            sb.append(if (isMap) '}' else ']')
            return
        }
        when (v) {
            is String -> sb.append(jsonString(v))
            is Float -> sb.append(number(v.toDouble()))
            is Double -> sb.append(number(v))
            else -> sb.append(v.toString()) // Boolean もここで toString
        }
    }

    /** JSON の配列(Map は (キー, 値) の組)として出せるものの要素列。対象でなければ null。 */
    private fun elements(v: Any): Iterable<Any?>? = when (v) {
        is Map<*, *> -> v.entries.map { it.key.toString() to it.value }
        is Iterable<*> -> v
        is Array<*> -> v.asIterable()
        is IntArray -> v.asIterable()
        is LongArray -> v.asIterable()
        is ShortArray -> v.asIterable()
        is ByteArray -> v.asIterable()
        is FloatArray -> v.asIterable()
        is DoubleArray -> v.asIterable()
        is BooleanArray -> v.asIterable()
        is UByteArray -> v.map { it.toInt() }
        is UShortArray -> v.map { it.toUInt() }
        is UIntArray -> v.map { it.toLong() }
        is ULongArray -> v.map { it }
        else -> null
    }

    /** 数値。整数値は小数点なし、NaN と無限大は JS と同じ表記。 */
    private fun number(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "Infinity"
        v == Double.NEGATIVE_INFINITY -> "-Infinity"
        v == 0.0 -> "0" // -0.0 も 0
        v == Math.floor(v) && kotlin.math.abs(v) < 1e21 -> BigDecimal(v).setScale(0).toPlainString()
        else -> v.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s.take(MAX_CHARS + 1)) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else -> if (c.code < 0x20 || c.code == 0x7F) sb.append("\\u%04X".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
