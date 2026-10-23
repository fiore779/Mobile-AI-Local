package com.mobileailocal.runner

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.mobileailocal.BuildConfig
import com.mobileailocal.data.JsProtocol
import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.data.toMessage
import com.mobileailocal.data.toRunRequest
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `:runner` プロセスで動く実行サービス(設計書 6.1 の 6)。1 度に 1 件だけ実行する。
 * 二段目の時間監視(watchdog)を持ち、固まったときは途中経過を返して自分のプロセスを終わらせる。
 */
class JsRunnerService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                JsProtocol.MSG_PING -> reply(
                    msg.replyTo,
                    Message.obtain(null, JsProtocol.MSG_PONG).apply {
                        data = Bundle().apply {
                            putInt(JsProtocol.K_PID, Process.myPid())
                            putInt(JsProtocol.K_VERSION, JsProtocol.PROTOCOL_VERSION)
                        }
                    },
                )
                JsProtocol.MSG_RUN -> startRun(msg)
            }
        }
    }
    private val messenger = Messenger(handler)

    /** メインスレッドからだけ読み書きする */
    private var busy = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** すべての接続が外れた。常駐メモリを残さないよう、プロセスごと終わらせる */
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun reply(to: Messenger?, m: Message) {
        try { to?.send(m) } catch (e: Exception) { Log.w(TAG, "reply failed: $e") }
    }

    private fun startRun(msg: Message) {
        val to = msg.replyTo
        val request: JsRunRequest = try { msg.toRunRequest() } catch (e: Exception) {
            reply(to, quick(0, JsRunStatus.Error, "bad request: $e").toMessage()); return
        }
        if (busy) {
            reply(to, quick(request.id, JsRunStatus.Error, "runner busy").toMessage())
            return
        }
        busy = true
        val t0 = System.nanoTime()
        val done = AtomicBoolean(false)
        val console = ConsoleBuffer()
        val workspace = JsWorkspace(File(request.workDir))

        // 二段目: QuickJS の割り込みが効かない固まり(ホスト関数など)を実時間で止める
        val watchdog = Runnable {
            if (done.compareAndSet(false, true)) {
                val r = JsRunResult(
                    id = request.id, status = JsRunStatus.Timeout, returnValue = null, console = console.text(),
                    error = null, elapsedMs = (System.nanoTime() - t0) / 1_000_000,
                    consoleTruncated = console.isTruncated(), cause = JsStopCause.WatchdogTimeout,
                )
                reply(to, r.toMessage(exiting = true))
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            }
        }
        if (!debugWatchdogDisabled(filesDir))         handler.postDelayed(watchdog, request.timeoutMs + JsLimits.WATCHDOG_MARGIN_MS)

        Thread({
            val session = QuickJsSession(workspace)
            val result = try {
                workspace.ensureRoot()
                workspace.sanitize()
                session.run(request, console)
            } catch (t: Throwable) {
                Log.e(TAG, "run failed", t)
                quick(request.id, JsRunStatus.Error, "$t")
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "result $result")
            val exiting = session.hostOutOfMemory
            if (done.compareAndSet(false, true)) {
                reply(to, result.toMessage(exiting = exiting))
                // ART のヒープが尽きた runner は、次の実行の前に作り直したほうが安全
                if (exiting) handler.postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            }
            handler.post {
                handler.removeCallbacks(watchdog)
                busy = false
            }
        }, "runner-worker").start()
    }

    private fun quick(id: Int, status: JsRunStatus, error: String) = JsRunResult(
        id = id, status = status, returnValue = null, console = "", error = error,
        elapsedMs = 0, consoleTruncated = false, cause = null,
    )

    private companion object {
        const val TAG = "JsRunner"
    }
}
