package com.mobileailocal.spike

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** :runner プロセスで動く実行サービス。1 度に 1 件だけ実行する */
class RunnerService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Proto.PING -> reply(msg.replyTo, Message.obtain(null, Proto.PONG).apply {
                    data = Bundle().apply { putInt(Proto.K_PID, Process.myPid()) }
                })
                Proto.RUN -> startRun(msg.replyTo, msg.data)
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        Log.i("SPIKE", "RUNNER_ONCREATE pid=${Process.myPid()} proc=${Application.getProcessName()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun reply(to: Messenger?, m: Message) {
        try { to?.send(m) } catch (e: Exception) { Log.w("SPIKE", "reply failed: $e") }
    }

    @Volatile private var busy = false

    private fun startRun(to: Messenger?, b: Bundle) {
        val id = b.getInt(Proto.K_ID)
        if (busy) {
            reply(to, result(id, "error", "", "runner busy", 0, ""))
            return
        }
        busy = true
        val lang = b.getString(Proto.K_LANG)!!
        val code = b.getString(Proto.K_CODE)!!
        val timeoutMs = b.getLong(Proto.K_TIMEOUT)
        val watchdog = b.getBoolean(Proto.K_WATCHDOG, true)
        val work = b.getString(Proto.K_WORKDIR)!!
        val t0 = System.nanoTime()
        val done = AtomicBoolean(false)
        val partial = Partial()

        if (watchdog) {
            // (a) runner 側の監視: 時間切れなら途中経過を返してから自分を kill する
            handler.postDelayed({
                if (done.compareAndSet(false, true)) {
                    val (o, e) = partial.snapshot(lang)
                    reply(to, result(id, "timeout", o, e, ms(t0), "watchdog-kill"))
                    handler.postDelayed({ Process.killProcess(Process.myPid()) }, 100)
                }
            }, timeoutMs + WATCHDOG_MARGIN_MS)
        }
        Thread({
            var note = ""
            val res = try {
                when (lang) {
                    "js" -> runJs(code, timeoutMs, partial, work).also { note = lastNote }
                    else -> Triple("error", "", "unknown lang")
                }
            } catch (t: Throwable) {
                Log.e("SPIKE", "run failed", t)
                Triple("error", "", t.toString())
            }
            if (done.compareAndSet(false, true)) {
                reply(to, result(id, res.first, res.second, res.third, ms(t0), note))
                handler.post { busy = false }
            }
        }, "runner-worker").start()
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun result(id: Int, status: String, out: String, err: String, elapsed: Long, note: String) =
        Message.obtain(null, Proto.RESULT).apply {
            data = Bundle().apply {
                putInt(Proto.K_ID, id)
                putString(Proto.K_STATUS, status)
                putString(Proto.K_STDOUT, out)
                putString(Proto.K_STDERR, err)
                putLong(Proto.K_ELAPSED, elapsed)
                putInt(Proto.K_PID, Process.myPid())
                putLong(Proto.K_PSS, Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong())
                putString(Proto.K_NOTE, note)
            }
        }

    /** 時間切れ時に取り出す途中経過 */
    private class Partial {
        val js = StringBuilder()
        fun snapshot(lang: String): Pair<String, String> = synchronized(js) { js.toString() to "" }
    }

    @Volatile private var lastNote = ""

    // ---------------- JavaScript ----------------
    private fun runJs(code: String, timeoutMs: Long, partial: Partial, work: String): Triple<String, String, String> {
        val out = partial.js
        val err = StringBuilder()
        var status = "ok"
        fun append(sb: StringBuilder, s: String) = synchronized(partial.js) {
            if (sb.length < Proto.MAX_OUT) {
                sb.append(s.take(Proto.MAX_OUT - sb.length))
                if (sb.length >= Proto.MAX_OUT) sb.append("\n[output truncated]")
            }
        }
        val t = System.nanoTime()
        try {
            runBlocking(Dispatchers.Default) {
                quickJs {
                    memoryLimit = 64L * 1024 * 1024
                    maxStackSize = 256L * 1024
                    evaluationTimeoutMillis = timeoutMs
                    function("__log") { args -> append(out, args[0].toString() + "\n"); null }
                    installHost(this, work)
                    evaluate<Any?>(PRELUDE, "prelude.js")
                    val v = evaluate<Any?>(code)
                    if (v != null) append(out, "=> $v\n")
                }
            }
        } catch (e: QuickJsInterruptedException) {
            status = "timeout"; append(err, "interrupted: ${e.message}")
        } catch (e: QuickJsException) {
            status = "error"; append(err, "${e.javaClass.simpleName}: ${e.message}")
        }
        lastNote = "jsRunMs=${ms(t)}"
        return Triple(status, synchronized(partial.js) { out.toString() }, err.toString())
    }

    // ---------------- ホスト API(fs と setTimeout) ----------------
    private val WATCHDOG_MARGIN_MS = 1500L
    private val PRELUDE = """
const __fmt = (v) => {
  if (typeof v === 'string') return v;
  if (typeof v === 'bigint') return v.toString() + 'n';
  if (typeof v === 'function') return String(v);
  if (typeof v === 'symbol') return v.toString();
  if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
  if (v && typeof v === 'object') { try { return JSON.stringify(v, (k, x) => typeof x === 'bigint' ? x.toString() + 'n' : x); } catch (e) { return String(v); } }
  return String(v);
};
const __out = (...a) => __log(a.map(__fmt).join(' '));
globalThis.console = { log: __out, info: __out, warn: __out, error: __out, debug: __out };
const __timers = new Map(); let __tid = 0;
globalThis.setTimeout = (fn, ms = 0, ...a) => { const id = ++__tid; __timers.set(id, 1); __sleep(ms).then(() => { if (__timers.delete(id)) fn(...a); }); return id; };
globalThis.setInterval = (fn, ms = 0, ...a) => { const id = ++__tid; __timers.set(id, 1); const tick = () => __sleep(Math.max(ms, 1)).then(() => { if (__timers.has(id)) { fn(...a); tick(); } }); tick(); return id; };
globalThis.clearTimeout = globalThis.clearInterval = (id) => { __timers.delete(id); };
globalThis.queueMicrotask = (fn) => Promise.resolve().then(fn);
globalThis.sleep = __sleep;
""".trimIndent()

    private val MAX_FILE = 1_000_000
    private val MAX_FILES = 100
    private val MAX_TOTAL = 10_000_000L

    /** work の中に限る。".." や symlink で外に出る場合は例外(canonicalPath で解決してから判定) */
    private fun safe(work: String, rel: Any?): File {
        val root = File(work).canonicalFile
        val f = File(root, rel.toString()).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) throw SecurityException("path outside workspace: $rel")
        return f
    }

    private fun installHost(q: com.dokar.quickjs.QuickJs, work: String) {
        File(work).mkdirs()
        q.define("fs") {
            function("writeFile") { a ->
                val f = safe(work, a[0])
                val text = a[1].toString()
                if (text.toByteArray().size > MAX_FILE) throw IllegalArgumentException("file too large (max $MAX_FILE bytes)")
                val all = File(work).walkTopDown().filter { it.isFile }.toList()
                if (!f.exists() && all.size >= MAX_FILES) throw IllegalArgumentException("too many files (max $MAX_FILES)")
                if (all.sumOf { it.length() } - (if (f.exists()) f.length() else 0) + text.length > MAX_TOTAL) throw IllegalArgumentException("workspace full")
                f.parentFile?.mkdirs()
                f.writeText(text)
                null
            }
            function("readFile") { a ->
                val f = safe(work, a[0])
                if (!f.isFile) throw java.io.FileNotFoundException(a[0].toString())
                if (f.length() > MAX_FILE) throw IllegalArgumentException("file too large")
                f.readText()
            }
            function("list") { a ->
                val f = safe(work, a.getOrNull(0) ?: ".")
                (f.list() ?: emptyArray()).sorted()
            }
            function("exists") { a -> safe(work, a[0]).exists() }
            function("remove") { a -> safe(work, a[0]).let { if (it.isFile) it.delete() else false } }
        }
        // 検証専用: QuickJS の割り込みが効かない状況(ホスト関数で固まる/即死)を再現する
        q.function("__block") { Thread.sleep(600_000); null }
        q.function("__exit") { Process.killProcess(Process.myPid()); null }
        q.function("__oom") { val l = ArrayList<ByteArray>(); while (true) l.add(ByteArray(50_000_000)) }
        q.asyncFunction("__sleep") { a -> delay((a[0] as Number).toLong()); null }
    }
}
