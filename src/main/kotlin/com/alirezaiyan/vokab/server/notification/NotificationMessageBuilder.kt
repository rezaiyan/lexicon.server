package com.alirezaiyan.vokab.server.notification

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification

interface NotificationMessageBuilder {
    fun build(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): Message

    /**
     * Data-only message the app handles in the background without showing anything
     * (Android: no notification block; iOS: content-available background push).
     */
    fun buildSilent(token: String, data: Map<String, String>): Message
}

class FirebaseNotificationMessageBuilder : NotificationMessageBuilder {
    override fun build(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?,
        category: NotificationCategory
    ): Message {
        val notification = buildNotification(title, body, imageUrl)
        val enhancedData = enhanceDataWithCategory(data, category)
        
        return Message.builder()
            // setFid() replaces this, but it takes a Firebase Installation ID; clients register FCM
            // registration tokens, so switching needs client changes first
            .apply { @Suppress("DEPRECATION") setToken(token) }
            .setNotification(notification)
            .putAllData(enhancedData)
            .build()
    }
    
    override fun buildSilent(token: String, data: Map<String, String>): Message =
        Message.builder()
            .apply { @Suppress("DEPRECATION") setToken(token) }
            .putAllData(data)
            // Normal priority: a background refetch isn't urgent and must not wake the device eagerly
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.NORMAL).build())
            .setApnsConfig(
                ApnsConfig.builder()
                    // Apple requires priority 5 and push type "background" for content-available pushes
                    .putHeader("apns-push-type", "background")
                    .putHeader("apns-priority", "5")
                    .setAps(Aps.builder().setContentAvailable(true).build())
                    .build()
            )
            .build()

    private fun buildNotification(
        title: String,
        body: String,
        imageUrl: String?
    ): Notification {
        val builder = Notification.builder()
            .setTitle(title)
            .setBody(body)
        
        imageUrl?.let { builder.setImage(it) }
        
        return builder.build()
    }
    
    private fun enhanceDataWithCategory(
        data: Map<String, String>?,
        category: NotificationCategory
    ): Map<String, String> {
        return (data?.toMutableMap() ?: mutableMapOf()).apply {
            put("category", category.value)
        }
    }
}