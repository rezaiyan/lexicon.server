package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val appProperties: AppProperties
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
                    .requestMatchers(
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
                        // Only served on the internal management port (management.server.port)
                        "/actuator/**",
                        "/error"
                    ).permitAll()
                    // Admin endpoints require ADMIN role
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // All other endpoints require authentication (including /auth/logout, /auth/delete-account)
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Allow H2 console
            .headers { it.frameOptions { frameOptions -> frameOptions.sameOrigin() } }
        
        return http.build()
    }
    
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        
        // Allow configured origins (web apps) and null origin (mobile apps)
        val origins = appProperties.cors.allowedOrigins.split(",").toMutableList()
        // Mobile native apps don't send Origin header, so we need to allow all origins for API
        configuration.allowedOriginPatterns = listOf("*")  // Allow all origins including mobile apps
        
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

