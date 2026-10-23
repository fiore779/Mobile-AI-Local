package com.mobileailocal

import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.domain.JsFsException
import com.mobileailocal.domain.WorkspaceUsage
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class JsWorkspaceTest {
    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var ws: JsWorkspace

    @Before fun setUp() {
        root = Files.createTempDirectory("jsws-root").toFile()
        outside = Files.createTempDirectory("jsws-outside").toFile()
        ws = JsWorkspace(root)
        ws.ensureRoot()
    }

    @After fun tearDown() {
        for (dir in listOf(root, outside)) {
            try {
                Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                        try { Files.deleteIfExists(file) } catch (_: IOException) {}
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) {
            }
        }
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected JsFsException($code)")
        } catch (e: JsFsException) {
            assertEquals(code, e.code)
        }
    }

    private fun writeDirect(dir: File, name: String, bytes: ByteArray) =
        Files.write(Paths.get(dir.path, name), bytes)

    private fun link(from: File, to: File) {
        try {
            Files.createSymbolicLink(from.toPath(), to.toPath())
        } catch (e: Exception) {
            assumeNoException("symbolic links are not available", e)
        }
    }

    @Test fun writeReadListExistsRemove() {
        ws.write("a.txt", "hello")
        ws.write("sub/b.txt", "x")
        assertEquals("hello", ws.read("a.txt"))
        assertEquals(listOf("a.txt", "sub/"), ws.list(""))
        assertEquals(listOf("b.txt"), ws.list("sub"))
        assertTrue(ws.exists("a.txt"))
        assertTrue(ws.remove("a.txt"))
        assertFalse(ws.remove("a.txt"))
        assertFalse(ws.exists("a.txt"))
    }

    @Test fun overwrite() {
        ws.write("a.txt", "one")
        ws.write("a.txt", "two")
        assertEquals("two", ws.read("a.txt"))
        assertEquals(WorkspaceUsage(1, 0, 3L), ws.usage())
    }

    @Test fun oneFileByteLimit() {
        ws.write("big", "a".repeat(1_000_000))
        assertEquals(1_000_000, ws.read("big").length)
        expectCode("EFBIG") { ws.write("big2", "a".repeat(1_000_001)) }
        assertFalse(ws.exists("big2"))
    }

    @Test fun fileCountLimit() {
        for (i in 0 until 100) ws.write("f$i", "x")
        assertEquals(100, ws.usage().files)
        expectCode("ENOSPC") { ws.write("f100", "x") }
        // 既存の上書きは数えないのでできる
        ws.write("f0", "y")
        assertEquals("y", ws.read("f0"))
    }

    @Test fun dirCountLimit() {
        for (i in 0 until 50) ws.write("d$i/f", "x")
        assertEquals(50, ws.usage().dirs)
        expectCode("ENOSPC") { ws.write("d50/f", "x") }
    }

    @Test fun totalBytesLimit() {
        for (i in 0 until 10) ws.write("f$i", "a".repeat(1_000_000))
        assertEquals(10_000_000L, ws.usage().bytes)
        // 11 個目は合計を超えて書けない
        expectCode("ENOSPC") { ws.write("f10", "a".repeat(1_000_000)) }
        // 既存と同じサイズで上書き(差し引きゼロ)はできる
        ws.write("f0", "b".repeat(1_000_000))
        assertEquals(10_000_000L, ws.usage().bytes)
    }

    @Test fun japaneseIsCountedByUtf8Bytes() {
        expectCode("EFBIG") { ws.write("x", "あ".repeat(333_334)) }
        ws.write("y", "あ".repeat(333_333))
        assertEquals("あ".repeat(333_333), ws.read("y"))
    }

    @Test fun symbolicLinksAreRejected() {
        val outsideDir = File(outside, "d").apply { mkdirs() }
        val outsideFile = File(outside, "f.txt")
        Files.write(outsideFile.toPath(), "outside".toByteArray(StandardCharsets.UTF_8))
        link(File(root, "dirlink"), outsideDir)
        link(File(root, "filelink"), outsideFile)

        expectCode("EPERM") { ws.read("dirlink/x") }
        expectCode("EPERM") { ws.read("filelink") }
        expectCode("EPERM") { ws.write("dirlink/x", "hi") }
        expectCode("EPERM") { ws.write("filelink", "hi") }
        expectCode("EPERM") { ws.list("dirlink") }
        expectCode("EPERM") { ws.remove("dirlink") }
        expectCode("EPERM") { ws.remove("filelink") }
        expectCode("EPERM") { ws.exists("dirlink") }
        expectCode("EPERM") { ws.exists("filelink") }
        // リンクは見せない
        assertEquals(emptyList<String>(), ws.list(""))
    }

    @Test fun sanitizeRemovesTempFiles() {
        ws.write("keep.txt", "keep")
        writeDirect(root, ".x.tmp-123", "tmp".toByteArray(StandardCharsets.UTF_8))
        writeDirect(root, "a.tmp-1.txt", "user file".toByteArray(StandardCharsets.UTF_8)) // 一時ファイルの形ではないので残る
        ws.sanitize()
        assertTrue(ws.exists("keep.txt"))
        assertTrue(ws.exists("a.tmp-1.txt"))
        assertFalse(File(root, ".x.tmp-123").exists())
    }

    @Test fun sanitizeRemovesLinks() {
        ws.write("keep.txt", "keep")
        link(File(root, "link"), outside)
        ws.sanitize()
        assertTrue(ws.exists("keep.txt"))
        assertFalse(Files.exists(Paths.get(root.path, "link"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun clearLeavesRoot() {
        ws.write("a.txt", "x")
        ws.write("sub/b.txt", "y")
        assertTrue(ws.clear())
        assertEquals(WorkspaceUsage(0, 0, 0L), ws.usage())
        assertTrue(root.isDirectory)
    }

    @Test fun errorCodes() {
        writeDirect(root, "bin.dat", byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        expectCode("EINVAL") { ws.read("bin.dat") }
        expectCode("ENOENT") { ws.read("nope.txt") }
        ws.write("d/f", "x")
        expectCode("EISDIR") { ws.read("d") }
        expectCode("ENOTDIR") { ws.list("d/f") }
        expectCode("EISDIR") { ws.write("d", "x") }
        ws.write("a.txt", "x")
        expectCode("ENOTDIR") { ws.write("a.txt/b", "y") }
    }
}
