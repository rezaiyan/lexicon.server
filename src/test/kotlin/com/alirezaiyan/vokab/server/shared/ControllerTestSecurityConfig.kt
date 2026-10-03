package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.auth.JwtAuthenticationFilter
import com.alirezaiyan.vokab.server.auth.RS256JwtTokenProvider
import com.alirezaiyan.vokab.server.auth.UserAccessCache
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Shared test configuration for all controller tests.
 *
 * Provides:
 * - A real [AppProperties] with defaults so [com.alirezaiyan.vokab.server.auth.SecurityConfig]
 *   can construct its CORS configuration without a NullPointerException.
 * - A no-op [JwtAuthenticationFilter] that skips JWT validation for every request, letting
 *   [org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication]
 *   inject the principal directly from the test.
 */
@TestConfiguration
class ControllerTestSecurityConfig {

    @Bean
    @Primary
    fun appProperties(): AppProperties = AppProperties()

    @Bean
    @Primary
    fun jwtAuthenticationFilter(): JwtAuthenticationFilter {
        // Use a Mockito mock of RS256JwtTokenProvider just so the constructor is satisfied;
        // the filter body is completely replaced by the overrides below.
        val tokenProvider = Mockito.mock(RS256JwtTokenProvider::class.java)
        val userAccessCache = Mockito.mock(UserAccessCache::class.java)
        return object : JwtAuthenticationFilter(tokenProvider, AppProperties(), userAccessCache) {
            override fun shouldNotFilter(request: HttpServletRequest): Boolean = true

            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain,
            ) {
                filterChain.doFilter(request, response)
            }
        }
    }
}
