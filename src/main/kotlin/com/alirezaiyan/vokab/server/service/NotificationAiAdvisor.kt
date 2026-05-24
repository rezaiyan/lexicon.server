package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

private val logger = KotlinLogging.logger {}

/**
 * Calls Claude Haiku via OpenRouter to decide the notification strategy for a
 * COLD or DORMANT user. Result is cached on NotificationSchedule for 7 days.
 *
 * Decision is deliberately simple and structured — AI picks one of three actions
 * and one of five content angles. No free-text generation; templates handle copy.
 *
 * Valid actions:   send | pause | motivate
 * Valid hints:     loss_aversion | curiosity | social_proof | fresh_start | achievement
 */
@Service
class NotificationAiAdvisor(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {
    data class UserNotificationContext(
        val userId: Long,
        val segment: String,
        val openRate7dPercent: Int,
        val openRate30dPercent: Int,
        val daysSinceLastOpen: Long?,
        val currentStreak: Int,
        val longestStreak: Int,
        val dueCards: Int,
        val accountAgeDays: Long
    )

    data class AiAdvice(
        val action: String,         // "send" | "pause" | "motivate"
        val intervalDays: Int,
        val contentHint: String?    // null unless action = "motivate"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OpenRouterRequest(val model: String, val messages: List<Message>)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Message(val role: String, val content: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OpenRouterResponse(val choices: List<Choice>?, val error: ErrorBody?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Choice(val message: MessageBody)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessageBody(val content: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ErrorBody(val message: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AiResponse(
        val action: String = "send",
        val intervalDays: Int = 3,
        val contentHint: String? = null
    )

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(appProperties.openrouter.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${appProperties.openrouter.apiKey}")
            .defaultHeader("HTTP-Referer", "https://vokab.app")
            .defaultHeader("X-Title", "Vokab")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    private val validActions   = setOf("send", "pause", "motivate")
    private val validHints     = setOf("loss_aversion", "curiosity", "social_proof", "fresh_start", "achievement")
    private val defaultAdvice  = AiAdvice(action = "send", intervalDays = 3, contentHint = null)

    /**
     * Synchronous — called from the nightly scheduler thread, not a request thread.
     */
    fun advise(context: UserNotificationContext): AiAdvice {
        val prompt = buildPrompt(context)
        return runCatching {
            val raw = callOpenRouter(prompt)
            parseResponse(raw, context.userId)
        }.getOrElse { e ->
            logger.warn(e) { "AI advisor failed for user=${context.userId} — using default advice" }
            defaultAdvice
        }
    }

    private fun buildPrompt(ctx: UserNotificationContext): String = """
        You are a push notification strategy advisor for a vocabulary learning app.

        Decide the best notification strategy for this user based on their engagement data.

        User data:
        - segment: ${ctx.segment}
        - open rate last 7 days: ${ctx.openRate7dPercent}%
        - open rate last 30 days: ${ctx.openRate30dPercent}%
        - days since last notification open: ${ctx.daysSinceLastOpen ?: "never"}
        - current streak: ${ctx.currentStreak} days
        - longest streak ever: ${ctx.longestStreak} days
        - words due for review: ${ctx.dueCards}
        - account age: ${ctx.accountAgeDays} days

        Choose ONE action:
        - "send": user may still engage with the right content (use intervalDays 1-3)
        - "pause": user is burned out, skip notifications entirely (use intervalDays 3-7)
        - "motivate": user needs emotional re-engagement (use intervalDays 3-7)

        If action = "motivate", choose ONE contentHint:
        - "loss_aversion": they had progress they're losing (good when longestStreak > 0)
        - "curiosity": something interesting is waiting for them
        - "social_proof": other learners are progressing
        - "fresh_start": it's never too late to restart (good for DORMANT users)
        - "achievement": they're close to a milestone (good when dueCards > 0)

        Respond with ONLY valid JSON, no explanation, no markdown:
        {"action":"...","intervalDays":N,"contentHint":"..." or null}
    """.trimIndent()

    internal fun callOpenRouter(prompt: String): String {
        val request = OpenRouterRequest(
            model = "anthropic/claude-haiku-4.5",
            messages = listOf(Message(role = "user", content = prompt))
        )
        val response = webClient.post()
            .uri("/chat/completions")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<OpenRouterResponse>()
            .block()
            ?: error("OpenRouter returned null response")

        if (response.error != null) {
            error("OpenRouter error: ${response.error.message}")
        }
        return response.choices?.firstOrNull()?.message?.content
            ?: error("OpenRouter returned empty choices")
    }

    internal fun parseResponse(raw: String, userId: Long): AiAdvice {
        val aiResponse = runCatching {
            objectMapper.readValue(raw.trim(), AiResponse::class.java)
        }.getOrElse { e ->
            logger.warn(e) { "Failed to parse AI response for user=$userId: $raw" }
            return defaultAdvice
        }

        val action = aiResponse.action.lowercase()
        if (action !in validActions) {
            logger.warn { "AI returned unknown action='${aiResponse.action}' for user=$userId — defaulting to send" }
            return defaultAdvice
        }

        val intervalDays = aiResponse.intervalDays.coerceIn(1, 14)

        val contentHint = if (action == "motivate") {
            val hint = aiResponse.contentHint?.lowercase()
            if (hint !in validHints) {
                logger.warn { "AI returned unknown contentHint='${aiResponse.contentHint}' for user=$userId — using curiosity" }
                "curiosity"
            } else hint
        } else null

        return AiAdvice(action = action, intervalDays = intervalDays, contentHint = contentHint)
    }
}
