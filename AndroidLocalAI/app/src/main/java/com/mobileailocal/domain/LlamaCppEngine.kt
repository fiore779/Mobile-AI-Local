package com.mobileailocal.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** プロンプト処理の進捗(処理済み/全体)を表示用の割合にする。全体が 0 以下、または終わったら null(表示を消す)。0〜99 に収める。 */
fun prefillPercentOf(done: Int, total: Int): Int? =
    if (total <= 0 || done >= total) null else (done.coerceAtLeast(0).toLong() * 100 / total).toInt().coerceIn(0, 99)

/** JNI から呼ばれる受け口。accept が false を返すと生成を止める。progress はプロンプトの処理済み/全体(トークン数。再利用した分は除く)。 */
interface TokenSink {
    fun accept(piece: String): Boolean
    fun progress(done: Int, total: Int)
}

/** llama.cpp(JNI)による端末内推論。ネイティブ側は1組のモデルだけを持つので、操作は Mutex で直列にする。 */
class LlamaCppEngine : InferenceEngine {
    private val lock = Mutex()

    @Volatile
    override var isLoaded = false
        private set

    private val _prefill = MutableStateFlow<Int?>(null)
    override val prefillPercent: StateFlow<Int?> = _prefill

    override suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit) {
        nativeStop()
        lock.withLock {
            isLoaded = false
            onProgress(0f)
            // 読み込みは止められない native 呼び出しなので、取り消されたら nativeStop で読み込みを打ち切らせる
            // (コルーチンの取り消しだけだと、読み終わるまで戻ってこない)
            val err = coroutineScope {
                val loader = async(Dispatchers.IO) { nativeLoad(modelPath, params.contextLength, params.threads) }
                try {
                    loader.await()
                } catch (e: CancellationException) {
                    nativeStop()
                    throw e
                }
            }
            if (err.isNotEmpty()) error(err)
            isLoaded = true
            onProgress(1f)
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
                // 停止フラグは、ここで下ろしてから「まだ有効か」を確かめる。順序が逆だと、
                // 取り消しで立てた停止要求を下ろしてしまい、プロンプト処理を最後まで続けてしまう。
                nativeClearStop()
                if (!isActive) { close(); return@withLock }
                _prefill.value = 0
                try {
                    val err = nativeGenerate(
                        roles, contents, params.temperature, params.topP, params.maxTokens,
                        System.nanoTime().toInt(),
                        object : TokenSink {
                            // 取りこぼし防止: バッファが満杯なら JNI(IO)スレッドをブロックして待つ
                            override fun accept(piece: String): Boolean {
                                if (_prefill.value != null) _prefill.value = null
                                return trySendBlocking(piece).isSuccess && isActive
                            }
                            override fun progress(done: Int, total: Int) {
                                // 塊(128 トークン)ごとに呼ばれる。100% になったら表示を消す
                                _prefill.value = prefillPercentOf(done, total)
                            }
                        },
                    )
                    if (err.isNotEmpty()) close(IllegalStateException(err)) else close()
                } finally {
                    _prefill.value = null
                }
            }
        }
        awaitClose { nativeStop(); job.cancel() }
    }.flowOn(Dispatchers.IO)

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String
    private external fun nativeUnload()
    private external fun nativeStop()
    private external fun nativeClearStop()
    private external fun nativeGenerate(
        roles: Array<String>, contents: Array<ByteArray>,
        temperature: Float, topP: Float, maxTokens: Int, seed: Int, sink: TokenSink,
    ): String

    companion object {
        init { System.loadLibrary("mal_llama") }
    }
}
