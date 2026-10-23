package com.mobileailocal

import com.mobileailocal.data.JsRunManager
import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.domain.BlockKey
import com.mobileailocal.domain.JsExecutor
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunRequest
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 設計書 6.3 の状態遷移(470〜489 行)と 10.1 の JsRunManager の行(608 行)のテスト */
@OptIn(ExperimentalCoroutinesApi::class)
class JsRunManagerTest {
    private lateinit var tmp: File
    private lateinit var executor: FakeExecutor
    private var enabled = true
    private var freeMb = 1000L
    private var now = 1000L
    private lateinit var scope: TestScope
    private lateinit var manager: JsRunManager

    private val key = BlockKey(0, 0, 123)

    @Before fun setUp() {
        tmp = Files.createTempDirectory("jsrm").toFile()
        executor = FakeExecutor()
        scope = TestScope(UnconfinedTestDispatcher())
        manager = buildManager(scope)
    }

    @After fun tearDown() {
        scope.cancel()
        deleteTree(tmp)
    }

    private fun buildManager(s: TestScope) = JsRunManager(
        workspace = JsWorkspace(tmp), executor = executor, jsEnabled = { enabled },
        scope = s, clock = { now }, freeMemoryMb = { freeMb },
    )

    private fun ok(id: Int) = JsRunResult(id, JsRunStatus.Ok, "2", "", null, 5, false, null)

    private fun finished(): JsRunState.Finished = manager.state.value as JsRunState.Finished

    private fun deleteTree(dir: File) {
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    try { Files.deleteIfExists(file) } catch (_: IOException) {}
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(d); return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }

    // ---- 1. 初期状態 ----

    @Test fun initialStateIsIdle() {
        assertEquals(JsRunState.Idle, manager.state.value)
    }

    // ---- 2. 実行 → 結果 ----

    @Test fun runThenFinish() {
        assertTrue(manager.run(key, "1+1"))
        assertEquals(JsRunState.Running(key, 1000L), manager.state.value)

        assertEquals(1, executor.requests.size)
        val req = executor.requests[0]
        assertEquals(1, req.id)
        assertEquals("1+1", req.code)
        assertEquals(tmp.path, req.workDir)
        assertEquals(JsLimits.TIMEOUT_MS, req.timeoutMs)

        executor.complete(1, ok(1))
        assertEquals(JsRunState.Finished(key, ok(1)), manager.state.value)
    }

    // ---- 3. 実行中の実行は拒否 ----

    @Test fun runWhileRunningIsRejected() {
        assertTrue(manager.run(key, "1+1"))
        assertFalse(manager.run(BlockKey(0, 1, 456), "2+2"))
        assertEquals(JsRunState.Running(key, 1000L), manager.state.value)
        assertEquals(1, executor.requests.size)
    }

    // ---- 4. dismiss ----

    @Test fun dismissFinishedToIdle() {
        assertTrue(manager.run(key, "1+1"))
        executor.complete(1, ok(1))
        manager.dismiss()
        assertEquals(JsRunState.Idle, manager.state.value)
    }

    @Test fun dismissWhileRunningDoesNothing() {
        assertTrue(manager.run(key, "1+1"))
        manager.dismiss()
        assertEquals(JsRunState.Running(key, 1000L), manager.state.value)
    }

    @Test fun dismissOnIdleStaysIdle() {
        manager.dismiss()
        assertEquals(JsRunState.Idle, manager.state.value)
    }

    // ---- 5. cancel ----

    @Test fun cancelWhileRunning() {
        assertTrue(manager.run(key, "1+1"))
        manager.cancel()
        assertEquals(1, executor.cancelCount)
        assertEquals(JsStopCause.UserCancel, finished().result.cause)
        assertEquals(JsRunStatus.Killed, finished().result.status)
        assertEquals(key, finished().key)

        // 遅れて届いた古い結果は無視
        executor.complete(1, ok(1))
        assertEquals(JsRunStatus.Killed, finished().result.status)
        assertEquals(JsStopCause.UserCancel, finished().result.cause)
        assertEquals(1, executor.cancelCount)
    }

    @Test fun cancelCancelsExecutorRunCoroutine() {
        // executor.run が接続待ちなどで止まっている間に cancel しても、executor 側の run が取り消される
        assertTrue(manager.run(key, "1+1"))
        manager.cancel()
        assertEquals(listOf(1), executor.cancelledIds)
    }

    @Test fun cancelAndClearCancelsExecutorRunCoroutine() {
        assertTrue(manager.run(key, "1+1"))
        manager.cancelAndClear()
        assertEquals(listOf(1), executor.cancelledIds)
        assertEquals(JsRunState.Idle, manager.state.value)
    }

    @Test fun cancelWhenNotRunningDoesNothing() {
        manager.cancel()
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(0, executor.cancelCount)

        assertTrue(manager.run(key, "1+1"))
        executor.complete(1, ok(1))
        manager.cancel()
        assertEquals(JsRunStatus.Ok, finished().result.status)
        assertEquals(0, executor.cancelCount)
    }

    // ---- 6. cancelAndClear ----

    @Test fun cancelAndClearFromRunning() {
        assertTrue(manager.run(key, "1+1"))
        manager.cancelAndClear()
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(1, executor.cancelCount)
    }

    @Test fun cancelAndClearFromFinished() {
        assertTrue(manager.run(key, "1+1"))
        executor.complete(1, ok(1))
        manager.cancelAndClear()
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(0, executor.cancelCount)
    }

