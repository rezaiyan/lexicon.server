package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.UserSignedInEvent
import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class AdminNotificationListener(
    private val notificationChannel: NotificationChannel,
    private val appProperties: AppProperties
) {

    private fun buildMeta(platform: String?, country: String?): String {
        val parts = listOfNotNull(platform, country)
        return if (parts.isEmpty()) "" else " | ${parts.joinToString(" | ")}"
    }

    @Async
    @EventListener
    fun onUserSignedUp(event: UserSignedUpEvent) {
        if (!appProperties.notifications.admin.enabled) return

        try {
            val meta = buildMeta(event.platform, event.country)
            notificationChannel.send(
                title = "New Signup",
                body = "${event.name} joined via ${event.provider}$meta"
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send admin notification for signup userId=${event.userId}" }
        }
    }

    @Async
    @EventListener
    fun onUserSignedIn(event: UserSignedInEvent) {
        if (!appProperties.notifications.admin.enabled) return

        try {
            val meta = buildMeta(event.platform, event.country)
            notificationChannel.send(
                title = "Login",
                body = "${event.name} signed in via ${event.provider}$meta"
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send admin notification for login userId=${event.userId}" }
        }
    }
}
