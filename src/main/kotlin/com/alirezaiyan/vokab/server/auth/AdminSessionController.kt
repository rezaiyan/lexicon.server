package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class AdminSignInRequest(
    @field:NotBlank(message = "Admin key is required")
    val key: String,
)

data class AdminSessionDto(
    val authenticated: Boolean,
    val expiresAt: Instant? = null,
)

/**
 * Admin dashboard sign-in: trades the admin key for a short-lived session in an HttpOnly,
 * SameSite=Strict cookie, so the key itself never sits in browser storage.
 */
@Hidden
@RestController
@RequestMapping(ADMIN_SESSION_PATH)
class AdminSessionController(
    private val adminSessionTokens: AdminSessionTokens,
    private val rateLimitConfig: RateLimitConfig,
) {
    @PostMapping
    fun signIn(
        @Valid @RequestBody body: AdminSignInRequest,
        @RequestHeader(ADMIN_REQUEST_HEADER, required = false) adminRequest: String?,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<AdminSessionDto>> {
        val ip = AdminAuthenticationFilter.clientIp(request)
        val bucket = rateLimitConfig.getAdminAuthBucket(ip)
        refusal(adminRequest, bucket.availableTokens)?.let { return it }
        if (!adminSessionTokens.keyMatches(body.key)) {
            bucket.tryConsume(1)
            logger.warn { "Rejected admin dashboard sign-in from $ip" }
            return failure(HttpStatus.UNAUTHORIZED, "Invalid admin key")
        }

        val session = adminSessionTokens.issue()
        logger.info { "Admin dashboard sign-in from $ip" }
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(session.token, AdminSessionTokens.SESSION_TTL).toString())
            .body(ApiResponse(success = true, data = AdminSessionDto(true, session.expiresAt)))
    }

    @GetMapping
    fun current(
        @CookieValue(ADMIN_SESSION_COOKIE, required = false) token: String?,
    ): ResponseEntity<ApiResponse<AdminSessionDto>> {
        val expiresAt = adminSessionTokens.validate(token)
        return ResponseEntity.ok(ApiResponse(success = true, data = AdminSessionDto(expiresAt != null, expiresAt)))
    }

    @DeleteMapping
    fun signOut(): ResponseEntity<ApiResponse<AdminSessionDto>> =
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .body(ApiResponse(success = true, data = AdminSessionDto(authenticated = false)))

    /** Why a sign-in is refused before the key is even checked, or null to go on. */
    private fun refusal(adminRequest: String?, attemptsLeft: Long): ResponseEntity<ApiResponse<AdminSessionDto>>? =
        when {
            adminRequest != "1" -> failure(HttpStatus.FORBIDDEN, "Missing $ADMIN_REQUEST_HEADER header")
            !adminSessionTokens.dashboardSignInEnabled() -> failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dashboard sign-in is disabled: the admin key must be at least " +
                    "${AdminSessionTokens.MIN_DASHBOARD_KEY_LENGTH} characters",
            )
            attemptsLeft <= 0 -> failure(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.")
            else -> null
        }

    private fun failure(status: HttpStatus, message: String): ResponseEntity<ApiResponse<AdminSessionDto>> =
        ResponseEntity.status(status).body(ApiResponse(success = false, message = message))

    // Secure is honoured on http://localhost by current browsers, so local dev still works
    private fun sessionCookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(ADMIN_SESSION_COOKIE, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path(ADMIN_PATH_PREFIX.trimEnd('/'))
            .maxAge(maxAge)
            .build()
}
