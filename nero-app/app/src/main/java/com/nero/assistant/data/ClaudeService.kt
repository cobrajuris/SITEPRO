package com.nero.assistant.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

/** Erro amigável para mostrar ao usuário. */
class NeroException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Conversa com o Claude via SDK oficial, com resposta em streaming.
 * Não depende de Android, então pode ser testado na JVM.
 */
class ClaudeService(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

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
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(64000L)
            .system(if (userName.isBlank()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\nO usuário se chama $userName.")
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (deepMode) BetaOutputConfig.Effort.HIGH else BetaOutputConfig.Effort.LOW)
                    .build()
            )
            // Se o modelo recusar por política, a API refaz o pedido em outro modelo automaticamente.
            .addBeta(FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

        history.forEach { turn ->
            when (turn.role) {
                Role.USER -> builder.addUserMessage(turn.text)
                Role.ASSISTANT -> builder.addAssistantMessage(turn.text)
            }
        }

        var refused = false
        try {
            client.beta().messages().createStreaming(builder.build()).use { stream ->
                val events = stream.stream().iterator()
                while (events.hasNext()) {
                    if (isCancelled()) break
                    val event = events.next()
                    event.contentBlockDelta().flatMap { it.delta().text() }
                        .ifPresent { onText(it.text()) }
                    event.messageDelta().flatMap { it.delta().stopReason() }
                        .ifPresent { if (it == BetaStopReason.REFUSAL) refused = true }
                }
            }
        } catch (e: UnauthorizedException) {
            throw NeroException("Chave de API inválida. Confira em Ajustes.", e)
        } catch (e: RateLimitException) {
            throw NeroException("Muitas mensagens seguidas. Aguarde alguns segundos.", e)
        } catch (e: AnthropicServiceException) {
            throw NeroException("O serviço respondeu com erro (${e.statusCode()}). Tente novamente.", e)
        } catch (e: NeroException) {
            throw e
        } catch (e: Exception) {
            throw NeroException("Sem conexão. Verifique sua internet.", e)
        }
        if (refused) onText("\n\n_Não posso ajudar com esse pedido._")
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        private val SYSTEM_PROMPT = """
            Você é o Nero, um assistente pessoal premium com a personalidade de um gato preto:
            calmo, perspicaz, levemente irônico e sempre útil. Responda em português do Brasil,
            a menos que o usuário escreva em outro idioma. Seja direto e organizado: use listas
            e blocos de código quando ajudarem, e evite enrolação.
        """.trimIndent()
    }
}
