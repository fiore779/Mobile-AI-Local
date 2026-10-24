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
import com.mobileailocal.data.DeviceMemory
import com.mobileailocal.domain.ModelBudget
import com.mobileailocal.domain.ModelRecommender
import com.mobileailocal.domain.Recommendation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Idle, Loading, Done, Error }

/** 端末に合わせたおすすめの状態。 */
data class RecommendUiState(
    val phase: Phase = Phase.Idle,
    val budgetMb: Long = 0,
    val memory: DeviceMemory? = null,
    val nonArm64: Boolean = false,
    val items: List<Recommendation> = emptyList(),
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<RemoteModel> = emptyList(),
    val error: String? = null,
    /** 展開したリポジトリ → その GGUF ファイル一覧(読み込み中は null) */
    val files: Map<String, List<RemoteFile>?> = emptyMap(),
    val fileErrors: Map<String, String> = emptyMap(),
    val recommend: RecommendUiState = RecommendUiState(),
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

    fun setQuery(q: String) = _search.update {
        if (q.isBlank()) it.copy(query = q, results = emptyList(), files = emptyMap(), fileErrors = emptyMap(), error = null)
        else it.copy(query = q)
    }

    /** 端末のメモリから目安を出し、人気の GGUF モデルのうち入るものをおすすめとして取得する。 */
    fun loadRecommendations(force: Boolean = false) {
        if (_search.value.recommend.phase != Phase.Idle && !force) return
        _search.update { it.copy(recommend = it.recommend.copy(phase = Phase.Loading, error = null)) }
        viewModelScope.launch {
            val mem = a.deviceMonitor.memoryInfo()
            val ctx = a.settings.settings.first().params.contextLength
            val abis = Build.SUPPORTED_ABIS.toList()
            val budget = ModelBudget.budgetMb(mem.totalMb, mem.freeMb, mem.appMb, ctx, abis)
            val base = RecommendUiState(Phase.Loading, budget, mem, "arm64-v8a" !in abis)
            _search.update { it.copy(recommend = base) }
            try {
                val popular = repo.popular()
                val candidates = popular.filterNot { ModelRecommender.clearlyTooBig(it.repoId, budget) }
                    .take(ModelRecommender.MAX_CANDIDATES_FOR_TREE)
                val gate = Semaphore(4)
                var lastError: Exception? = null
                var failures = 0 // viewModelScope はメインスレッド上なので、ここでの加算は競合しない
                val picked = coroutineScope {
                    candidates.map { m ->
                        async {
                            gate.withPermit {
                                try {
                                    ModelRecommender.pickFile(repo.files(m.repoId), budget)
                                        ?.let { (f, fit) -> Recommendation(m, f, fit) }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastError = e
                                    failures++
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val err = lastError
                if (picked.isEmpty() && err != null && failures == candidates.size) {
                    _search.update { it.copy(recommend = base.copy(phase = Phase.Error, error = errorText(err))) }
                } else {
                    _search.update {
                        it.copy(recommend = base.copy(phase = Phase.Done, items = ModelRecommender.rank(picked, budget)))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.update { it.copy(recommend = base.copy(phase = Phase.Error, error = errorText(e))) }
            }
        }
    }

    /** 導入済み判定の共通化。 */
    fun installedName(file: RemoteFile): String = repo.installedName(file)

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
