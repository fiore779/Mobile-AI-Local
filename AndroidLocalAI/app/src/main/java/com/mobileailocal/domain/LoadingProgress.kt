package com.mobileailocal.domain

/** モデル読み込みの進捗(0..1)を、表示用の整数パーセント(0..100)にする。範囲外・NaN は丸める。 */
fun loadingPercent(progress: Float): Int =
    if (progress.isNaN()) 0 else (progress.coerceIn(0f, 1f) * 100f).toInt()
