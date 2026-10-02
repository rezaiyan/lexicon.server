package com.alirezaiyan.vokab.server.presentation.controller.handler

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.presentation.dto.RegisterPushTokenRequest
import com.alirezaiyan.vokab.server.presentation.dto.SendNotificationRequest
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.alirezaiyan.vokab.server.service.push.PushTokenService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class NotificationControllerHandler(
    private val pushTokenService: PushTokenService,
    private val pushNotificationService: PushNotificationService
) {
    
    fun registerToken(user: User, request: RegisterPushTokenRequest): ResponseEntity<ApiResponse<Unit>> {
        return execute<Unit> {
            pushTokenService.registerToken(
                userId = user.id!!,
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
            pushTokenService.deactivateAllUserTokens(user.id!!)
            ApiResponse(success = true, message = "All tokens deactivated successfully")
        }
    }
    
    fun sendNotification(user: User, request: SendNotificationRequest): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        return execute<List<NotificationResponse>> {
            val responses = pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
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
            val tokens = pushTokenService.getActiveTokensForUser(user.id!!)
            ApiResponse(
                success = true,
                data = tokens.size,
                message = "${tokens.size} active tokens"
            )
        }
    }
    
    private inline fun <T> execute(operation: () -> ApiResponse<T>): ResponseEntity<ApiResponse<T>> {
        return try {
            val result = operation()
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            logger.error(e) { "Operation failed" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Operation failed")))
        }
    }
}


