package com.mobileailocal.data

import com.mobileailocal.domain.ReadChunk
import com.mobileailocal.domain.WorkspaceEntry
import com.mobileailocal.domain.WorkspaceException
import com.mobileailocal.domain.WorkspaceFiles
import com.mobileailocal.domain.WorkspaceLimits
import com.mobileailocal.domain.WorkspacePath
import com.mobileailocal.domain.WorkspaceStat
import com.mobileailocal.domain.WorkspaceText
import com.mobileailocal.domain.WriteResult
import com.mobileailocal.domain.blockingWithTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 画面用の一覧の 1 行。 */
data class WorkspaceItem(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * 作業フォルダの実体: アプリ内部領域 `filesDir/workspace`(java.io。アプリが自動で作る。JS の `js-workspace` とは別)。
 * パスは三重に守る: (1) 字句検査(WorkspacePath)、(2) ルートから 1 要素ずつ辿り、シンボリックリンクは辿らず拒否、
 * (3) canonicalPath が root 配下であること。
 * モデル向けの WorkspaceFiles(削除なし)に加え、利用者の操作用の関数(browse・preview・delete など)を持つ。
 */
class InternalWorkspace(rootDir: File) : WorkspaceFiles {
    companion object {
        /** blocking の呼び出し用。呼び出し側の Job の子にしない(固まっても停止を妨げない)。 */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val root: File = rootDir

    /** 実体の root(canonical)。作成も行う。 */
    private fun rootCanonical(): String {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw WorkspaceException("EACCES", "cannot create the work folder")
        return root.canonicalPath
    }

    private fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    private fun checkInside(f: File, rootPath: String) {
        val c = f.canonicalPath
        if (c != rootPath && !c.startsWith(rootPath + File.separator)) throw WorkspaceException("EPERM", "outside the work folder")
    }

    /**
     * rel(正規化済み)を辿って File を返す。無ければ null。途中がファイルなら ENOTDIR。リンクは EPERM。
     * 最後の要素が無くても、途中までの検査は済ませる(parent を create 側で使うため resolveParent も別にある)。
     */
    private fun resolve(rel: String): File? {
        val rootPath = rootCanonical()
        var cur = root
        if (rel.isEmpty()) return cur
        for (name in rel.split('/')) {
            if (!cur.isDirectory) throw WorkspaceException("ENOTDIR", "not a directory")
            val child = File(cur, name)
            if (isLink(child)) throw WorkspaceException("EPERM", "symbolic links are not allowed")
            if (!child.exists()) return null
            checkInside(child, rootPath)
            cur = child
        }
        return cur
    }

    private suspend fun <T> io(block: () -> T): T = try {
        // 固まったときも待つ側だけをタイムアウトで打ち切る(裏の呼び出しは止められない。書き込みは裏で完了しうる)
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

    // ---------------- モデル向け(WorkspaceFiles) ----------------

    override suspend fun list(path: String): List<WorkspaceEntry> {
        val rel = WorkspacePath.normalize(path, allowEmpty = true)
        return io {
            val dir = resolve(rel) ?: throw WorkspaceException("ENOENT", "no such directory")
            if (!dir.isDirectory) throw WorkspaceException("ENOTDIR", "not a directory")
            (dir.listFiles() ?: throw WorkspaceException("EIO", "cannot list"))
                .filterNot { isLink(it) }
                .map { WorkspaceEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
                .sortedWith(compareByDescending<WorkspaceEntry> { it.isDir }.thenBy { it.name })
        }
    }

    override suspend fun stat(path: String): WorkspaceStat? {
        val rel = WorkspacePath.normalize(path, allowEmpty = true)
        return io { resolve(rel)?.let { WorkspaceStat(it.isDirectory, if (it.isDirectory) 0L else it.length()) } }
    }

    override suspend fun read(path: String, start: Int, maxChars: Int): ReadChunk {
        val rel = WorkspacePath.normalize(path, allowEmpty = false)
        return io {
            val f = resolve(rel) ?: throw WorkspaceException("ENOENT", "no such file")
            if (f.isDirectory) throw WorkspaceException("EISDIR", "is a directory")
            if (f.length() > WorkspaceLimits.MAX_READ_FILE_BYTES) throw WorkspaceException("EFBIG", "file too large to read")
            val bytes = f.inputStream().use { readUpTo(it, (WorkspaceLimits.MAX_READ_FILE_BYTES + 1).toInt()) }
            if (bytes.size > WorkspaceLimits.MAX_READ_FILE_BYTES) throw WorkspaceException("EFBIG", "file too large to read")
            WorkspaceText.slice(WorkspaceText.decodeStrict(bytes), start, maxChars)
        }
    }

    override suspend fun write(path: String, text: String): WriteResult {
        val rel = WorkspacePath.normalize(path, allowEmpty = false)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > WorkspaceLimits.MAX_WRITE_BYTES) throw WorkspaceException("EFBIG", "file too large")
        return io {
            val rootPath = rootCanonical()
            val parts = rel.split('/')
            var dir = root
            for (name in parts.dropLast(1)) {
                val child = File(dir, name)
                if (isLink(child)) throw WorkspaceException("EPERM", "symbolic links are not allowed")
                if (!child.exists()) {
                    if (!child.mkdir()) throw WorkspaceException("EIO", "cannot create a directory")
                } else if (!child.isDirectory) {
                    throw WorkspaceException("ENOTDIR", "not a directory")
                }
                checkInside(child, rootPath)
                dir = child
            }
            val target = File(dir, parts.last())
            if (isLink(target)) throw WorkspaceException("EPERM", "symbolic links are not allowed")
            if (target.isDirectory) throw WorkspaceException("EISDIR", "is a directory")
            val created = !target.exists()
            checkInside(target, rootPath)
            target.outputStream().use { it.write(bytes) } // 既存なら切り詰めて書く
            if (target.length() != bytes.size.toLong()) throw WorkspaceException("EIO", "size mismatch after writing")
            WriteResult(created, bytes.size)
        }
    }

    // ---------------- 利用者の操作用(モデルからは呼べない) ----------------

    /** 画面用の一覧(ディレクトリ先、名前順)。リンクは出さない。 */
    fun browse(rel: String): List<WorkspaceItem> {
        val dir = resolve(WorkspacePath.normalize(rel, allowEmpty = true)) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: return emptyList())
            .filterNot { isLink(it) }
            .map { WorkspaceItem(it.name, it.isDirectory, if (it.isDirectory) dirBytes(it) else it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<WorkspaceItem> { it.isDir }.thenBy { it.name })
    }

    /** 共有・書き出し用に、検証済みの通常ファイルを返す(無ければ null)。 */
    fun fileFor(rel: String): File? {
        val f = resolve(WorkspacePath.normalize(rel, allowEmpty = false)) ?: return null
        return if (f.isFile) f else null
    }

    /** 内容の表示用。先頭 maxChars 文字だけ。(テキスト, 切り詰めたか)。テキストでなければ EBINARY。 */
    fun preview(rel: String, maxChars: Int): Pair<String, Boolean> {
        val f = fileFor(rel) ?: throw WorkspaceException("ENOENT", "no such file")
        val limit = maxChars * 4 + 4 // UTF-8 は 1 文字最大 4 バイト。先頭だけ読む
        val all = f.inputStream().use { readUpTo(it, limit) }
        val truncatedBytes = f.length() > all.size
        // 途中で切れた末尾の UTF-8 を落としてからデコードする
        val bytes = if (truncatedBytes) trimIncompleteUtf8(all) else all
        val text = WorkspaceText.decodeStrict(bytes)
        return if (text.length > maxChars || truncatedBytes) text.take(maxChars) to true else text to false
    }

    /** ファイルまたはディレクトリ(中身ごと)を削除する。リンクは辿らない。root 自身は消さない。 */
    fun delete(rel: String): Boolean {
        val n = WorkspacePath.normalize(rel, allowEmpty = false)
        val f = resolve(n) ?: return false
        return deleteTree(f)
    }

    /** 中身をすべて削除する(root は残す)。 */
    fun deleteAll(): Boolean {
        rootCanonical()
        return (root.listFiles() ?: return true).all { deleteTree(it) }
    }

    /** 合計バイト数。 */
    fun totalBytes(): Long = if (root.isDirectory) dirBytes(root) else 0L

    private fun deleteTree(f: File): Boolean {
        if (!isLink(f) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() // リンクはリンク自体だけ消す(先へ入らない)
    }

    private fun dirBytes(f: File): Long {
        if (isLink(f)) return 0L
        if (!f.isDirectory) return f.length()
        return f.listFiles()?.sumOf { dirBytes(it) } ?: 0L
    }

    private fun trimIncompleteUtf8(b: ByteArray): ByteArray {
        var end = b.size
        var back = 0
        while (end > 0 && back < 4 && (b[end - 1].toInt() and 0xC0) == 0x80) { end--; back++ }
        // end-1 は先頭バイトかASCII。先頭バイトなら、その文字が欠けているので落とす
        if (end > 0 && (b[end - 1].toInt() and 0x80) != 0) end--
        return b.copyOf(end)
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
