package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
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
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: RegisterPushTokenRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.registerToken(user.id, request)
    }
    
    @DeleteMapping("/token/{token}")
    fun deactivateToken(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable token: String
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.deactivateToken(user.id, token)
    }
    
    @DeleteMapping("/tokens")
    fun deactivateAllTokens(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.deactivateAllTokens(user.id)
    }
    
    @PostMapping("/send")
    fun sendNotification(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SendNotificationRequest
    ): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        return handler.sendNotification(user.id, request)
    }
    
    /** Idempotent: repeated calls for the same notification are no-ops. */
    @PostMapping("/{notificationLogId}/opened")
    fun markOpened(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable notificationLogId: Long
    ): ResponseEntity<ApiResponse<Unit>> {
        return handler.markOpened(user.id, notificationLogId)
    }

    @GetMapping("/tokens")
    fun getUserTokens(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Int>> {
        return handler.getUserTokens(user.id)
    }
}

