package com.alirezaiyan.vokab.server.notification

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PushNotificationConfig {
    
    @Bean
    fun notificationMessageBuilder(): NotificationMessageBuilder {
        return FirebaseNotificationMessageBuilder()
    }
    
    @Bean
    fun tokenInvalidationHandler(
        pushTokenService: PushTokenService
    ): TokenInvalidationHandler {
        return FirebaseTokenInvalidationHandler(pushTokenService)
    }
    
    @Bean
    fun notificationSender(
        messageBuilder: NotificationMessageBuilder,
        tokenInvalidationHandler: TokenInvalidationHandler
    ): NotificationSender {
        return FirebaseNotificationSender(messageBuilder, tokenInvalidationHandler)
    }
}
