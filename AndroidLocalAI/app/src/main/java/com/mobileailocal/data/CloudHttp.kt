package com.mobileailocal.data

import com.mobileailocal.domain.CloudTimeoutException
import com.mobileailocal.domain.HttpRequestSpec
import com.mobileailocal.domain.TimeoutPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 時間切れの判定(純粋なロジック。時計は差し替えられる)。
 * 接続(別に socket の connectTimeout で扱う)の後、リクエストを送ってから最初の本文のバイトが来るまでを
 * FirstByte、最初のバイトの後の無通信を Idle として数える。
 */
class CloudTimeoutState(
    private val firstByteMs: Long,
    private val idleMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var armedAt = -1L
    @Volatile private var lastByteAt = -1L
    @Volatile var fired: TimeoutPhase? = null
        private set

    /** 接続できた。ここから「最初の応答」の待ちを数える。 */
    fun arm() { armedAt = clock() }

    /** 本文のバイトを受け取った。 */
    fun onBytes() { lastByteAt = clock() }

    /** 時間切れなら、その種別を返す(以後は同じ値を返し続ける)。 */
    fun check(): TimeoutPhase? {
        fired?.let { return it }
        if (armedAt < 0) return null
        val now = clock()
        val phase = when {
            lastByteAt < 0 && now - armedAt > firstByteMs -> TimeoutPhase.FirstByte
            lastByteAt >= 0 && now - lastByteAt > idleMs -> TimeoutPhase.Idle
            else -> null
        }
        fired = phase
        return phase
    }
}

/** 応答。status と headers は接続済み。本文は 1 行ずつ(または全文)読む。 */
class CloudResponse internal constructor(
    val status: Int,
    /** キーは小文字、値は先頭の 1 つ。 */
    val headers: Map<String, String>,
    private val reader: BufferedReader,
    private val timeouts: CloudTimeoutState,
    private val timeoutException: (TimeoutPhase) -> CloudTimeoutException,
) {
    /** 時間切れで接続を切られたあとの読み取りの失敗は、区別された時間切れとして投げる。 */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw timeouts.fired?.let(timeoutException) ?: e
    }

    /** 1 行読む(行末の CR/LF は除く)。終わりなら null。ブロッキングなので IO で読む。 */
    suspend fun readLine(): String? = withContext(Dispatchers.IO) { guarded { reader.readLine() } }

    /** 本文を最大 maxChars 文字まで読む(エラー本文用)。 */
    suspend fun readText(maxChars: Int = 64 * 1024): String = withContext(Dispatchers.IO) {
        guarded {
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (sb.length < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }
}

/**
 * HttpURLConnection の薄いラッパー。コルーチンが取り消されたら接続を切って、ブロック中の読み取りを止める。
 * キー(ヘッダー)・本文はログに出さない。リダイレクトは追わない(キーを別ホストへ送らないため)。
 *
 * 時間切れは 3 種類に分ける: 接続(connectTimeoutMs)/ 最初の応答が来ない(firstByteTimeoutMs。
 * 推論型のモデルは長くかかる)/ 途中で止まった(idleTimeoutMs)。HttpURLConnection の readTimeout は
 * 接続後に切り替えられないので、見張りのコルーチンが時間を数え、超えたら接続を切る。
 * 取り消しも同じ見張りが接続を切るので、待っている間でも中止がすぐ効く。
 */
class CloudHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val firstByteTimeoutMs: Int = 120_000,
    private val idleTimeoutMs: Int = 60_000,
    private val pollMs: Long = 250,
) {
    suspend fun <T> request(spec: HttpRequestSpec, block: suspend (CloudResponse) -> T): T = coroutineScope {
        val timeouts = CloudTimeoutState(firstByteTimeoutMs.toLong(), idleTimeoutMs.toLong())
        val conn = withContext(Dispatchers.IO) {
            (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                // 保険。実際の時間切れは見張りが数える(最大の待ちより少し長くしておく)
                readTimeout = maxOf(firstByteTimeoutMs, idleTimeoutMs) + 30_000
                requestMethod = spec.method
                instanceFollowRedirects = false
                useCaches = false
                if (spec.body != null) doOutput = true // connect() の前に決める
                // SSE が圧縮でまとめて届かないようにする
                setRequestProperty("Accept-Encoding", "identity")
                spec.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        }
        fun exception(phase: TimeoutPhase) = CloudTimeoutException(
            phase,
            when (phase) {
                TimeoutPhase.Connect -> connectTimeoutMs / 1000
                TimeoutPhase.FirstByte -> firstByteTimeoutMs / 1000
                TimeoutPhase.Idle -> idleTimeoutMs / 1000
            },
        )
        // 見張り: 取り消されたら接続を切る。時間切れでも切る(ブロック中の読み取りは切断で IOException になって止まる)
        val watcher = launch {
            try {
                while (isActive && timeouts.check() == null) delay(pollMs)
                if (isActive) return@launch // 時間切れ: finally で切る
                awaitCancellation()
            } finally {
                conn.disconnect()
            }
        }
        try {
            val response = withContext(Dispatchers.IO) {
                try {
                    conn.connect()
                } catch (e: SocketTimeoutException) {
                    throw exception(TimeoutPhase.Connect)
                }
                timeouts.arm()
                try {
                    if (spec.body != null) {
                        conn.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = conn.responseCode
                    val raw = if (status in 200..299) conn.inputStream else (conn.errorStream ?: "".byteInputStream())
                    val headers = LinkedHashMap<String, String>()
                    for ((k, v) in conn.headerFields) if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v[0]
                    val monitored = object : FilterInputStream(raw) {
                        override fun read(): Int = super.read().also { if (it >= 0) timeouts.onBytes() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) timeouts.onBytes() }
                    }
                    CloudResponse(
                        status, headers, BufferedReader(InputStreamReader(monitored as InputStream, Charsets.UTF_8)),
                        timeouts, ::exception,
                    )
                } catch (e: IOException) {
                    throw timeouts.fired?.let(::exception) ?: e
                }
            }
            block(response)
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }
}
