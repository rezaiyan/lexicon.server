package com.alirezaiyan.vokab.server.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.function.Consumer

/**
 * SAS's checks on an authorization request, except for the redirect URI. SAS matches redirect URIs
 * exactly and refuses `localhost`, which locks out native clients: Claude Code registers
 * `http://localhost/callback` and listens on a random port. RFC 8252 allows any port on a loopback
 * redirect, so we match those with [McpRedirectUris.matches]. OpenID prompts don't apply (no openid scope).
 */
internal object McpAuthorizationRequestChecks {

    fun validator(): Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> =
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> { checkRedirectUri(it) }
            .andThen { checkGrantType(it) }
            .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR)
            .andThen { checkCodeChallenge(it) }

    /** A bad redirect URI is answered here, never sent to that URI. */
    private fun checkRedirectUri(context: OAuth2AuthorizationCodeRequestAuthenticationContext) {
        val request = context.getAuthentication<OAuth2AuthorizationCodeRequestAuthenticationToken>()
        val registered = context.registeredClient.redirectUris
        val requested = request.redirectUri
        val ok = if (requested.isNullOrBlank()) registered.size == 1
            else McpRedirectUris.matches(requested, registered)
        if (!ok) {
            val withoutRedirect = OAuth2AuthorizationCodeRequestAuthenticationToken(
                request.authorizationUri, request.clientId, request.principal as Authentication,
                null, request.state, request.scopes, request.additionalParameters,
            )
            throw OAuth2AuthorizationCodeRequestAuthenticationException(
                error(OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ParameterNames.REDIRECT_URI), withoutRedirect,
            )
        }
    }

    private fun checkGrantType(context: OAuth2AuthorizationCodeRequestAuthenticationContext) {
        if (AuthorizationGrantType.AUTHORIZATION_CODE !in context.registeredClient.authorizationGrantTypes) {
            fail(context, OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, OAuth2ParameterNames.CLIENT_ID)
        }
    }

    /** PKCE with S256 only; required when the client says so (all of ours do). */
    private fun checkCodeChallenge(context: OAuth2AuthorizationCodeRequestAuthenticationContext) {
        val parameters =
            context.getAuthentication<OAuth2AuthorizationCodeRequestAuthenticationToken>().additionalParameters
        val challenge = parameters[PkceParameterNames.CODE_CHALLENGE] as? String
        val method = parameters[PkceParameterNames.CODE_CHALLENGE_METHOD] as? String
        when {
            challenge.isNullOrBlank() && context.registeredClient.clientSettings.isRequireProofKey ->
                fail(context, OAuth2ErrorCodes.INVALID_REQUEST, PkceParameterNames.CODE_CHALLENGE)
            !challenge.isNullOrBlank() && method != S256 ->
                fail(context, OAuth2ErrorCodes.INVALID_REQUEST, PkceParameterNames.CODE_CHALLENGE_METHOD)
        }
    }

    /** Past the redirect check, errors go back to the client's redirect URI. */
    private fun fail(
        context: OAuth2AuthorizationCodeRequestAuthenticationContext,
        code: String,
        parameter: String,
    ): Nothing =
        throw OAuth2AuthorizationCodeRequestAuthenticationException(
            error(code, parameter), context.getAuthentication<OAuth2AuthorizationCodeRequestAuthenticationToken>(),
        )

    private fun error(code: String, parameter: String) = OAuth2Error(code, "OAuth 2.0 Parameter: $parameter", ERROR_URI)

    private const val S256 = "S256"
    private const val ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1"
}

/**
 * The `resource` parameter (RFC 8707). We protect exactly one resource, `/mcp`, and only issue
 * tokens for it; a client asking for anything else gets `invalid_target` instead of a token it
 * would then send somewhere it doesn't work.
 */
internal class McpResourceParameter(private val mcpResource: String) {

    fun accepts(value: Any?): Boolean = valuesOf(value).all { it.trimEnd('/') == mcpResource }

    /** For SAS's authorization request: fails the request back to the client's redirect URI. */
    fun authorizationRequestValidator() = Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> { context ->
        val request = context.getAuthentication<OAuth2AuthorizationCodeRequestAuthenticationToken>()
        if (!accepts(request.additionalParameters[RESOURCE])) {
            throw OAuth2AuthorizationCodeRequestAuthenticationException(invalidTarget(), request)
        }
    }

    /** For the token endpoint, which SAS doesn't validate `resource` on. */
    fun tokenRequestFilter(tokenPath: String) = object : OncePerRequestFilter() {
        override fun shouldNotFilter(request: HttpServletRequest) =
            request.method != "POST" || request.requestURI.removePrefix(request.contextPath) != tokenPath

        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            if (accepts(request.getParameterValues(RESOURCE))) {
                chain.doFilter(request, response)
                return
            }
            val error = invalidTarget()
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.contentType = "application/json"
            response.writer.write("""{"error":"${error.errorCode}","error_description":"${error.description}"}""")
        }
    }

    private fun invalidTarget() = OAuth2Error(INVALID_TARGET, "Tokens are only issued for $mcpResource", null)

    private companion object {
        const val RESOURCE = "resource"
        const val INVALID_TARGET = "invalid_target"

        fun valuesOf(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is String -> listOf(value)
            is Array<*> -> value.map { it.toString() }
            is Collection<*> -> value.map { it.toString() }
            else -> listOf(value.toString())
        }
    }
}

