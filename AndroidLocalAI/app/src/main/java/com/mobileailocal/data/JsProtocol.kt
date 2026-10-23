package com.mobileailocal.data

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause

/**
 * 本体と :runner の Messenger のメッセージ種別・Bundle のキー・変換(設計書 4.7)。
 * Bundle は JVM の単体テストでは使えないので、ここは実機/エミュレーターで確認する。
 */
object JsProtocol {
    const val PROTOCOL_VERSION = 1

    const val MSG_PING = 1       // 本体→runner。replyTo に本体の Messenger
    const val MSG_PONG = 2       // runner→本体。pid, version
    const val MSG_RUN = 3        // 本体→runner。id, code, timeoutMs, workDir, replyTo
    const val MSG_RESULT = 4     // runner→本体

    const val K_ID = "id"
    const val K_CODE = "code"
    const val K_TIMEOUT = "timeoutMs"
    const val K_WORKDIR = "workDir"
    const val K_PID = "pid"
    const val K_VERSION = "version"
    const val K_STATUS = "status"
    const val K_RETURN = "returnValue"
    const val K_CONSOLE = "console"
    const val K_ERROR = "error"
    const val K_TRUNCATED = "truncated"
    const val K_CAUSE = "cause"
    const val K_RUNNER_ELAPSED = "runnerElapsedMs"
    /** runner がこの返信の後で自分のプロセスを終わらせる(ホスト側 OOM など)。本体は切断を待ってから次を送る */
    const val K_EXITING = "exiting"

    fun statusToWire(s: JsRunStatus): String = when (s) {
        JsRunStatus.Ok -> "ok"
        JsRunStatus.Error -> "error"
        JsRunStatus.Timeout -> "timeout"
        JsRunStatus.Crashed -> "crashed"
        JsRunStatus.Killed -> "killed"
    }

    fun statusFromWire(s: String?): JsRunStatus = when (s) {
        "ok" -> JsRunStatus.Ok
        "timeout" -> JsRunStatus.Timeout
        "crashed" -> JsRunStatus.Crashed
        "killed" -> JsRunStatus.Killed
        else -> JsRunStatus.Error
    }
}

fun JsRunRequest.toMessage(replyTo: Messenger): Message =
    Message.obtain(null, JsProtocol.MSG_RUN).apply {
        this.replyTo = replyTo
        data = Bundle().apply {
            putInt(JsProtocol.K_ID, id)
            putString(JsProtocol.K_CODE, code)
            putLong(JsProtocol.K_TIMEOUT, timeoutMs)
            putString(JsProtocol.K_WORKDIR, workDir)
        }
    }

fun Message.toRunRequest(): JsRunRequest {
    val b = data
    return JsRunRequest(
        id = b.getInt(JsProtocol.K_ID),
        code = b.getString(JsProtocol.K_CODE) ?: "",
        timeoutMs = b.getLong(JsProtocol.K_TIMEOUT),
        workDir = b.getString(JsProtocol.K_WORKDIR) ?: "",
    )
}

/** runner→本体。elapsedMs は runner から見た時間(本体は自分の計測で上書きする) */
fun JsRunResult.toMessage(exiting: Boolean = false): Message =
    Message.obtain(null, JsProtocol.MSG_RESULT).apply {
        data = Bundle().apply {
            putInt(JsProtocol.K_ID, id)
            putString(JsProtocol.K_STATUS, JsProtocol.statusToWire(status))
            returnValue?.let { putString(JsProtocol.K_RETURN, it) }
            putString(JsProtocol.K_CONSOLE, console)
            error?.let { putString(JsProtocol.K_ERROR, it) }
            putBoolean(JsProtocol.K_TRUNCATED, consoleTruncated)
            cause?.let { putString(JsProtocol.K_CAUSE, it.name) }
            putLong(JsProtocol.K_RUNNER_ELAPSED, elapsedMs)
            putBoolean(JsProtocol.K_EXITING, exiting)
        }
    }

fun Message.toRunResult(): JsRunResult {
    val b = data
    return JsRunResult(
        id = b.getInt(JsProtocol.K_ID),
        status = JsProtocol.statusFromWire(b.getString(JsProtocol.K_STATUS)),
        returnValue = b.getString(JsProtocol.K_RETURN),
        console = b.getString(JsProtocol.K_CONSOLE) ?: "",
        error = b.getString(JsProtocol.K_ERROR),
        elapsedMs = b.getLong(JsProtocol.K_RUNNER_ELAPSED),
        consoleTruncated = b.getBoolean(JsProtocol.K_TRUNCATED),
        cause = b.getString(JsProtocol.K_CAUSE)?.let { n -> JsStopCause.entries.firstOrNull { it.name == n } },
    )
}
