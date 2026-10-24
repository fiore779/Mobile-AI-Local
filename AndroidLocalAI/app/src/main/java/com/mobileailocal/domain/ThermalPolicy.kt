package com.mobileailocal.domain

import java.util.concurrent.atomic.AtomicReference

/** 発熱の閾値の判定。定数は PowerManager.THERMAL_STATUS_* と同値(単体テストを Android 抜きで動かすため直書き)。 */
object ThermalPolicy {
    const val MODERATE = 2
    const val SEVERE = 3
    /** 警告表示(従来どおり) */
    fun isHot(status: Int): Boolean = status >= MODERATE
    /** 停止・送信拒否 */
    fun shouldStop(status: Int): Boolean = status >= SEVERE
}

enum class StopReason { User, Thermal }

/** 停止理由の先着判定。最初に request した理由だけが残る。スレッド安全。 */
class StopArbiter {
    private val ref = AtomicReference<StopReason?>(null)

    /** 先着なら true、すでに決まっていれば false。 */
    fun request(reason: StopReason): Boolean = ref.compareAndSet(null, reason)

    val reason: StopReason? get() = ref.get()
}
