package com.mobileailocal.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import com.mobileailocal.domain.ThermalPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 画面に出す端末の状態。tokensPerSec は直近の生成の速度(まだ生成していなければ null)。 */
data class DeviceStatus(
    val appMemoryMb: Long = 0,
    val freeMemoryMb: Long = 0,
    val totalMemoryMb: Long = 0,
    val tokensPerSec: Float? = null,
    /** 端末が熱を持っていて、速度を落とすか休ませたほうがよい状態か。 */
    val hot: Boolean = false,
    /** 熱状態が SEVERE 以上で、生成の停止・送信拒否の対象か。 */
    val thermalBlocked: Boolean = false,
)

/** 総メモリ・空きメモリ・アプリ使用量(MB)。 */
data class DeviceMemory(val totalMb: Long, val freeMb: Long, val appMb: Long)

/** メモリ・発熱・生成速度をまとめて見る。メモリと発熱は snapshot() の時点の値。 */
class DeviceMonitor(private val context: Context) {
    private val _tokensPerSec = MutableStateFlow<Float?>(null)
    val tokensPerSec: StateFlow<Float?> = _tokensPerSec

    fun recordGeneration(tokens: Int, millis: Long) {
        if (tokens >= 2 && millis > 0) _tokensPerSec.value = tokens * 1000f / millis
    }

    /** PowerManager.currentThermalStatus。読めない(OEM の不具合などの例外)ときは 0。 */
    fun currentThermalStatus(): Int = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    } catch (e: Exception) {
        Log.w("DeviceMonitor", "currentThermalStatus failed", e)
        0
    }

    /** 熱状態の変化を流す。登録時に現在値を1回 emit する。登録に失敗したら閉じる(監視が無くなるだけ)。 */
    fun thermalStatusUpdates(): Flow<Int> = callbackFlow {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status -> trySend(status) }
        try {
            pm.addThermalStatusListener(Dispatchers.Default.asExecutor(), listener)
        } catch (e: Exception) {
            Log.w("DeviceMonitor", "addThermalStatusListener failed", e)
            close()
            awaitClose { }
            return@callbackFlow
        }
        trySend(currentThermalStatus())
        awaitClose {
            try { pm.removeThermalStatusListener(listener) } catch (e: Exception) { Log.w("DeviceMonitor", "remove failed", e) }
        }
    }.distinctUntilChanged()

    fun memoryInfo(): DeviceMemory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return DeviceMemory(
            totalMb = info.totalMem / (1024 * 1024),
            freeMb = info.availMem / (1024 * 1024),
            appMb = mem.totalPss / 1024L,
        )
    }

    fun snapshot(): DeviceStatus {
        val m = memoryInfo()
        val thermal = currentThermalStatus()
        return DeviceStatus(
            appMemoryMb = m.appMb,
            freeMemoryMb = m.freeMb,
            totalMemoryMb = m.totalMb,
            tokensPerSec = _tokensPerSec.value,
            hot = ThermalPolicy.isHot(thermal),
            thermalBlocked = ThermalPolicy.shouldStop(thermal),
        )
    }
}
