package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val appProperties: AppProperties
) {
    
    @PostMapping("/google")
    fun authenticateWithGoogle(
        @Valid @RequestBody request: GoogleAuthRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        return try {
            val ipAddress = getClientIpAddress(httpRequest)
            val response = authService.authenticateWithGoogle(request.idToken, platform, appVersion, ipAddress)

            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Google authentication failed" }
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = e.clientMessage("Authentication failed")))
        }
    }

    @PostMapping("/apple")
    fun authenticateWithApple(
        @Valid @RequestBody request: AppleAuthRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        return try {
            val ipAddress = getClientIpAddress(httpRequest)
            val response = authService.authenticateWithApple(
                request.idToken,
                request.fullName,
                request.appleUserId,
                platform,
                appVersion,
                ipAddress
            )

            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Apple authentication failed" }
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = e.clientMessage("Authentication failed")))
        }
    }
    
    @PostMapping("/ci-token")
    fun authenticateForCi(
        @RequestHeader("X-CI-Secret", required = false) ciSecret: String?,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        @RequestParam("premium", defaultValue = "true") premium: Boolean,
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        if (!appProperties.ciAuth.enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse(success = false, message = "Not found"))
        }

        if (ciSecret.isNullOrBlank() || ciSecret != appProperties.ciAuth.secret) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = "Unauthorized"))
        }

        return try {
            val response = authService.authenticateForCi(premium = premium, platform = platform, appVersion = appVersion)
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "CI authentication failed" }
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse(success = false, message = e.clientMessage("CI authentication failed")))
        }
    }

    @PostMapping("/refresh")
    fun refreshToken(
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        return try {
            val response = authService.refreshAccessToken(request.refreshToken)
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Token refresh failed" }
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = e.clientMessage("Token refresh failed")))
        }
    }
    
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            authService.logout(user.id!!, request.refreshToken)
            ResponseEntity.ok(ApiResponse(success = true, message = "Logged out successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Logout failed" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Logout failed")))
        }
    }
    
    private fun getClientIpAddress(request: HttpServletRequest): String {
        var ipAddress = request.getHeader("X-Forwarded-For")
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress, ignoreCase = true)) {
            ipAddress = request.getHeader("X-Real-IP")
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress, ignoreCase = true)) {
            ipAddress = request.getHeader("Proxy-Client-IP")
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress, ignoreCase = true)) {
            ipAddress = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress, ignoreCase = true)) {
            ipAddress = request.remoteAddr
        }
        return ipAddress?.split(",")?.firstOrNull()?.trim() ?: request.remoteAddr
    }
    
    @PostMapping("/logout-all")
    fun logoutAll(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            authService.logoutAll(user.id!!)
            ResponseEntity.ok(ApiResponse(success = true, message = "All sessions logged out successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Logout all failed" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Logout all failed")))
        }
    }
    
    @DeleteMapping("/delete-account")
    fun deleteAccount(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            logger.info { "Delete account request received for user: ${user.id}" }
            authService.deleteAccount(user.id!!)
            ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Delete account failed" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to delete account")))
        }
    }
}

