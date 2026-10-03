package com.alirezaiyan.vokab.server.notification

/**
 * Abstraction over admin notification delivery.
 * Implementations: Telegram, Slack, email, etc.
 */
interface NotificationChannel {
    fun send(title: String, body: String)
}
