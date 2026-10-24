package com.mobileailocal.domain

/** 端末のメモリから、モデルファイル(GGUF)として入る目安(MB)を計算する。係数は実機で見直す。 */
object ModelBudget {
    const val TOTAL_RATIO = 0.50
    const val AVAIL_RATIO = 0.85
    const val RESERVE_BASE_MB = 400
    const val RESERVE_PER_4K_CTX_MB = 200
    const val NON_ARM64_RATIO = 0.5

    /**
     * モデルファイルの大きさとして入る目安(MB)。0 以上。
     * @param appMb このアプリの PSS(読み込み済みモデルは置き換わるので、空きに戻して数える)
     */
    fun budgetMb(totalMb: Long, freeMb: Long, appMb: Long, contextLength: Int, abis: List<String>): Long {
        val reserve = RESERVE_BASE_MB + RESERVE_PER_4K_CTX_MB * (contextLength / 4096.0)
        val limitTotal = totalMb * TOTAL_RATIO - reserve
        val limitAvail = (freeMb + appMb) * AVAIL_RATIO - reserve
        var budget = maxOf(0.0, minOf(limitTotal, limitAvail))
        if ("arm64-v8a" !in abis) budget *= NON_ARM64_RATIO
        return budget.toLong()
    }
}
