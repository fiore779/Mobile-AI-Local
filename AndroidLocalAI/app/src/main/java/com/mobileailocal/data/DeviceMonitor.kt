package com.mobileailocal.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 画面に出す端末の状態。tokensPerSec は直近の生成の速度(まだ生成していなければ null)。 */
data class DeviceStatus(
    val appMemoryMb: Long = 0,
    val freeMemoryMb: Long = 0,
    val totalMemoryMb: Long = 0,
    val tokensPerSec: Float? = null,
    /** 端末が熱を持っていて、速度を落とすか休ませたほうがよい状態か。 */
    val hot: Boolean = false,
)

/** メモリ・発熱・生成速度をまとめて見る。メモリと発熱は snapshot() の時点の値。 */
class DeviceMonitor(private val context: Context) {
    private val _tokensPerSec = MutableStateFlow<Float?>(null)
    val tokensPerSec: StateFlow<Float?> = _tokensPerSec

    fun recordGeneration(tokens: Int, millis: Long) {
        if (tokens >= 2 && millis > 0) _tokensPerSec.value = tokens * 1000f / millis
    }

    fun snapshot(): DeviceStatus {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return DeviceStatus(
            appMemoryMb = mem.totalPss / 1024L,
            freeMemoryMb = info.availMem / (1024 * 1024),
            totalMemoryMb = info.totalMem / (1024 * 1024),
            tokensPerSec = _tokensPerSec.value,
            hot = Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE,
        )
    }
}
