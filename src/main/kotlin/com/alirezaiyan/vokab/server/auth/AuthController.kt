package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.clientAddress
import com.alirezaiyan.vokab.server.shared.consumeOrThrow
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
    private val tokenService: TokenService,
    private val accountDeletionService: AccountDeletionService,
    private val appProperties: AppProperties,
    private val rateLimitConfig: RateLimitConfig,
) {
    
    @PostMapping("/google")
    fun authenticateWithGoogle(
        @Valid @RequestBody request: GoogleAuthRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val ipAddress = httpRequest.clientAddress()
        consumeAuthAttempt(ipAddress, "google")
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
        val ipAddress = httpRequest.clientAddress()
        consumeAuthAttempt(ipAddress, "apple")
        val response = authService.authenticateWithApple(
            request.idToken,
            request.fullName,
            request.appleUserId,
            platform,
            appVersion,
            ipAddress,
            request.nonce
        )
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @PostMapping("/ci-token")
    fun authenticateForCi(
        @RequestHeader("X-CI-Secret", required = false) ciSecret: String?,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        @RequestParam("premium", defaultValue = "true") premium: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        if (!appProperties.ciAuth.enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse(success = false, message = "Not found"))
        }
        // After the enabled check: a disabled endpoint keeps answering a plain 404
        consumeAuthAttempt(httpRequest.clientAddress(), "ci-token")

        if (ciSecret.isNullOrBlank() || !constantTimeEquals(ciSecret, appProperties.ciAuth.secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = "Unauthorized"))
        }

        val response = authService.authenticateForCi(premium = premium, platform = platform, appVersion = appVersion)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @PostMapping("/refresh")
    fun refreshToken(
        @Valid @RequestBody request: RefreshTokenRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val ipAddress = httpRequest.clientAddress()
        rateLimitConfig.getRefreshBucket(ipAddress).consumeOrThrow {
            logger.warn { "Refresh rate limit exceeded for IP $ipAddress" }
        }
        val response = tokenService.rotate(request.refreshToken)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }
    
    /** Public: the refresh token is the credential (see [TokenService.logout]). */
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: RefreshTokenRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<Unit>> {
        val ipAddress = httpRequest.clientAddress()
        rateLimitConfig.getRefreshBucket(ipAddress).consumeOrThrow {
            logger.warn { "Logout rate limit exceeded for IP $ipAddress" }
        }
        tokenService.logout(request.refreshToken, ipAddress)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Logged out successfully"))
    }
    
    /** Takes one sign-in attempt for [ipAddress], or rejects the call with a 429 and Retry-After. */
    private fun consumeAuthAttempt(ipAddress: String, endpoint: String) {
        rateLimitConfig.getAuthBucket(ipAddress).consumeOrThrow {
            logger.warn { "Auth rate limit exceeded for IP $ipAddress on $endpoint" }
        }
    }
    
    @PostMapping("/logout-all")
    fun logoutAll(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        tokenService.logoutAll(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "All sessions logged out successfully"))
    }
    
    @DeleteMapping("/delete-account")
    fun deleteAccount(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        logger.info { "Delete account request received for user: ${user.id}" }
        accountDeletionService.deleteAccount(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
    }

    /** Compares secrets without leaking, through timing, how many leading characters matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
