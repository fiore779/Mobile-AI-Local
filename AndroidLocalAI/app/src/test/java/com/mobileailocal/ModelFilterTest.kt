package com.mobileailocal

import com.mobileailocal.domain.ModelFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {
    @Test fun chatModelsAreKept() {
        for (n in listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-5", "o3-mini", "claude-sonnet-5-5", "claude-opus-4",
            "gemini-3.8-flash", "gemini-3.8-flash-lite", "models/gemini-2.5-pro", "gemma-4-26b-a4b-it",
            "llama-3.1-70b", "mistral-large-latest", "olive-chat", "deliver-7b",
        )) assertTrue(n, ModelFilter.isChatModel(n))
    }

    @Test fun nonChatModelsAreRemoved() {
        for (n in listOf(
            "gemini-3.8-flash-lite-tts", "gpt-4o-mini-tts", "tts-1", "tts-1-hd", "models/gemini-2.5-flash-preview-tts",
            "gpt-image-1", "gemini-2.5-flash-image", "imagen-4.0-generate", "dall-e-3", "veo-3.0", "sora-2",
            "text-embedding-3-small", "gemini-embedding-001", "embedding-gecko", "models/embed-001",
            "aqa", "gpt-4o-audio-preview", "gpt-4o-realtime-preview", "gemini-live-2.5-flash",
            "whisper-1", "gpt-4o-transcribe", "omni-moderation-latest", "text-moderation-stable", "rerank-v3",
        )) assertFalse(n, ModelFilter.isChatModel(n))
    }

    @Test fun visibleListRespectsSelectionAndShowAll() {
        val all = listOf("gpt-4o", "tts-1", "text-embedding-3-small")
        assertEquals(listOf("gpt-4o"), ModelFilter.visible(all, emptySet(), showAll = false))
        // すでに選んでいる(手入力や編集中の)ものは、除外対象でも隠さない
        assertEquals(listOf("gpt-4o", "tts-1"), ModelFilter.visible(all, setOf("tts-1"), showAll = false))
        assertEquals(all, ModelFilter.visible(all, emptySet(), showAll = true))
    }
}
