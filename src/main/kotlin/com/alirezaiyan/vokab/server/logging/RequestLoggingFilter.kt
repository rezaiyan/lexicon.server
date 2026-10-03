package com.alirezaiyan.vokab.server.logging

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.domain.entity.User
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}
private const val REQUEST_ID_MDC_KEY = "requestId"
private const val MAX_BODY_DISPLAY_LENGTH = 1000

@Component
@Order(1)
class RequestLoggingFilter(
    private val appProperties: AppProperties
) : OncePerRequestFilter() {
    
    private val antPathMatcher = AntPathMatcher()
    
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!appProperties.logging.enabled) {
            return true
        }
        
        val path = request.requestURI
        val excludePatterns = appProperties.logging.excludePatterns
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        return excludePatterns.any { pattern ->
            antPathMatcher.match(pattern, path)
        }
    }
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = UUID.randomUUID().toString()
        MDC.put(REQUEST_ID_MDC_KEY, requestId)
        
        val startNanos = System.nanoTime()
        val cachingRequest = CachingRequestWrapper(request)
        val cachingResponse = CachingResponseWrapper(response)

        try {
            logRequest(cachingRequest)
            filterChain.doFilter(cachingRequest, cachingResponse)
            val executionTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            logResponse(cachingRequest, cachingResponse, executionTime)
        } finally {
            // Pooled threads: a leaked requestId would tag the next request's logs
            MDC.remove(REQUEST_ID_MDC_KEY)
        }
    }
    
    private fun logRequest(request: HttpServletRequest) {
        val requestId = MDC.get(REQUEST_ID_MDC_KEY) ?: "unknown"
        val method = request.method
        val uri = request.requestURI
        val queryString = request.queryString
        val clientIp = getClientIpAddress(request)
        val userAgent = request.getHeader("User-Agent") ?: "unknown"
        
        val user = getAuthenticatedUser()
        val userId = user?.id?.toString() ?: "anonymous"
        val userEmail = user?.email ?: "anonymous"
        
        log.info {
            """
            |[REQUEST START] RequestID: $requestId
            |  Method: $method
            |  URI: $uri
            |  Query: ${if (queryString != null) SensitiveDataMasker.maskQueryParams(queryString) else "none"}
            |  Client IP: $clientIp
            |  User-Agent: $userAgent
            |  User ID: $userId
            |  User Email: $userEmail
            """.trimMargin()
        }
        
        val headers = getRequestHeaders(request)
        val maskedHeaders = SensitiveDataMasker.maskHeaders(headers)
        log.debug {
            """
            |[REQUEST HEADERS] RequestID: $requestId
            |${formatHeaders(maskedHeaders)}
            """.trimMargin()
        }
        
        val body = getRequestBody(request)
        if (body.isNotEmpty()) {
            val maskedBody = maskBody(body, request.contentType ?: "")
            val truncatedBody = if (maskedBody.length > MAX_BODY_DISPLAY_LENGTH) {
                maskedBody.take(MAX_BODY_DISPLAY_LENGTH) + "... [truncated]"
            } else {
                maskedBody
            }
            log.debug {
                """
                |[REQUEST BODY] RequestID: $requestId
                |  Content-Type: ${request.contentType ?: "unknown"}
                |  Content-Length: ${body.size} bytes
                |  Body: $truncatedBody
                """.trimMargin()
            }
        }
    }
    
    private fun logResponse(
        request: HttpServletRequest,
        response: CachingResponseWrapper,
        executionTime: Long
    ) {
        val requestId = MDC.get(REQUEST_ID_MDC_KEY) ?: "unknown"
        val method = request.method
        val uri = request.requestURI
        val status = response.status
        val responseBody = getResponseBody(response)
        val contentLength = responseBody.size
        
        log.info {
            """
            |[REQUEST END] RequestID: $requestId
            |  Method: $method
            |  URI: $uri
            |  Status: $status
            |  Execution Time: ${executionTime}ms
            |  Response Size: $contentLength bytes
            """.trimMargin()
        }
        
        val headers = getResponseHeaders(response)
        log.debug {
            """
            |[RESPONSE HEADERS] RequestID: $requestId
            |${formatHeaders(headers)}
            """.trimMargin()
        }
        
        if (responseBody.isNotEmpty()) {
            val contentType = response.contentType ?: response.getHeader("Content-Type") ?: "unknown"
            val maskedBody = maskBody(responseBody, contentType)
            val truncatedBody = if (maskedBody.length > MAX_BODY_DISPLAY_LENGTH) {
                maskedBody.take(MAX_BODY_DISPLAY_LENGTH) + "... [truncated]"
            } else {
                maskedBody
            }
            log.debug {
                """
                |[RESPONSE BODY] RequestID: $requestId
                |  Content-Type: $contentType
                |  Content-Length: ${responseBody.size} bytes
                |  Body: $truncatedBody
                """.trimMargin()
            }
        }
        
        if (executionTime > 1000) {
            log.warn {
                "[SLOW REQUEST] RequestID: $requestId, URI: $uri, Execution Time: ${executionTime}ms"
            }
        }
    }
    
    private fun getRequestHeaders(request: HttpServletRequest): Map<String, List<String>> {
        val headers = mutableMapOf<String, MutableList<String>>()
        request.headerNames.asIterator().forEach { headerName ->
            val headerValues = request.getHeaders(headerName).toList()
            headers[headerName] = headerValues.toMutableList()
        }
        return headers
    }
    
    private fun getResponseHeaders(response: HttpServletResponse): Map<String, List<String>> {
        val headers = mutableMapOf<String, MutableList<String>>()
        response.headerNames.forEach { headerName ->
            val headerValues = response.getHeaders(headerName).toList()
            headers[headerName] = headerValues.toMutableList()
        }
        return headers
    }
    
    private fun formatHeaders(headers: Map<String, List<String>>): String {
        if (headers.isEmpty()) {
            return "  (no headers)"
        }
        return headers.entries.joinToString("\n") { (name, values) ->
            "  $name: ${values.joinToString(", ")}"
        }
    }
    
    private fun getRequestBody(request: HttpServletRequest): ByteArray {
        return if (request is CachingRequestWrapper) {
            val body = request.getCachedBody()
            if (body.size <= appProperties.logging.maxBodySize) {
                body
            } else {
                byteArrayOf()
            }
        } else {
            byteArrayOf()
        }
    }
    
    private fun getResponseBody(response: CachingResponseWrapper): ByteArray {
        val body = response.getCachedBody()
        return if (body.size <= appProperties.logging.maxBodySize) {
            body
        } else {
            byteArrayOf()
        }
    }
    
    private fun maskBody(body: ByteArray, contentType: String): String {
        val bodyString = String(body, StandardCharsets.UTF_8)
        
        return when {
            contentType.contains("application/json", ignoreCase = true) -> {
                SensitiveDataMasker.maskJsonBody(bodyString)
            }
            contentType.contains("application/x-www-form-urlencoded", ignoreCase = true) -> {
                SensitiveDataMasker.maskQueryParams(bodyString)
            }
            else -> {
                if (bodyString.length > appProperties.logging.maxBodySize) {
                    "[Body too large to display: ${body.size} bytes]"
                } else {
                    bodyString
                }
            }
        }
    }
    
    private fun getAuthenticatedUser(): User? {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication?.principal as? User
    }
    
    private fun getClientIpAddress(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (xForwardedFor != null && xForwardedFor.isNotEmpty() && xForwardedFor != "unknown") {
            return xForwardedFor.split(",").first().trim()
        }
        
        val xRealIp = request.getHeader("X-Real-IP")
        if (xRealIp != null && xRealIp.isNotEmpty() && xRealIp != "unknown") {
            return xRealIp
        }
        
        return request.remoteAddr ?: "unknown"
    }
}
