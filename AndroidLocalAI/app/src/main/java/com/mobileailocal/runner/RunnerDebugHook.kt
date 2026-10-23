package com.mobileailocal.runner

import com.dokar.quickjs.QuickJs
import com.mobileailocal.BuildConfig

/**
 * 検証専用の差し込み口(設計書 10.2 の 13)。`BuildConfig.DEBUG` のときだけ、debug の sourceSet にある
 * `com.mobileailocal.debug.RunnerDebug` を reflection で読み込んで呼ぶ。release には実装クラスが存在しない。
 */
interface RunnerDebugHook {
    fun install(q: QuickJs)

    /** true なら runner 側の二段目 watchdog を張らない(三段目 ClientTimeout の検証用)。既定は false */
    fun watchdogDisabled(dir: java.io.File): Boolean = false
}

/** debug ビルドのときだけ、debug 実装に問い合わせる。release では常に false */
internal fun debugWatchdogDisabled(dir: java.io.File): Boolean {
    if (!BuildConfig.DEBUG) return false
    return try {
        val hook = Class.forName("com.mobileailocal.debug.RunnerDebug").getDeclaredConstructor().newInstance()
        (hook as RunnerDebugHook).watchdogDisabled(dir)
    } catch (_: Throwable) { false }
}

internal fun QuickJs.installDebugHook() {
    try {
        val hook = Class.forName("com.mobileailocal.debug.RunnerDebug").getDeclaredConstructor().newInstance()
        (hook as RunnerDebugHook).install(this)
    } catch (_: Throwable) {
        // debug 実装が無ければ何もしない
    }
}
