package com.mobileailocal.data

import com.mobileailocal.domain.ToolCall
import com.mobileailocal.domain.ToolOutcome
import com.mobileailocal.domain.WorkspaceException
import com.mobileailocal.domain.WorkspaceFiles
import com.mobileailocal.domain.WorkspaceLimits
import com.mobileailocal.domain.WorkspacePath
import com.mobileailocal.domain.WritePreview
import kotlinx.coroutines.CancellationException

/**
 * ツール呼び出しを実行して ToolOutcome にする(設計書 context-and-workspace 4.5)。
 * 書き込みは executeApproved だけが行う(承認の確認を持たない書き込みの入口を作らない。8.1)。
 */
class WorkspaceToolRunner(private val files: WorkspaceFiles) {
    /** 書き込みの承認ダイアログに出す事前情報。例外は投げず、検査に通らないときは null。 */
    suspend fun preview(call: ToolCall.Write): WritePreview? = try {
        previewOrThrow(call)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 事前情報。失敗は例外のまま流す(executeApproved が ENOTDIR / EACCES / ETIMEDOUT などの結果にする)。 */
    private suspend fun previewOrThrow(call: ToolCall.Write): WritePreview {
        val path = WorkspacePath.normalize(call.path, allowEmpty = false)
        val st = files.stat(path)
        if (st != null && st.isDir) throw WorkspaceException("EISDIR", "is a directory")
        return WritePreview(
            path = path, bytes = call.text.toByteArray(Charsets.UTF_8).size, overwrite = st != null, oldBytes = st?.size,
            head = call.text.take(WorkspaceLimits.PREVIEW_HEAD_CHARS), totalChars = call.text.length,
        )
    }

    /**
     * 承認が要らない操作(一覧・読み取り)を実行する。例外は投げない(CancellationException は再送出)。
     * 書き込みはここでは実行しない(executeApproved を使う)。
     */
    suspend fun execute(call: ToolCall, contextLength: Int): ToolOutcome {
        if (call is ToolCall.Write) return ToolOutcome.Failed("EPERM", "write requires approval")
        return guarded {
            when (call) {
                is ToolCall.List -> {
                    val path = WorkspacePath.normalize(call.path, allowEmpty = true)
                    val all = files.list(path)
                    val shown = all.take(WorkspaceLimits.MAX_LIST_ENTRIES)
                    val sb = StringBuilder()
                    for (e in shown) sb.append(e.name).append(if (e.isDir) "/" else "").append('\n')
                    if (all.size > shown.size) sb.append("[+${all.size - shown.size} more]\n")
                    if (all.isEmpty()) sb.append("(empty)\n")
                    ToolOutcome.Ok(sb.toString().trimEnd('\n'))
                }
                is ToolCall.Read -> {
                    val path = WorkspacePath.normalize(call.path, allowEmpty = false)
                    val chunk = files.read(path, call.start.coerceAtLeast(0), WorkspaceLimits.readBudgetChars(contextLength))
                    val next = chunk.start + chunk.text.length
                    ToolOutcome.Ok(if (chunk.hasMore) chunk.text + "\n[more: start=$next]" else chunk.text)
                }
                is ToolCall.Write -> ToolOutcome.Failed("EPERM", "write requires approval")
            }
        }
    }

    /**
     * 書き込みの唯一の入口。事前検査 → approve(利用者の承認。false で Denied)→ 書き込み。
     * approve が呼ばれる前に files.write は呼ばれない。例外は投げない(CancellationException は再送出)。
     */
    suspend fun executeApproved(
        call: ToolCall.Write,
        approve: suspend (WritePreview) -> Boolean,
        remainingSendBytes: Int = Int.MAX_VALUE,
    ): ToolOutcome {
        val bytes = call.text.toByteArray(Charsets.UTF_8).size
        if (bytes > WorkspaceLimits.MAX_WRITE_BYTES || bytes > remainingSendBytes) {
            return ToolOutcome.Failed("EFBIG", "file too large (max ${WorkspaceLimits.MAX_WRITE_BYTES} bytes per write)")
        }
        return guarded {
            WorkspacePath.normalize(call.path, allowEmpty = false) // 承認の前に、パスが不正なら断る
            val pv = previewOrThrow(call)
            if (!approve(pv)) return@guarded ToolOutcome.Denied
            val r = files.write(pv.path, call.text)
            ToolOutcome.Ok(
                if (r.created) "created ${r.bytes} bytes"
                else "overwritten ${r.bytes} bytes (was ${pv.oldBytes ?: 0})",
            )
        }
    }

    private suspend fun guarded(block: suspend () -> ToolOutcome): ToolOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WorkspaceException) {
        ToolOutcome.Failed(e.code, e.detail)
    } catch (e: Exception) {
        ToolOutcome.Failed("EIO", e.message ?: "I/O error")
    }
}
