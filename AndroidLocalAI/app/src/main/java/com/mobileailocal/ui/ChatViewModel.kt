package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.data.ConversationEntity
import com.mobileailocal.data.MessageEntity
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val dao = a.db.chatDao()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var job: Job? = null

    fun send(text: String) {
        if (text.isBlank() || _state.value.generating) return
        job = viewModelScope.launch {
            val params = a.settings.settings.first().params
            val now = System.currentTimeMillis()
            val cid = _state.value.conversationId ?: withContext(Dispatchers.IO) {
                dao.insertConversation(ConversationEntity(title = text.take(30), createdAt = now, updatedAt = now))
            }
            val history = _state.value.messages + ChatMessage(Role.User, text)
            val aid = withContext(Dispatchers.IO) {
                dao.insertMessage(MessageEntity(conversationId = cid, role = "user", content = text, createdAt = now))
                dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "", createdAt = now))
            }
            _state.value = _state.value.copy(
                conversationId = cid,
                messages = history + ChatMessage(Role.Assistant, ""),
                generating = true,
                error = null,
            )
            val sb = StringBuilder()
            try {
                a.engine.generate(history, params).collect { piece ->
                    sb.append(piece)
                    _state.value = _state.value.copy(messages = history + ChatMessage(Role.Assistant, sb.toString()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            } finally {
                // 停止・失敗でも、そこまでの文章を保存する
                withContext(NonCancellable + Dispatchers.IO) {
                    dao.updateMessageContent(aid, sb.toString())
                    dao.touch(cid, System.currentTimeMillis())
                }
                _state.value = _state.value.copy(generating = false)
            }
        }
    }

    fun stop() { job?.cancel() }

    fun newConversation() {
        job?.cancel()
        _state.value = ChatUiState()
    }
}
