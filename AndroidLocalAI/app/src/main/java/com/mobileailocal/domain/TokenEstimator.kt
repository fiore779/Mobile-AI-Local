package com.mobileailocal.domain

/** トークン数の見積もり(設計書 context-and-workspace 4.1)。多めに出る式にして、ネイティブ層の黙った削除より先に圧縮が働くようにする。 */
object TokenEstimator {
    const val PER_MESSAGE_OVERHEAD = 8

    /** ASCII は 3.5 文字 = 1 トークン(切り上げ)、非 ASCII は 1 文字 = 1 トークン。空は 0。 */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (ch in text) if (ch.code < 128) ascii++ else other++
        return (ascii * 2 + 6) / 7 + other
    }

    fun estimate(m: ChatMessage): Int = estimate(m.content) + PER_MESSAGE_OVERHEAD
}
