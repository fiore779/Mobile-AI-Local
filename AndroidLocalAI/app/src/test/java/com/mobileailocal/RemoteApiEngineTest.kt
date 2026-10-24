package com.mobileailocal

import com.mobileailocal.data.CloudHttp
import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CloudCredentials
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.CloudModelRef
import com.mobileailocal.domain.GenerationParams
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.RemoteApiEngine
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.defaultRetryDelayMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 手書きの小さな HTTP サーバー(ServerSocket)を相手に、再送・中止・切断を確かめる。 */
class RemoteApiEngineTest {
    private lateinit var server: ServerSocket
    private val connections = AtomicInteger()
    private val requestBodies = CopyOnWriteArrayList<String>()
    private val clientClosed = CountDownLatch(1)

    /** n 番目(0 始まり)の接続を処理する。 */
    @Volatile private var handler: (Int, Socket, String) -> Unit = { _, _, _ -> }

    @Before fun start() {
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                val n = connections.getAndIncrement()
                Thread {
                    try {
                        val input = s.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; head.append(c.toChar()) }
                        val len = Regex("(?i)content-length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = String(input.readNBytes(len), Charsets.UTF_8)
                        requestBodies += body
                        handler(n, s, body)
                    } catch (_: Exception) {
                    } finally {
                        try { s.close() } catch (_: Exception) {}
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() { server.close() }

    private fun OutputStream.respond(status: Int, headers: String = "", body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        write(b); flush()
    }

    private fun OutputStream.sseHead() {
        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); flush()
    }

    private fun OutputStream.sse(text: String) { write(text.toByteArray(Charsets.UTF_8)); flush() }

    private fun chunk(s: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$s\"}}]}\n\n"

    private fun engine(type: ProviderType = ProviderType.OpenAi): RemoteApiEngine {
        val integ = CloudIntegration("i1", type, "T", "http://127.0.0.1:${server.localPort}/v1", listOf("m"), "m", 0, 0)
        val creds = object : CloudCredentials {
            override suspend fun integration(id: String) = integ.takeIf { it.id == id }
            override suspend fun apiKey(integrationId: String) = "KEY"
        }
        return RemoteApiEngine(creds, CloudHttp(connectTimeoutMs = 2000, readTimeoutMs = 3000), retryDelayMs = { _, _ -> 0L })
            .also { it.current = CloudModelRef("i1", "m") }
    }

    private val msgs = listOf(ChatMessage(Role.User, "hi"))
    private fun RemoteApiEngine.run() = runBlocking { withTimeout(10_000) { generate(msgs, GenerationParams()).toList() } }
    private fun errorOf(block: () -> Unit): ApiError = try { block(); throw AssertionError("no error") } catch (e: ApiError) { e }

    @Test fun streamsText() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("A") + chunk("B")); o.sse("data: [DONE]\n\n")
        }
        assertEquals(listOf("A", "B"), engine().run())
        assertEquals(1, connections.get())
        assertTrue(requestBodies[0].contains("\"stream\":true"))
    }

    @Test fun retriesTwiceThenFailsOnThirdBeforeAnyText() {
        handler = { _, s, _ -> s.getOutputStream().respond(429, body = """{"error":{"code":"rate_limit_exceeded"}}""") }
        val e = errorOf { engine().run() }
        assertEquals(ApiErrorKind.RateLimited, e.kind)
        assertEquals(3, connections.get()) // 最初 + 再送 2 回
    }

    @Test fun retrySucceedsOnSecondTry() {
        handler = { n, s, _ ->
            val o = s.getOutputStream()
            if (n == 0) o.respond(503, body = """{"error":{"code":"server_is_overloaded"}}""")
            else { o.sseHead(); o.sse(chunk("ok")); o.sse("data: [DONE]\n\n") }
        }
        assertEquals(listOf("ok"), engine().run())
        assertEquals(2, connections.get())
    }

    @Test fun billingErrorsAreNotRetried() {
        handler = { _, s, _ -> s.getOutputStream().respond(429, body = """{"error":{"code":"credit_balance_exhausted"}}""") }
        assertEquals(ApiErrorKind.BillingRequired, errorOf { engine().run() }.kind)
        assertEquals(1, connections.get())
    }

    @Test fun authErrorIsNotRetried() {
        handler = { _, s, _ -> s.getOutputStream().respond(401, body = """{"error":{"message":"bad key"}}""") }
        assertEquals(ApiErrorKind.AuthInvalid, errorOf { engine().run() }.kind)
        assertEquals(1, connections.get())
    }

