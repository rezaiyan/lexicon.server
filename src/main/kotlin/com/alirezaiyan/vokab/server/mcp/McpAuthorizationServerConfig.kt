package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.UserAccessCache
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher

/**
 * OAuth 2.1 for MCP clients. We are both sides:
 * - authorization server (Spring Authorization Server): metadata, authorize + consent, token, jwks
 * - resource server: `/mcp` accepts only tokens we issued for it, still on record, for an active user
 *
 * Three filter chains, ahead of the app's stateless API chain:
 * 1. SAS endpoints, session-based so the browser stays signed in between login and consent
 * 2. our own pages: sign-in, open client registration, protected-resource metadata
 * 3. `/mcp` as a bearer-token resource server
 */
@Configuration
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpAuthorizationServerConfig(private val appProperties: AppProperties) {

    private val issuer get() = appProperties.mcp.issuer.trimEnd('/')
    private val signingKey: RSAKey by lazy { McpSigningKey.loadOrCreate(appProperties.mcp.signingKeyPath) }

    @Bean
    @Order(1)
    fun mcpAuthorizationServerChain(http: HttpSecurity): SecurityFilterChain {
        val authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer()
        http
            .securityMatcher(authorizationServer.endpointsMatcher)
            .with(authorizationServer) { server ->
                server.authorizationServerMetadataEndpoint { endpoint ->
                    endpoint.authorizationServerMetadataCustomizer { metadata ->
                        metadata.clientRegistrationEndpoint("$issuer$REGISTRATION_PATH")
                        metadata.scopes { it.addAll(McpScopes.ALL) }
                        // Public clients (PKCE, no secret) are supported; SAS just doesn't advertise it
                        metadata.tokenEndpointAuthenticationMethod(ClientAuthenticationMethod.NONE.value)
                    }
                }
            }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .csrf { it.ignoringRequestMatchers(authorizationServer.endpointsMatcher) }
            .exceptionHandling {
                it.defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint(LOGIN_PATH),
                    MediaTypeRequestMatcher(MediaType.TEXT_HTML),
                )
            }
        return http.build()
    }

    @Bean
    @Order(2)
    fun mcpOAuthPagesChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/oauth/**", PROTECTED_RESOURCE_METADATA_PATH, "$PROTECTED_RESOURCE_METADATA_PATH/**")
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            // Sign-in is a JSON POST from our own page, checked by Origin; registration is open by design
            .csrf { it.disable() }
        return http.build()
    }

    @Bean
    @Order(3)
    fun mcpResourceServerChain(
        http: HttpSecurity,
        authorizationService: OAuth2AuthorizationService,
        userAccessCache: UserAccessCache,
    ): SecurityFilterChain {
        val entryPoint = McpBearerEntryPoint("$issuer$PROTECTED_RESOURCE_METADATA_PATH$MCP_PATH")
        http
            .securityMatcher(MCP_PATH, "$MCP_PATH/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { it.decoder(mcpJwtDecoder(authorizationService, userAccessCache)) }
                resourceServer.authenticationEntryPoint(entryPoint)
            }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
        return http.build()
    }

    @Bean
    fun registeredClientRepository(jdbcTemplate: JdbcTemplate): RegisteredClientRepository =
        JdbcRegisteredClientRepository(jdbcTemplate)

    @Bean
    fun authorizationService(
        jdbcTemplate: JdbcTemplate,
        clients: RegisteredClientRepository,
    ): OAuth2AuthorizationService = JdbcOAuth2AuthorizationService(jdbcTemplate, clients)

    @Bean
    fun authorizationConsentService(
        jdbcTemplate: JdbcTemplate,
        clients: RegisteredClientRepository,
    ): OAuth2AuthorizationConsentService = JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients)

    @Bean
    fun mcpJwkSource(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(signingKey))

    @Bean
    fun authorizationServerSettings(): AuthorizationServerSettings =
        AuthorizationServerSettings.builder().issuer(issuer).build()

    /** Access tokens are for `/mcp` only: the audience the resource server insists on. */
    @Bean
    fun mcpTokenCustomizer(): OAuth2TokenCustomizer<JwtEncodingContext> = OAuth2TokenCustomizer { context ->
        if (context.tokenType == OAuth2TokenType.ACCESS_TOKEN) {
            context.claims.audience(listOf(mcpResource()))
        }
    }

    private fun mcpResource() = "$issuer$MCP_PATH"

    private fun mcpJwtDecoder(
        authorizationService: OAuth2AuthorizationService,
        userAccessCache: UserAccessCache,
    ): JwtDecoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build().apply {
        setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(issuer),
                validator("Token is not for $MCP_PATH") { mcpResource() in it.audience.orEmpty() },
                // Revoked grants (disconnected apps) fail at once, not when the token expires
                validator("Token was revoked") { jwt ->
                    authorizationService.findByToken(jwt.tokenValue, OAuth2TokenType.ACCESS_TOKEN)
                        ?.accessToken?.isActive == true
                },
                validator("Account is not active") { jwt ->
                    jwt.subject?.toLongOrNull()?.let(userAccessCache::isAllowed) == true
                },
            )
        )
    }

    private fun validator(description: String, check: (Jwt) -> Boolean) = OAuth2TokenValidator<Jwt> { jwt ->
        if (check(jwt)) OAuth2TokenValidatorResult.success()
        else OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null))
    }

    companion object {
        const val MCP_PATH = "/mcp"
        const val LOGIN_PATH = "/oauth/login"
        const val SESSION_PATH = "/oauth/session"
        const val REGISTRATION_PATH = "/oauth/register"
        const val PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"
    }
}

/**
 * 401 for `/mcp` that tells the client where to start OAuth (RFC 9728): `resource_metadata` names
 * the protected-resource document, which names our authorization server.
 */
private class McpBearerEntryPoint(private val resourceMetadataUrl: String) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val error = (exception as? OAuth2AuthenticationException)?.error
        val params = buildList {
            add("""resource_metadata="$resourceMetadataUrl"""")
            if (error != null) {
                add("""error="${error.errorCode}"""")
                error.description?.let { add("""error_description="${it.replace('"', '\'')}"""") }
            }
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer " + params.joinToString(", "))
        response.status = HttpServletResponse.SC_UNAUTHORIZED
    }
}
