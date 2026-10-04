package com.alirezaiyan.vokab.server.notification

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

interface NotificationSender {
    fun send(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): NotificationResponse
    
    fun sendToTokens(
        tokens: List<PushToken>,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): List<NotificationResponse>

    /** See [NotificationMessageBuilder.buildSilent]. */
    fun sendSilentToTokens(tokens: List<PushToken>, data: Map<String, String>): List<NotificationResponse>
}

class FirebaseNotificationSender(
    private val messageBuilder: NotificationMessageBuilder,
    private val tokenInvalidationHandler: TokenInvalidationHandler
) : NotificationSender {
    
    override fun send(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): NotificationResponse {
        return try {
            val message = messageBuilder.build(token, title, body, data, imageUrl, category)
            val messageId = FirebaseMessaging.getInstance().send(message)
            
            logger.info { "Notification sent successfully. Message ID: $messageId" }
            
            NotificationResponse(
                success = true,
                messageId = messageId
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to send notification" }
            
            tokenInvalidationHandler.handleInvalidToken(e, token)
            
            NotificationResponse(
                success = false,
                error = e.message
            )
        }
    }
    
    override fun sendToTokens(
        tokens: List<PushToken>,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): List<NotificationResponse> {
        return tokens.map { pushToken ->
            send(pushToken.token, title, body, data, imageUrl, category)
        }
    }

    override fun sendSilentToTokens(tokens: List<PushToken>, data: Map<String, String>): List<NotificationResponse> =
        tokens.map { pushToken -> deliver(pushToken.token) { messageBuilder.buildSilent(pushToken.token, data) } }

    private fun deliver(token: String, message: () -> Message): NotificationResponse =
        try {
            val messageId = FirebaseMessaging.getInstance().send(message())
            logger.info { "Silent push sent. Message ID: $messageId" }
            NotificationResponse(success = true, messageId = messageId)
        } catch (e: Exception) {
            logger.error(e) { "Failed to send silent push" }
            tokenInvalidationHandler.handleInvalidToken(e, token)
            NotificationResponse(success = false, error = e.message)
        }
}

interface TokenInvalidationHandler {
    fun handleInvalidToken(exception: Exception, token: String)
}

class FirebaseTokenInvalidationHandler(
    private val pushTokenService: PushTokenService
) : TokenInvalidationHandler {
    
    override fun handleInvalidToken(exception: Exception, token: String) {
        if (isInvalidTokenError(exception)) {
            pushTokenService.deactivateToken(token)
        }
    }
    
    private fun isInvalidTokenError(exception: Exception): Boolean {
        val message = exception.message ?: return false
        return message.contains("registration-token-not-registered") ||
               message.contains("invalid-registration-token")
    }
}