    // ---- 7. 機能が無効 ----

    @Test fun disabledRejectsRun() {
        enabled = false
        assertFalse(manager.run(key, "1+1"))
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(0, executor.requests.size)
    }

    // ---- 8. コードの長さ・空白 ----

    @Test fun codeTooLongIsRejected() {
        assertFalse(manager.run(key, "a".repeat(JsLimits.MAX_CODE_CHARS + 1)))
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(0, executor.requests.size)
    }

    @Test fun codeExactlyMaxCharsIsAccepted() {
        assertTrue(manager.run(key, "a".repeat(JsLimits.MAX_CODE_CHARS)))
        assertEquals(JsRunState.Running(key, 1000L), manager.state.value)
    }

    @Test fun blankCodeIsRejected() {
        assertFalse(manager.run(key, "   \n"))
        assertEquals(JsRunState.Idle, manager.state.value)
        assertEquals(0, executor.requests.size)
    }

    // ---- 9. 空きメモリ ----

    @Test fun lowMemoryFinishesImmediately() {
        freeMb = JsLimits.MIN_FREE_MEMORY_MB - 1
        assertTrue(manager.run(key, "1+1"))
        assertEquals(JsRunStatus.Error, finished().result.status)
        assertEquals(JsStopCause.LowMemory, finished().result.cause)
        assertEquals(0, executor.requests.size)
    }

    @Test fun exactlyMinFreeMemoryRuns() {
        freeMb = JsLimits.MIN_FREE_MEMORY_MB
        assertTrue(manager.run(key, "1+1"))
        assertEquals(JsRunState.Running(key, 1000L), manager.state.value)
        assertEquals(1, executor.requests.size)
    }

    // ---- 10. executor が例外を投げる ----

    @Test fun executorThrowIsCrashed() {
        executor.throwOnRun = IllegalStateException("boom")
        assertTrue(manager.run(key, "1+1"))
        assertEquals(JsRunStatus.Crashed, finished().result.status)
        assertEquals(JsStopCause.ProcessDied, finished().result.cause)
    }

    // ---- 11. 連続実行 ----

    @Test fun sequentialRuns() {
        assertTrue(manager.run(key, "1+1"))
        executor.complete(1, ok(1))
        assertEquals(JsRunState.Finished(key, ok(1)), manager.state.value)

        val key2 = BlockKey(1, 0, 789)
        assertTrue(manager.run(key2, "2+2"))
        assertEquals(2, executor.requests.size)
        assertEquals(2, executor.requests[1].id)
        assertEquals("2+2", executor.requests[1].code)
        assertEquals(JsRunState.Running(key2, 1000L), manager.state.value)

        executor.complete(2, ok(2))
        assertEquals(JsRunState.Finished(key2, ok(2)), manager.state.value)
    }

    // ---- 12. 古い id の結果は無視 ----

    @Test fun staleResultIsIgnored() {
        assertTrue(manager.run(key, "1+1"))
        manager.cancel()
        assertEquals(JsRunStatus.Killed, finished().result.status)

        val key2 = BlockKey(0, 1, 456)
        assertTrue(manager.run(key2, "2+2"))
        assertEquals(2, executor.requests.size)

        // 1 回目の結果が遅れて届いても 2 回目を実行中のまま
        executor.complete(1, ok(1))
        assertEquals(JsRunState.Running(key2, 1000L), manager.state.value)

        executor.complete(2, ok(2))
        assertEquals(JsRunState.Finished(key2, ok(2)), manager.state.value)
    }

    // ---- 13. 作業フォルダ ----

    @Test fun workspaceClearAndUsage() = kotlinx.coroutines.runBlocking {
        val m = manager

        File(tmp, "a.txt").writeText("x")
        assertEquals(1, m.workspaceUsage().files)

        assertTrue(m.run(key, "1+1"))
        assertEquals(JsRunState.Running(key, 1000L), m.state.value)
        assertFalse(m.clearWorkspace())
        assertEquals(JsRunState.Running(key, 1000L), m.state.value)

        // 実行中のリクエストを片付けてから Idle にする
        executor.complete(1, ok(1))
        m.dismiss()
        assertEquals(JsRunState.Idle, m.state.value)

        assertTrue(m.clearWorkspace())
        assertArrayEquals(emptyArray<String>(), tmp.list())
        assertEquals(0, m.workspaceUsage().files)

    }
}

/** run() は受け取った request ごとに CompletableDeferred を作り、テストが complete() で結果を渡す */
private class FakeExecutor : JsExecutor {
    val requests = mutableListOf<JsRunRequest>()
    val pending = mutableListOf<Pair<JsRunRequest, CompletableDeferred<JsRunResult>>>()
    var cancelCount = 0
    var releaseCount = 0
    var throwOnRun: Throwable? = null
    val cancelledIds = mutableListOf<Int>()

    override suspend fun run(request: JsRunRequest): JsRunResult {
        val d = CompletableDeferred<JsRunResult>()
        requests.add(request)
        pending.add(request to d)
        throwOnRun?.let { throw it }
        try {
            return d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledIds.add(request.id) // 接続待ち・実行中のどちらでも、Job の取り消しが executor に届く
            throw e
        }
    }

    override fun cancel() {
        cancelCount++
    }

    override fun release() {
        releaseCount++
    }

    fun complete(id: Int, result: JsRunResult) {
        pending.first { it.first.id == id }.second.complete(result)
    }
}
