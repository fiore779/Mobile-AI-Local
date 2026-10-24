package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.data.LOCAL_UID
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CloudIntegration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 設定の「API連携」一覧。 */
class ApiSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App

    val integrations: StateFlow<List<CloudIntegration>> =
        a.cloudRepo.observe(LOCAL_UID).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** キーが端末に保存されているか(連携 ID → 有無)。 */
    val hasKey: StateFlow<Map<String, Boolean>> = a.cloudRepo.observe(LOCAL_UID)
        .map { list -> list.associate { it.id to a.cloudKeys.has(LOCAL_UID, it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val warnings: StateFlow<Map<String, ApiErrorKind>> = a.cloudWarnings

    fun delete(id: String) = a.deleteIntegration(id)
}
