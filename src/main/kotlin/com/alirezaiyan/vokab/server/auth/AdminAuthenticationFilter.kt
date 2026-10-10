package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AccessLogFilter
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val logger = KotlinLogging.logger {}

/** Header carrying the static admin key (CLI and scripts). */
const val ADMIN_KEY_HEADER = "X-Admin-Key"

/** Cookie holding an admin dashboard session; scoped to the admin path prefix only. */
const val ADMIN_SESSION_COOKIE = "lexicon_admin_session"

/**
 * Required next to the session cookie. A cross-site page can't set a custom header without a
 * CORS preflight, and CORS here never allows credentials: a second CSRF guard behind SameSite.
 */
const val ADMIN_REQUEST_HEADER = "X-Admin-Request"

const val ADMIN_PATH_PREFIX = "/admin/"
const val ADMIN_SESSION_PATH = "/admin/session"

/**
 * Grants ROLE_ADMIN on the admin path prefix, from either the `X-Admin-Key` header or a
 * dashboard session cookie. Wrong keys are throttled per client IP; while an IP is throttled even
 * the right key is refused, so guessing can't continue in the background.
 */
@Component
class AdminAuthenticationFilter(
    private val adminSessionTokens: AdminSessionTokens,
    private val rateLimitConfig: RateLimitConfig,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !path.startsWith(ADMIN_PATH_PREFIX) || path == ADMIN_SESSION_PATH
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val key = request.getHeader(ADMIN_KEY_HEADER)
        val principal = when {
            key != null -> authenticateKey(key, request, response) ?: return
            else -> authenticateSession(request, response) ?: return
        }
        if (principal != ANONYMOUS) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
            )
            request.setAttribute(AccessLogFilter.USER_ID_ATTRIBUTE, "admin")
            if (request.method != "GET") {
                logger.info { "Admin ${request.method} ${request.requestURI} via $principal from ${clientIp(request)}" }
            }
        }
        filterChain.doFilter(request, response)
    }

    /** @return the principal, or null once a response has been written. */
    private fun authenticateKey(key: String, request: HttpServletRequest, response: HttpServletResponse): String? {
        val ip = clientIp(request)
        val bucket = rateLimitConfig.getAdminAuthBucket(ip)
        if (bucket.availableTokens <= 0) {
            reject(response, HttpStatus.TOO_MANY_REQUESTS.value(), "Too many failed admin attempts. Try again later.")
            return null
        }
        if (adminSessionTokens.keyMatches(key)) return CLI_PRINCIPAL
        bucket.tryConsume(1)
        logger.warn { "Rejected admin key for ${request.requestURI} from $ip" }
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid admin key")
        return null
    }

    private fun authenticateSession(request: HttpServletRequest, response: HttpServletResponse): String? {
        val token = request.cookies?.firstOrNull { it.name == ADMIN_SESSION_COOKIE }?.value
        if (adminSessionTokens.validate(token) == null) return ANONYMOUS // Spring Security answers 403
        if (request.getHeader(ADMIN_REQUEST_HEADER) != "1") {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Missing $ADMIN_REQUEST_HEADER header")
            return null
        }
        return DASHBOARD_PRINCIPAL
    }

    private fun reject(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write("""{"success":false,"message":"$message"}""")
    }

    companion object {
        const val CLI_PRINCIPAL = "ali-cli"
        const val DASHBOARD_PRINCIPAL = "admin-dashboard"
        private const val ANONYMOUS = ""

        /** First X-Forwarded-For hop (set by the reverse proxy), else the socket address. */
        fun clientIp(request: HttpServletRequest): String =
            request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: request.remoteAddr
                ?: "unknown"
    }
}
