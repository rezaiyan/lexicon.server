package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AdminTopic

/**
 * Abstraction over admin notification delivery.
 * Implementations: Telegram, Slack, email, etc.
 */
interface NotificationChannel {
    fun send(topic: AdminTopic, title: String, body: String)
}
