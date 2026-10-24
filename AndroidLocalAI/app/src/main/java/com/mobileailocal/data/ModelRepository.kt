package com.mobileailocal.data

import android.content.Context
import android.os.StatFs
import com.mobileailocal.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 検索結果の1件(Hugging Face のリポジトリ)。 */
data class RemoteModel(val repoId: String, val downloads: Long, val likes: Long)

/** リポジトリ内の GGUF ファイル1つ。 */
data class RemoteFile(val repoId: String, val fileName: String, val sizeBytes: Long,
    /** LFS ファイルの SHA-256(16進)。LFS でない小さいファイルは null で、検証を省略する。 */
    val sha256: String? = null,
) {
    val url get() = "https://huggingface.co/$repoId/resolve/main/$fileName"
    /** ファイル名から量子化(Q4_K_M など)を推定する。 */
    val quant get() = Regex("(I?Q\\d[A-Z0-9_]*|F16|BF16|F32)", RegexOption.IGNORE_CASE)
        .find(fileName)?.value?.uppercase() ?: "?"
}

/** ダウンロード後の照合。期待値が null なら検証を省略して true。大文字小文字は区別しない。 */
fun sha256Matches(expected: String?, actual: String): Boolean =
    expected == null || expected.equals(actual, ignoreCase = true)

/** MessageDigest の結果を16進文字列にする。 */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** モデルファイルの保管・検索・ダウンロード・削除。 */
class ModelRepository(private val context: Context, private val dao: ModelDao) {
    private val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").also { it.mkdirs() }

    val installed: Flow<List<ModelEntity>> = dao.models()

    fun fileOf(model: ModelEntity): File = File(dir, model.fileName)

    /** 導入済みモデルの合計サイズ(登録されたファイルの実測。無いファイルは 0)。 */
    suspend fun installedBytes(): Long = withContext(Dispatchers.IO) {
        dao.models().first().sumOf { fileOf(it).length() }
    }

    /** モデルの保存先の空き容量(バイト)。 */
    suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        StatFs(dir.path).availableBytes
    }

    /** Hugging Face で GGUF モデルを検索する(ダウンロード数順)。 */
    suspend fun search(query: String): List<RemoteModel> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = httpGet("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=20")
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RemoteModel(o.getString("id"), o.optLong("downloads"), o.optLong("likes"))
        }
    }

    /** 人気の GGUF テキスト生成モデル(取得順=人気順)。失敗は例外。trendingScore が 400 なら downloads でやり直す。 */
    suspend fun popular(limit: Int = 30): List<RemoteModel> = withContext(Dispatchers.IO) {
        val base = "https://huggingface.co/api/models?filter=gguf&pipeline_tag=text-generation&direction=-1&limit=$limit"
        val json = try {
            httpGet("$base&sort=trendingScore")
        } catch (e: HttpStatusException) {
            if (e.code != 400) throw e
            httpGet("$base&sort=downloads")
        }
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RemoteModel(o.getString("id"), o.optLong("downloads"), o.optLong("likes"))
        }
    }

    /** 導入名(ModelEntity.fileName と同じ規則)。 */
    fun installedName(file: RemoteFile): String = file.repoId.replace('/', '_') + "__" + file.fileName

    /** リポジトリ内の .gguf ファイル一覧(分割ファイルは除く)。 */
    suspend fun files(repoId: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val arr = JSONArray(httpGet("https://huggingface.co/api/models/$repoId/tree/main"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "file" && it.getString("path").endsWith(".gguf") }
            .filterNot { Regex("-\\d{5}-of-\\d{5}").containsMatchIn(it.getString("path")) }
            .map { RemoteFile(repoId, it.getString("path"), it.optLong("size"),
                it.optJSONObject("lfs")?.optString("oid")?.takeIf { h -> h.isNotEmpty() }) }
            .sortedBy { it.sizeBytes }
    }

    /**
     * ダウンロードして導入する。途中まである場合は続きから再開する。
     * 進捗は onProgress(取得済みバイト, 全体バイト) で通知。キャンセルは coroutine の取り消しで行う。
     */
    suspend fun download(file: RemoteFile, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val safeName = installedName(file)
        val part = File(dir, "$safeName.part")
        val target = File(dir, safeName)
        var done = if (part.exists()) part.length() else 0L
        var digest = MessageDigest.getInstance("SHA-256")
        var retry = true
        while (retry) {
            val conn = (URL(file.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            retry = false
            try {
                coroutineScope {
                    // 取り消されたらすぐ接続を切って、ブロック中の read を解く(次の start と二重に書かないため)
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { conn.disconnect() } }
                    try {
                        val code = conn.responseCode
                        val action = resumeAction(done, code, file.sizeBytes) ?: throw HttpStatusException(code)
                        if (action == ResumeAction.Fresh && code == 416) {
                            // 長さが合わない .part は捨てて、最初からやり直す
                            part.delete(); done = 0L; retry = true
                        } else {
                            digest = MessageDigest.getInstance("SHA-256")
                            if (action == ResumeAction.Complete) {
                                // .part が既に全体(416): 全体をハッシュして検証する
                                part.inputStream().buffered().use { updateDigest(digest, it) { coroutineContext.ensureActive() } }
                            } else {
                                val start = if (action == ResumeAction.Resume) done else 0L
                                // 再開時は、既にある部分を先にハッシュへ通してから続きを足す
                                if (start > 0) part.inputStream().buffered().use {
                                    updateDigest(digest, it, start) { coroutineContext.ensureActive() }
                                }
                                val total = downloadTotal(start, conn.contentLengthLong)
                                RandomAccessFile(part, "rw").use { out ->
                                    out.seek(start); out.setLength(start)
                                    var got = start
                                    val buf = ByteArray(256 * 1024)
                                    conn.inputStream.use { input ->
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                                            onProgress(got, total)
                                        }
                                    }
                                }
                            }
                        }
                    } finally { watcher.cancel() }
                }
            } finally { conn.disconnect() }
        }
        // 改ざん・破損の検出。不一致なら中途半端なファイルを残さない
        if (shouldDiscard(file.sha256, digest.digest().toHex())) {
            part.delete(); target.delete()
            error(context.localizedString(R.string.download_hash_mismatch))
        }
        if (!part.renameTo(target)) {
            part.delete()
            error(context.localizedString(R.string.download_save_failed))
        }
        dao.upsert(
            ModelEntity(
                id = safeName, name = file.fileName.removeSuffix(".gguf"), fileName = safeName,
                sizeBytes = target.length(), quant = file.quant, contextLength = 4096,
                source = "download", license = "", installedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 導入済みモデルを削除(ファイルと登録の両方)。 */
    suspend fun delete(model: ModelEntity) = withContext(Dispatchers.IO) {
        fileOf(model).delete()
        dao.delete(model.id)
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 20000 }
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
