package com.mobileailocal.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/**
 * 固まる可能性のある blocking 呼び出し(ファイル入出力など)を、独立スコープの async で動かし、
 * 待つ側だけを timeoutMs で打ち切る。呼び出し側の停止(キャンセル)も、blocking 呼び出しの終わりを待たずに戻る。
 * 打ち切っても blocking 呼び出し自体は止められない(裏で完了しうる。書き込みなら書き終わることがある)。
 * 時間切れは WorkspaceException("ETIMEDOUT")。block の例外はそのまま再送出する。
 * scope は呼び出し側の Job の子にしないこと(SupervisorJob + Dispatchers.IO など)。
 */
suspend fun <T> blockingWithTimeout(scope: CoroutineScope, timeoutMs: Long, block: () -> T): T {
    val d = scope.async { block() }
    try {
        return withTimeout(timeoutMs) { d.await() }
    } catch (e: TimeoutCancellationException) {
        d.cancel()
        throw WorkspaceException("ETIMEDOUT", "the folder did not respond")
    } catch (e: CancellationException) {
        d.cancel()
        throw e
    }
}
