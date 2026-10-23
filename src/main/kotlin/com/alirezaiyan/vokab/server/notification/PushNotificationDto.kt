package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.Platform
import jakarta.validation.constraints.NotBlank

data class RegisterPushTokenRequest(
    @field:NotBlank(message = "Push token is required")
    val token: String,
    
    val platform: Platform,
    
    val deviceId: String? = null
)

data class SendNotificationRequest(
    @field:NotBlank(message = "Title is required")
    val title: String,
    
    @field:NotBlank(message = "Body is required")
    val body: String,
    
    val data: Map<String, String>? = null,
    
    val imageUrl: String? = null
)

data class NotificationResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)

