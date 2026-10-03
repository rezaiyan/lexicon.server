package com.alirezaiyan.vokab.server.service.notification

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.describe
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private val logger = KotlinLogging.logger {}

@Component
class TelegramChannel(
    private val appProperties: AppProperties,
    restClientBuilder: RestClient.Builder,
) : NotificationChannel {

    private val restClient = restClientBuilder.baseUrl(BASE_URL).build()

    override fun send(title: String, body: String) {
        val config = appProperties.notifications.admin.telegram
        if (config.botToken.isBlank() || config.chatId.isBlank()) {
            logger.debug { "Telegram notification skipped: bot token or chat ID not configured" }
            return
        }

        try {
            restClient.post()
                // Literal path: as a URI variable the token's ':' would be sent percent-encoded.
                .uri("/bot${config.botToken}/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("chat_id" to config.chatId, "text" to "$title\n$body"))
                .retrieve()
                .toBodilessEntity()
            logger.debug { "Telegram admin notification sent" }
        } catch (e: RestClientException) {
            // describe(): the request URL contains the bot token, so never log the exception itself.
            logger.warn { "Failed to send Telegram admin notification: ${e.describe()}" }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.telegram.org"
    }
}
