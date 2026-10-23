package com.mobileailocal.spike

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 検証用ハーネス。assets/tests/ のファイルを名前順に runner で実行し、結果を logcat(タグ SPIKE)に出す。
 *   adb shell am start -n com.mobileailocal.spike/.MainActivity --es filter py_
 * ファイル先頭行 "#@ timeout=3000 watchdog=0 grace=1500" で個別設定。
 */
class MainActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var client: RunnerClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply { textSize = 11f; text = "spike" }
        setContentView(ScrollView(this).apply { addView(tv) })
        client = RunnerClient(this)
        client.bind()
        handle()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val filter = intent.getStringExtra("filter") ?: return
        Thread { runSuite(filter) }.start()
    }

    private fun log(s: String) {
        // logcat は 1 行 4000 文字弱で切れるので行ごとに出す
        for (line in s.split("\n")) {
            var l = line
            while (l.length > 3000) { Log.i("SPIKE", l.substring(0, 3000)); l = l.substring(3000) }
            Log.i("SPIKE", l)
        }
        runOnUiThread { tv.append("\n" + s) }
    }

    private fun runSuite(filter: String) {
        val work = File(filesDir, "workspace").absolutePath
        File(work).mkdirs()
        val names = (assets.list("tests") ?: emptyArray()).filter { it.contains(filter) }.sorted()
        log("=== SUITE filter=$filter tests=${names.size} abi=${android.os.Build.SUPPORTED_ABIS.joinToString()} api=${android.os.Build.VERSION.SDK_INT}")
        for (n in names) {
            val code = assets.open("tests/$n").bufferedReader().readText()
            val opts = code.lineSequence().first().let { l ->
                val i = l.indexOf("@")
                if (i in 1..3) l.substring(i + 1).trim().split(" ").associate { kv -> kv.substringBefore("=") to kv.substringAfter("=") } else emptyMap()
            }
            val lang = if (n.endsWith(".py")) "py" else "js"
            val timeout = opts["timeout"]?.toLong() ?: 10_000
            val wd = opts["watchdog"] != "0"
            val grace = opts["grace"]?.toLong() ?: 2000
            val repeat = opts["repeat"]?.toInt() ?: 1
            for (r in 1..repeat) {
                val res = client.run(lang, code, timeout, work, wd, grace)
                log("--- TEST $n run#$r status=${res.status} elapsed=${res.elapsedMs}ms total=${res.totalMs}ms pid=${res.pid} pssKb=${res.pssKb} note=[${res.note}]")
                if (res.stdout.isNotEmpty()) log("[stdout]\n" + res.stdout.trimEnd())
                if (res.stderr.isNotEmpty()) log("[stderr]\n" + res.stderr.trimEnd())
            }
        }
        log("=== SUITE DONE")
    }

    override fun onDestroy() { super.onDestroy(); client.unbind() }
}
