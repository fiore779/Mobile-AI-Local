package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.WorkspaceUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 拡張機能画面(JavaScript 実行)の ViewModel。 */
class ExtensionsViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val settings = a.settings
    private val jsRunManager = a.jsRunManager

    /** 拡張機能(JavaScript 実行)を許可するか。 */
    val enabled: StateFlow<Boolean> = settings.settings
        .map { it.jsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 実行中か。実行中はワークスペースを空にできない。 */
    val running: StateFlow<Boolean> = jsRunManager.state
        .map { it is JsRunState.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** ワークスペースの使用状況。まだ読んでいなければ null。 */
    private val _usage = MutableStateFlow<WorkspaceUsage?>(null)
    val usage: StateFlow<WorkspaceUsage?> = _usage

    fun refreshUsage() { viewModelScope.launch { _usage.value = jsRunManager.workspaceUsage() } }

    fun setEnabled(v: Boolean) {
        // 無効にするときは実行中の切り替えなので、先に実行を止めてから設定へ書く
        if (!v) jsRunManager.cancelAndClear()
        viewModelScope.launch { settings.setJsEnabled(v) }
    }

    fun clearWorkspace() {
        viewModelScope.launch {
            jsRunManager.clearWorkspace()
            refreshUsage()
        }
    }
}
