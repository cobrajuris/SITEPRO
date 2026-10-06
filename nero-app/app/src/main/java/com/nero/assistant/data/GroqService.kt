package com.nero.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

/** Erro amigável para mostrar ao usuário. */
class NeroException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Conversa com a Groq (API compatível com OpenAI) e devolve a resposta em streaming.
 * Usa só HttpURLConnection e org.json, que já vêm no Android.
 */
class GroqService(
    private val apiKey: String,
    private val baseUrl: String = "https://api.groq.com/openai/v1",
) {

    /**
     * Envia o histórico e chama [onText] a cada pedaço de texto recebido.
     * Bloqueia até o fim da resposta — chame fora da thread principal.
     */
    fun streamReply(
        history: List<ChatTurn>,
        deepMode: Boolean,
        userName: String,
        isCancelled: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        val system = if (userName.isBlank()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\nO usuário se chama $userName."
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { turn ->
            val role = if (turn.role == Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }
        val body = JSONObject()
            .put("model", if (deepMode) DEEP_MODEL else FAST_MODEL)
            .put("messages", messages)
            .put("stream", true)
        if (deepMode) body.put("reasoning_effort", "high")

        val conn = try {
            (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
        } catch (e: IOException) {
            throw NeroException("Sem conexão. Verifique sua internet.", e)
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(status, conn)

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (isCancelled()) break
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    json.optJSONObject("error")?.let { throw NeroException(errorMessage(it)) }
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty() && delta.has("content") && !delta.isNull("content")) onText(piece)
                }
            }
        } catch (e: NeroException) {
            throw e
        } catch (e: IOException) {
            if (!isCancelled()) throw NeroException("Sem conexão. Verifique sua internet.", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpError(status: Int, conn: HttpURLConnection): NeroException {
        val detail = runCatching {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(text).optJSONObject("error")?.let(::errorMessage)
        }.getOrNull()
        return when (status) {
            401 -> NeroException("Chave de API da Groq inválida. Confira em Ajustes.")
            429 -> NeroException("Limite de uso da Groq atingido. Aguarde um pouco e tente de novo.")
            else -> NeroException(
                "A Groq respondeu com erro ($status)" + (detail?.let { ": $it" } ?: ". Tente novamente.")
            )
        }
    }

    private fun errorMessage(error: JSONObject) = error.optString("message", "erro desconhecido")

    companion object {
        /** Modelo rápido para o dia a dia. */
        const val FAST_MODEL = "llama-3.3-70b-versatile"
        /** Modelo que raciocina antes de responder (modo profundo). */
        const val DEEP_MODEL = "openai/gpt-oss-120b"

        private val SYSTEM_PROMPT = """
            Você é o Nero, um assistente pessoal premium com a personalidade de um gato preto:
            calmo, perspicaz, levemente irônico e sempre útil. Responda em português do Brasil,
            a menos que o usuário escreva em outro idioma. Seja direto e organizado: use listas
            e blocos de código quando ajudarem, e evite enrolação.
        """.trimIndent()
    }
}
