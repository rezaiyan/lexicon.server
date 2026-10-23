package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.describe
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private val logger = KotlinLogging.logger {}

/** Where support messages go for the admin to answer. Every call is best-effort and never throws. */
interface SupportRelay {
    /** Posts [text] to the admin chat; returns the posted message id, or null when not delivered. */
    fun post(text: String): Long?

    /** Marks an admin chat message as handled (a reaction), so the admin sees the reply landed. */
    fun acknowledge(messageId: Long)
}

/** Relays through the admin Telegram bot and chat (`app.notifications.admin.telegram`). */
@Component
class TelegramSupportRelay(
    private val appProperties: AppProperties,
    restClientBuilder: RestClient.Builder,
) : SupportRelay {

    private val restClient = restClientBuilder.baseUrl(BASE_URL).build()

    override fun post(text: String): Long? {
        val config = appProperties.notifications.admin.telegram
        if (config.botToken.isBlank() || config.chatId.isBlank()) {
            logger.debug { "Support relay skipped: Telegram bot token or chat ID not configured" }
            return null
        }
        return try {
            restClient.post()
                // Literal path: as a URI variable the token's ':' would be sent percent-encoded.
                .uri("/bot${config.botToken}/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("chat_id" to config.chatId, "text" to text.take(TELEGRAM_TEXT_LIMIT)))
                .retrieve()
                .body(SendMessageResponse::class.java)
                ?.result
                ?.messageId
        } catch (e: RestClientException) {
            // describe(): the request URL contains the bot token, so never log the exception itself.
            logger.warn { "Support relay to Telegram failed: ${e.describe()}" }
            null
        }
    }

    override fun acknowledge(messageId: Long) {
        val config = appProperties.notifications.admin.telegram
        if (config.botToken.isBlank() || config.chatId.isBlank()) return
        try {
            restClient.post()
                .uri("/bot${config.botToken}/setMessageReaction")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "chat_id" to config.chatId,
                        "message_id" to messageId,
                        "reaction" to listOf(mapOf("type" to "emoji", "emoji" to ACK_EMOJI)),
                    )
                )
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientException) {
            logger.warn { "Support reply acknowledgement failed: ${e.describe()}" }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SendMessageResponse(val result: SentMessage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SentMessage(@JsonProperty("message_id") val messageId: Long = 0)

    private companion object {
        const val BASE_URL = "https://api.telegram.org"
        const val TELEGRAM_TEXT_LIMIT = 4096
        const val ACK_EMOJI = "👍"
    }
}
