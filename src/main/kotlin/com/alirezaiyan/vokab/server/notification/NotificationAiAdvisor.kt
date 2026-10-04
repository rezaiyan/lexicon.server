package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates

private val logger = KotlinLogging.logger {}

/**
 * Asks the configured OpenRouter model (through [AiClient], so the shared timeout applies)
 * to decide the notification strategy for a COLD or DORMANT user. Result is cached on
 * NotificationSchedule for 7 days.
 *
 * Decision is deliberately simple and structured — AI picks one of three actions
 * and one of five content angles. No free-text generation; templates handle copy.
 *
 * Valid actions:   send | pause | motivate
 * Valid hints:     loss_aversion | curiosity | social_proof | fresh_start | achievement
 */
@Service
class NotificationAiAdvisor(
    private val aiClient: AiClient,
    private val promptTemplates: PromptTemplates,
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
    private data class AiResponse(
        val action: String = "send",
        val intervalDays: Int = 3,
        val contentHint: String? = null
    )

    private val validActions   = setOf("send", "pause", "motivate")
    private val validHints     = setOf("loss_aversion", "curiosity", "social_proof", "fresh_start", "achievement")
    private val defaultAdvice  = AiAdvice(action = "send", intervalDays = 3, contentHint = null)

    /**
     * Synchronous — called from the nightly scheduler thread, not a request thread.
     */
    fun advise(context: UserNotificationContext): AiAdvice {
        val prompt = buildPrompt(context)
        val raw = try {
            aiClient.complete(prompt, AiOperation.NOTIFICATION_ADVICE)
        } catch (e: UpstreamServiceException) {
            logger.warn { "AI advisor unavailable for user=${context.userId} — using default advice: ${e.message}" }
            return defaultAdvice
        }
        if (raw == null) {
            logger.warn { "AI advisor returned no text for user=${context.userId} — using default advice" }
            return defaultAdvice
        }
        return parseResponse(raw, context.userId)
    }

    private fun buildPrompt(ctx: UserNotificationContext): String = promptTemplates.render(
        Prompt.NOTIFICATION_ADVICE,
        mapOf(
            "segment" to ctx.segment,
            "openRate7dPercent" to ctx.openRate7dPercent,
            "openRate30dPercent" to ctx.openRate30dPercent,
            "daysSinceLastOpen" to (ctx.daysSinceLastOpen ?: "never"),
            "currentStreak" to ctx.currentStreak,
            "longestStreak" to ctx.longestStreak,
            "dueCards" to ctx.dueCards,
            "accountAgeDays" to ctx.accountAgeDays,
        ),
    )

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
