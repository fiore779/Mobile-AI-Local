package com.mobileailocal

import com.mobileailocal.data.InternalWorkspace
import com.mobileailocal.domain.WorkspaceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 実ファイル(TemporaryFolder)でのパス検証・読み書き・削除。 */
class InternalWorkspaceTest {
    @Rule @JvmField val tmp = TemporaryFolder()

    private fun ws() = InternalWorkspace(File(tmp.root, "workspace"))
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }
    private fun code(b: suspend () -> Unit): String = try { run(b); "" } catch (e: WorkspaceException) { e.code }

    /** root の外に置いた秘密のファイルとフォルダ(リンクの先)。 */
    private fun outside(): File = tmp.newFolder("outside").also { File(it, "secret.txt").writeText("SECRET") }

    private fun link(at: File, target: File): Boolean = try {
        Files.createSymbolicLink(at.toPath(), target.toPath()); true
    } catch (_: Exception) { false }

    @Test fun rootIsCreatedAutomatically() {
        val w = ws()
        assertTrue(run { w.list("") }.isEmpty())
        assertTrue(File(tmp.root, "workspace").isDirectory)
    }

    @Test fun writeReadRoundTripAndOverwrite() {
        val w = ws()
        val r = run { w.write("a/b/c.txt", "héllo") }
        assertTrue(r.created)
        assertEquals(6, r.bytes)
        assertEquals("héllo", run { w.read("a/b/c.txt", 0, 100) }.text)
        val r2 = run { w.write("a/b/c.txt", "x") }
        assertFalse(r2.created)
        assertEquals("x", run { w.read("a/b/c.txt", 0, 100) }.text)
        assertEquals(listOf("b"), run { w.list("a") }.map { it.name })
        assertTrue(run { w.stat("a") }!!.isDir)
        assertNull(run { w.stat("nope") })
    }

    @Test fun dotDotAndAbsolutePathsAreRejected() {
        val w = ws()
        File(tmp.root, "x.txt").writeText("outside")
        for (p in listOf("../x.txt", "a/../../x.txt", "/etc/hosts", "a\\b", "C:x")) {
            assertTrue(p, code { w.read(p, 0, 10) }.isNotEmpty())
            assertTrue(p, code { w.write(p, "t") }.isNotEmpty())
        }
        assertFalse(File(tmp.root, "x.txt").readText() != "outside")
    }

    @Test fun symlinkedDirectoryToOutsideIsRejected() {
        val w = ws()
        run { w.list("") }
        val out = outside()
        assumeTrue("symlinks unavailable", link(File(tmp.root, "workspace/evil"), out))
        assertEquals("EPERM", code { w.list("evil") })
        assertEquals("EPERM", code { w.read("evil/secret.txt", 0, 100) })
        assertEquals("EPERM", code { w.write("evil/new.txt", "x") })
        assertFalse(File(out, "new.txt").exists())
        assertEquals("EPERM", code { w.stat("evil") })
    }

    @Test fun symlinkedFileToOutsideIsRejected() {
        val w = ws()
        run { w.list("") }
        val out = outside()
        assumeTrue("symlinks unavailable", link(File(tmp.root, "workspace/s.txt"), File(out, "secret.txt")))
        assertEquals("EPERM", code { w.read("s.txt", 0, 100) })
        assertEquals("EPERM", code { w.write("s.txt", "overwrite") })
        assertEquals("SECRET", File(out, "secret.txt").readText())
        // 一覧にもリンクは出さない
        assertTrue(run { w.list("") }.none { it.name == "s.txt" })
    }

    @Test fun directoryAndFileKindErrors() {
        val w = ws()
        run { w.write("d/f.txt", "x") }
        assertEquals("EISDIR", code { w.read("d", 0, 10) })
        assertEquals("EISDIR", code { w.write("d", "x") })
        assertEquals("ENOTDIR", code { w.write("d/f.txt/g.txt", "x") })
        assertEquals("ENOENT", code { w.read("none.txt", 0, 10) })
    }

    @Test fun binaryFileIsEbinary() {
        val w = ws()
        run { w.list("") }
        File(tmp.root, "workspace/b.bin").writeBytes(byteArrayOf(0x41, 0, 0x42))
        assertEquals("EBINARY", code { w.read("b.bin", 0, 10) })
    }

    @Test fun userDeleteRemovesTreeButNotRootAndNotThroughLinks() {
        val w = ws()
        run { w.write("d/e/f.txt", "x") }
        val out = outside()
        val linked = link(File(tmp.root, "workspace/d/lnk"), out)
        assertTrue(w.delete("d"))
        assertFalse(File(tmp.root, "workspace/d").exists())
        if (linked) assertEquals("SECRET", File(out, "secret.txt").readText()) // リンクの先は消えない
        assertTrue(File(tmp.root, "workspace").isDirectory)
        assertFalse(w.delete("missing"))
    }

    @Test fun deleteAllKeepsRoot() {
        val w = ws()
        run { w.write("a.txt", "1"); w.write("d/b.txt", "2") }
        assertTrue(w.totalBytes() >= 2)
        assertTrue(w.deleteAll())
        assertEquals(0L, w.totalBytes())
        assertTrue(File(tmp.root, "workspace").isDirectory)
        assertTrue(w.browse("").isEmpty())
    }

    @Test fun browseOrdersDirsFirst() {
        val w = ws()
        run { w.write("z.txt", "1"); w.write("a/x.txt", "22") }
        val items = w.browse("")
        assertEquals(listOf("a", "z.txt"), items.map { it.name })
        assertEquals(2L, items[0].size) // フォルダは中身の合計
        assertTrue(items[0].isDir)
    }

    @Test fun previewTruncatesAndFileForIsVerified() {
        val w = ws()
        run { w.write("long.txt", "x".repeat(500)) }
        val (t, cut) = w.preview("long.txt", 100)
        assertEquals(100, t.length)
        assertTrue(cut)
        val (t2, cut2) = w.preview("long.txt", 1000)
        assertEquals(500, t2.length)
        assertFalse(cut2)
        assertNull(w.fileFor("d"))
        assertTrue(try { w.fileFor("../x"); false } catch (_: WorkspaceException) { true })
        assertTrue(w.fileFor("long.txt")!!.isFile)
    }

    @Test fun previewOfBinaryThrowsEbinary() {
        val w = ws()
        run { w.list("") }
        File(tmp.root, "workspace/b.bin").writeBytes(byteArrayOf(1, 0, 2))
        try { w.preview("b.bin", 10); fail() } catch (e: WorkspaceException) { assertEquals("EBINARY", e.code) }
    }

    /** Windows のジャンクション(リンクとして検出されない)でも、canonicalPath の検査で root の外は拒否される。 */
    @Test fun junctionToOutsideIsRejectedByCanonicalCheck() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))
        val w = ws()
        run { w.list("") }
        val out = outside()
        val p = ProcessBuilder("cmd", "/c", "mklink", "/J", File(tmp.root, "workspace/j").absolutePath, out.absolutePath).start()
        assumeTrue("junction unavailable", p.waitFor() == 0)
        assertEquals("EPERM", code { w.read("j/secret.txt", 0, 100) })
        assertEquals("EPERM", code { w.write("j/new.txt", "x") })
        assertFalse(File(out, "new.txt").exists())
        assertTrue(try { w.delete("j"); false } catch (_: WorkspaceException) { true }) // 外を指すものは削除にも入れない
        assertEquals("SECRET", File(out, "secret.txt").readText())
    }
}
