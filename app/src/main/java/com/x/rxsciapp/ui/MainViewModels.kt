package com.x.rxsciapp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.x.rxsciapp.data.repository.MobileRepository
import com.x.rxsciapp.model.AttachmentItem
import com.x.rxsciapp.model.ConnectionState
import com.x.rxsciapp.model.MessageItem
import com.x.rxsciapp.model.SessionItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionsViewModel(
    private val repository: MobileRepository,
) : ViewModel() {
    val sessions: StateFlow<List<SessionItem>> = repository.sessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    fun createSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createSession())
        }
    }

    fun setArchived(sessionId: String, archived: Boolean) {
        viewModelScope.launch {
            repository.setSessionArchived(sessionId, archived)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }
}

class ChatViewModel(
    private val repository: MobileRepository,
    private val sessionId: String,
) : ViewModel() {
    val session = repository.session(sessionId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val messages: StateFlow<List<MessageItem>> = repository.messages(sessionId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun sendMessage(
        text: String,
        attachments: List<Uri>,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val result = repository.sendMessage(sessionId, text, attachments)
            onDone(result.isSuccess)
        }
    }

    fun openAttachment(
        attachment: AttachmentItem,
        onDone: (Result<Uri>) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(repository.downloadAttachment(attachment))
        }
    }
}

class RepositoryFactory(
    private val create: () -> ViewModel,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return create() as T
    }
}
