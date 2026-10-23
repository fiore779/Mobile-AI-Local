package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.data.ConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as App).db.chatDao()

    /** 新しい順の会話一覧。 */
    val conversations: StateFlow<List<ConversationEntity>> =
        dao.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteConversation(id) }
    }

    fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { dao.rename(id, title.trim(), System.currentTimeMillis()) }
    }
}
