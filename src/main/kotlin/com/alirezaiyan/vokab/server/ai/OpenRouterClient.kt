package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.AiRestClient
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.shared.describe
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

private const val CHAT_COMPLETIONS = "/chat/completions"

/**
 * [AiClient] over OpenRouter's chat-completions API. Calls are synchronous and bounded by
 * `app.openrouter.timeout-seconds` (the [AiRestClient] builder). Every call counts in
 * `ai.requests{operation,outcome}` and in [AiCallTracker]; every answered one reports its tokens and
 * cost to [AiUsageRecorder].
 */
@Component
class OpenRouterClient(
    @AiRestClient restClientBuilder: RestClient.Builder,
    private val appProperties: AppProperties,
    private val meterRegistry: MeterRegistry,
    private val aiCallTracker: AiCallTracker,
    private val usageRecorder: AiUsageRecorder,
) : AiClient {

    private val restClient: RestClient = restClientBuilder
        .baseUrl(appProperties.openrouter.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${appProperties.openrouter.apiKey}")
        .defaultHeader("HTTP-Referer", "https://vokab.app")
        .defaultHeader("X-Title", "Vokab")
        .build()

    /** [usage] asks OpenRouter to report the call's cost next to its token counts. */
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val usage: UsageOption = UsageOption(include = true),
    )

    private data class UsageOption(val include: Boolean)

    private data class Message(val role: String, val content: List<Content>)

    @Suppress("ConstructorParameterNaming") // OpenRouter's wire name
    private data class Content(val type: String, val text: String? = null, val image_url: ImageUrl? = null)

    private data class ImageUrl(val url: String)

    private data class ChatResponse(val choices: List<Choice>?, val error: ErrorDetail?, val usage: Usage? = null)

    @Suppress("ConstructorParameterNaming") // OpenRouter's wire names
    private data class Usage(
        val prompt_tokens: Int? = null,
        val completion_tokens: Int? = null,
        val cost: BigDecimal? = null,
    )

    private data class Choice(val message: MessageContent)

    /** [content] is null when the model answered without text (e.g. refusal or tool call). */
    private data class MessageContent(val content: String?)

    private data class ErrorDetail(val message: String)

    override fun complete(prompt: String, operation: AiOperation): String? =
        chat(listOf(Content(type = "text", text = prompt)), operation)

    override fun completeWithImage(jpegBase64: String, prompt: String, operation: AiOperation): String? =
        chat(
            listOf(
                Content(type = "image_url", image_url = ImageUrl(url = "data:image/jpeg;base64,$jpegBase64")),
                Content(type = "text", text = prompt),
            ),
            operation,
        )

    private fun chat(content: List<Content>, operation: AiOperation): String? {
        val result = runCatching { requestChat(content, operation) }
        val outcome = when {
            result.isFailure -> "error"
            result.getOrNull() == null -> "empty"
            else -> "success"
        }
        meterRegistry.counter("ai.requests", "operation", operation.tag, "outcome", outcome).increment()
        // An empty answer still means OpenRouter is reachable
        aiCallTracker.record(success = result.isSuccess)
        return result.getOrThrow()
    }

    /** Response bodies are logged, never put into exception messages. */
    private fun requestChat(content: List<Content>, operation: AiOperation): String? {
        val model = appProperties.openrouter.model
        val request = ChatRequest(model = model, messages = listOf(Message(role = "user", content = content)))
        val response = post(request, operation.tag)
        response?.error?.let { error ->
            logger.error { "[OpenRouter] ${operation.tag} API error: ${error.message}" }
            throw UpstreamServiceException("OpenRouter ${operation.tag} returned an error")
        }
        // Answered, even if empty: the call was billed
        response?.let {
            val usage = it.usage
            usageRecorder.record(
                operation, model, AiUsageReport(usage?.prompt_tokens, usage?.completion_tokens, usage?.cost),
            )
        }
        return response?.choices?.firstOrNull()?.message?.content?.trim()?.ifEmpty { null }
    }

    private fun post(request: ChatRequest, operation: String): ChatResponse? = try {
        restClient.post()
            .uri(CHAT_COMPLETIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body<ChatResponse>()
    } catch (e: RestClientResponseException) {
        logger.error { "[OpenRouter] $operation HTTP ${e.statusCode.value()}: ${e.responseBodyAsString}" }
        throw UpstreamServiceException("OpenRouter $operation failed: ${e.describe()}", e)
    } catch (e: RestClientException) {
        logger.error { "[OpenRouter] $operation failed: ${e.describe()}" }
        throw UpstreamServiceException("OpenRouter $operation failed: ${e.describe()}", e)
    }
}
