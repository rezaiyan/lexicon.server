package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AccessLogFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import com.alirezaiyan.vokab.server.shared.AuthUser

private val log = KotlinLogging.logger {}

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: RS256JwtTokenProvider,
    private val appProperties: AppProperties,
    private val userAccessCache: UserAccessCache,
) : OncePerRequestFilter() {
    
    // Paths that should skip JWT authentication
    private val excludedPaths = listOf(
        "/api/v1/auth/google",
        "/api/v1/auth/apple",
        "/api/v1/auth/refresh",
        "/api/v1/auth/ci-token",
        "/api/v1/auth/jwks",
        "/api/v1/webhooks/",
        "/api/v1/health",
        "/api/v1/version",
        "/api/v1/users/feature-flags",
        "/api/v1/onboarding/",
        "/h2-console/",
        "/actuator/",
        "/error"
    )
    
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return excludedPaths.any { path.startsWith(it) }
    }
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI
        log.debug { "🔐 JWT Filter [START]: Processing ${request.method} $path" }

        // Admin key — grants ROLE_ADMIN for /admin/** without requiring a user JWT
        val adminKey = request.getHeader("X-Admin-Key")
        val configuredKey = appProperties.security.adminApiKey
        if (!adminKey.isNullOrBlank() && configuredKey.isNotBlank() && adminKey == configuredKey) {
            val auth = UsernamePasswordAuthenticationToken(
                "ali-cli", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
            )
            SecurityContextHolder.getContext().authentication = auth
            request.setAttribute(AccessLogFilter.USER_ID_ATTRIBUTE, "admin")
            filterChain.doFilter(request, response)
            return
        }

        try {
            val jwt = getJwtFromRequest(request)
            
            if (jwt == null) {
                log.warn { "❌ JWT Filter [NO_TOKEN]: No JWT token found for $path - returning 401" }
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.writer.write("""{"success":false,"message":"Authentication required"}""")
                response.contentType = "application/json"
                return
            } else {
                log.debug { "🔑 JWT Filter [TOKEN_FOUND]: Token length ${jwt.length} for $path" }
                
                val isValid = jwtTokenProvider.validateToken(jwt)
                log.debug { "🔍 JWT Filter [VALIDATE]: Token valid=$isValid for $path" }
                
                if (isValid) {
                    val userId = jwtTokenProvider.getUserIdFromToken(jwt)
                    log.debug { "👤 JWT Filter [USER_ID]: Extracted user ID=$userId for $path" }
                    
                    if (userId != null) {
                        if (userAccessCache.isAllowed(userId)) {
                            val authentication = UsernamePasswordAuthenticationToken(
                                AuthUser(userId),
                                null,
                                emptyList()
                            )
                            authentication.details = WebAuthenticationDetailsSource().buildDetails(request)

                            SecurityContextHolder.getContext().authentication = authentication
                            request.setAttribute(AccessLogFilter.USER_ID_ATTRIBUTE, userId)
                            log.debug { "✅ JWT Filter [AUTH_SUCCESS]: Set authentication for user=$userId for $path" }
                            log.debug { "🔄 JWT Filter [FILTER_CHAIN]: Proceeding to next filter for $path" }
                        } else {
                            log.warn { "❌ JWT Filter [AUTH_FAILED]: unknown or inactive userId=$userId - 403 for $path" }
                            response.status = HttpServletResponse.SC_FORBIDDEN
                            response.writer.write("""{"success":false,"message":"User account has been deleted or deactivated"}""")
                            response.contentType = "application/json"
                            return
                        }
                    } else {
                        log.warn { "❌ JWT Filter [NO_USER_ID]: Unable to extract user ID from token - returning 401 for $path" }
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                        response.writer.write("""{"success":false,"message":"Invalid token"}""")
                        response.contentType = "application/json"
                        return
                    }
                } else {
                    log.warn { "❌ JWT Filter [INVALID_TOKEN]: Token validation failed - returning 401 for $path" }
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.writer.write("""{"success":false,"message":"Invalid or expired token"}""")
                    response.contentType = "application/json"
                    return
                }
            }
        } catch (e: Exception) {
            log.error { "💥 JWT Filter [EXCEPTION]: Error during authentication for $path - ${e.message}" }
            log.error { "Stack trace: ${e.stackTraceToString()}" }
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.writer.write("""{"success":false,"message":"Authentication error"}""")
            response.contentType = "application/json"
            return
        }
        
        log.debug { "🔄 JWT Filter [CONTINUE]: Calling filter chain for $path" }
        filterChain.doFilter(request, response)
        log.debug { "✅ JWT Filter [END]: Filter chain completed for $path, response status: ${response.status}" }
    }
    
    private fun getJwtFromRequest(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    }
}

