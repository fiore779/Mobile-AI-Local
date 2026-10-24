package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.R
import com.mobileailocal.data.CloudHttp
import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.ApiErrorClassifier
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.CloudProviders
import com.mobileailocal.domain.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

data class ApiAddState(
    val editingId: String? = null,
    val type: ProviderType? = null,
    val name: String = "",
    val baseUrl: String = "",
    val key: String = "",
    /** 接続確認で取得できたモデル名。 */
    val fetched: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val manual: String = "",
    val testing: Boolean = false,
    val testOk: Boolean = false,
    val testError: ApiError? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** 入力の誤り(文字列リソース id)。 */
    val formError: Int? = null,
    /** 同意の確認待ちの種別。 */
    val consentFor: ProviderType? = null,
    /** 同意の本文に出す接続先ホスト(カスタムのみ)。 */
    val consentHost: String? = null,
    val hasStoredKey: Boolean = false,
)

/** 「API連携」の追加と編集(設計書 8.2)。編集のときは連携 ID を受け取る。 */
class ApiAddViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val a = app as App
    private val editId: String? = handle.get<String>("integrationId")?.takeIf { it != "new" }
    private val http = CloudHttp()
    private val _state = MutableStateFlow(ApiAddState())
    val state: StateFlow<ApiAddState> = _state
    private var createdAt = 0L
    private var originalBaseUrl = ""
    // カスタムは接続先が決まってから(保存の直前に)同意を取るので、同意後に保存を続ける
    private var saveAfterConsent = false

    init {
        if (editId != null) viewModelScope.launch {
            val i = a.cloudRepo.get(a.uid(), editId) ?: return@launch
            createdAt = i.createdAt
            originalBaseUrl = i.baseUrl
            _state.value = ApiAddState(
                editingId = i.id, type = i.type, name = i.displayName, baseUrl = i.baseUrl,
                fetched = i.enabledModels, selected = i.enabledModels.toSet(),
                hasStoredKey = a.cloudKeys.has(a.uid(), i.id),
            )
        }
    }

    /** プロバイダーを選ぶ。同意がまだなら、先に確認を出す。 */
    fun pickType(type: ProviderType) {
        // カスタムは送信先が入力されてから、保存の前に同意を取る(接続先のホストを本文に出すため)
        if (type == ProviderType.OpenAiCompatCustom) { applyType(type); return }
        viewModelScope.launch {
            if (a.cloudConsent.isAccepted(a.uid(), type)) applyType(type)
            else _state.update { it.copy(consentFor = type) }
        }
    }

    fun acceptConsent() {
        val s = _state.value
        val t = s.consentFor ?: return
        viewModelScope.launch {
            a.cloudConsent.accept(a.uid(), t, CloudInputs.consentScope(t, s.baseUrl))
            _state.update { it.copy(consentFor = null, consentHost = null) }
            if (saveAfterConsent) { saveAfterConsent = false; save() } else applyType(t)
        }
    }

    fun declineConsent() {
        saveAfterConsent = false
        _state.update { it.copy(consentFor = null, consentHost = null) }
    }

    private fun applyType(t: ProviderType) {
        val p = CloudProviders.of(t)
        _state.update {
            it.copy(type = t, baseUrl = p.defaultBaseUrl, name = it.name.ifBlank { defaultName(t) }, formError = null)
        }
    }

    private fun defaultName(t: ProviderType): String = a.getString(
        when (t) {
            ProviderType.OpenAi -> R.string.cloud_provider_openai
            ProviderType.Anthropic -> R.string.cloud_provider_anthropic
            ProviderType.Gemini -> R.string.cloud_provider_gemini
            ProviderType.OpenAiCompatCustom -> R.string.cloud_provider_custom
        },
    )

    fun setName(v: String) = _state.update { it.copy(name = v, formError = null) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, formError = null) }
    fun setKey(v: String) = _state.update { it.copy(key = CloudInputs.cleanKey(v), formError = null, testOk = false, testError = null) }
    fun setManual(v: String) = _state.update { it.copy(manual = v) }

    fun toggleModel(m: String) = _state.update {
        val s = if (m in it.selected) it.selected - m else if (it.selected.size < CloudInputs.MAX_MODELS) it.selected + m else it.selected
        it.copy(selected = s, formError = null)
    }

    /** 手入力のモデル名を足す(許可文字・長さを検証)。 */
    fun addManual() {
        val s = _state.value
        val name = CloudInputs.cleanModelName(s.manual)
        if (name == null) { _state.update { it.copy(formError = R.string.cloud_form_model_invalid) }; return }
        if (s.selected.size >= CloudInputs.MAX_MODELS && name !in s.selected) {
            _state.update { it.copy(formError = R.string.cloud_form_models_many) }; return
        }
        _state.update {
            it.copy(
                manual = "", formError = null, selected = it.selected + name,
                fetched = if (name in it.fetched) it.fetched else it.fetched + name,
            )
        }
    }

    /** モデル一覧の取得で接続を確認する(キーが有効なら 200)。 */
    fun testConnection() {
        val s = _state.value
        val type = s.type ?: return
        val p = CloudProviders.of(type)
        val urlCheck = urlProblem(s)
        if (urlCheck != null) { _state.update { it.copy(formError = urlCheck) }; return }
        _state.update { it.copy(testing = true, testOk = false, testError = null, formError = null) }
        viewModelScope.launch {
            // 接続先を変えたときは、保存済みのキーを新しい接続先へ送らない
            val key = s.key.ifEmpty {
                if (urlChanged(s)) "" else s.editingId?.let { a.cloudKeys.get(a.uid(), it) }.orEmpty()
            }
            try {
                val spec = p.buildListModelsRequest(s.baseUrl.trim(), key)
                val models = http.request(spec) { resp ->
                    val body = resp.readText()
                    if (resp.status != 200) throw p.classify(resp.status, resp.headers, body)
                    p.parseModelList(body)
                }
                _state.update {
                    it.copy(
                        testing = false, testOk = true, fetched = (models + it.selected).distinct(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = if (e is ApiError) e else ApiErrorClassifier.fromException(e as? IOException ?: e, midStream = false)
                _state.update { it.copy(testing = false, testError = err) }
            }
        }
    }

    private fun urlChanged(s: ApiAddState): Boolean =
        s.editingId != null && s.baseUrl.trim().trimEnd('/') != originalBaseUrl.trimEnd('/')

    private fun urlProblem(s: ApiAddState): Int? {
        if (s.type != ProviderType.OpenAiCompatCustom) return null
        return when (CloudInputs.checkBaseUrl(s.baseUrl)) {
            CloudInputs.UrlCheck.Invalid -> R.string.cloud_form_url_invalid
            CloudInputs.UrlCheck.HttpNotAllowed -> R.string.cloud_form_url_http
            else -> null
        }
    }

    fun save() {
        val s = _state.value
        val type = s.type ?: return
        // クラウド連携の保存はサインインしているときだけ(連携はアカウントごとに記録する)
        if (a.accounts.uid.value == null) { _state.update { it.copy(formError = R.string.cloud_form_signin_required) }; return }
        urlProblem(s)?.let { e -> _state.update { it.copy(formError = e) }; return }
        val name = CloudInputs.cleanDisplayName(s.name)
        val needsKey = type != ProviderType.OpenAiCompatCustom && s.key.isEmpty() && !s.hasStoredKey
        val error = when {
            name == null -> R.string.cloud_form_name_invalid
            needsKey -> R.string.cloud_form_key_empty
            // 接続先を変えたら、古いキーを新しい接続先へ送らないよう、キーの入れ直しを求める
            urlChanged(s) && s.key.isEmpty() && s.hasStoredKey -> R.string.cloud_form_key_required_for_url
            s.selected.isEmpty() -> R.string.cloud_form_models_none
            s.selected.size > CloudInputs.MAX_MODELS -> R.string.cloud_form_models_many
            else -> null
        }
        if (error != null) { _state.update { it.copy(formError = error) }; return }
        viewModelScope.launch {
            // 同意がまだなら、保存の前に確認する(カスタムは接続先ホストごと)
            val scope = CloudInputs.consentScope(type, s.baseUrl)
            if (!a.cloudConsent.isAccepted(a.uid(), type, scope)) {
                saveAfterConsent = true
                _state.update { it.copy(consentFor = type, consentHost = scope.ifEmpty { null }) }
                return@launch
            }
            _state.update { it.copy(saving = true) }
            val now = System.currentTimeMillis()
            val id = s.editingId ?: UUID.randomUUID().toString()
            val models = s.selected.toList()
            val item = CloudIntegration(
                id = id, type = type, displayName = name!!, baseUrl = s.baseUrl.trim().trimEnd('/'),
                enabledModels = models, lastModel = models.firstOrNull(),
                createdAt = if (s.editingId != null) createdAt else now, updatedAt = now,
            )
            // 接続テストで上限・課金の失敗が出ていても、キーは有効なので保存する(設計書 7.2)
            try {
                if (s.key.isNotEmpty()) a.cloudKeys.put(a.uid(), id, s.key)
                a.cloudRepo.upsert(a.uid(), item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // キーの暗号化保存や DB の失敗で落とさない(原因の詳細はログにも出さない)
                _state.update { it.copy(saving = false, formError = R.string.cloud_form_save_failed) }
                return@launch
            }
            a.requestSync() // アカウントへ連携メタデータ(キーを除く)を記録する。オフラインなら次回に回る
            _state.update { it.copy(saving = false, saved = true, key = "") }
        }
    }

    fun revokeConsent() {
        val s = _state.value
        val t = s.type ?: return
        // 選択中なら未選択に戻す(次に使うときに再度確認する)
        a.revokeCloudConsent(t, CloudInputs.consentScope(t, s.baseUrl))
    }

    fun delete() {
        val id = _state.value.editingId ?: return
        a.deleteIntegration(id)
        _state.update { it.copy(saved = true) }
    }
}
