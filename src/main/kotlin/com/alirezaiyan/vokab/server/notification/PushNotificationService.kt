package com.alirezaiyan.vokab.server.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class PushNotificationService(
    private val pushTokenService: PushTokenService,
    private val notificationSender: NotificationSender
) {
    
    fun sendNotificationToUser(
        userId: Long,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        imageUrl: String? = null,
        category: NotificationCategory = NotificationCategory.SYSTEM
    ): List<NotificationResponse> {
        val tokens = pushTokenService.getActiveTokensForUser(userId)
        
        if (tokens.isEmpty()) {
            logger.warn { "No active push tokens found for user: $userId" }
            return emptyList()
        }
        
        return notificationSender.sendToTokens(tokens, title, body, data, imageUrl, category)
    }
    
    fun sendNotification(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        imageUrl: String? = null,
        category: NotificationCategory = NotificationCategory.SYSTEM
    ): NotificationResponse {
        return notificationSender.send(token, title, body, data, imageUrl, category)
    }
}
