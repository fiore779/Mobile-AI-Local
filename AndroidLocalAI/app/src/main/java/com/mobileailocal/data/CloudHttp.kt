package com.mobileailocal.data

import com.mobileailocal.domain.HttpRequestSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 応答。status と headers は接続済み。本文は 1 行ずつ(または全文)読む。 */
class CloudResponse internal constructor(
    val status: Int,
    /** キーは小文字、値は先頭の 1 つ。 */
    val headers: Map<String, String>,
    private val reader: BufferedReader,
) {
    /** 1 行読む(行末の CR/LF は除く)。終わりなら null。ブロッキングなので IO で読む。 */
    suspend fun readLine(): String? = withContext(Dispatchers.IO) { reader.readLine() }

    /** 本文を最大 maxChars 文字まで読む(エラー本文用)。 */
    suspend fun readText(maxChars: Int = 64 * 1024): String = withContext(Dispatchers.IO) {
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

/**
 * HttpURLConnection の薄いラッパー。コルーチンが取り消されたら接続を切って、ブロック中の読み取りを止める。
 * キー(ヘッダー)・本文はログに出さない。リダイレクトは追わない(キーを別ホストへ送らないため)。
 */
class CloudHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    /**
     * リクエストを送り、応答を block に渡す。block が終わる(または取り消される)と接続を閉じる。
     * 接続できないなどの通信例外は IOException のまま投げる(分類は呼び出し側)。
     */
    suspend fun <T> request(spec: HttpRequestSpec, block: suspend (CloudResponse) -> T): T = coroutineScope {
        val conn = withContext(Dispatchers.IO) {
            (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = spec.method
                instanceFollowRedirects = false
                useCaches = false
                // SSE が圧縮でまとめて届かないようにする
                setRequestProperty("Accept-Encoding", "identity")
                spec.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        }
        // 取り消されたら接続を切る(ブロック中の読み取りは、切断で IOException になって止まる)
        val watcher = launch {
            try { awaitCancellation() } finally { conn.disconnect() }
        }
        try {
            val response = withContext(Dispatchers.IO) {
                if (spec.body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: "".byteInputStream())
                val headers = LinkedHashMap<String, String>()
                for ((k, v) in conn.headerFields) if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v[0]
                CloudResponse(status, headers, BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            }
            block(response)
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }
}
