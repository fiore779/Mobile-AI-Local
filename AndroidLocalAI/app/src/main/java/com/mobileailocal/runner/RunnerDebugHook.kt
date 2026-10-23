package com.mobileailocal.runner

import com.dokar.quickjs.QuickJs

/**
 * 検証専用の差し込み口(設計書 10.2 の 13)。`BuildConfig.DEBUG` のときだけ、debug の sourceSet にある
 * `com.mobileailocal.debug.RunnerDebug` を reflection で読み込んで呼ぶ。release には実装クラスが存在しない。
 */
interface RunnerDebugHook {
    fun install(q: QuickJs)
}

internal fun QuickJs.installDebugHook() {
    try {
        val hook = Class.forName("com.mobileailocal.debug.RunnerDebug").getDeclaredConstructor().newInstance()
        (hook as RunnerDebugHook).install(this)
    } catch (_: Throwable) {
        // debug 実装が無ければ何もしない
    }
}
