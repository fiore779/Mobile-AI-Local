package com.mobileailocal.debug

import android.os.Process
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.mobileailocal.runner.RunnerDebugHook

/** debug ビルドだけに入る検証用ホスト関数(QuickJS の割り込みが効かない状況を作る)。release には入らない。 */
class RunnerDebug : RunnerDebugHook {
    override fun install(q: QuickJs) {
        q.function("__block") { Thread.sleep(600_000); null }
        q.function("__exit") { Process.killProcess(Process.myPid()); null }
        q.function("__oom") { val l = ArrayList<ByteArray>(); while (true) l.add(ByteArray(50_000_000)) }
    }

    /** adb 用の入口 `op=nowatchdog` が files/debug_no_watchdog を作っている間だけ二段目 watchdog を無効にする */
    override fun watchdogDisabled(dir: java.io.File): Boolean = java.io.File(dir, "debug_no_watchdog").exists()
}
