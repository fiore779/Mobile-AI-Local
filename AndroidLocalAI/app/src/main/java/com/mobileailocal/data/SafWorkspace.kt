package com.mobileailocal.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.mobileailocal.domain.ReadChunk
import com.mobileailocal.domain.blockingWithTimeout
import com.mobileailocal.domain.WorkspaceEntry
import com.mobileailocal.domain.WorkspaceException
import com.mobileailocal.domain.WorkspaceFiles
import com.mobileailocal.domain.WorkspaceLimits
import com.mobileailocal.domain.WorkspacePath
import com.mobileailocal.domain.WorkspaceStat
import com.mobileailocal.domain.WorkspaceText
import com.mobileailocal.domain.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

/**
 * 作業フォルダ(SAF)の実体(設計書 context-and-workspace 6.7)。
 * 入口で字句検査(WorkspacePath)、実体はツリーのルートから名前 1 要素ずつ findFile でたどる(二重に守る)。
 * treeUri は構築時に渡す(実行のたびに、そのときの設定値で作る)。
 */
class SafWorkspace(private val context: Context, private val treeUri: String) : WorkspaceFiles {

    companion object {
        /** blocking の SAF 呼び出し用。呼び出し側の Job の子にしない(固まっても停止を妨げない)。 */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 永続 URI 権限(読み書き)が残っているか。 */
        fun hasPersistedPermission(context: Context, uriString: String): Boolean {
            if (uriString.isBlank()) return false
            val uri = try { Uri.parse(uriString) } catch (_: Exception) { return false }
            return context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        }
    }

    private fun root(): DocumentFile {
        val s = treeUri
        if (!hasPersistedPermission(context, s)) throw WorkspaceException("EACCES", "no access to the work folder")
        val r = DocumentFile.fromTreeUri(context, Uri.parse(s)) ?: throw WorkspaceException("EACCES", "no access to the work folder")
        if (!r.exists()) throw WorkspaceException("EACCES", "the work folder is gone")
        return r
    }

    /** ルートから 1 要素ずつたどる。無ければ null。途中がファイルなら ENOTDIR。 */
    private fun resolve(rel: String): DocumentFile? {
        var cur = root()
        if (rel.isEmpty()) return cur
        for (name in rel.split('/')) {
            if (!cur.isDirectory) throw WorkspaceException("ENOTDIR", "not a directory")
            cur = cur.findFile(name) ?: return null
        }
        return cur
    }

    private suspend fun <T> io(block: () -> T): T = try {
        // 固まった提供元を待たないよう、待つ側だけをタイムアウトで打ち切る(裏の呼び出しは止められない)
        blockingWithTimeout(ioScope, WorkspaceLimits.OP_TIMEOUT_MS, block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: WorkspaceException) {
        throw e
    } catch (e: SecurityException) {
        throw WorkspaceException("EACCES", "no access to the work folder")
    } catch (e: IOException) {
        throw WorkspaceException("EIO", e.message ?: "I/O error")
    } catch (e: RuntimeException) {
        throw WorkspaceException("EIO", e.message ?: "I/O error")
    }

    override suspend fun list(path: String): List<WorkspaceEntry> {
        val rel = WorkspacePath.normalize(path, allowEmpty = true)
        return io {
            val dir = resolve(rel) ?: throw WorkspaceException("ENOENT", "no such directory")
            if (!dir.isDirectory) throw WorkspaceException("ENOTDIR", "not a directory")
            dir.listFiles().mapNotNull { f -> f.name?.let { WorkspaceEntry(it, f.isDirectory, if (f.isDirectory) 0L else f.length()) } }
                .sortedWith(compareByDescending<WorkspaceEntry> { it.isDir }.thenBy { it.name })
        }
    }

    override suspend fun stat(path: String): WorkspaceStat? {
        val rel = WorkspacePath.normalize(path, allowEmpty = true)
        return io {
            resolve(rel)?.let { WorkspaceStat(it.isDirectory, if (it.isDirectory) 0L else it.length()) }
        }
    }

    override suspend fun read(path: String, start: Int, maxChars: Int): ReadChunk {
        val rel = WorkspacePath.normalize(path, allowEmpty = false)
        return io {
            val f = resolve(rel) ?: throw WorkspaceException("ENOENT", "no such file")
            if (f.isDirectory) throw WorkspaceException("EISDIR", "is a directory")
            if (f.length() > WorkspaceLimits.MAX_READ_FILE_BYTES) throw WorkspaceException("EFBIG", "file too large to read")
            // length() が 0 を返す提供元でも全体を読まないよう、MAX_READ_FILE_BYTES + 1 までしか読まない
            val limit = (WorkspaceLimits.MAX_READ_FILE_BYTES + 1).toInt()
            val bytes = context.contentResolver.openInputStream(f.uri)?.use { readUpTo(it, limit) }
                ?: throw WorkspaceException("EIO", "cannot open the file")
            if (bytes.size > WorkspaceLimits.MAX_READ_FILE_BYTES) throw WorkspaceException("EFBIG", "file too large to read")
            val text = WorkspaceText.decodeStrict(bytes)
            WorkspaceText.slice(text, start, maxChars)
        }
    }

    override suspend fun write(path: String, text: String): WriteResult {
        val rel = WorkspacePath.normalize(path, allowEmpty = false)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > WorkspaceLimits.MAX_WRITE_BYTES) throw WorkspaceException("EFBIG", "file too large")
        return io {
            val parts = rel.split('/')
            var dir = root()
            for (name in parts.dropLast(1)) {
                val child = dir.findFile(name)
                dir = when {
                    child == null -> dir.createDirectory(name) ?: throw WorkspaceException("EIO", "cannot create a directory")
                    child.isDirectory -> child
                    else -> throw WorkspaceException("ENOTDIR", "not a directory")
                }
            }
            val name = parts.last()
            // createFile の前に必ず同名を確認する(提供元によっては同名で "name (1)" が作られるため)
            val existing = dir.findFile(name)
            val created = existing == null
            val target = when {
                existing == null -> {
                    val ext = name.substringAfterLast('.', "")
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
                    val f = dir.createFile(mime, name) ?: throw WorkspaceException("EIO", "cannot create the file")
                    if (f.name != name) { // 名前が変わってしまった(拡張子の付加など)。作ったものを消して失敗にする
                        // 提供元が名前を変えたとき、いま自分が作ったこのファイルの後始末だけに限る(既存のファイルは消さない)
                        f.delete()
                        throw WorkspaceException("EIO", "the folder provider renamed the file")
                    }
                    f
                }
                existing.isDirectory -> throw WorkspaceException("EISDIR", "is a directory")
                else -> existing
            }
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                ?: throw WorkspaceException("EIO", "cannot open the file for writing")
            if (target.length() != bytes.size.toLong()) throw WorkspaceException("EIO", "size mismatch after writing")
            WriteResult(created, bytes.size)
        }
    }
}

/** 最大 limit バイトまで読む(それ以上は読まない)。 */
internal fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
