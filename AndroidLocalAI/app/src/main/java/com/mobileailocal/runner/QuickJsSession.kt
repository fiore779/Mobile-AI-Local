package com.mobileailocal.runner

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import com.mobileailocal.BuildConfig
import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.domain.JsFsException
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause
import com.mobileailocal.domain.JsValueFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** console 出力の溜め場所。watchdog が途中経過を取り出すので、スレッド間で安全にする。 */
class ConsoleBuffer(private val max: Int = JsLimits.MAX_OUTPUT_CHARS) {
    private val sb = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(line: String) {
        if (truncated) return
        val room = max - sb.length
        if (line.length <= room) {
            sb.append(line)
        } else {
            sb.append(line, 0, room)
            sb.append("\n[output truncated]")
            truncated = true
        }
    }

    @Synchronized fun text(): String = sb.toString()
    @Synchronized fun isTruncated(): Boolean = truncated
}

/** QuickJS の生成・上限の設定・prelude・評価・結果の組み立て。実行ごとに新しい QuickJS を作る(状態は持ち越さない)。 */
class QuickJsSession(private val workspace: JsWorkspace) {
    /** ホスト側の OutOfMemoryError を捕まえたか(runner が返信後に自分を終わらせる判断に使う) */
    @Volatile var hostOutOfMemory = false
        private set

    fun run(request: JsRunRequest, console: ConsoleBuffer): JsRunResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        fun result(
            status: JsRunStatus, ret: String? = null, error: String? = null, cause: JsStopCause? = null,
        ) = JsRunResult(
            id = request.id, status = status, returnValue = ret?.let { limit(it, JsLimits.MAX_RETURN_CHARS) },
            console = console.text(), error = error?.let { limit(it, JsLimits.MAX_ERROR_CHARS) },
            elapsedMs = ms(), consoleTruncated = console.isTruncated(), cause = cause,
        )

        return try {
            val value = runBlocking(Dispatchers.Default) {
                quickJs {
                    memoryLimit = JsLimits.MEMORY_BYTES
                    maxStackSize = JsLimits.STACK_BYTES
                    evaluationTimeoutMillis = request.timeoutMs
                    function("__log") { args -> console.append((args.firstOrNull()?.toString() ?: "") + "\n"); null }
                    asyncFunction("__sleep") { args -> delay(((args.firstOrNull() as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)); null }
                    installFs(workspace)
                    if (BuildConfig.DEBUG) installDebugHook() // 検証用のホスト関数(debug ビルドだけ)
                    evaluate<Any?>(Prelude.SOURCE, "prelude.js")
                    // QuickJS の評価時間には sleep/setTimeout の待ちが数えられない(手順 0 の実測)ので、実時間でも打ち切る
                    withTimeout(request.timeoutMs) { evaluate<Any?>(request.code) }
                }
            }
            result(JsRunStatus.Ok, ret = JsValueFormatter.formatReturn(value))
        } catch (e: TimeoutCancellationException) {
            result(JsRunStatus.Timeout, cause = JsStopCause.InterruptTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuickJsInterruptedException) {
            result(JsRunStatus.Timeout, cause = JsStopCause.InterruptTimeout)
        } catch (e: JsFsException) {
            result(JsRunStatus.Error, error = e.message)
        } catch (e: QuickJsException) {
            // quickjs-kt はメモリ超過などで message に文字列 "null" を入れてくる(手順 0 の実測)ので、null と同じに扱う
            val msg = e.message?.takeIf { it.isNotBlank() && it != "null" }
            when {
                // 戻り値として返せない型(BigInt・Symbol)。コードは最後まで動いているので成功扱いにする
                msg != null && msg.startsWith("Unsupported js value type") ->
                    result(JsRunStatus.Ok, ret = "[unsupported value type]")
                // メモリ超過の判別: メッセージに out of memory を含む、または null(配列の積み上げ等で null になる)。誤判定の可能性あり
                msg == null || msg.contains("out of memory", ignoreCase = true) ->
                    result(JsRunStatus.Error, error = msg ?: "InternalError: out of memory", cause = JsStopCause.MemoryLimit)
                else -> result(JsRunStatus.Error, error = msg)
            }
        } catch (e: OutOfMemoryError) {
            hostOutOfMemory = true
            result(JsRunStatus.Error, error = "OutOfMemoryError (host)", cause = JsStopCause.MemoryLimit)
        } catch (t: Throwable) {
            result(JsRunStatus.Error, error = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun limit(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…[truncated]"
}
