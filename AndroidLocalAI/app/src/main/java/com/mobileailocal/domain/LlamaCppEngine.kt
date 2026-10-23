package com.mobileailocal.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** JNI から呼ばれる受け口。false を返すと生成を止める。 */
fun interface TokenSink {
    fun accept(piece: String): Boolean
}

/** llama.cpp(JNI)による端末内推論。ネイティブ側は1組のモデルだけを持つので、操作は Mutex で直列にする。 */
class LlamaCppEngine : InferenceEngine {
    private val lock = Mutex()

    @Volatile
    override var isLoaded = false
        private set

    override suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit) {
        nativeStop()
        lock.withLock {
            withContext(Dispatchers.IO) {
                isLoaded = false
                onProgress(0f)
                val err = nativeLoad(modelPath, params.contextLength, params.threads)
                if (err.isNotEmpty()) error(err)
                isLoaded = true
                onProgress(1f)
            }
        }
    }

    override suspend fun unload() {
        nativeStop()
        lock.withLock {
            withContext(Dispatchers.IO) { nativeUnload(); isLoaded = false }
        }
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<String> = channelFlow {
        if (!isLoaded) error(NativeError.NoModel.code)
        val roles = messages.map { it.role.name.lowercase() }.toTypedArray()
        // 本文は UTF-8 のバイト列で渡す(JNI の文字列は絵文字を壊すため)
        val contents = messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val job = launch(Dispatchers.IO) {
            lock.withLock {
                val err = nativeGenerate(
                    roles, contents, params.temperature, params.topP, params.maxTokens,
                    System.nanoTime().toInt(),
                    // 取りこぼし防止: バッファが満杯なら JNI(IO)スレッドをブロックして待つ
                    TokenSink { piece -> trySendBlocking(piece).isSuccess && isActive },
                )
                if (err.isNotEmpty()) close(IllegalStateException(err)) else close()
            }
        }
        awaitClose { nativeStop(); job.cancel() }
    }.flowOn(Dispatchers.IO)

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String
    private external fun nativeUnload()
    private external fun nativeStop()
    private external fun nativeGenerate(
        roles: Array<String>, contents: Array<ByteArray>,
        temperature: Float, topP: Float, maxTokens: Int, seed: Int, sink: TokenSink,
    ): String

    companion object {
        init { System.loadLibrary("mal_llama") }
    }
}
