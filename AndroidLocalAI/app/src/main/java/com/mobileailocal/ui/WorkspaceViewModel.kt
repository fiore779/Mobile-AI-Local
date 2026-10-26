package com.mobileailocal.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.data.AppSettings
import com.mobileailocal.data.SafWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 作業フォルダ画面(設計書 context-and-workspace 6.6)。 */
class WorkspaceViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val repo = a.settings

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** フォルダを選んだが、永続権限が OS 側で失われている(画面を開くたびに確認する)。 */
    private val _permissionLost = MutableStateFlow(false)
    val permissionLost: StateFlow<Boolean> = _permissionLost

    /** 権限の取得に失敗した(SecurityException)。 */
    private val _pickError = MutableStateFlow(false)
    val pickError: StateFlow<Boolean> = _pickError

    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 画面を開くたびに呼ぶ。保存値は消さない。 */
    fun refresh() {
        val s = settings.value
        _permissionLost.value = s.workspaceTreeUri.isNotBlank() &&
            !SafWorkspace.hasPersistedPermission(a, s.workspaceTreeUri)
    }

    /** OpenDocumentTree の結果。null(取り消し)なら何もしない。 */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        _pickError.value = false
        viewModelScope.launch {
            val old = settings.value.workspaceTreeUri
            try {
                a.contentResolver.takePersistableUriPermission(uri, rw)
            } catch (_: SecurityException) {
                _pickError.value = true
                return@launch
            }
            // 以前のフォルダは手放す(永続権限の数には端末ごとの上限がある)
            if (old.isNotBlank() && old != uri.toString()) {
                try { a.contentResolver.releasePersistableUriPermission(Uri.parse(old), rw) } catch (_: Exception) { }
            }
            val name = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(a, uri)?.name.orEmpty() }
            repo.setWorkspaceFolder(uri.toString(), name) // 有効／無効は変えない
            _permissionLost.value = false
        }
    }

    /** 解除。権限を手放し、URI と名前を消し、無効にする(往復中なら ChatViewModel が設定の変化を見て止める)。 */
    fun clear() {
        viewModelScope.launch {
            val old = settings.value.workspaceTreeUri
            if (old.isNotBlank()) {
                try { a.contentResolver.releasePersistableUriPermission(Uri.parse(old), rw) } catch (_: Exception) { }
            }
            repo.clearWorkspace()
            _permissionLost.value = false
        }
    }

    fun setEnabled(v: Boolean) { viewModelScope.launch { repo.setWorkspaceEnabled(v) } }
}
