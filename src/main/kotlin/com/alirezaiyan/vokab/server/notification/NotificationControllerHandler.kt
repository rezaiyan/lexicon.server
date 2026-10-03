package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class NotificationControllerHandler(
    private val pushTokenService: PushTokenService,
    private val pushNotificationService: PushNotificationService,
    private val notificationEngagementService: NotificationEngagementService,
) {
    
    fun registerToken(userId: Long, request: RegisterPushTokenRequest): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.registerToken(
                userId = userId,
                token = request.token,
                platform = request.platform,
                deviceId = request.deviceId
            )
            ApiResponse(success = true, message = "Push token registered successfully")
        }
    }
    
    fun deactivateToken(userId: Long, token: String): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.deactivateToken(token)
            ApiResponse(success = true, message = "Token deactivated successfully")
        }
    }
    
    fun deactivateAllTokens(userId: Long): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.deactivateAllUserTokens(userId)
            ApiResponse(success = true, message = "All tokens deactivated successfully")
        }
    }
    
    fun sendNotification(userId: Long, request: SendNotificationRequest): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        return execute<List<NotificationResponse>> {
            val responses = pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = request.title,
                body = request.body,
                data = request.data,
                imageUrl = request.imageUrl
            )
            ApiResponse(success = true, data = responses)
        }
    }
    
    fun getUserTokens(userId: Long): ResponseEntity<ApiResponse<Int>> {
        return execute<Int> {
            val tokens = pushTokenService.getActiveTokensForUser(userId)
            ApiResponse(
                success = true,
                data = tokens.size,
                message = "${tokens.size} active tokens"
            )
        }
    }
    
    fun markOpened(userId: Long, notificationLogId: Long): ResponseEntity<ApiResponse<Unit>> {
        // Unknown and foreign ids both 404 so ids can't be probed across users
        if (!notificationEngagementService.recordOpen(userId, notificationLogId)) {
            throw NoSuchElementException("Notification not found")
        }
        return ResponseEntity.ok(ApiResponse(success = true))
    }

    private inline fun <T> execute(operation: () -> ApiResponse<T>): ResponseEntity<ApiResponse<T>> =
        ResponseEntity.ok(operation())
}


