package com.mobileailocal.domain

/** 作業フォルダのツール呼び出し(設計書 context-and-workspace 4.5)。 */
sealed interface ToolCall {
    /** path は "" か "." でルート */
    data class List(val path: String) : ToolCall
    /** start は文字の位置(0 以上) */
    data class Read(val path: String, val start: Int = 0) : ToolCall
    data class Write(val path: String, val text: String) : ToolCall
}

/** 返答からタグを探した結果。 */
sealed interface ToolFind {
    /** ツールのタグが無い */
    data object NoCall : ToolFind
    /** 最初の完成したタグ。startIndex はタグの始まり、endIndex は終わりの次の位置(answer 中) */
    data class Call(val call: ToolCall, val endIndex: Int, val startIndex: Int = 0) : ToolFind
    /** 開きはあるが閉じが無い(途中で切れた) */
    data class Incomplete(val tag: String) : ToolFind
    /** 未知のタグ・属性が無い・属性が不正など */
    data class Malformed(val reason: String) : ToolFind
}

/** 書き込みや読み取りの上限。固定。 */
object WorkspaceLimits {
    const val MAX_WRITE_BYTES = 200_000          // 1 回の書き込み(UTF-8)
    const val MAX_READ_FILE_BYTES = 1_000_000L   // これより大きいファイルは読まない
    const val MAX_LIST_ENTRIES = 100
    const val MAX_TOOL_TURNS = 6                 // 1 回のユーザー送信につきツールで往復できる回数
    const val MAX_FORMAT_ERRORS = 2              // 連続した形式の誤りの許容
    const val MAX_WRITE_BYTES_PER_SEND = 500_000 // 1 回のユーザー送信での書き込みの合計
    const val OP_TIMEOUT_MS = 10_000L            // 1 操作の時間(SAF 提供元が固まる場合の保険)
    const val APPROVAL_TIMEOUT_MS = 300_000L     // 承認待ち(5 分)。超えたら拒否と同じ
    const val MAX_DEPTH = 8
    const val MAX_PATH_CHARS = 512
    const val MAX_NAME_CHARS = 100
    const val PREVIEW_HEAD_CHARS = 1_500         // 承認ダイアログに出す本文の先頭の長さ

    /** 読み取りで返す文字数: (contextLength / 3).coerceIn(1000, 8000) */
    fun readBudgetChars(contextLength: Int): Int = (contextLength / 3).coerceIn(1000, 8000)
}

/** code: EPERM / EINVAL / ENAMETOOLONG / ENOENT / EISDIR / ENOTDIR / EFBIG / EACCES / ETIMEDOUT / EIO / EBINARY */
class WorkspaceException(val code: String, message: String) : RuntimeException("$code: $message") {
    /** code を除いた説明 */
    val detail: String = message
}

data class WorkspaceEntry(val name: String, val isDir: Boolean, val size: Long)
data class WorkspaceStat(val isDir: Boolean, val size: Long)
data class ReadChunk(val text: String, val totalChars: Int, val start: Int, val hasMore: Boolean)
data class WriteResult(val created: Boolean, val bytes: Int)

/** 作業フォルダの実体。実装は SAF(SafWorkspace)。テストでは偽物に差し替える。パスは相対。 */
interface WorkspaceFiles {
    /** 全件を名前順(ディレクトリ先)で返す。100 件への切り詰めは WorkspaceToolRunner が行う */
    suspend fun list(path: String): kotlin.collections.List<WorkspaceEntry>
    /** 無ければ null */
    suspend fun stat(path: String): WorkspaceStat?
    suspend fun read(path: String, start: Int, maxChars: Int): ReadChunk
    /** 親ディレクトリは自動で作る */
    suspend fun write(path: String, text: String): WriteResult
}

sealed interface ToolOutcome {
    /** モデルへ返す本文(結果の見出しは ToolResult が付ける) */
    data class Ok(val text: String) : ToolOutcome
    data class Failed(val code: String, val message: String) : ToolOutcome
    /** 利用者が拒否(または承認の時間切れ) */
    data object Denied : ToolOutcome
}

/** 書き込みの承認ダイアログに出す事前情報。 */
data class WritePreview(val path: String, val bytes: Int, val overwrite: Boolean, val oldBytes: Long?, val head: String, val totalChars: Int = head.length)

/** パスの字句検査(JsPathPolicy と同じ規則)。WorkspaceException に写す。 */
object WorkspacePath {
    fun normalize(raw: String, allowEmpty: Boolean): String = try {
        JsPathPolicy.normalize(raw, allowEmpty)
    } catch (e: JsFsException) {
        throw WorkspaceException(e.code, e.message?.substringAfter(": ") ?: "invalid path")
    }
}
