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
 * Conversa com o OpenRouter (API compatível com OpenAI) e devolve a resposta em streaming.
 * Usa só HttpURLConnection e org.json, que já vêm no Android.
 */
class OpenRouterService(
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {

    /**
     * Envia o histórico e chama [onText] a cada pedaço de texto recebido.
     * Bloqueia até o fim da resposta — chame fora da thread principal.
     */
    fun streamReply(
        history: List<ChatTurn>,
        model: String,
        deepMode: Boolean,
        userName: String,
        systemPrompt: String,
        isCancelled: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        val persona = systemPrompt.ifBlank { DEFAULT_PROMPT }
        val system = if (userName.isBlank()) persona else "$persona\nO usuário se chama $userName."
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { turn ->
            val role = if (turn.role == Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }
        val chosen = model.ifBlank { FREE_MODEL }
        val body = JSONObject()
            .put("model", chosen)
            .put("messages", messages)
            .put("stream", true)
        // Se o modelo escolhido falhar (fora do ar, sem créditos, limite), o OpenRouter usa o grátis.
        if (chosen != FREE_MODEL) body.put("models", JSONArray().put(chosen).put(FREE_MODEL))
        if (deepMode) body.put("reasoning", JSONObject().put("effort", "high"))

        val conn = open("chat/completions").apply {
            requestMethod = "POST"
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
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

    /**
     * Confere a chave no OpenRouter (GET /auth/key) antes de salvar.
     * Devolve true se a conta está no plano grátis. Lança [NeroException] se a chave não serve.
     */
    fun validateKey(): Boolean {
        if (!apiKey.startsWith(KEY_PREFIX)) throw NeroException("A chave do OpenRouter começa com \"$KEY_PREFIX\".")
        val conn = open("auth/key").apply { requestMethod = "GET" }
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(status, conn)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return runCatching { JSONObject(text).optJSONObject("data")?.optBoolean("is_free_tier") }
                .getOrNull() ?: false
        } catch (e: IOException) {
            throw NeroException("Sem conexão. Verifique sua internet.", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection = try {
        (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/cobrajuris/SITEPRO")
            setRequestProperty("X-Title", "Nero")
        }
    } catch (e: IOException) {
        throw NeroException("Sem conexão. Verifique sua internet.", e)
    }

    private fun httpError(status: Int, conn: HttpURLConnection): NeroException {
        val detail = runCatching {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            JSONObject(text).optJSONObject("error")?.let(::errorMessage)
        }.getOrNull()
        return when (status) {
            401 -> NeroException("Chave do OpenRouter inválida. Confira em Ajustes.")
            402 -> NeroException("Sem créditos no OpenRouter para este modelo. Use o modelo grátis em Ajustes.")
            429 -> NeroException("Limite de uso do OpenRouter atingido. Aguarde um pouco e tente de novo.")
            in 500..599 -> NeroException("O OpenRouter está fora do ar no momento. Tente novamente em instantes.")
            else -> NeroException(
                "O OpenRouter respondeu com erro ($status)" + (detail?.let { ": $it" } ?: ". Tente novamente.")
            )
        }
    }

    private fun errorMessage(error: JSONObject) = error.optString("message", "erro desconhecido")

    companion object {
        /** Escolhe sozinho um modelo gratuito disponível. Também é a reserva dos outros modelos. */
        const val FREE_MODEL = "openrouter/free"
        /** Deixa o OpenRouter escolher o melhor modelo para cada pergunta (usa créditos). */
        const val AUTO_MODEL = "openrouter/auto"

        /** Toda chave do OpenRouter começa assim. */
        const val KEY_PREFIX = "sk-or-"

        /** Personalidade padrão do Nero; pode ser trocada em Ajustes. */
        val DEFAULT_PROMPT = """
            Você é o Nero, um assistente pessoal premium com a personalidade de um gato preto:
            calmo, perspicaz, levemente irônico e sempre útil. Responda em português do Brasil,
            a menos que o usuário escreva em outro idioma. Seja direto e organizado: use listas
            e blocos de código quando ajudarem, e evite enrolação.
        """.trimIndent()
    }
}
