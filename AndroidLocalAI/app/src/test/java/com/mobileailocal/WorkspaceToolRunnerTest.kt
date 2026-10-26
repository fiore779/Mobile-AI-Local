package com.mobileailocal

import com.mobileailocal.data.WorkspaceToolRunner
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.ReadChunk
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.ToolCall
import com.mobileailocal.domain.ToolOutcome
import com.mobileailocal.domain.ToolResult
import com.mobileailocal.domain.ToolResultFormat
import com.mobileailocal.domain.WorkspaceEntry
import com.mobileailocal.domain.WorkspaceException
import com.mobileailocal.domain.WorkspaceFiles
import com.mobileailocal.domain.WorkspaceLimits
import com.mobileailocal.domain.WorkspacePath
import com.mobileailocal.domain.WorkspaceStat
import com.mobileailocal.domain.WorkspaceText
import com.mobileailocal.domain.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 偽の作業フォルダ(メモリ上)。 */
class FakeFiles : WorkspaceFiles {
    val files = linkedMapOf<String, String>()
    val dirs = mutableSetOf<String>()
    var writes = 0
    var throwOn: Throwable? = null

    override suspend fun list(path: String): List<WorkspaceEntry> {
        throwOn?.let { throw it }
        val prefix = if (path.isEmpty()) "" else "$path/"
        val names = mutableMapOf<String, Boolean>()
        for (f in files.keys) if (f.startsWith(prefix)) {
            val rest = f.removePrefix(prefix)
            if ('/' in rest) names[rest.substringBefore('/')] = true else names[rest] = false
        }
        for (d in dirs) if (d.startsWith(prefix) && '/' !in d.removePrefix(prefix) && d != path) names[d.removePrefix(prefix)] = true
        return names.map { WorkspaceEntry(it.key, it.value, 0) }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDir }.thenBy { it.name })
    }

    override suspend fun stat(path: String): WorkspaceStat? {
        throwOn?.let { throw it }
        files[path]?.let { return WorkspaceStat(false, it.toByteArray().size.toLong()) }
        if (path in dirs || files.keys.any { it.startsWith("$path/") }) return WorkspaceStat(true, 0)
        return null
    }

    override suspend fun read(path: String, start: Int, maxChars: Int): ReadChunk {
        throwOn?.let { throw it }
        val t = files[path] ?: throw WorkspaceException("ENOENT", "no such file")
        return WorkspaceText.slice(t, start, maxChars)
    }

    override suspend fun write(path: String, text: String): WriteResult {
        throwOn?.let { throw it }
        writes++
        val created = path !in files
        files[path] = text
        return WriteResult(created, text.toByteArray().size)
    }
}

