package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.FirebaseIdTokenVerifier
import com.alirezaiyan.vokab.server.auth.UserAccessCache
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Who may connect an MCP client (sign-in) and which clients exist (open registration, RFC 7591). */
@Service
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpOAuthService(
    private val clients: RegisteredClientRepository,
    private val firebase: FirebaseIdTokenVerifier,
    private val userRepository: UserRepository,
    private val userAccessCache: UserAccessCache,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val secretEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    private val random = SecureRandom()

    sealed interface SignInResult {
        data class SignedIn(val userId: Long) : SignInResult
        data object InvalidToken : SignInResult
        data object NoAccount : SignInResult
    }

    /**
     * The existing, active account behind a Google (Firebase) ID token. Never creates one: an AI
     * client is no place to sign up, and onboarding happens in the app.
     */
    @Transactional(readOnly = true)
    fun signIn(idToken: String): SignInResult {
        val claims = firebase.verify(idToken) ?: return SignInResult.InvalidToken
        val userId = (
            userRepository.findByGoogleId(claims.uid).orElse(null)
                ?: claims.email?.let { userRepository.findByEmail(it).orElse(null) }
            )?.id
        return if (userId != null && userAccessCache.isAllowed(userId)) SignInResult.SignedIn(userId)
        else SignInResult.NoAccount
    }

    sealed interface RegistrationResult {
        data class Registered(val client: ClientRegistrationResponse) : RegistrationResult
        data class Rejected(val error: String, val description: String) : RegistrationResult
    }

    /** Registers a client. Grants nothing yet: the learner still signs in and consents per client. */
    @Transactional
    fun register(request: ClientRegistrationRequest): RegistrationResult {
        request.problem()?.let { (error, description) -> return RegistrationResult.Rejected(error, description) }

        val authMethod = request.tokenEndpointAuthMethod ?: CLIENT_SECRET_BASIC
        val publicClient = authMethod == NONE
        val secret = if (publicClient) null else randomToken()
        val now = Instant.now(clock)
        val client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("mcp_" + randomToken())
            .clientIdIssuedAt(now)
            .apply { secret?.let { clientSecret(secretEncoder.encode(it)) } }
            .clientName(request.clientName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENT_NAME)
            .clientAuthenticationMethod(ClientAuthenticationMethod(authMethod))
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUris { it.addAll(request.redirectUris) }
            .scopes { it.addAll(McpScopes.ALL) }
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(tokenSettings(publicClient))
            .build()
        clients.save(client)
        logger.info { "Registered MCP client ${client.clientId} (${client.clientName}, $authMethod)" }

        return RegistrationResult.Registered(
            ClientRegistrationResponse(
                clientId = client.clientId,
                clientIdIssuedAt = now.epochSecond,
                clientSecret = secret,
                clientSecretExpiresAt = secret?.let { 0L },
                clientName = client.clientName,
                redirectUris = request.redirectUris,
                grantTypes = GRANT_TYPES.toList(),
                responseTypes = listOf(CODE),
                tokenEndpointAuthMethod = authMethod,
                scope = McpScopes.ALL.joinToString(" "),
            )
        )
    }

    private fun tokenSettings(publicClient: Boolean): TokenSettings {
        val mcp = appProperties.mcp
        // SAS 1.x issues no refresh token to public clients, so theirs is the longer-lived access token
        val accessTokenTtl = if (publicClient) Duration.ofHours(mcp.publicAccessTokenHours)
        else Duration.ofMinutes(mcp.confidentialAccessTokenMinutes)
        return TokenSettings.builder()
            .authorizationCodeTimeToLive(Duration.ofMinutes(AUTHORIZATION_CODE_MINUTES))
            .accessTokenTimeToLive(accessTokenTtl)
            .refreshTokenTimeToLive(Duration.ofDays(mcp.refreshTokenDays))
            .reuseRefreshTokens(false)
            .build()
    }

    /** Error code and description per RFC 7591, or null when the request is acceptable. */
    private fun ClientRegistrationRequest.problem(): Pair<String, String>? = when {
        redirectUris.isEmpty() || redirectUris.size > MAX_REDIRECT_URIS ->
            "invalid_redirect_uri" to "Between 1 and $MAX_REDIRECT_URIS redirect_uris required"
        redirectUris.any { !isAllowedRedirect(it) } ->
            "invalid_redirect_uri" to "Redirect URIs must be https, or http on a loopback address"
        tokenEndpointAuthMethod != null && tokenEndpointAuthMethod !in AUTH_METHODS ->
            "invalid_client_metadata" to "token_endpoint_auth_method must be one of $AUTH_METHODS"
        grantTypes != null && !GRANT_TYPES.containsAll(grantTypes) ->
            "invalid_client_metadata" to "Only authorization_code and refresh_token grants are supported"
        responseTypes != null && responseTypes != listOf(CODE) ->
            "invalid_client_metadata" to "Only the code response type is supported"
        (clientName?.length ?: 0) > MAX_CLIENT_NAME ->
            "invalid_client_metadata" to "client_name is longer than $MAX_CLIENT_NAME characters"
        else -> null
    }

    private fun isAllowedRedirect(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull()
        val wellFormed = uri != null && uri.fragment == null && !uri.host.isNullOrEmpty() &&
            value.length <= MAX_REDIRECT_LENGTH
        return wellFormed && when (uri?.scheme) {
            "https" -> true
            "http" -> uri.host in LOOPBACK_HOSTS
            else -> false
        }
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    private companion object {
        const val NONE = "none"
        const val CODE = "code"
        const val CLIENT_SECRET_BASIC = "client_secret_basic"
        val AUTH_METHODS = setOf(NONE, CLIENT_SECRET_BASIC, "client_secret_post")
        val GRANT_TYPES = setOf("authorization_code", "refresh_token")
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
        const val DEFAULT_CLIENT_NAME = "MCP client"
        const val MAX_REDIRECT_URIS = 5
        const val MAX_REDIRECT_LENGTH = 500
        const val MAX_CLIENT_NAME = 100
        const val TOKEN_BYTES = 24
        const val AUTHORIZATION_CODE_MINUTES = 5L
    }
}
