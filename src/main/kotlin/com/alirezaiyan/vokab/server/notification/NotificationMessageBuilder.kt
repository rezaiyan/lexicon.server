package com.alirezaiyan.vokab.server.notification

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