/**
 * Authorization responses carry `iss` (RFC 9207) so clients can tell which authorization server
 * answered (mix-up defence); SAS 1.x doesn't add it, so these replace its success and error redirects.
 */
internal class McpAuthorizationResponses(private val issuer: String) {
    private val redirect = DefaultRedirectStrategy()

    val success = AuthenticationSuccessHandler { request, response, authentication ->
        val result = authentication as OAuth2AuthorizationCodeRequestAuthenticationToken
        val params = buildMap {
            put("code", requireNotNull(result.authorizationCode).tokenValue)
            result.state?.let { put("state", it) }
        }
        redirect.sendRedirect(request, response, callback(requireNotNull(result.redirectUri), params))
    }

    val failure = AuthenticationFailureHandler { request, response, exception ->
        val error = (exception as? OAuth2AuthenticationException)?.error
        val authorizationRequest = (exception as? OAuth2AuthorizationCodeRequestAuthenticationException)
            ?.authorizationCodeRequestAuthentication
        val redirectUri = authorizationRequest?.redirectUri
        if (error == null || redirectUri.isNullOrBlank()) {
            // No trustworthy redirect URI: answer the browser directly, never redirect blindly
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, error?.toString() ?: exception.message)
            return@AuthenticationFailureHandler
        }
        val params = buildMap {
            put("error", error.errorCode)
            error.description?.let { put("error_description", it) }
            error.uri?.let { put("error_uri", it) }
            authorizationRequest.state?.let { put("state", it) }
        }
        redirect.sendRedirect(request, response, callback(redirectUri, params))
    }

    private fun callback(redirectUri: String, params: Map<String, String>): String {
        val builder = UriComponentsBuilder.fromUriString(redirectUri)
        (params + ("iss" to issuer)).forEach { (name, value) ->
            builder.queryParam(name, UriUtils.encode(value, Charsets.UTF_8))
        }
        return builder.build(true).toUriString()
    }
}

/**
 * Lets public clients (PKCE, no secret: Claude Code, desktop apps) use `refresh_token`. OAuth 2.1
 * allows it when refresh tokens rotate, which ours do (`reuseRefreshTokens(false)`); SAS 1.x only
 * authenticates public clients on the code exchange, by their `code_verifier`.
 */
internal class PublicClientRefreshAuthentication(private val clients: RegisteredClientRepository) {

    val converter = AuthenticationConverter { request ->
        val clientIds = request.getParameterValues(CLIENT_ID)
        val isPublicRefresh = request.getParameter(GRANT_TYPE) == AuthorizationGrantType.REFRESH_TOKEN.value &&
            request.getHeader(HttpHeaders.AUTHORIZATION) == null &&
            request.getParameter("client_secret") == null &&
            request.getParameter("client_assertion") == null &&
            clientIds != null
        when {
            !isPublicRefresh -> null
            clientIds.size != 1 || clientIds[0].isBlank() ->
                throw OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST)
            else -> OAuth2ClientAuthenticationToken(
                clientIds[0],
                ClientAuthenticationMethod.NONE,
                null,
                mapOf(GRANT_TYPE to AuthorizationGrantType.REFRESH_TOKEN.value),
            )
        }
    }

    val provider = object : AuthenticationProvider {
        override fun authenticate(authentication: Authentication): Authentication? {
            val request = authentication as OAuth2ClientAuthenticationToken
            val isPublicRefresh = request.clientAuthenticationMethod == ClientAuthenticationMethod.NONE &&
                request.additionalParameters[GRANT_TYPE] == AuthorizationGrantType.REFRESH_TOKEN.value
            if (!isPublicRefresh) return null
            val client = clients.findByClientId(request.principal.toString())
            val allowed = client != null &&
                ClientAuthenticationMethod.NONE in client.clientAuthenticationMethods &&
                AuthorizationGrantType.REFRESH_TOKEN in client.authorizationGrantTypes
            if (!allowed) throw OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)
            return OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null)
        }

        override fun supports(authentication: Class<*>) =
            OAuth2ClientAuthenticationToken::class.java.isAssignableFrom(authentication)
    }

    private companion object {
        const val CLIENT_ID = "client_id"
        const val GRANT_TYPE = "grant_type"
    }
}

/** SAS's refresh token generator minus its "no refresh tokens for public clients" rule. */
internal class McpRefreshTokenGenerator(private val clock: Clock) : OAuth2TokenGenerator<OAuth2RefreshToken> {
    private val keys = Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), TOKEN_BYTES)

    override fun generate(context: OAuth2TokenContext): OAuth2RefreshToken? {
        if (context.tokenType != OAuth2TokenType.REFRESH_TOKEN) return null
        val now = Instant.now(clock)
        val ttl = context.registeredClient.tokenSettings.refreshTokenTimeToLive
        return OAuth2RefreshToken(keys.generateKey(), now, now.plus(ttl))
    }

    private companion object {
        const val TOKEN_BYTES = 96
    }
}
