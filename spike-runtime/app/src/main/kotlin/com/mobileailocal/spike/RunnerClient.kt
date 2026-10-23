package com.mobileailocal.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class RunResult(
    val status: String, val stdout: String, val stderr: String, val elapsedMs: Long,
    val pid: Int, val pssKb: Long, val note: String, val totalMs: Long,
)

/** メインプロセス側。runner に要求を送り、返事が来なければ runner を kill して再接続する */
class RunnerClient(private val ctx: Context) {
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private var pong: CompletableFuture<Int>? = null
    @Volatile private var service: Messenger? = null
    @Volatile private var connected = CompletableFuture<Unit>()
    private var nextId = 1

    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Proto.RESULT -> pending.remove(msg.data.getInt(Proto.K_ID))?.complete(msg.data)
                Proto.PONG -> pong?.complete(msg.data.getInt(Proto.K_PID))
            }
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            Log.i("SPIKE", "client: onServiceConnected")
            service = Messenger(b)
            connected.complete(Unit)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            Log.i("SPIKE", "client: onServiceDisconnected")
            service = null
            connected = CompletableFuture()
            // 自動再接続は 1〜5 秒以上かかる(システムの再起動バックオフ)ので、すぐ unbind→bind し直す
            try { ctx.unbindService(this) } catch (_: Exception) {}
            ctx.bindService(Intent(ctx, RunnerService::class.java), this, Context.BIND_AUTO_CREATE)
            // runner が死んだ: 待っている要求を即座に "crashed" で終わらせる
            for ((id, f) in pending) {
                pending.remove(id)
                f.complete(Bundle().apply { putString(Proto.K_STATUS, "crashed"); putString(Proto.K_STDERR, "runner process died") })
            }
        }
        override fun onBindingDied(name: ComponentName) { Log.i("SPIKE", "client: onBindingDied") }
    }

    fun bind() {
        ctx.bindService(Intent(ctx, RunnerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        try { ctx.unbindService(conn) } catch (_: Exception) {}
        service = null
        connected = CompletableFuture()
    }

    /** 接続を待つ。待てなければ unbind→bind し直す。接続までの ms を返す(-1: 失敗) */
    fun awaitConnected(waitMs: Long = 5000): Long {
        val t = System.nanoTime()
        try { connected.get(waitMs, TimeUnit.MILLISECONDS) } catch (e: Exception) {
            Log.i("SPIKE", "client: auto reconnect did not happen in ${waitMs}ms -> rebind")
            unbind(); bind()
            try { connected.get(10_000, TimeUnit.MILLISECONDS) } catch (e2: Exception) { return -1 }
        }
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun awaitDisconnected(waitMs: Long = 3000) {
        val end = System.nanoTime() + waitMs * 1_000_000
        while (service != null && System.nanoTime() < end) Thread.sleep(10)
    }

    fun pid(): Int {
        val f = CompletableFuture<Int>()
        pong = f
        service?.send(Message.obtain(null, Proto.PING).apply { replyTo = inbox })
        return f.get(5, TimeUnit.SECONDS)
    }

    /** watchdog=true: runner 自身が時間切れを検知して返事+自殺。false: 返事が来ないので呼び出し側が Process.killProcess */
    fun run(
        lang: String, code: String, timeoutMs: Long, workDir: String,
        watchdog: Boolean = true, graceMs: Long = 2000,
    ): RunResult {
        val t0 = System.nanoTime()
        val id = nextId++
        val f = CompletableFuture<Bundle>()
        pending[id] = f
        val m = Message.obtain(null, Proto.RUN).apply {
            replyTo = inbox
            data = Bundle().apply {
                putInt(Proto.K_ID, id); putString(Proto.K_LANG, lang); putString(Proto.K_CODE, code)
                putLong(Proto.K_TIMEOUT, timeoutMs); putString(Proto.K_WORKDIR, workDir)
                putBoolean(Proto.K_WATCHDOG, watchdog)
            }
        }
        val conMs = awaitConnected()
        if (conMs < 0) return RunResult("killed", "", "runner not connected", 0, 0, 0, "", 0)
        var runnerPid = 0
        try { runnerPid = pid() } catch (_: Exception) {}
        service!!.send(m)
        return try {
            val b = f.get(timeoutMs + graceMs, TimeUnit.MILLISECONDS)
            val r = fromBundle(b, t0, "connectMs=$conMs")
            if (r.status == "timeout" && r.note.contains("watchdog-kill")) {
                // runner は返事の後で自分を kill する。待たずに次の要求を送ると「死ぬ直前の runner」に当たるので、
                // こちらからも確実に kill し、切断を確認してから返す
                Process.killProcess(runnerPid)
                awaitDisconnected()
            } else if (r.status == "crashed") awaitDisconnected()
            r
        } catch (e: TimeoutException) {
            pending.remove(id)
            Log.w("SPIKE", "client: no reply within ${timeoutMs + graceMs}ms -> killing runner pid=$runnerPid")
            Process.killProcess(runnerPid)
            awaitDisconnected()
            RunResult("timeout", "", "killed by client", 0, runnerPid, 0, "client-kill", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun fromBundle(b: Bundle, t0: Long, extra: String) = RunResult(
        b.getString(Proto.K_STATUS)!!, b.getString(Proto.K_STDOUT) ?: "", b.getString(Proto.K_STDERR) ?: "",
        b.getLong(Proto.K_ELAPSED), b.getInt(Proto.K_PID), b.getLong(Proto.K_PSS),
        (b.getString(Proto.K_NOTE) ?: "") + " " + extra,
        (System.nanoTime() - t0) / 1_000_000,
    )
}
