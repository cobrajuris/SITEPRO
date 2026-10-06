package com.nero.assistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nero.assistant.data.ChatStore
import com.nero.assistant.data.ChatTurn
import com.nero.assistant.data.ClaudeService
import com.nero.assistant.data.Conversation
import com.nero.assistant.data.NeroException
import com.nero.assistant.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ChatStore(app)
    private var service: ClaudeService? = null
    private var serviceKey: String? = null
    private var job: Job? = null
    /** Sinal de cancelamento da resposta em andamento (um por pedido). */
    private var cancelFlag = AtomicBoolean(false)

    var conversations by mutableStateOf(store.loadConversations())
        private set
    var current by mutableStateOf(Conversation())
        private set
    /** Texto chegando em streaming para a resposta atual. */
    var streamingText by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var apiKey by mutableStateOf(store.apiKey)
        private set
    var deepMode by mutableStateOf(store.deepMode)
        private set
    var userName by mutableStateOf(store.userName)
        private set

    val isBusy get() = streamingText != null

    fun saveSettings(key: String, name: String) {
        store.apiKey = key
        store.userName = name
        apiKey = store.apiKey
        userName = store.userName
    }

    fun toggleDeepMode() {
        deepMode = !deepMode
        store.deepMode = deepMode
    }

    fun newConversation() {
        stop()
        current = Conversation()
        error = null
    }

    fun open(conversation: Conversation) {
        stop()
        current = conversation
        error = null
    }

    fun delete(conversation: Conversation) {
        conversations = conversations.filterNot { it.id == conversation.id }
        store.saveConversations(conversations)
        if (current.id == conversation.id) current = Conversation()
    }

    fun clearError() { error = null }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isBusy) return
        if (apiKey.isBlank()) {
            error = "Adicione sua chave de API em Ajustes para conversar com o Nero."
            return
        }
        val title = if (current.turns.isEmpty()) clean.take(40) else current.title
        current = current.copy(
            title = title,
            turns = current.turns + ChatTurn(Role.USER, clean),
            updatedAt = System.currentTimeMillis(),
        )
        persist()
        respond()
    }

    /** Refaz a última resposta do Nero. */
    fun regenerate() {
        if (isBusy) return
        val turns = current.turns
        if (turns.lastOrNull()?.role == Role.ASSISTANT) {
            current = current.copy(turns = turns.dropLast(1))
        }
        if (current.turns.lastOrNull()?.role == Role.USER) respond()
    }

    fun stop() {
        cancelFlag.set(true)
        job?.cancel()
        job = null
        val partial = streamingText
        if (!partial.isNullOrBlank()) commitReply(partial)
        streamingText = null
    }

    private fun respond() {
        error = null
        val cancelled = AtomicBoolean(false)
        cancelFlag = cancelled
        streamingText = ""
        val history = current.turns
        val conversationId = current.id
        val svc = serviceFor(apiKey)
        job = viewModelScope.launch {
            val buffer = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    svc.streamReply(history, deepMode, userName, { cancelled.get() }) { piece ->
                        buffer.append(piece)
                        val snapshot = buffer.toString()
                        viewModelScope.launch(Dispatchers.Main) {
                            if (!cancelled.get() && current.id == conversationId) streamingText = snapshot
                        }
                    }
                }
                if (!cancelled.get() && current.id == conversationId) {
                    commitReply(buffer.toString())
                    streamingText = null
                }
            } catch (e: NeroException) {
                if (!cancelled.get()) {
                    error = e.message
                    streamingText = null
                }
            }
        }
    }

    private fun commitReply(text: String) {
        current = current.copy(
            turns = current.turns + ChatTurn(Role.ASSISTANT, text),
            updatedAt = System.currentTimeMillis(),
        )
        persist()
    }

    private fun persist() {
        conversations = (listOf(current) + conversations.filterNot { it.id == current.id })
            .filter { it.turns.isNotEmpty() }
        store.saveConversations(conversations)
    }

    private fun serviceFor(key: String): ClaudeService {
        if (service == null || serviceKey != key) {
            service = ClaudeService(key)
            serviceKey = key
        }
        return service!!
    }
}
