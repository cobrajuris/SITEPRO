package com.nero.assistant

import android.os.Handler
import android.os.Looper
import com.nero.assistant.data.ChatStore
import com.nero.assistant.data.ChatTurn
import com.nero.assistant.data.Conversation
import com.nero.assistant.data.NeroException
import com.nero.assistant.data.OpenRouterService
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderParser
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Role
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado e regras do chat. A tela observa via [onChange]; tudo aqui roda na thread principal,
 * exceto a chamada ao OpenRouter, que vai para uma thread de fundo.
 */
class ChatController(
    private val store: ChatStore,
    val reminders: ReminderStore,
    private val isOnline: () -> Boolean = { true },
    /** Chamado quando a IA cria um lembrete, para agendar o alarme e salvar no calendário. */
    private val onReminderCreated: (Reminder) -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private var service: OpenRouterService? = null
    private var serviceKey: String? = null
    private var cancelFlag = AtomicBoolean(false)

    var onChange: () -> Unit = {}

    var conversations: List<Conversation> = store.loadConversations()
        private set
    var current = Conversation()
        private set
    /** Texto chegando em streaming; null quando não há resposta em andamento. */
    var streamingText: String? = null
        private set
    var error: String? = null
        private set
    /** Foto escolhida pelo usuário que vai junto com a próxima mensagem. */
    var pendingImage: String? = null
        private set

    val apiKey get() = store.apiKey
    val hasValidApiKey get() = store.hasValidApiKey
    val systemPrompt get() = store.systemPrompt
    val userName get() = store.userName
    val model get() = store.model
    val deepMode get() = store.deepMode
    val isBusy get() = streamingText != null

    /**
     * Salva os ajustes. Se [newKey] vier preenchida, ela é conferida no OpenRouter antes de ser
     * guardada; [onDone] recebe a mensagem para mostrar e se deu certo.
     */
    fun saveSettings(
        newKey: String,
        name: String,
        model: String,
        prompt: String,
        onDone: (ok: Boolean, message: String) -> Unit,
    ) {
        store.userName = name
        store.model = model.ifBlank { OpenRouterService.FREE_MODEL }
        store.systemPrompt = prompt
        val key = newKey.trim()
        if (key.isEmpty()) {
            onChange()
            onDone(true, "Ajustes salvos")
            return
        }
        if (!isOnline()) {
            onDone(false, "Sem internet para conferir a chave. Conecte-se e tente de novo.")
            return
        }
        Thread {
            val result = try {
                val free = OpenRouterService(key).validateKey()
                true to if (free) "Chave válida (plano grátis). Tudo pronto!" else "Chave válida. Tudo pronto!"
            } catch (e: Exception) {
                false to ((e as? NeroException)?.message ?: "Não consegui conferir a chave.")
            }
            main.post {
                if (result.first) {
                    store.apiKey = key
                    error = null
                }
                onChange()
                onDone(result.first, result.second)
            }
        }.start()
    }

    fun toggleDeepMode() {
        store.deepMode = !store.deepMode
        onChange()
    }

    fun newConversation() {
        stop()
        current = Conversation()
        error = null
        onChange()
    }

    fun open(conversation: Conversation) {
        stop()
        current = conversation
        error = null
        onChange()
    }

    fun delete(conversation: Conversation) {
        conversations = conversations.filterNot { it.id == conversation.id }
        store.saveConversations(conversations)
        if (current.id == conversation.id) current = Conversation()
        onChange()
    }

    fun attachImage(path: String) {
        pendingImage = path
        onChange()
    }

    fun clearImage() {
        pendingImage = null
        onChange()
    }

    /** Avisa a tela que a agenda mudou (lembrete criado, editado, concluído ou apagado). */
    fun remindersChanged() = onChange()

    fun clearError() {
        error = null
        onChange()
    }

    fun send(text: String) {
        val image = pendingImage
        val clean = text.trim().ifEmpty { if (image != null) "O que tem nesta imagem?" else "" }
        if (clean.isEmpty() || isBusy) return
        if (!hasValidApiKey) {
            error = "Adicione sua chave do OpenRouter em Ajustes para conversar com o Nero."
            onChange()
            return
        }
        if (!isOnline()) {
            error = "Sem internet. Verifique sua conexão e tente de novo."
            onChange()
            return
        }
        val title = if (current.turns.isEmpty()) clean.take(40) else current.title
        current = current.copy(
            title = title,
            turns = current.turns + ChatTurn(Role.USER, clean, image),
            updatedAt = System.currentTimeMillis(),
        )
        pendingImage = null
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
        val partial = streamingText
        streamingText = null
        if (!partial.isNullOrBlank()) commitReply(partial)
        onChange()
    }

    private fun respond() {
        error = null
        val cancelled = AtomicBoolean(false)
        cancelFlag = cancelled
        streamingText = ""
        onChange()

        // O modelo vê os lembretes já criados como uma frase, sem as marcações internas.
        val history = current.turns.map { turn ->
            val ids = ReminderParser.tokenIds(turn.text)
            if (ids.isEmpty()) turn else turn.copy(
                text = ReminderParser.stripTokens(turn.text) + ids.joinToString("") { id ->
                    reminders.get(id)?.let { "\n(Lembrete criado: ${it.title}, ${ReminderParser.describe(it)})" } ?: ""
                }
            )
        }
        val conversationId = current.id
        val svc = serviceFor(apiKey)
        val chosenModel = model
        val deep = deepMode
        val name = userName
        val prompt = systemPrompt
        val instructions = ReminderParser.instructions(agenda = reminders.upcoming())
        Thread {
            val buffer = StringBuilder()
            try {
                svc.streamReply(history, chosenModel, deep, name, prompt, instructions, { cancelled.get() }) { piece ->
                    buffer.append(piece)
                    val snapshot = buffer.toString()
                    main.post {
                        if (!cancelled.get() && current.id == conversationId) {
                            streamingText = snapshot
                            onChange()
                        }
                    }
                }
                main.post {
                    if (!cancelled.get() && current.id == conversationId) {
                        streamingText = null
                        commitReply(buffer.toString())
                        onChange()
                    }
                }
            } catch (e: Exception) {
                val message = (e as? NeroException)?.message ?: "Algo deu errado. Tente novamente."
                main.post {
                    if (!cancelled.get()) {
                        error = message
                        streamingText = null
                        onChange()
                    }
                }
            }
        }.start()
    }

    private fun commitReply(raw: String) {
        // Cada compromisso pedido vira um lembrete com alarme (e vai para o calendário pela tela);
        // o texto guarda uma marca por lembrete para a tela mostrar os cartões.
        val (full, requests) = ReminderParser.extractAll(raw)
        val clean = ReminderParser.hidePartial(full)
        val text = if (requests.isNotEmpty()) {
            val created = requests.map { request ->
                reminders.add(request.title, request.timeMillis, request.repeat, alarm = true, leadMinutes = request.leadMinutes)
                    .also(onReminderCreated)
            }
            clean + "\n" + created.joinToString("") { ReminderParser.token(it.id) }
        } else {
            clean
        }
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

    private fun serviceFor(key: String): OpenRouterService {
        if (service == null || serviceKey != key) {
            service = OpenRouterService(key)
            serviceKey = key
        }
        return service!!
    }
}
