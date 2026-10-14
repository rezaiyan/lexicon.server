package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication

/**
 * Replaces Spring AI's default stateless transport so each MCP call carries the caller's user id.
 * Tools read it from [McpTransportContext] instead of the SecurityContext thread-local, which the
 * MCP handler isn't guaranteed to run on. `/mcp` itself sits behind the normal JWT filter chain.
 */
@Configuration
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpServerConfig {

    @Bean
    fun webMvcStatelessServerTransport(objectMapper: ObjectMapper): WebMvcStatelessServerTransport =
        WebMvcStatelessServerTransport.builder()
            .jsonMapper(JacksonMcpJsonMapper(objectMapper))
            .messageEndpoint(MCP_ENDPOINT)
            .contextExtractor { request ->
                val userId = ((request.servletRequest().userPrincipal as? Authentication)?.principal as? AuthUser)?.id
                if (userId == null) McpTransportContext.EMPTY else McpTransportContext.create(mapOf(USER_ID to userId))
            }
            .build()

    companion object {
        const val MCP_ENDPOINT = "/mcp"
        const val USER_ID = "userId"
    }
}

/** The authenticated caller of this MCP request; the filter chain rejects anonymous calls before this point. */
internal fun McpTransportContext.userId(): Long =
    get(McpServerConfig.USER_ID) as? Long ?: error("MCP call without an authenticated user")
