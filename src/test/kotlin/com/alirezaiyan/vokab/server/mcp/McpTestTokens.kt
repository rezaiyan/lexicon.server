package com.alirezaiyan.vokab.server.mcp

import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Same settings in every MCP test, so they share one Spring context. */
internal const val MCP_TEST_ISSUER = "http://localhost"
internal const val MCP_ENABLED = "spring.ai.mcp.server.enabled=true"
internal const val MCP_TEST_ISSUER_PROPERTY = "app.mcp.issuer=$MCP_TEST_ISSUER"
internal const val MCP_TEST_KEY_PROPERTY = "app.mcp.signing-key-path=build/tmp/mcp-test-signing-key.json"

/**
 * Issues an MCP access token as if [userId] had connected a client and granted [scopes], and records
 * the grant, so tool tests skip the browser flow ([McpOAuthFlowTest] covers that).
 */
internal class McpTestTokens(
    private val clients: RegisteredClientRepository,
    private val authorizations: OAuth2AuthorizationService,
    private val jwkSource: JWKSource<SecurityContext>,
) {
    fun accessToken(userId: Long, scopes: Set<String> = McpScopes.ALL): String {
        val client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("test_" + UUID.randomUUID())
            .clientName("Test client")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://client.example.com/callback")
            .scopes { it.addAll(McpScopes.ALL) }
            .build()
        clients.save(client)

        val now = Instant.now()
        val expiresAt = now.plus(Duration.ofHours(1))
        val claims = JwtClaimsSet.builder()
            .issuer(MCP_TEST_ISSUER)
            .subject(userId.toString())
            .audience(listOf("$MCP_TEST_ISSUER/mcp"))
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("scope", scopes.toList())
            .build()
        val jwt = NimbusJwtEncoder(jwkSource)
            .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))

        authorizations.save(
            OAuth2Authorization.withRegisteredClient(client)
                .principalName(userId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .token(OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.tokenValue, now, expiresAt, scopes))
                .build()
        )
        return jwt.tokenValue
    }
}