class WorkspaceToolRunnerTest {
    private val fake = FakeFiles()
    private val runner = WorkspaceToolRunner(fake)
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }
    private fun ok(o: ToolOutcome) = (o as ToolOutcome.Ok).text

    // ---- パスの検査(R13) ----

    @Test fun pathPolicyRejectsEscapes() {
        val bad = listOf("../x", "/etc/hosts", "a/../b", "C:x", "a\\b", "a\u0000b", "x".repeat(513), "a/b/c/d/e/f/g/h/i", "n".repeat(101))
        for (p in bad) {
            try {
                WorkspacePath.normalize(p, allowEmpty = false)
                fail("accepted: $p")
            } catch (_: WorkspaceException) {
            }
        }
        assertEquals("a/b.txt", WorkspacePath.normalize("./a//b.txt", allowEmpty = false))
        assertEquals("", WorkspacePath.normalize(".", allowEmpty = true))
    }

    @Test fun badPathBecomesFailedNotException() {
        val o = run { runner.execute(ToolCall.Read("../x"), 4096) }
        assertEquals("EPERM", (o as ToolOutcome.Failed).code)
        val o2 = run { runner.execute(ToolCall.List("/abs"), 4096) }
        assertEquals("EPERM", (o2 as ToolOutcome.Failed).code)
    }

    @Test fun badPathWriteNeverReachesApprovalOrFiles() {
        var asked = false
        val o = run { runner.executeApproved(ToolCall.Write("../x", "t"), { asked = true; true }) }
        assertEquals("EPERM", (o as ToolOutcome.Failed).code)
        assertFalse(asked)
        assertEquals(0, fake.writes)
    }

    // ---- list ----

    @Test fun listOrdersDirectoriesFirstAndMarksThem() {
        fake.files["b.txt"] = "x"; fake.files["a.txt"] = "x"; fake.files["z/in.txt"] = "x"
        assertEquals("z/\na.txt\nb.txt", ok(run { runner.execute(ToolCall.List(""), 4096) }))
    }

    @Test fun listTruncatesAt100() {
        for (i in 0 until 130) fake.files["f%03d.txt".format(i)] = "x"
        val text = ok(run { runner.execute(ToolCall.List("."), 4096) })
        assertEquals(WorkspaceLimits.MAX_LIST_ENTRIES + 1, text.lines().size)
        assertEquals("[+30 more]", text.lines().last())
    }

    // ---- read ----

    @Test fun readStartAndMore() {
        fake.files["a.txt"] = "x".repeat(2500)
        val first = ok(run { runner.execute(ToolCall.Read("a.txt", 0), 3000) }) // 予算 1000 文字
        assertTrue(first.endsWith("\n[more: start=1000]"))
        val last = ok(run { runner.execute(ToolCall.Read("a.txt", 2000), 3000) })
        assertEquals("x".repeat(500), last)
    }

    @Test fun readBudgetBounds() {
        assertEquals(1000, WorkspaceLimits.readBudgetChars(512))
        assertEquals(1000, WorkspaceLimits.readBudgetChars(3000))
        assertEquals(1365, WorkspaceLimits.readBudgetChars(4096))
        assertEquals(8000, WorkspaceLimits.readBudgetChars(100_000))
    }

    @Test fun readStartPastEndIsEmpty() {
        fake.files["a.txt"] = "abc"
        assertEquals("", ok(run { runner.execute(ToolCall.Read("a.txt", 10), 4096) }))
    }

    @Test fun readMissingIsEnoent() {
        val o = run { runner.execute(ToolCall.Read("none.txt"), 4096) } as ToolOutcome.Failed
        assertEquals("ENOENT", o.code)
    }

    // ---- write と承認(R14) ----

    @Test fun writeIsNeverExecutedWithoutApproval() {
        val o = run { runner.execute(ToolCall.Write("a.txt", "x"), 4096) }
        assertEquals("EPERM", (o as ToolOutcome.Failed).code)
        assertEquals(0, fake.writes)
    }

    @Test fun deniedDoesNotWrite() {
        val o = run { runner.executeApproved(ToolCall.Write("a.txt", "x"), { false }) }
        assertEquals(ToolOutcome.Denied, o)
        assertEquals(0, fake.writes)
        assertTrue(fake.files.isEmpty())
    }

    @Test fun approvedNewFile() {
        var pv: com.mobileailocal.domain.WritePreview? = null
        val o = run { runner.executeApproved(ToolCall.Write("d/a.txt", "héllo"), { pv = it; true }) }
        assertEquals("created 6 bytes", ok(o))
        assertEquals("héllo", fake.files["d/a.txt"])
        assertFalse(pv!!.overwrite)
        assertNull(pv!!.oldBytes)
        assertEquals(6, pv!!.bytes)
    }

    @Test fun approvedOverwriteShowsOldSize() {
        fake.files["a.txt"] = "12345"
        var pv: com.mobileailocal.domain.WritePreview? = null
        val o = run { runner.executeApproved(ToolCall.Write("a.txt", "xy"), { pv = it; true }) }
        assertEquals("overwritten 2 bytes (was 5)", ok(o))
        assertTrue(pv!!.overwrite)
        assertEquals(5L, pv!!.oldBytes)
        assertEquals("xy", fake.files["a.txt"])
    }

    @Test fun previewHeadIsLimitedAndTotalKept() {
        val big = "z".repeat(5000)
        val pv = run { runner.preview(ToolCall.Write("a.txt", big)) }!!
        assertEquals(WorkspaceLimits.PREVIEW_HEAD_CHARS, pv.head.length)
        assertEquals(5000, pv.totalChars)
    }

    @Test fun previewOfDirectoryOrBadPathIsNull() {
        fake.dirs += "d"
        assertNull(run { runner.preview(ToolCall.Write("d", "x")) })
        assertNull(run { runner.preview(ToolCall.Write("../x", "x")) })
        assertNotNull(run { runner.preview(ToolCall.Write("ok.txt", "x")) })
    }

    @Test fun writeOverLimitIsEfbigBeforeApproval() {
        var asked = false
        val o = run { runner.executeApproved(ToolCall.Write("a", "x".repeat(WorkspaceLimits.MAX_WRITE_BYTES + 1)), { asked = true; true }) }
        assertEquals("EFBIG", (o as ToolOutcome.Failed).code)
        assertFalse(asked)
        assertEquals(0, fake.writes)
        // ちょうど上限は通る
        val ok = run { runner.executeApproved(ToolCall.Write("b", "x".repeat(WorkspaceLimits.MAX_WRITE_BYTES)), { true }) }
        assertTrue(ok is ToolOutcome.Ok)
    }

    @Test fun perSendRemainingBudgetIsEnforced() {
        var asked = false
        val o = run { runner.executeApproved(ToolCall.Write("a", "x".repeat(100)), { asked = true; true }, remainingSendBytes = 99) }
        assertEquals("EFBIG", (o as ToolOutcome.Failed).code)
        assertFalse(asked)
    }

    // ---- 例外の扱い ----

    @Test fun workspaceExceptionBecomesFailed() {
        fake.throwOn = WorkspaceException("EACCES", "no access")
        val o = run { runner.execute(ToolCall.List(""), 4096) } as ToolOutcome.Failed
        assertEquals("EACCES", o.code)
        assertEquals("no access", o.message)
    }

    @Test fun unexpectedExceptionBecomesEio() {
        fake.throwOn = IllegalStateException("boom")
        val o = run { runner.execute(ToolCall.List(""), 4096) } as ToolOutcome.Failed
        assertEquals("EIO", o.code)
    }

    @Test fun cancellationIsRethrown() {
        fake.throwOn = CancellationException("stop")
        var thrown = false
        try { run { runner.execute(ToolCall.List(""), 4096) } } catch (_: CancellationException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { run { runner.executeApproved(ToolCall.Write("a", "x"), { true }) } } catch (_: CancellationException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun cancellationDuringApprovalIsRethrownAndNothingWritten() {
        var thrown = false
        try {
            run { runner.executeApproved(ToolCall.Write("a", "x"), { throw CancellationException("stop") }) }
        } catch (_: CancellationException) { thrown = true }
        assertTrue(thrown)
        assertEquals(0, fake.writes)
    }

    // ---- テキストの扱い ----

    @Test fun decodeStrictRejectsBinaryAndInvalidUtf8() {
        assertEquals("héllo", WorkspaceText.decodeStrict("héllo".toByteArray()))
        for (b in listOf(byteArrayOf(0x41, 0, 0x42), byteArrayOf(0xC3.toByte(), 0x28))) {
            try { WorkspaceText.decodeStrict(b); fail() } catch (e: WorkspaceException) { assertEquals("EBINARY", e.code) }
        }
    }

    @Test fun sliceDoesNotSplitSurrogatePair() {
        val t = "ab😀cd" // 😀 は 2 文字(index 2,3)
        val c = WorkspaceText.slice(t, 0, 3)
        assertEquals("ab", c.text)
        assertTrue(c.hasMore)
    }

    // ---- 結果の形式とクラウド ----

    @Test fun resultFormats() {
        assertEquals(
            "[File tool result]\nop: read\npath: a.txt\nstatus: ok\nbody",
            ToolResult.format(ToolCall.Read("a.txt"), ToolOutcome.Ok("body")),
        )
        assertEquals(
            "[File tool result]\nop: read\npath: a.txt\nstatus: error\ncode: ENOENT\nno such file",
            ToolResult.format(ToolCall.Read("a.txt"), ToolOutcome.Failed("ENOENT", "no such file")),
        )
        assertEquals(
            "[File tool result]\nop: write\npath: a.txt\nstatus: denied by user",
            ToolResult.format(ToolCall.Write("a.txt", "x"), ToolOutcome.Denied),
        )
        assertTrue(ToolResult.formatError("hint").startsWith("[File tool result]\nstatus: error\ncode: FORMAT\n"))
        assertEquals(ToolResult.Status.Ok, ToolResult.statusOf(ToolResult.format(ToolCall.List(""), ToolOutcome.Ok("x"))))
        assertEquals(ToolResult.Status.Denied, ToolResult.statusOf(ToolResult.format(ToolCall.List(""), ToolOutcome.Denied)))
        assertEquals(ToolResult.Status.Error, ToolResult.statusOf(ToolResult.formatError("h")))
        assertTrue(ToolResult.isResult(ToolResult.PREFIX + "x"))
        assertFalse(ToolResult.isResult("hello"))
    }

    @Test fun forCloudOmitsOnlyToolResults() {
        val secret = ToolResult.format(ToolCall.Read("a"), ToolOutcome.Ok("SECRET"))
        val msgs = listOf(
            ChatMessage(Role.User, "q"),
            ChatMessage(Role.Assistant, "<file_read path=\"a\"/>"),
            ChatMessage(Role.User, secret),
            ChatMessage(Role.Assistant, "answer"),
        )
        val out = ToolResultFormat.forCloud(msgs)
        assertEquals(ToolResult.CLOUD_OMITTED, out[2].content)
        assertFalse(out.any { "SECRET" in it.content })
        assertEquals(msgs[0], out[0])
        assertEquals(msgs[1], out[1])
        assertEquals(msgs[3], out[3])
    }

    // ---- 見直し分 ----

    @Test fun statFailureInWriteKeepsItsCodeInsteadOfEinval() {
        fake.throwOn = WorkspaceException("EACCES", "no access")
        var asked = false
        val o = run { runner.executeApproved(ToolCall.Write("a.txt", "x"), { asked = true; true }) }
        assertEquals("EACCES", (o as ToolOutcome.Failed).code)
        assertFalse(asked)
        fake.throwOn = WorkspaceException("ENOTDIR", "not a directory")
        assertEquals("ENOTDIR", (run { runner.executeApproved(ToolCall.Write("a/b.txt", "x"), { true }) } as ToolOutcome.Failed).code)
    }

    @Test fun writingToADirectoryIsEisdir() {
        fake.dirs += "d"
        assertEquals("EISDIR", (run { runner.executeApproved(ToolCall.Write("d", "x"), { true }) } as ToolOutcome.Failed).code)
    }

    @Test fun blockingCallTimesOutWithoutWaitingForIt() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val t0 = System.currentTimeMillis()
        try {
            run { com.mobileailocal.domain.blockingWithTimeout(scope, 100) { Thread.sleep(3000); 1 } }
            fail()
        } catch (e: WorkspaceException) {
            assertEquals("ETIMEDOUT", e.code)
        }
        assertTrue(System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun cancellingTheCallerDoesNotWaitForTheBlockingCall() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val t0 = System.currentTimeMillis()
        runBlocking {
            val job = this.launch(kotlinx.coroutines.Dispatchers.Default) {
                com.mobileailocal.domain.blockingWithTimeout(scope, 60_000) { Thread.sleep(3000); 1 }
            }
            kotlinx.coroutines.delay(100)
            job.cancel()
            job.join()
        }
        assertTrue(System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun blockingCallPassesResultAndExceptions() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        assertEquals(5, run { com.mobileailocal.domain.blockingWithTimeout(scope, 1000) { 5 } })
        try {
            run { com.mobileailocal.domain.blockingWithTimeout<Int>(scope, 1000) { throw WorkspaceException("ENOENT", "x") } }
            fail()
        } catch (e: WorkspaceException) {
            assertEquals("ENOENT", e.code)
        }
    }

    @Test fun readUpToStopsAtLimit() {
        val data = ByteArray(10_000) { 65 }
        assertEquals(1001, com.mobileailocal.data.readUpTo(java.io.ByteArrayInputStream(data), 1001).size)
        assertEquals(10_000, com.mobileailocal.data.readUpTo(java.io.ByteArrayInputStream(data), 20_000).size)
    }
}
