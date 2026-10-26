package com.mobileailocal.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.data.AppSettings
import com.mobileailocal.data.WorkspaceItem
import com.mobileailocal.domain.WorkspaceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 開いているファイルの内容(表示用)。binary なら text は空。 */
data class WorkspaceFileView(val path: String, val text: String, val truncated: Boolean, val binary: Boolean)

/** 作業フォルダ画面: 有効／無効、中身の一覧、内容表示、共有・書き出し・削除(利用者の操作だけ。モデルからは削除できない)。 */
class WorkspaceViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val repo = a.settings
    private val ws get() = a.workspace

    companion object {
        /** 内容表示で出す最大文字数。 */
        const val VIEW_MAX_CHARS = 20_000
    }

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 開いているディレクトリ(ルートからの相対。ルートは "")。 */
    private val _dir = MutableStateFlow("")
    val dir: StateFlow<String> = _dir

    private val _items = MutableStateFlow<List<WorkspaceItem>>(emptyList())
    val items: StateFlow<List<WorkspaceItem>> = _items

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes

    private val _file = MutableStateFlow<WorkspaceFileView?>(null)
    val file: StateFlow<WorkspaceFileView?> = _file

    /** 書き出し・共有などの結果の一言(成功 true / 失敗 false)。画面が読んだら consumeResult。 */
    private val _result = MutableStateFlow<Boolean?>(null)
    val result: StateFlow<Boolean?> = _result

    fun consumeResult() { _result.value = null }

    fun setEnabled(v: Boolean) { viewModelScope.launch { repo.setWorkspaceEnabled(v) } }

    /** 画面を開いたとき・操作のあとに呼ぶ。 */
    fun refresh() {
        viewModelScope.launch {
            val (list, total) = withContext(Dispatchers.IO) {
                try { ws.browse(_dir.value) to ws.totalBytes() } catch (_: Exception) { emptyList<WorkspaceItem>() to 0L }
            }
            _items.value = list
            _totalBytes.value = total
        }
    }

    fun open(name: String) {
        _dir.value = if (_dir.value.isEmpty()) name else _dir.value + "/" + name
        refresh()
    }

    fun up() {
        _dir.value = _dir.value.substringBeforeLast('/', "")
        refresh()
    }

    private fun child(name: String) = if (_dir.value.isEmpty()) name else _dir.value + "/" + name

    fun view(name: String) {
        val path = child(name)
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) {
                try {
                    val (t, tr) = ws.preview(path, VIEW_MAX_CHARS)
                    WorkspaceFileView(path, t, tr, false)
                } catch (e: WorkspaceException) {
                    WorkspaceFileView(path, "", false, e.code == "EBINARY")
                } catch (_: Exception) {
                    WorkspaceFileView(path, "", false, true)
                }
            }
            _file.value = v
        }
    }

    fun closeFile() { _file.value = null }

    /** ファイルまたはフォルダを削除する(確認ダイアログのあとに呼ぶ)。 */
    fun delete(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { try { ws.delete(path) } catch (_: Exception) { false } }
            _file.value = null
            refresh()
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { try { ws.deleteAll() } catch (_: Exception) { false } }
            _file.value = null
            _dir.value = ""
            refresh()
        }
    }

    /** 共有する Intent(作れたとき)。画面が startActivity したら consumeShare で消す。 */
    private val _share = MutableStateFlow<Intent?>(null)
    val share: StateFlow<Intent?> = _share

    fun consumeShare() { _share.value = null }

    /** 共有の準備。検証とファイル取得は IO スレッドで行い、Intent(FileProvider の content:// と一時的な読み取り権限だけ)を状態で返す。 */
    fun requestShare(path: String) {
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) {
                try {
                    val f = ws.fileFor(path) ?: return@withContext null
                    val uri: Uri = FileProvider.getUriForFile(a, a.packageName + ".workspace", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri(f.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    Intent.createChooser(send, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    null
                }
            }
            if (intent == null) _result.value = false else _share.value = intent
        }
    }

    /** 端末へ書き出す(ACTION_CREATE_DOCUMENT で利用者が選んだ場所へコピー)。 */
    fun export(path: String, dest: Uri?) {
        if (dest == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val f = ws.fileFor(path) ?: return@withContext false
                    a.contentResolver.openOutputStream(dest, "wt")?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
                } catch (_: Exception) {
                    false
                }
            }
            _result.value = ok
        }
    }
}
