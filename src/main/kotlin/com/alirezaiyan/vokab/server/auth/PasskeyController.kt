package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.clientAddress
import com.alirezaiyan.vokab.server.shared.consumeOrThrow
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * Passkeys: create and manage them while signed in, and sign in with one (the two sign-in
 * endpoints are public and share the sign-in rate limit).
 */
@RestController
@RequestMapping("/api/v1/auth/passkeys")
class PasskeyController(
    private val passkeyService: PasskeyService,
    private val authService: AuthService,
    private val rateLimitConfig: RateLimitConfig,
) {

    @PostMapping("/registration-options")
    fun registrationOptions(
        @AuthenticationPrincipal user: AuthUser,
    ): ResponseEntity<ApiResponse<PasskeyCreationOptions>> =
        ResponseEntity.ok(ApiResponse(success = true, data = passkeyService.registrationOptions(user.id)))

    @PostMapping
    fun register(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: PasskeyResponseRequest,
    ): ResponseEntity<ApiResponse<PasskeyDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = passkeyService.register(user.id, request.responseJson)))

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthUser,
    ): ResponseEntity<ApiResponse<List<PasskeyDto>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = passkeyService.list(user.id)))

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<Unit>> {
        passkeyService.delete(user.id, id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Passkey deleted"))
    }

    @PostMapping("/sign-in-options")
    fun signInOptions(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<PasskeyRequestOptions>> {
        consumeAuthAttempt(httpRequest.clientAddress(), "passkey options")
        return ResponseEntity.ok(ApiResponse(success = true, data = passkeyService.signInOptions()))
    }

    @PostMapping("/sign-in")
    fun signIn(
        @Valid @RequestBody request: PasskeyResponseRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val ipAddress = httpRequest.clientAddress()
        consumeAuthAttempt(ipAddress, "passkey")
        val response = authService.authenticateWithPasskey(request.responseJson, platform, appVersion, ipAddress)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    private fun consumeAuthAttempt(ipAddress: String, endpoint: String) {
        rateLimitConfig.getAuthBucket(ipAddress).consumeOrThrow {
            logger.warn { "Auth rate limit exceeded for IP $ipAddress on $endpoint" }
        }
    }
}
