package com.mobileailocal

import com.mobileailocal.domain.AnthropicProvider
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CloudParams
import com.mobileailocal.domain.CloudProvider
import com.mobileailocal.domain.CloudProviders
import com.mobileailocal.domain.CloudRequest
import com.mobileailocal.domain.OpenAiCompatProvider
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.SseParser
import com.mobileailocal.domain.StreamEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProvidersTest {
    private val msgs = listOf(
        ChatMessage(Role.System, "sys"), ChatMessage(Role.User, "hi"),
        ChatMessage(Role.Assistant, "yo"), ChatMessage(Role.User, "again"),
    )

    private fun req(provider: CloudProvider, params: CloudParams = CloudParams(0.7f, 0.9f, 256)) =
        CloudRequest(provider.defaultBaseUrl.ifEmpty { "http://192.168.0.2:8080/v1" } + "/", "KEY123", "m1", msgs, params)

    /** SSE テキスト全体をプロバイダーに通して、イベント列にする。 */
    private fun run(p: CloudProvider, sse: String): List<StreamEvent> {
        val parser = SseParser()
        val out = mutableListOf<StreamEvent>()
        val evs = parser.feed(sse) + listOfNotNull(parser.finish())
        for (e in evs) p.parseEvent(e.event, e.data)?.let { if (it != StreamEvent.Unreadable) out.add(it) }
        return out
    }

    @Test fun registryHasEveryType() {
        for (t in ProviderType.entries) assertEquals(t, CloudProviders.of(t).type)
        assertEquals(ProviderType.entries.size, CloudProviders.all().size)
    }

    // --- OpenAI 互換 ---
    @Test fun openAiRequest() {
        val p = OpenAiCompatProvider.openAi()
        val spec = p.buildStreamRequest(req(p))
        assertEquals("https://api.openai.com/v1/chat/completions", spec.url)
        assertEquals("POST", spec.method)
        assertEquals("Bearer KEY123", spec.headers["Authorization"])
        val b = JSONObject(spec.body!!)
        assertEquals("m1", b.getString("model"))
        assertTrue(b.getBoolean("stream"))
        assertEquals(256, b.getInt("max_tokens"))
        assertEquals(0.7, b.getDouble("temperature"), 1e-6)
        assertEquals(0.9, b.getDouble("top_p"), 1e-6)
        assertFalse(b.has("context_length"))
        assertFalse(b.has("threads"))
        assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals(4, b.getJSONArray("messages").length())
        assertFalse(spec.toString().contains("KEY123"))
    }

    @Test fun openAiOptionalParams() {
        val p = OpenAiCompatProvider.openAi()
        val b = JSONObject(p.buildStreamRequest(req(p, CloudParams(null, null, 100, useMaxCompletionTokens = true))).body!!)
        assertFalse(b.has("temperature"))
        assertFalse(b.has("top_p"))
        assertFalse(b.has("max_tokens"))
        assertEquals(100, b.getInt("max_completion_tokens"))
    }

    @Test fun customWithoutKeyHasNoAuthHeader() {
        val p = OpenAiCompatProvider.custom()
        val spec = p.buildStreamRequest(req(p).copy(apiKey = ""))
        assertFalse(spec.headers.containsKey("Authorization"))
        assertEquals("http://192.168.0.2:8080/v1/chat/completions", spec.url)
    }

    @Test fun openAiStream() {
        val p = OpenAiCompatProvider.openAi()
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

            data: {"choices":[{"delta":{"content":"こん"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"think"}}]}

            data: {"choices":[{"delta":{"content":null}}]}

            data: {"choices":[]}

            data: {"choices":[{"delta":{"content":"にちは"},"finish_reason":null}]}

            data: not json

            data: [DONE]

        """.trimIndent() + "\n"
        val ev = run(p, sse)
        assertEquals(listOf(StreamEvent.Text("こん"), StreamEvent.Text("にちは"), StreamEvent.Done), ev)
    }

    @Test fun openAiStreamErrorAndEofWithoutDone() {
        val p = OpenAiCompatProvider.openAi()
        val ev = run(p, "data: {\"error\":{\"message\":\"x\",\"code\":\"rate_limit_exceeded\"}}\n\n")
        assertEquals(ApiErrorKind.RateLimited, (ev.single() as StreamEvent.Failure).error.kind)
        // [DONE] なしの EOF はイベント列に Done が無い(StreamCut の判定は RemoteApiEngine)
        assertFalse(run(p, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n").contains(StreamEvent.Done))
        val filtered = run(p, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n")
        assertEquals(ApiErrorKind.ContentBlocked, (filtered.single() as StreamEvent.Failure).error.kind)
        assertEquals(listOf(StreamEvent.MaxTokens), run(p, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"))
    }

    @Test fun openAiModelList() {
        val p = OpenAiCompatProvider.openAi()
        assertEquals(listOf("a", "b"), p.parseModelList("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
        val g = OpenAiCompatProvider.gemini()
        assertEquals(listOf("gemini-x"), g.parseModelList("""{"models":[{"name":"models/gemini-x"}]}"""))
        assertEquals(emptyList<String>(), p.parseModelList("oops"))
        val spec = p.buildListModelsRequest("https://api.openai.com/v1/", "K")
        assertEquals("https://api.openai.com/v1/models", spec.url)
        assertEquals("GET", spec.method)
        assertNull(spec.body)
    }

    // --- Anthropic ---
    @Test fun anthropicRequest() {
        val p = AnthropicProvider()
        val spec = p.buildStreamRequest(req(p, CloudParams(1.5f, 0.9f, 300)))
        assertEquals("https://api.anthropic.com/v1/messages", spec.url)
        assertEquals("KEY123", spec.headers["x-api-key"])
        assertEquals(AnthropicProvider.ANTHROPIC_VERSION, spec.headers["anthropic-version"])
        val b = JSONObject(spec.body!!)
        assertEquals(300, b.getInt("max_tokens"))
        assertEquals(1.0, b.getDouble("temperature"), 1e-6) // 1.0 に丸める
        assertFalse(b.has("top_p"))
        assertEquals("sys", b.getString("system"))
        val m = b.getJSONArray("messages")
        assertEquals(3, m.length()) // System は messages に入れない
        assertEquals("user", m.getJSONObject(0).getString("role"))
        assertTrue(b.getBoolean("stream"))
    }

    @Test fun anthropicNoTemperature() {
        val p = AnthropicProvider()
        val b = JSONObject(p.buildStreamRequest(req(p, CloudParams(null, null, 10))).body!!)
        assertFalse(b.has("temperature"))
    }

    private val anthropicStream = """
        event: message_start
        data: {"type":"message_start","message":{"id":"m","role":"assistant"}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: ping
        data: {"type":"ping"}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hel"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"lo"}}

        event: future_event
        data: {"type":"future_event"}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent() + "\n"

    @Test fun anthropicStream() {
        assertEquals(
            listOf(StreamEvent.Text("Hel"), StreamEvent.Text("lo"), StreamEvent.Done),
            run(AnthropicProvider(), anthropicStream),
        )
    }

    @Test fun anthropicErrorRefusalAndMaxTokens() {
        val p = AnthropicProvider()
        val err = run(p, "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
        assertEquals(ApiErrorKind.Overloaded, (err.single() as StreamEvent.Failure).error.kind)
        val ref = run(p, "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}\n\n")
        assertEquals(ApiErrorKind.ContentBlocked, (ref.single() as StreamEvent.Failure).error.kind)
        val mt = run(p, "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n")
        assertEquals(listOf(StreamEvent.MaxTokens), mt)
        // event 行が無くても data の type で解釈できる
        assertEquals(listOf(StreamEvent.Done), run(p, "data: {\"type\":\"message_stop\"}\n\n"))
    }

    @Test fun anthropicModelList() {
        val p = AnthropicProvider()
        assertEquals(listOf("claude-a", "claude-b"), p.parseModelList("""{"data":[{"id":"claude-a"},{"id":"claude-b"}]}"""))
        assertEquals(emptyList<String>(), p.parseModelList("x"))
        assertEquals("https://api.anthropic.com/v1/models", p.buildListModelsRequest(p.defaultBaseUrl, "K").url)
    }

    @Test fun requestToStringHidesKey() {
        val s = req(AnthropicProvider()).toString()
        assertFalse("KEY123" in s)
        assertTrue("••••" in s)
        assertTrue("4 件" in s)
    }
}
