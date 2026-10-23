package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.R
import com.mobileailocal.data.HttpStatusException
import com.mobileailocal.data.localizedString
import com.mobileailocal.data.DownloadState
import com.mobileailocal.data.ModelEntity
import com.mobileailocal.data.RemoteFile
import com.mobileailocal.data.RemoteModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<RemoteModel> = emptyList(),
    val error: String? = null,
    /** 展開したリポジトリ → その GGUF ファイル一覧(読み込み中は null) */
    val files: Map<String, List<RemoteFile>?> = emptyMap(),
    val fileErrors: Map<String, String> = emptyMap(),
)

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val repo = a.models
    private val tracker = a.downloads

    /** HTTP の失敗は文字列リソースの文言にする。 */
    private fun errorText(e: Exception): String =
        if (e is HttpStatusException) a.localizedString(R.string.download_http_error, e.code)
        else e.message ?: e.toString()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search

    /** 導入済みモデル(新しい順)。 */
    val installed: StateFlow<List<ModelEntity>> =
        repo.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** ダウンロード中の状態。キーは RemoteFile.url。 */
    val downloads: StateFlow<Map<String, DownloadState>> = tracker.states

    /** 既定(チャットで選択中)のモデル id。 */
    val selectedId: StateFlow<String?> = a.selectedModelId

    fun setQuery(q: String) = _search.update { it.copy(query = q) }

    fun runSearch() {
        val q = _search.value.query.trim()
        if (q.isEmpty()) return
        _search.update { it.copy(searching = true, error = null, results = emptyList(), files = emptyMap(), fileErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val r = repo.search(q)
                _search.update { it.copy(searching = false, results = r) }
            } catch (e: Exception) {
                _search.update { it.copy(searching = false, error = errorText(e)) }
            }
        }
    }

    /** 検索結果のリポジトリを開閉し、開くときにファイル一覧を取得する。 */
    fun toggleRepo(repoId: String) {
        if (_search.value.files.containsKey(repoId)) {
            _search.update { it.copy(files = it.files - repoId) }
            return
        }
        _search.update { it.copy(files = it.files + (repoId to null), fileErrors = it.fileErrors - repoId) }
        viewModelScope.launch {
            try {
                val f = repo.files(repoId)
                _search.update { it.copy(files = it.files + (repoId to f)) }
            } catch (e: Exception) {
                _search.update { it.copy(files = it.files - repoId, fileErrors = it.fileErrors + (repoId to errorText(e))) }
            }
        }
    }

    fun download(file: RemoteFile) = tracker.start(file)
    fun cancelDownload(file: RemoteFile) = tracker.cancel(file)

    /** チャットで使うモデルに選ぶ。 */
    fun select(model: ModelEntity) = a.selectModel(model.id)

    fun delete(model: ModelEntity) {
        viewModelScope.launch {
            if (a.selectedModelId.value == model.id) a.selectModel(null)
            repo.delete(model)
        }
    }
}
