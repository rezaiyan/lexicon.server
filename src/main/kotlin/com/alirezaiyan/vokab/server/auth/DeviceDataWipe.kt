package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.notification.NotificationCategory
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/** Tells every device of a user to wipe its local data (after sign-out everywhere or deletion). */
@Component
class DeviceDataWipe(private val pushNotificationService: PushNotificationService) {

    /** Best effort: a failed push never blocks the caller's flow. */
    fun notify(userId: Long, title: String, body: String, type: String) {
        runCatching {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = title,
                body = body,
                data = mapOf(
                    "type" to type,
                    "action" to "clear_local_data",
                    "clear_daily_insights" to "true",
                ),
                category = NotificationCategory.SYSTEM,
            )
        }
            .onSuccess { logger.info { "Sent $type notification to ${it.size} devices" } }
            .onFailure { logger.warn(it) { "Failed to send $type notification, continuing" } }
    }
}
