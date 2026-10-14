package com.alirezaiyan.vokab.server.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Replaces Spring AI's default stateless transport so each MCP call carries the caller's user id and
 * granted scopes, taken from the OAuth access token `/mcp` was called with
 * ([McpAuthorizationServerConfig] has already validated it). Tools read them from
 * [McpTransportContext] instead of the SecurityContext thread-local, which the MCP handler isn't
 * guaranteed to run on.
 */
@Configuration
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpServerConfig {

    @Bean
    fun webMvcStatelessServerTransport(objectMapper: ObjectMapper): WebMvcStatelessServerTransport =
        WebMvcStatelessServerTransport.builder()
            .jsonMapper(JacksonMcpJsonMapper(objectMapper))
            .messageEndpoint(McpAuthorizationServerConfig.MCP_PATH)
            .contextExtractor { request ->
                val token = request.servletRequest().userPrincipal as? JwtAuthenticationToken
                val userId = token?.name?.toLongOrNull()
                if (token == null || userId == null) {
                    McpTransportContext.EMPTY
                } else {
                    val scopes = token.authorities.map { it.authority }
                        .filter { it.startsWith(SCOPE_PREFIX) }
                        .map { it.removePrefix(SCOPE_PREFIX) }
                    McpTransportContext.create(mapOf(USER_ID to userId, SCOPES to scopes.toSet()))
                }
            }
            .build()

    companion object {
        const val USER_ID = "userId"
        const val SCOPES = "scopes"
        private const val SCOPE_PREFIX = "SCOPE_"
    }
}

/** The authenticated caller of this MCP request; the filter chain rejects anonymous calls before this point. */
internal fun McpTransportContext.userId(): Long =
    get(McpServerConfig.USER_ID) as? Long ?: error("MCP call without an authenticated user")

/** The caller's user id, once the learner granted [scope] to this client. Fails the tool call otherwise. */
internal fun McpTransportContext.userIdWithScope(scope: String): Long {
    val granted = get(McpServerConfig.SCOPES) as? Set<*> ?: emptySet<Any>()
    check(scope in granted) { "This app wasn't given \"$scope\" access. Reconnect it to Lexicon to grant it." }
    return userId()
}
