package com.alirezaiyan.vokab.server.shared

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

private val log = KotlinLogging.logger(AccessLogFilter::class.java.name)

/**
 * One access-log line per request: method, path template, status, duration, user id and request id.
 * No headers or bodies, so no tokens or PII reach the logs.
 *
 * Runs before Spring Security so rejected requests (401/403) are logged and every log line of the
 * request carries `requestId` (MDC). The id comes from a well-formed `X-Request-Id` header or is
 * generated, and is echoed in the response for client-side correlation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessLogFilter(private val appProperties: AppProperties) : OncePerRequestFilter() {

    private val pathMatcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!appProperties.logging.enabled) return true
        return appProperties.logging.excludePatterns.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { pathMatcher.match(it, request.requestURI) }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf { SAFE_REQUEST_ID.matches(it) }
            ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put(REQUEST_ID_MDC_KEY, requestId)
        val start = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            logAccess(request, response, (System.nanoTime() - start) / 1_000_000)
            MDC.remove(REQUEST_ID_MDC_KEY)
        }
    }

    private fun logAccess(request: HttpServletRequest, response: HttpServletResponse, durationMs: Long) {
        // The route template ("/api/v1/words/{id}") once Spring MVC matched a handler
        val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
            ?: request.requestURI
        val userId = request.getAttribute(USER_ID_ATTRIBUTE)?.toString() ?: "anonymous"
        val status = response.status
        val line = "${request.method} $path $status ${durationMs}ms user=$userId"
        val fields = mapOf(
            "http.method" to request.method,
            "http.route" to path,
            "http.status" to status,
            "duration.ms" to durationMs,
            "user.id" to userId,
        )
        if (durationMs > SLOW_REQUEST_MS) {
            log.atWarn { message = "$line (slow)"; payload = fields }
        } else {
            log.atInfo { message = line; payload = fields }
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_MDC_KEY = "requestId"

        /** Set by JwtAuthenticationFilter; the security context is already cleared when this filter logs. */
        const val USER_ID_ATTRIBUTE = "com.alirezaiyan.vokab.server.userId"

        private const val SLOW_REQUEST_MS = 1000L

        // Client-supplied ids end up in logs and a response header: no whitespace/control characters
        private val SAFE_REQUEST_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
