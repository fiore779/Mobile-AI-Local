package com.mobileailocal.domain

/**
 * 上限値(JS-05)。固定。runner の中でも本体でも同じ値を使う。
 * 設計規則: ホスト(Kotlin)関数を足すときは、必ずここに上限(サイズ・時間)を足して守ること。
 */
object JsLimits {
    const val TIMEOUT_MS = 5_000L            // QuickJS の評価時間(一段目)
    const val WATCHDOG_MARGIN_MS = 1_500L    // runner の watchdog = TIMEOUT_MS + これ(二段目)
    const val CLIENT_MARGIN_MS = 2_500L      // 本体の最後の保険 = TIMEOUT_MS + これ(三段目)
    const val MEMORY_BYTES = 64L * 1024 * 1024
    const val STACK_BYTES = 256L * 1024
    const val MAX_CODE_CHARS = 100_000       // 実行するコードの長さ(Kotlin の String.length)
    const val MAX_OUTPUT_CHARS = 65_536      // console 出力の合計(戻り値は別枠 = MAX_RETURN_CHARS)
    const val MAX_RETURN_CHARS = 16_384
    const val MAX_ERROR_CHARS = 8_192
    const val MAX_FILE_BYTES = 1_000_000L    // 1 ファイル(UTF-8 のバイト数。1MB = 10^6)
    const val MAX_FILES = 100                // 作業フォルダ内の通常ファイル数
    const val MAX_DIRS = 50                  // 作業フォルダ内のディレクトリ数
    const val MAX_DEPTH = 8                  // パスの階層数
    const val MAX_TOTAL_BYTES = 10_000_000L  // 作業フォルダの合計(10^7)
    const val MAX_PATH_CHARS = 512
    const val MAX_NAME_CHARS = 100           // パスの 1 要素の長さ
    const val IDLE_UNBIND_MS = 60_000L       // 最後の実行から runner を手放すまで
    const val CONNECT_TIMEOUT_MS = 5_000L
    const val MIN_FREE_MEMORY_MB = 200L      // 実行前の空きメモリの下限(実機で調整。推測値)
}

enum class JsRunStatus { Ok, Error, Timeout, Crashed, Killed }

data class JsRunRequest(
    val id: Int,            // 本体が採番する(1 から単調増加)。古い結果の取り違え防止
    val code: String,       // 確認ダイアログで見せたものと同一の文字列
    val timeoutMs: Long = JsLimits.TIMEOUT_MS,
    val workDir: String,    // JsWorkspace の絶対パス
)

data class JsRunResult(
    val id: Int,
    val status: JsRunStatus,
    val returnValue: String?,   // 最後の式の値の文字列化。undefined/null/無しは null
    val console: String,        // console.* の出力(最大 MAX_OUTPUT_CHARS)
    val error: String?,         // Error: メッセージ + スタック(最大 MAX_ERROR_CHARS)。Ok なら null
    val elapsedMs: Long,        // 本体から見た開始〜結果までの時間
    val consoleTruncated: Boolean,
    val cause: JsStopCause?,    // Timeout / Killed / Crashed の細かい理由(null 可)
)

enum class JsStopCause {
    InterruptTimeout,   // 一段目: QuickJS の割り込み
    WatchdogTimeout,    // 二段目: runner の watchdog
    ClientTimeout,      // 三段目: 本体が kill
    UserCancel,         // 利用者の「中止」(status = Killed)
    ProcessDied,        // runner が突然死(status = Crashed)
    MemoryLimit,        // QuickJS のメモリ超過(status = Error。判別できた場合のみ。9.2 参照)
    ConnectFailed,      // runner に接続できなかった(status = Crashed)
    LowMemory,          // 実行前の空きメモリ不足で実行しなかった(status = Error)
}

/** チャットのどのコードブロックか。会話の切り替えで無効にするので会話 ID は持たない */
data class BlockKey(val messageIndex: Int, val blockIndex: Int, val codeHash: Int)

sealed interface JsRunState {
    data object Idle : JsRunState
    data class Running(val key: BlockKey, val startedAtMs: Long) : JsRunState
    data class Finished(val key: BlockKey, val result: JsRunResult) : JsRunState
}

/** 本体側の実行口。テストでは偽物に差し替える */
interface JsExecutor {
    /** 1 件実行。例外は投げず、失敗も JsRunResult で返す(CancellationException は例外) */
    suspend fun run(request: JsRunRequest): JsRunResult
    /** 実行中なら runner を kill する。実行中の run() は status=Killed, cause=UserCancel で戻る */
    fun cancel()
    /** 接続を手放す(runner は自分でプロセスを終わらせる) */
    fun release()
}

data class WorkspaceUsage(val files: Int, val dirs: Int, val bytes: Long)

/** fs API のエラー。code は設計書 5.2(EPERM / EINVAL / ENAMETOOLONG / ENOENT / EISDIR / ENOTDIR / EFBIG / ENOSPC / EIO) */
class JsFsException(val code: String, message: String) : RuntimeException("$code: $message")
