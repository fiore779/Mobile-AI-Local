package com.mobileailocal.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mobileailocal.App
import com.mobileailocal.domain.BlockKey
import com.mobileailocal.domain.JsRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 検証専用(debug ビルドのみ。release には入らない)。モデル無しで JS 実行を試すための adb 用の入口:
 *   adb shell am broadcast -a com.mobileailocal.DEBUG_JS --es op enable|disable|run|cancel|status|clear  [--es code '...'] [--es file /path]
 * 結果は logcat のタグ JSDEBUG に出る。確認ダイアログを通らないので、本番の経路には絶対に置かない。
 */
class JsDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        if (!watching) { watching = true; watch(app) }
        val op = intent.getStringExtra("op") ?: "status"
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (op) {
                    "enable" -> { app.settings.setJsEnabled(true); Log.i(TAG, "enabled") }
                    "disable" -> { app.jsRunManager.cancelAndClear(); app.settings.setJsEnabled(false); Log.i(TAG, "disabled") }
                    "run" -> {
                        val code = intent.getStringExtra("code") ?: intent.getStringExtra("file")?.let { java.io.File(it).readText() } ?: ""
                        val accepted = app.jsRunManager.run(BlockKey(0, 0, code.hashCode()), code)
                        Log.i(TAG, "accepted=$accepted")
                    }
                    "cancel" -> app.jsRunManager.cancel()
                    "clear" -> Log.i(TAG, "clear=${app.jsRunManager.clearWorkspace()}")
                    "usage" -> Log.i(TAG, "usage=${app.jsRunManager.workspaceUsage()}")
                    "status" -> Log.i(TAG, "state=${app.jsRunManager.state.value}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "JSDEBUG"
        @Volatile private var watching = false
        /** App から呼ぶ: 状態の変化を logcat に出す */
        fun watch(app: App) {
            CoroutineScope(Dispatchers.Default).launch {
                app.jsRunManager.state.collect { s ->
                    when (s) {
                        is JsRunState.Finished -> Log.i(TAG, "FINISHED ${s.result}")
                        is JsRunState.Running -> Log.i(TAG, "RUNNING")
                        JsRunState.Idle -> Log.i(TAG, "IDLE")
                    }
                }
            }
        }
    }
}
