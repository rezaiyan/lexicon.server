package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.security.AuthUser
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest

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
        val ipAddress = getClientIpAddress(httpRequest)
        val response = authService.authenticateWithGoogle(request.idToken, platform, appVersion, ipAddress)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @PostMapping("/apple")
    fun authenticateWithApple(
        @Valid @RequestBody request: AppleAuthRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val ipAddress = getClientIpAddress(httpRequest)
        val response = authService.authenticateWithApple(
            request.idToken,
            request.fullName,
            request.appleUserId,
            platform,
            appVersion,
            ipAddress
        )
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
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

        if (ciSecret.isNullOrBlank() || !constantTimeEquals(ciSecret, appProperties.ciAuth.secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = "Unauthorized"))
        }

        val response = authService.authenticateForCi(premium = premium, platform = platform, appVersion = appVersion)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @PostMapping("/refresh")
    fun refreshToken(
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val response = authService.refreshAccessToken(request.refreshToken)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }
    
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        authService.logout(user.id, request.refreshToken)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Logged out successfully"))
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
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        authService.logoutAll(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "All sessions logged out successfully"))
    }
    
    @DeleteMapping("/delete-account")
    fun deleteAccount(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        logger.info { "Delete account request received for user: ${user.id}" }
        authService.deleteAccount(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
    }

    /** Compares secrets without leaking, through timing, how many leading characters matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
