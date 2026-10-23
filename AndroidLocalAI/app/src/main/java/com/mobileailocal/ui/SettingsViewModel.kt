package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.BuildConfig
import com.mobileailocal.data.AppSettings
import com.mobileailocal.domain.GenerationParams
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).settings
    private val models = (app as App).models

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** ストレージの使用状況(モデルの合計サイズ, 空き容量。バイト)。導入済みモデルが変わるたびに更新する。 */
    val storage: StateFlow<Pair<Long, Long>> = models.installed
        .map { models.installedBytes() to models.freeBytes() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L to 0L)

    val versionName: String = BuildConfig.VERSION_NAME

    /** v: system / light / dark */
    fun setTheme(v: String) { viewModelScope.launch { repo.setTheme(v) } }

    /** v: system / ja / en / zh-CN / zh-TW */
    fun setLanguage(v: String) { viewModelScope.launch { repo.setLanguage(v) } }

    fun setWifiOnly(v: Boolean) { viewModelScope.launch { repo.setWifiOnly(v) } }

    fun setParams(v: GenerationParams) { viewModelScope.launch { repo.setParams(v) } }
}
