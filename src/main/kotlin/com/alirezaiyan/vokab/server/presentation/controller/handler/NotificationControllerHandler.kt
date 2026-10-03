package com.alirezaiyan.vokab.server.presentation.controller.handler

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.presentation.dto.RegisterPushTokenRequest
import com.alirezaiyan.vokab.server.presentation.dto.SendNotificationRequest
import com.alirezaiyan.vokab.server.service.NotificationEngagementService
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.alirezaiyan.vokab.server.service.push.PushTokenService
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import com.alirezaiyan.vokab.server.domain.entity.requireId

@Component
class NotificationControllerHandler(
    private val pushTokenService: PushTokenService,
    private val pushNotificationService: PushNotificationService,
    private val notificationEngagementService: NotificationEngagementService,
) {
    
    fun registerToken(user: User, request: RegisterPushTokenRequest): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.registerToken(
                userId = user.requireId(),
                token = request.token,
                platform = request.platform,
                deviceId = request.deviceId
            )
            ApiResponse(success = true, message = "Push token registered successfully")
        }
    }
    
    fun deactivateToken(user: User, token: String): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.deactivateToken(token)
            ApiResponse(success = true, message = "Token deactivated successfully")
        }
    }
    
    fun deactivateAllTokens(user: User): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.deactivateAllUserTokens(user.requireId())
            ApiResponse(success = true, message = "All tokens deactivated successfully")
        }
    }
    
    fun sendNotification(user: User, request: SendNotificationRequest): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        return execute<List<NotificationResponse>> {
            val responses = pushNotificationService.sendNotificationToUser(
                userId = user.requireId(),
                title = request.title,
                body = request.body,
                data = request.data,
                imageUrl = request.imageUrl
            )
            ApiResponse(success = true, data = responses)
        }
    }
    
    fun getUserTokens(user: User): ResponseEntity<ApiResponse<Int>> {
        return execute<Int> {
            val tokens = pushTokenService.getActiveTokensForUser(user.requireId())
            ApiResponse(
                success = true,
                data = tokens.size,
                message = "${tokens.size} active tokens"
            )
        }
    }
    
    fun markOpened(user: User, notificationLogId: Long): ResponseEntity<ApiResponse<Unit>> {
        // Unknown and foreign ids both 404 so ids can't be probed across users
        if (!notificationEngagementService.recordOpen(user.requireId(), notificationLogId)) {
            throw NoSuchElementException("Notification not found")
        }
        return ResponseEntity.ok(ApiResponse(success = true))
    }

    private inline fun <T> execute(operation: () -> ApiResponse<T>): ResponseEntity<ApiResponse<T>> =
        ResponseEntity.ok(operation())
}


