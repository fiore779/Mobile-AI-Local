package com.mobileailocal.data

import com.mobileailocal.domain.JsFsException
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsPathPolicy
import com.mobileailocal.domain.WorkspaceUsage
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * JS の fs API の実体(設計書 4.8, 5.2, 5.3)。root の外には出ない。
 * 守り方: 字句の検査(JsPathPolicy) → 実体の検査(リンクをたどらない・canonical が root 配下) → sanitize(掃除)。
 * 書き込みは同じ親ディレクトリの一時ファイル + move で、途中で kill されても中途半端な内容を残さない。
 */
class JsWorkspace(val root: File) {
    /** root の実体パス(filesDir 自体がリンク経由でも、比較は実体同士で行う)。 */
    private val base: File get() = root.canonicalFile

    fun ensureRoot() {
        root.mkdirs()
    }

    /** シンボリックリンクと一時ファイルを消す。実行の開始前に runner が呼ぶ。 */
    fun sanitize() {
        if (!root.isDirectory) return
        try {
            Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || TEMP_NAME.matches(file.fileName.toString())) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) { Files.deleteIfExists(dir); return FileVisitResult.SKIP_SUBTREE }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // 掃除の失敗で実行全体を止めない(各操作が個別にリンクを拒否する)
        }
    }

    fun usage(): WorkspaceUsage {
        var files = 0
        var dirs = 0
        var bytes = 0L
        if (root.isDirectory) {
            val b = base.toPath()
            try {
                Files.walkFileTree(b, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != b) dirs++
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) { files++; bytes += attrs.size() }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (_: IOException) {
            }
        }
        return WorkspaceUsage(files, dirs, bytes)
    }

    /** root の中身をすべて削除する(root は残す)。リンクはたどらず、リンク自体を消す。 */
    fun clear(): Boolean {
        if (!root.exists()) return true
        val b = base.toPath()
        try {
            Files.walkFileTree(b, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    try { Files.deleteIfExists(file) } catch (_: IOException) {}
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (dir != b) Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
        return (base.list()?.isEmpty()) ?: true
    }

    // ---------------- fs API ----------------

    fun write(path: String, text: String) {
        val rel = JsPathPolicy.normalize(path, allowEmpty = false)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > JsLimits.MAX_FILE_BYTES) throw JsFsException("EFBIG", "file too large (max ${JsLimits.MAX_FILE_BYTES} bytes)")
        val target = resolve(rel)
        if (target.isDirectory) throw JsFsException("EISDIR", "is a directory: $rel")
        // 祖先に通常ファイルがあれば ENOTDIR、無いディレクトリは新規に作る数として数える
        val missingDirs = missingAncestors(rel)
        val u = usage()
        val exists = target.isFile
        val oldSize = if (exists) target.length() else 0L
        if (!exists && u.files + 1 > JsLimits.MAX_FILES) throw JsFsException("ENOSPC", "too many files (max ${JsLimits.MAX_FILES})")
        if (u.dirs + missingDirs > JsLimits.MAX_DIRS) throw JsFsException("ENOSPC", "too many directories (max ${JsLimits.MAX_DIRS})")
        if (u.bytes - oldSize + bytes.size > JsLimits.MAX_TOTAL_BYTES) throw JsFsException("ENOSPC", "workspace full (max ${JsLimits.MAX_TOTAL_BYTES} bytes)")
        val parent = target.parentFile ?: throw JsFsException("EIO", "no parent")
        try {
            if (!parent.isDirectory && !parent.mkdirs()) throw JsFsException("EIO", "cannot create directory")
            // mkdirs の後にもう一度、リンク・実体を検査する
            resolve(rel)
            val tmp = File(parent, ".${target.name}.tmp-${System.nanoTime() and 0xFFFFFF}")
            try {
                Files.write(tmp.toPath(), bytes)
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
        } catch (e: IOException) {
            throw JsFsException("EIO", e.message ?: "I/O error")
        }
    }

    fun read(path: String): String {
        val rel = JsPathPolicy.normalize(path, allowEmpty = false)
        val f = resolve(rel)
        if (!f.exists()) throw JsFsException("ENOENT", "no such file: $rel")
        if (f.isDirectory) throw JsFsException("EISDIR", "is a directory: $rel")
        if (f.length() > JsLimits.MAX_FILE_BYTES) throw JsFsException("EFBIG", "file too large")
        try {
            val bytes = Files.readAllBytes(f.toPath())
            val dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw JsFsException("EINVAL", "not valid UTF-8")
        } catch (e: IOException) {
            throw JsFsException("EIO", e.message ?: "I/O error")
        }
    }

    /** ディレクトリは末尾に "/" を付ける。名前順。リンクは見せない。 */
    fun list(path: String): List<String> {
        val rel = JsPathPolicy.normalize(path, allowEmpty = true)
        val d = resolve(rel)
        if (!d.exists()) throw JsFsException("ENOENT", "no such directory: ${rel.ifEmpty { "." }}")
        if (!d.isDirectory) throw JsFsException("ENOTDIR", "not a directory: $rel")
        val names = d.list() ?: throw JsFsException("EIO", "cannot list")
        return names.filter { !Files.isSymbolicLink(File(d, it).toPath()) }
            .sorted()
            .map { if (File(d, it).isDirectory) "$it/" else it }
    }

    fun exists(path: String): Boolean {
        val rel = JsPathPolicy.normalize(path, allowEmpty = false)
        return resolve(rel).exists()
    }

    /** ファイルだけ消す。消したら true、無い・ディレクトリなら false。 */
    fun remove(path: String): Boolean {
        val rel = JsPathPolicy.normalize(path, allowEmpty = false)
        val f = resolve(rel)
        if (!f.isFile) return false
        return try { Files.deleteIfExists(f.toPath()) } catch (e: IOException) { throw JsFsException("EIO", e.message ?: "I/O error") }
    }

    // ---------------- 実体の検査 ----------------

    /**
     * 正規化済みの相対パスを実ファイルにする。root から各要素をたどり、既存の要素がシンボリックリンクなら EPERM。
     * さらに、既存の最も深い祖先の canonical パスが root 配下でなければ EPERM。
     */
    private fun resolve(rel: String): File {
        val b = base
        if (rel.isEmpty()) return b
        var cur = b
        var deepestExisting = b
        for (el in rel.split('/')) {
            cur = File(cur, el)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) throw JsFsException("EPERM", "symbolic links are not allowed")
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) deepestExisting = cur else break
        }
        val canon = try { deepestExisting.canonicalFile } catch (e: IOException) { throw JsFsException("EIO", e.message ?: "I/O error") }
        if (canon != b && !canon.path.startsWith(b.path + File.separator)) throw JsFsException("EPERM", "path outside workspace")
        return File(b, rel)
    }

    /** rel の祖先のうち、まだ無いディレクトリの数。途中に通常ファイルがあれば ENOTDIR。 */
    private fun missingAncestors(rel: String): Int {
        val parts = rel.split('/').dropLast(1)
        var cur = base
        var missing = 0
        for (el in parts) {
            cur = File(cur, el)
            if (cur.isFile) throw JsFsException("ENOTDIR", "not a directory: $el")
            if (!cur.exists()) missing++
        }
        return missing
    }

    companion object {
        /** write が作る一時ファイル名 `.<name>.tmp-<数字>` */
        private val TEMP_NAME = Regex("""^\..+\.tmp-\d+$""")
    }
}
