package com.alirezaiyan.vokab.server.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Endpoints anyone may call. [SecurityConfig] permits them and [JwtAuthenticationFilter] skips them,
 * both from this one list so the two can't drift apart.
 */
internal val PUBLIC_PATHS = listOf(
    "/api/v1/auth/google",
    "/api/v1/auth/apple",
    "/api/v1/auth/refresh",
    "/api/v1/auth/ci-token",
    "/api/v1/auth/jwks",
    "/api/v1/webhooks/**",
    "/api/v1/health",
    "/api/v1/version",
    "/api/v1/users/feature-flags",
    "/api/v1/onboarding/**",
    "/h2-console/**",
    // Admin dashboard page and its sign-in; the data endpoints stay behind ROLE_ADMIN
    "/admin-ui/**",
    ADMIN_SESSION_PATH,
    // Only served on the internal management port (management.server.port)
    "/actuator/**",
    "/error",
)

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val adminAuthenticationFilter: AdminAuthenticationFilter,
) {
    
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    // Public endpoints - authentication not required
                    .requestMatchers(*PUBLIC_PATHS.toTypedArray()).permitAll()
                    // Admin endpoints require ADMIN role
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // All other endpoints require authentication (including /auth/logout, /auth/delete-account)
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(adminAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Allow H2 console
            .headers { it.frameOptions { frameOptions -> frameOptions.sameOrigin() } }
        
        return http.build()
    }
    
    /**
     * Any origin may call the API. It authenticates with bearer tokens only, never cookies, and CORS
     * never allows credentials, so a foreign page can't act as a signed-in user. The one cookie (the
     * admin dashboard session) is SameSite=Strict and also needs a custom header.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")

        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.exposedHeaders = listOf("Authorization", "Content-Type")
        configuration.allowCredentials = false  // Must be false when using wildcard origins
        configuration.maxAge = 3600L
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}

