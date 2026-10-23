package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.clientAddress
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Open dynamic client registration (RFC 7591), which MCP clients use to introduce themselves.
 * Spring Authorization Server 1.x only offers OIDC registration behind an initial access token, so
 * this is ours.
 */
@RestController
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpClientRegistrationController(
    private val oauthService: McpOAuthService,
    private val rateLimits: RateLimitConfig,
) {

    @PostMapping(McpAuthorizationServerConfig.REGISTRATION_PATH)
    fun register(@RequestBody request: ClientRegistrationRequest, http: HttpServletRequest): ResponseEntity<Any> {
        if (!rateLimits.getMcpClientRegistrationBucket(clientIp(http)).tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(OAuthErrorResponse("slow_down", "Too many registrations"))
        }
        return when (val result = oauthService.register(request)) {
            is McpOAuthService.RegistrationResult.Registered ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.client)
            is McpOAuthService.RegistrationResult.Rejected ->
                ResponseEntity.badRequest().body(OAuthErrorResponse(result.error, result.description))
        }
    }
}

/** The caller's address for per-IP limits; see [clientAddress] for why headers are not read here. */
internal fun clientIp(request: HttpServletRequest): String = request.clientAddress()
