package com.mobileailocal.domain

import kotlinx.coroutines.flow.Flow

enum class Role { System, User, Assistant }

data class ChatMessage(val role: Role, val content: String)

data class GenerationParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    /** 既定は端末のコア数の半分(性能コアに合わせる目安。2〜6)。設定で変更できる。 */
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6),
)

/** 推論エンジンの境界。ローカル(llama.cpp)・外部API・テスト用を差し替えられる。 */
interface InferenceEngine {
    val isLoaded: Boolean

    /** モデルを読み込む。進捗は 0f..1f で通知する。 */
    suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit = {})

    suspend fun unload()

    /** 生成結果を1トークン(断片)ずつ流す。collect を止めると生成も止まる。 */
    fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<String>
}
