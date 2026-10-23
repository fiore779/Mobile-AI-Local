package com.mobileailocal.data

import com.mobileailocal.domain.BlockKey
import com.mobileailocal.domain.JsExecutor
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause
import com.mobileailocal.domain.WorkspaceUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本体側の状態管理(設計書 4.2, 6.3)。`App` が保持し、ViewModel より長く生きる。
 * 確認ダイアログで「実行」が押されたコードを 1 件ずつ実行する。確認なしの入口は作らない(設計書 8 章)。
 *
 * 設計書 4.2 からの変更: `settings: SettingsRepository`(Context が要り JVM の単体テストで偽物にできない)の代わりに
 * `jsEnabled: () -> Boolean` を受け取る(実装は DataStore の最新値を持つ StateFlow の value)。`context` の代わりに `workspace` を受け取る。
 */
class JsRunManager(
    private val workspace: JsWorkspace,
    private val executor: JsExecutor,
    private val jsEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeMemoryMb: () -> Long,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<JsRunState>(JsRunState.Idle)
    val state: StateFlow<JsRunState> = _state

    private var nextId = 1
    /** 今の実行の id。cancel/cancelAndClear で進め、古い結果が state を上書きしないようにする */
    private var currentId = 0
    /** 実行中の executor.run の Job。中止・クリアで取り消す(接続待ち・送信前でも確実に止めるため) */
    private var job: Job? = null

    /**
     * 確認済みのコードを実行する。戻り値は「受け付けたか」。false の場合 state は変わらない:
     * Running 中、機能が無効(最新の設定値を見る)、コードが長すぎる・空白だけ。
     * 空きメモリが MIN_FREE_MEMORY_MB 未満のときは受け付け、Finished(Error, LowMemory) にする。
     */
    fun run(key: BlockKey, code: String): Boolean {
        if (code.length > JsLimits.MAX_CODE_CHARS || code.isBlank()) return false
        synchronized(lock) {
            if (_state.value is JsRunState.Running) return false
            if (!jsEnabled()) return false
            val id = nextId++
            currentId = id
            val startedAt = clock()
            if (freeMemoryMb() < JsLimits.MIN_FREE_MEMORY_MB) {
                _state.value = JsRunState.Finished(
                    key, result(id, JsRunStatus.Error, JsStopCause.LowMemory, elapsed = 0),
                )
                return true
            }
            _state.value = JsRunState.Running(key, startedAt)
            val request = JsRunRequest(id = id, code = code, workDir = workspace.root.path)
            job = scope.launch {
                val r = try {
                    executor.run(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    result(id, JsRunStatus.Crashed, JsStopCause.ProcessDied, error = e.toString(), elapsed = clock() - startedAt)
                }
                synchronized(lock) {
                    // 中止・クリア・次の実行で id が進んでいたら捨てる
                    if (currentId == id && _state.value is JsRunState.Running) {
                        _state.value = JsRunState.Finished(key, r)
                    }
                }
            }
            return true
        }
    }

    /** 実行中なら中止(→ Finished(Killed, UserCancel))。Running でなければ何もしない */
    fun cancel() {
        synchronized(lock) {
            val s = _state.value as? JsRunState.Running ?: return
            val id = currentId
            currentId = 0 // 実行側から遅れて届く結果を捨てる
            job?.cancel()
            job = null
            _state.value = JsRunState.Finished(
                s.key, result(id, JsRunStatus.Killed, JsStopCause.UserCancel, elapsed = clock() - s.startedAtMs),
            )
        }
        executor.cancel()
    }

    /** 結果カードを閉じる(Finished → Idle)。Running なら何もしない */
    fun dismiss() {
        synchronized(lock) {
            if (_state.value is JsRunState.Finished) _state.value = JsRunState.Idle
        }
    }

    /** 会話の切り替え・機能の無効化用。Running なら cancel してから Idle にする */
    fun cancelAndClear() {
        cancel()
        synchronized(lock) {
            currentId = 0
            _state.value = JsRunState.Idle
        }
    }

    /** 作業フォルダの使用状況(拡張機能画面用)。IO で実行 */
    suspend fun workspaceUsage(): WorkspaceUsage = withContext(Dispatchers.IO) { workspace.usage() }

    /** 作業フォルダの中身をすべて消す。Running のときは false を返して何もしない */
    suspend fun clearWorkspace(): Boolean {
        if (_state.value is JsRunState.Running) return false
        return withContext(Dispatchers.IO) { workspace.clear() }
    }


    private fun result(
        id: Int, status: JsRunStatus, cause: JsStopCause?, error: String? = null, elapsed: Long,
    ) = JsRunResult(
        id = id, status = status, returnValue = null, console = "", error = error,
        elapsedMs = elapsed, consoleTruncated = false, cause = cause,
    )
}
