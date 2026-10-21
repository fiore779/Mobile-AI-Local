package com.mobileailocal.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** M0 で llama.cpp が入るまでの代替。動作確認用に入力を言い換えて返す。 */
class FakeEngine : InferenceEngine {
    override var isLoaded = false
        private set

    override suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit) {
        for (i in 1..5) { delay(100); onProgress(i / 5f) }
        isLoaded = true
    }

    override suspend fun unload() { isLoaded = false }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<String> = flow {
        val last = messages.lastOrNull { it.role == Role.User }?.content.orEmpty()
        for (word in "(テスト応答) $last".split(" ")) {
            delay(80)
            emit("$word ")
        }
    }
}