    @Test fun cutAfterTextIsNotRetried() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("partial")) // [DONE] なしで閉じる
        }
        val got = mutableListOf<String>()
        val e = errorOf {
            runBlocking { withTimeout(10_000) { engine().generate(msgs, GenerationParams()).collect { got += it } } }
        }
        assertEquals(ApiErrorKind.StreamCut, e.kind)
        assertEquals(listOf("partial"), got)
        assertEquals(1, connections.get())
    }

    @Test fun serverErrorEventAfterTextIsNotRetried() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("x"))
            o.sse("data: {\"error\":{\"code\":\"server_error\",\"type\":\"server_error\"}}\n\n")
        }
        val e = errorOf { engine().run() }
        assertEquals(ApiErrorKind.ServerError, e.kind)
        assertEquals(1, connections.get())
    }

    @Test fun cancellingClosesTheConnection() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("first"))
            // クライアントが切るまで書き続け、切断(書き込みの失敗)を検知する
            try { while (true) { Thread.sleep(50); o.sse(": keep\n"); } } catch (_: Exception) { clientClosed.countDown() }
        }
        val first = runBlocking { withTimeout(10_000) { engine().generate(msgs, GenerationParams()).first() } }
        assertEquals("first", first)
        assertTrue("接続が閉じられていない", clientClosed.await(5, TimeUnit.SECONDS))
    }

    @Test fun retriesWithoutSamplingWhenRejected() {
        handler = { n, s, _ ->
            val o = s.getOutputStream()
            if (n == 0) o.respond(400, body = """{"error":{"message":"Unsupported parameter: temperature","code":"unsupported_parameter"}}""")
            else { o.sseHead(); o.sse(chunk("ok")); o.sse("data: [DONE]\n\n") }
        }
        val eng = engine()
        assertEquals(listOf("ok"), eng.run())
        assertEquals(2, connections.get())
        assertTrue(JSONObject(requestBodies[0]).has("temperature"))
        assertFalse(JSONObject(requestBodies[1]).has("temperature"))
        assertFalse(JSONObject(requestBodies[1]).has("top_p"))
        // 覚えているので、次の呼び出しは最初から送らない
        assertEquals(listOf("ok"), eng.run())
        assertFalse(JSONObject(requestBodies[2]).has("temperature"))
    }

    @Test fun swapsMaxTokensName() {
        handler = { n, s, _ ->
            val o = s.getOutputStream()
            if (n == 0) o.respond(400, body = """{"error":{"message":"Use max_completion_tokens instead of max_tokens"}}""")
            else { o.sseHead(); o.sse(chunk("ok")); o.sse("data: [DONE]\n\n") }
        }
        assertEquals(listOf("ok"), engine().run())
        assertTrue(JSONObject(requestBodies[0]).has("max_tokens"))
        assertTrue(JSONObject(requestBodies[1]).has("max_completion_tokens"))
        assertFalse(JSONObject(requestBodies[1]).has("max_tokens"))
    }

    @Test fun invalidRequestWithoutHintIsNotRetried() {
        handler = { _, s, _ -> s.getOutputStream().respond(400, body = """{"error":{"message":"bad"}}""") }
        assertEquals(ApiErrorKind.InvalidRequest, errorOf { engine().run() }.kind)
        assertEquals(1, connections.get())
    }

    @Test fun connectionRefusedIsNetwork() {
        val eng = engine()
        server.close()
        assertEquals(ApiErrorKind.Network, errorOf { eng.run() }.kind)
    }

    @Test fun noSelectionAndMissingKey() {
        val eng = engine()
        runBlocking { eng.unload() }
        assertFalse(eng.isLoaded)
        assertEquals(ApiErrorKind.Unknown, errorOf { eng.run() }.kind)
        val noKey = RemoteApiEngine(object : CloudCredentials {
            override suspend fun integration(id: String) = CloudIntegration(id, ProviderType.OpenAi, "T", "http://x", listOf("m"), null, 0, 0)
            override suspend fun apiKey(integrationId: String): String? = null
        }, CloudHttp())
        noKey.current = CloudModelRef("i", "m")
        assertEquals(ApiErrorKind.AuthInvalid, errorOf { noKey.run() }.kind)
    }

    @Test fun manyUnreadableLinesAreAnError() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("a"))
            repeat(25) { o.sse("data: <<garbage>>\n\n") }
            o.sse("data: [DONE]\n\n")
        }
        assertEquals(ApiErrorKind.Unknown, errorOf { engine().run() }.kind)
    }

    @Test fun truncationByMaxTokensIsReported() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("a"))
            o.sse("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n")
            o.sse("data: [DONE]\n\n")
        }
        val eng = engine()
        assertEquals(listOf("a"), eng.run())
        assertTrue(eng.truncated)
        // 次の生成の開始で戻る
        handler = { _, s, _ -> val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("b")); o.sse("data: [DONE]\n\n") }
        assertEquals(listOf("b"), eng.run())
        assertFalse(eng.truncated)
    }

    @Test fun cancelledStreamIsNotReportedAsStreamCut() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead(); o.sse(chunk("x")) // 閉じるまで何も送らない
            Thread.sleep(3000)
        }
        val eng = engine()
        // 最初の文字で collect を打ち切る(取り消し)。StreamCut などの ApiError は出ない
        val first = runBlocking { withTimeout(10_000) { eng.generate(msgs, GenerationParams()).first() } }
        assertEquals("x", first)
    }

    @Test fun fewUnreadableLinesAreSkipped() {
        handler = { _, s, _ ->
            val o = s.getOutputStream(); o.sseHead()
            repeat(5) { o.sse("data: <<garbage>>\n\n") }
            o.sse(chunk("a"))
            repeat(5) { o.sse("data: <<garbage>>\n\n") }
            o.sse("data: [DONE]\n\n")
        }
        assertEquals(listOf("a"), engine().run())
    }

    @Test fun retryDelays() {
        assertEquals(2000L, defaultRetryDelayMs(0, null))
        assertEquals(4000L, defaultRetryDelayMs(1, null))
        assertEquals(5000L, defaultRetryDelayMs(0, 5))
        assertEquals(30000L, defaultRetryDelayMs(0, 999))
        assertNull(null) // 形式合わせ
    }
}
