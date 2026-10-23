package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Protected-resource metadata (RFC 9728): how an MCP client that got a 401 from `/mcp` finds our
 * authorization server. Served at the root and with the resource path appended, as clients try both.
 */
@RestController
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpProtectedResourceController(private val appProperties: AppProperties) {

    @GetMapping(
        McpAuthorizationServerConfig.PROTECTED_RESOURCE_METADATA_PATH,
        McpAuthorizationServerConfig.PROTECTED_RESOURCE_METADATA_PATH + McpAuthorizationServerConfig.MCP_PATH,
    )
    fun metadata(): ProtectedResourceMetadata {
        val issuer = appProperties.mcp.issuer.trimEnd('/')
        return ProtectedResourceMetadata(
            resource = issuer + McpAuthorizationServerConfig.MCP_PATH,
            authorizationServers = listOf(issuer),
            scopesSupported = McpScopes.ALL.toList(),
        )
    }

    data class ProtectedResourceMetadata(
        @JsonProperty("resource") val resource: String,
        @JsonProperty("authorization_servers") val authorizationServers: List<String>,
        @JsonProperty("scopes_supported") val scopesSupported: List<String>,
        @JsonProperty("bearer_methods_supported") val bearerMethodsSupported: List<String> = listOf("header"),
        @JsonProperty("resource_name") val resourceName: String = "Lexicon",
    )
}
