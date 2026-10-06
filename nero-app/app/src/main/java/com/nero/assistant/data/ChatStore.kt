package com.nero.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Nova conversa",
    val turns: List<ChatTurn> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Guarda conversas e preferências localmente no aparelho. */
class ChatStore(context: Context) {

    private val prefs = context.getSharedPreferences("nero_store", Context.MODE_PRIVATE)
    private val secure = context.getSharedPreferences("nero_secure", Context.MODE_PRIVATE)

    /** Chave do OpenRouter, guardada criptografada com o Android Keystore. */
    var apiKey: String
        get() = secure.getString(KEY_API_ENCRYPTED, null)?.let(KeyVault::decrypt).orEmpty()
        set(value) {
            val clean = value.trim()
            secure.edit().apply {
                if (clean.isEmpty()) remove(KEY_API_ENCRYPTED) else putString(KEY_API_ENCRYPTED, KeyVault.encrypt(clean))
            }.apply()
        }

    /** Só chaves do OpenRouter contam (as antigas da Groq/Anthropic não servem mais). */
    val hasValidApiKey get() = apiKey.startsWith(OpenRouterService.KEY_PREFIX)

    /** Personalidade do Nero (system prompt). */
    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: OpenRouterService.DEFAULT_PROMPT
        set(value) = prefs.edit().putString(KEY_PROMPT, value.trim()).apply()

    init {
        // Versões antigas guardavam a chave em texto puro: criptografa e apaga o original.
        secure.getString(KEY_API_PLAIN, null)?.let { old ->
            if (old.isNotBlank()) apiKey = old
            secure.edit().remove(KEY_API_PLAIN).apply()
        }
    }

    var deepMode: Boolean
        get() = prefs.getBoolean(KEY_DEEP, false)
        set(value) = prefs.edit().putBoolean(KEY_DEEP, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: OpenRouterService.FREE_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var userName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    fun loadConversations(): List<Conversation> {
        val raw = prefs.getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val turnsArr = o.getJSONArray("turns")
                Conversation(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    updatedAt = o.getLong("updatedAt"),
                    turns = (0 until turnsArr.length()).map { j ->
                        val t = turnsArr.getJSONObject(j)
                        ChatTurn(
                            Role.valueOf(t.getString("role")),
                            t.getString("text"),
                            t.optString("image").takeIf { it.isNotEmpty() },
                        )
                    },
                )
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun saveConversations(list: List<Conversation>) {
        val arr = JSONArray()
        list.filter { it.turns.isNotEmpty() }.forEach { c ->
            val turns = JSONArray()
            c.turns.forEach { t ->
                turns.put(JSONObject().put("role", t.role.name).put("text", t.text).put("image", t.imagePath ?: ""))
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("updatedAt", c.updatedAt)
                    .put("turns", turns)
            )
        }
        prefs.edit().putString(KEY_CONVERSATIONS, arr.toString()).apply()
    }

    private companion object {
        const val KEY_API_PLAIN = "api_key"
        const val KEY_API_ENCRYPTED = "api_key_enc"
        const val KEY_PROMPT = "system_prompt"
        const val KEY_DEEP = "deep_mode"
        const val KEY_NAME = "user_name"
        const val KEY_MODEL = "model"
        const val KEY_CONVERSATIONS = "conversations"
    }
}
