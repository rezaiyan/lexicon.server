package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.controller.handler.NotificationControllerHandler
import com.alirezaiyan.vokab.server.presentation.dto.*
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/notifications")
class PushNotificationController(
    private val handler: NotificationControllerHandler
) {
    
    @PostMapping("/register-token")
    fun registerPushToken(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: RegisterPushTokenRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.registerToken(user, request)
    }
    
    @DeleteMapping("/token/{token}")
    fun deactivateToken(
        @AuthenticationPrincipal user: User,
        @PathVariable token: String
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.deactivateToken(user, token)
    }
    
    @DeleteMapping("/tokens")
    fun deactivateAllTokens(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.deactivateAllTokens(user)
    }
    
    @PostMapping("/send")
    fun sendNotification(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: SendNotificationRequest
    ): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        return handler.sendNotification(user, request)
    }
    
    /** Idempotent: repeated calls for the same notification are no-ops. */
    @PostMapping("/{notificationLogId}/opened")
    fun markOpened(
        @AuthenticationPrincipal user: User,
        @PathVariable notificationLogId: Long
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.markOpened(user, notificationLogId)
    }

    @GetMapping("/tokens")
    fun getUserTokens(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Int>> {
        return handler.getUserTokens(user)
    }
}

