package com.mobileailocal.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.R
import com.mobileailocal.data.AccountError
import com.mobileailocal.data.GoogleAccount
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CloudIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 設定の「API連携」一覧とアカウント(サインイン)。 */
class ApiSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App

    val integrations: StateFlow<List<CloudIntegration>> =
        a.integrationsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** キーが端末に保存されているか(連携 ID → 有無)。 */
    val hasKey: StateFlow<Map<String, Boolean>> = a.integrationsFlow()
        .map { list -> list.associate { it.id to a.cloudKeys.has(a.uid(), it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val warnings: StateFlow<Map<String, ApiErrorKind>> = a.cloudWarnings

    /** サインイン中のアカウント(クラウド連携を使うときだけ必要。未サインインは null)。 */
    val account: StateFlow<GoogleAccount?> = a.accounts.current

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 直近のサインイン・削除の失敗(文字列リソース id)。 */
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error

    init {
        // 画面を開いたときに、端末とアカウントの連携を合わせる(オフラインなら何もしない)
        a.requestSync()
    }

    fun delete(id: String) = a.deleteIntegration(id)

    /** Gemini の推論量(空 = モデルの既定)。 */
    val geminiEffort: StateFlow<String> = a.settings.settings.map { it.geminiReasoningEffort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setGeminiEffort(v: String) { viewModelScope.launch { a.settings.setGeminiReasoningEffort(v) } }

    fun signIn(activity: Activity) = run {
        _error.value = null
        a.accounts.signIn(activity)
    }

    fun signOut() = run { a.accounts.signOut() }

    /** 再認証(Credential Manager)してから、アカウントと連携情報を削除する。Activity が要る。 */
    fun deleteAccount(activity: Activity) = run { a.deleteAccountAndData(activity) }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                _error.value = errorText(e)
            } catch (_: Exception) {
                _error.value = R.string.api_signin_err_other
            } finally {
                _busy.value = false
            }
        }
    }

    private fun errorText(e: AccountError): Int? = when (e) {
        AccountError.Cancelled -> null // 利用者が閉じただけ。エラーにしない
        AccountError.NoCredential -> R.string.api_signin_err_no_credential
        AccountError.Network -> R.string.api_signin_err_network
        AccountError.RecentLoginRequired -> R.string.api_account_err_recent
        AccountError.Other -> R.string.api_signin_err_other
    }
}
