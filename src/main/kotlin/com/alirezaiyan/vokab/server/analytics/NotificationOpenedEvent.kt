package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.DomainEvent
import java.time.Instant

/** The app reported a `notification_opened` event for one of the user's notification logs. */
data class NotificationOpenedEvent(
    val userId: Long,
    val notificationLogId: Long,
    override val occurredAt: Instant,
) : DomainEvent
