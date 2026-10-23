package com.mobileailocal.data

import android.content.Context
import android.net.ConnectivityManager
import com.mobileailocal.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 1つのダウンロードの状態。error が null でなければ失敗。 */
data class DownloadState(val done: Long, val total: Long, val error: String? = null) {
    val fraction get() = if (total > 0) done.toFloat() / total else 0f
}

/** Wi-Fi 限定の設定で、従量制回線のためダウンロードを止めるべきか。 */
fun shouldBlockForMetered(wifiOnly: Boolean, metered: Boolean): Boolean = wifiOnly && metered

/**
 * ダウンロードを画面の寿命と切り離して続けるための管理役(アプリ全体で1つ)。
 * キーは RemoteFile.url。完了したものは一覧から消え、導入済みモデルに移る。
 */
class DownloadTracker(
    private val context: Context,
    private val repo: ModelRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    @Synchronized
    fun start(file: RemoteFile) {
        val old = jobs[file.url]
        if (old?.isActive == true && !old.isCancelled) return
        _states.update { it + (file.url to DownloadState(0, 0)) }
        jobs[file.url] = scope.launch {
            // 取り消した直後の古いジョブが .part に書き終えてから始める(二重書き込みの防止)
            old?.cancelAndJoin()
            try {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                if (shouldBlockForMetered(settings.settings.first().wifiOnly, cm.isActiveNetworkMetered)) {
                    _states.update { it + (file.url to DownloadState(0, 0, context.localizedString(R.string.download_wifi_required))) }
                    return@launch
                }
                repo.download(file) { done, total ->
                    _states.update { it + (file.url to DownloadState(done, total)) }
                }
                _states.update { it - file.url }
            } catch (e: CancellationException) {
                // 状態は cancel() が消している(ここで消すと、直後に始めた新しいダウンロードの状態を消してしまう)
                throw e
            } catch (e: Exception) {
                // 取り消し後に来た例外(切断による IOException など)でエラーを書かない
                coroutineContext.ensureActive()
                val msg = if (e is HttpStatusException) context.localizedString(R.string.download_http_error, e.code)
                else e.message ?: e.toString()
                _states.update { it + (file.url to DownloadState(0, 0, msg)) }
            }
        }
    }

    @Synchronized
    fun cancel(file: RemoteFile) {
        jobs[file.url]?.cancel() // 完全に止まるまで jobs に残し、次の start が待つ
        _states.update { it - file.url }
    }
}
