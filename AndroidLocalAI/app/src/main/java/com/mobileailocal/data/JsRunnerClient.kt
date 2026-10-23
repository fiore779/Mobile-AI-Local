package com.mobileailocal.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.mobileailocal.domain.JsExecutor
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause
import com.mobileailocal.runner.JsRunnerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 本体側の JsExecutor 実装(設計書 6.1 の 5)。`:runner` への接続・要求送信・三段目の監視(kill)・再接続。
 * 接続は使うときだけ bind し、IDLE_UNBIND_MS 使われなければ手放す(runner は onDestroy で自分を終わらせる)。
 * runner が死んだら即 unbind して、次の run() で bind し直す(自動再接続は 5 秒以上かかることがあるため)。
 */
class JsRunnerClient(
    private val context: Context,
    private val scope: CoroutineScope,
) : JsExecutor {
    private val lock = Any()
    private val runMutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsRunResult>>()

    private var conn: Conn? = null                       // lock で守る
    @Volatile private var service: Messenger? = null
    @Volatile private var runnerPid = 0
    @Volatile private var pong: CompletableDeferred<Pair<Int, Int>>? = null
    @Volatile private var userCancelled = false
    private var idleJob: Job? = null

    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                JsProtocol.MSG_PONG -> {
                    val b = msg.data
                    pong?.complete(b.getInt(JsProtocol.K_PID) to b.getInt(JsProtocol.K_VERSION))
                }
                JsProtocol.MSG_RESULT -> {
                    val r = try { msg.toRunResult() } catch (e: Exception) { return }
                    val d = pending.remove(r.id) ?: return // 無ければ古い id なので無視
                    exitingIds[r.id] = msg.data.getBoolean(JsProtocol.K_EXITING)
                    d.complete(r)
                }
            }
        }
    })
    private val exitingIds = ConcurrentHashMap<Int, Boolean>()

    private inner class Conn : ServiceConnection {
        val connected = CompletableDeferred<Unit>()
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!isCurrent(this)) return
            service = Messenger(binder)
            connected.complete(Unit)
        }
        override fun onServiceDisconnected(name: ComponentName) = onDisconnected(this)
        override fun onBindingDied(name: ComponentName) = onDisconnected(this)
        override fun onNullBinding(name: ComponentName) = onDisconnected(this)
    }

    private fun isCurrent(c: Conn) = synchronized(lock) { conn === c }

    /** runner が切れた(突然死・kill・bind 失敗)。待っている要求を Crashed/Killed で終わらせ、接続を捨てる */
    private fun onDisconnected(c: Conn) {
        synchronized(lock) {
            if (conn !== c) return
            conn = null
        }
        try { context.unbindService(c) } catch (_: Exception) {}
        service = null
        runnerPid = 0
        val cancelled = userCancelled
        for ((id, d) in pending) {
            if (pending.remove(id, d)) {
                d.complete(
                    JsRunResult(
                        id = id,
                        status = if (cancelled) JsRunStatus.Killed else JsRunStatus.Crashed,
                        returnValue = null, console = "", error = null, elapsedMs = 0, consoleTruncated = false,
                        cause = if (cancelled) JsStopCause.UserCancel else JsStopCause.ProcessDied,
                    ),
                )
            }
        }
    }

    private fun bind(): Conn? {
        val c = Conn()
        synchronized(lock) { conn = c }
        val ok = try {
            context.bindService(Intent(context, JsRunnerService::class.java), c, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { false }
        if (!ok) {
            synchronized(lock) { if (conn === c) conn = null }
            try { context.unbindService(c) } catch (_: Exception) {}
            return null
        }
        return c
    }

    private fun unbindQuiet() {
        val c = synchronized(lock) { conn.also { conn = null } }
        if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
        service = null
        runnerPid = 0
    }

    /** 接続して PING/PONG(version 確認)まで済ませる。1 回やり直す */
    private suspend fun ensureConnected(): Boolean {
        if (service != null && runnerPid != 0) return true
        repeat(2) {
            unbindQuiet()
            val c = bind() ?: return@repeat
            val connected = withTimeoutOrNull(JsLimits.CONNECT_TIMEOUT_MS) { c.connected.await() } != null
            if (connected && ping()) return true
        }
        unbindQuiet()
        return false
    }

    private suspend fun ping(): Boolean {
        val s = service ?: return false
        val d = CompletableDeferred<Pair<Int, Int>>()
        pong = d
        try { s.send(Message.obtain(null, JsProtocol.MSG_PING).apply { replyTo = inbox }) } catch (e: Exception) { return false }
        val (pid, version) = withTimeoutOrNull(3_000) { d.await() } ?: return false
        if (version != JsProtocol.PROTOCOL_VERSION) {
            // アプリ更新直後の古い runner が残っていた場合の保険
            Process.killProcess(pid)
            return false
        }
        runnerPid = pid
        return true
    }

    /** 切断(service == null)を最大 maxMs 待つ。死ぬ直前の runner に次の要求が当たらないようにする */
    private suspend fun awaitDisconnected(maxMs: Long = 3_000) {
        val end = SystemClock.elapsedRealtime() + maxMs
        while (service != null && SystemClock.elapsedRealtime() < end) delay(10)
        if (service != null) unbindQuiet()
    }

    private suspend fun killRunnerAndWait() {
        val pid = runnerPid
        if (pid != 0) Process.killProcess(pid)
        awaitDisconnected()
    }

    override suspend fun run(request: JsRunRequest): JsRunResult = runMutex.withLock {
        synchronized(this) { idleJob?.cancel(); idleJob = null }
        val t0 = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - t0
        fun fail(status: JsRunStatus, cause: JsStopCause, error: String? = null) = JsRunResult(
            id = request.id, status = status, returnValue = null, console = "", error = error,
            elapsedMs = elapsed(), consoleTruncated = false, cause = cause,
        )
        userCancelled = false
        try {
            if (!ensureConnected()) return@withLock fail(JsRunStatus.Crashed, JsStopCause.ConnectFailed)
            val d = CompletableDeferred<JsRunResult>()
            pending[request.id] = d
            val s = service
            if (s == null) {
                pending.remove(request.id)
                return@withLock fail(JsRunStatus.Crashed, JsStopCause.ProcessDied)
            }
            try {
                s.send(request.toMessage(inbox))
            } catch (e: Exception) {
                pending.remove(request.id)
                return@withLock fail(JsRunStatus.Crashed, JsStopCause.ProcessDied, e.toString())
            }
            // 三段目: 返事が無ければ本体が runner を kill する
            val r = withTimeoutOrNull(request.timeoutMs + JsLimits.CLIENT_MARGIN_MS) { d.await() }
            if (r == null) {
                pending.remove(request.id)
                killRunnerAndWait()
                return@withLock fail(JsRunStatus.Timeout, JsStopCause.ClientTimeout)
            }
            val exiting = exitingIds.remove(request.id) == true
            if (exiting || r.cause == JsStopCause.WatchdogTimeout || r.status == JsRunStatus.Crashed) {
                // runner は返事の後で自分を終わらせる。待たずに次の要求を送ると死ぬ直前の runner に当たる
                killRunnerAndWait()
            }
            r.copy(elapsedMs = elapsed())
        } catch (e: CancellationException) {
            // 呼び出し側が取り消された: 走っている runner を放置しない
            pending.remove(request.id)
            val pid = runnerPid
            if (pid != 0) Process.killProcess(pid)
            throw e
        } finally {
            exitingIds.remove(request.id)
            scheduleIdleRelease()
        }
    }

    override fun cancel() {
        if (pending.isEmpty()) return
        userCancelled = true
        val pid = runnerPid
        if (pid != 0) Process.killProcess(pid)
    }

    override fun release() {
        if (pending.isNotEmpty()) return
        unbindQuiet()
    }

    private fun scheduleIdleRelease() {
        synchronized(this) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(JsLimits.IDLE_UNBIND_MS)
                release()
            }
        }
    }

    private companion object {
        const val TAG = "JsRunnerClient"
    }
}
