package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.AppProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Settings every MCP client gets, however it registered. */
internal class McpClientDefaults(private val appProperties: AppProperties) {

    fun clientSettings(): ClientSettings =
        ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build()

    fun tokenSettings(): TokenSettings = TokenSettings.builder()
        .authorizationCodeTimeToLive(Duration.ofMinutes(AUTHORIZATION_CODE_MINUTES))
        .accessTokenTimeToLive(Duration.ofMinutes(appProperties.mcp.accessTokenMinutes))
        .refreshTokenTimeToLive(Duration.ofDays(appProperties.mcp.refreshTokenDays))
        .reuseRefreshTokens(false)
        .build()

    private companion object {
        const val AUTHORIZATION_CODE_MINUTES = 5L
    }
}

/** Redirect URIs we send authorization codes to: https anywhere, plain http only on loopback. */
internal object McpRedirectUris {
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    const val MAX_LENGTH = 500

    fun isAllowed(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull()
        val wellFormed = uri != null && uri.fragment == null && !uri.host.isNullOrEmpty() && value.length <= MAX_LENGTH
        return wellFormed && when (uri?.scheme) {
            "https" -> true
            "http" -> uri.host in LOOPBACK_HOSTS
            else -> false
        }
    }

    /**
     * Whether [requested] is one of [registered]. A loopback http redirect matches on any port
     * (RFC 8252 section 7.3): native apps such as Claude Code register `http://localhost/callback`
     * and listen on whatever port is free.
     */
    fun matches(requested: String, registered: Collection<String>): Boolean {
        if (requested in registered) return true
        val wanted = loopback(requested) ?: return false
        return registered.any { candidate ->
            val known = loopback(candidate)
            known != null && known.host == wanted.host &&
                known.rawPath == wanted.rawPath && known.rawQuery == wanted.rawQuery
        }
    }

    private fun loopback(value: String): URI? = runCatching { URI(value) }.getOrNull()
        ?.takeIf { isAllowed(value) && it.scheme == "http" && it.host in LOOPBACK_HOSTS && it.rawUserInfo == null }
}

/**
 * Clients as SAS sees them. Registered ones (our open registration) come from the database as usual.
 * A client_id that is an https URL is a Client ID Metadata Document (the MCP spec's preferred way):
 * the client publishes its metadata at that URL, we fetch and check it, and keep a copy that is
 * refreshed after [refreshAfter]. Nothing about such a client can be registered by anyone else.
 */
internal class McpRegisteredClientRepository(
    private val stored: RegisteredClientRepository,
    private val fetcher: McpClientMetadataFetcher,
    private val defaults: McpClientDefaults,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val refreshAfter: Duration = Duration.ofHours(DEFAULT_REFRESH_HOURS),
    private val failureBackoff: Duration = Duration.ofMinutes(DEFAULT_FAILURE_BACKOFF_MINUTES),
) : RegisteredClientRepository {

    override fun save(registeredClient: RegisteredClient) = stored.save(registeredClient)

    override fun findById(id: String): RegisteredClient? = stored.findById(id)?.withCurrentScopes()

    override fun findByClientId(clientId: String): RegisteredClient? {
        val url = metadataDocumentUrl(clientId) ?: return stored.findByClientId(clientId)?.withCurrentScopes()
        val known = stored.findByClientId(clientId)?.withCurrentScopes()
        val fresh = known?.clientIdIssuedAt?.isAfter(Instant.now(clock).minus(refreshAfter)) == true
        if ((known != null && fresh) || recentlyFailed(clientId)) return known
        val fetched = fetcher.fetch(url)?.let { parse(clientId, it, known?.id) }
        if (fetched == null) {
            rememberFailure(clientId)
        } else {
            synchronized(failedFetches) { failedFetches.remove(clientId) }
            stored.save(fetched)
        }
        return fetched ?: known
    }

    /**
     * Client ids whose document couldn't be fetched or was refused, and when. SAS looks a client up
     * several times per request, and anyone can send any client_id, so a failure is remembered for
     * [failureBackoff] instead of fetching again on every lookup. Bounded: oldest entries go first.
     */
    private val failedFetches = object : LinkedHashMap<String, Instant>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>?) = size > MAX_FAILED_FETCHES
    }

    private fun recentlyFailed(clientId: String): Boolean = synchronized(failedFetches) {
        val failedAt = failedFetches[clientId] ?: return false
        Instant.now(clock).isBefore(failedAt.plus(failureBackoff))
    }

    private fun rememberFailure(clientId: String) = synchronized(failedFetches) {
        failedFetches[clientId] = Instant.now(clock)
    }

    /**
     * Scopes are ours to define, not the client's: a client registered before a scope existed may
     * still ask for it (the learner decides on the consent screen).
     */
    private fun RegisteredClient.withCurrentScopes(): RegisteredClient =
        if (scopes.containsAll(McpScopes.ALL)) this
        else RegisteredClient.from(this).scopes { it.addAll(McpScopes.ALL) }.build()

    /** The document's client, or null when it isn't one we can trust (logged, never thrown). */
    private fun parse(clientId: String, body: String, existingId: String?): RegisteredClient? {
        val document = runCatching { objectMapper.readTree(body) }.getOrNull()
        val problem = document?.let { problemWith(clientId, it) } ?: "not JSON"
        if (problem.isNotEmpty() || document == null) {
            logger.info { "Rejected client metadata document ${URI(clientId).host}: $problem" }
            return null
        }
        val redirects = document["redirect_uris"].values().map { it.asString() }
        val name = document["client_name"]?.asString()?.trim()?.take(MAX_CLIENT_NAME)?.takeIf { it.isNotEmpty() }
            ?: URI(clientId).host
        return RegisteredClient.withId(existingId ?: UUID.randomUUID().toString())
            .clientId(clientId)
            .clientIdIssuedAt(Instant.now(clock))
            .clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUris { it.addAll(redirects) }
            .scopes { it.addAll(McpScopes.ALL) }
            .clientSettings(defaults.clientSettings())
            .tokenSettings(defaults.tokenSettings())
            .build()
    }

    /** Empty when acceptable; otherwise why not. */
    private fun problemWith(clientId: String, document: JsonNode): String {
        val redirects = document["redirect_uris"]
        val authMethod = document["token_endpoint_auth_method"]?.asString() ?: NONE
        val grantTypes = document["grant_types"]?.values()?.map { it.asString() } ?: listOf(AUTHORIZATION_CODE)
        return when {
            document["client_id"]?.asString() != clientId -> "client_id does not match its URL"
            redirects == null || !redirects.isArray || redirects.isEmpty || redirects.size() > MAX_REDIRECT_URIS ->
                "needs 1-$MAX_REDIRECT_URIS redirect_uris"
            redirects.any { !McpRedirectUris.isAllowed(it.asString()) } -> "redirect_uris must be https or loopback"
            authMethod != NONE -> "only public clients (token_endpoint_auth_method none) are supported"
            document.has("client_secret") -> "metadata documents must not carry a client_secret"
            // Grants we don't offer are ignored (Claude also lists jwt-bearer); the client gets ours.
            AUTHORIZATION_CODE !in grantTypes -> "the authorization_code grant is required"
            else -> ""
        }
    }

    internal companion object {
        const val NONE = "none"
        const val AUTHORIZATION_CODE = "authorization_code"
        const val MAX_REDIRECT_URIS = 5
        const val MAX_CLIENT_NAME = 100
        const val MAX_URL_LENGTH = 1000
        const val DEFAULT_REFRESH_HOURS = 24L
        const val DEFAULT_FAILURE_BACKOFF_MINUTES = 10L
        const val MAX_FAILED_FETCHES = 1000
        const val HTTPS_PORT = 443

        /**
         * The URL when [clientId] is a valid metadata-document client id: https on the default port,
         * a host, a path other than "/", no fragment, user info, query, or dot segments.
         */
        fun metadataDocumentUrl(clientId: String): URI? {
            if (!clientId.startsWith("https://") || clientId.length > MAX_URL_LENGTH) return null
            val uri = runCatching { URI(clientId) }.getOrNull() ?: return null
            val path = uri.rawPath.orEmpty()
            val defaultPort = uri.port == -1 || uri.port == HTTPS_PORT
            val valid = !uri.host.isNullOrEmpty() && defaultPort &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.rawQuery == null && path.length > 1 &&
                path.split('/').none { it == "." || it == ".." }
            return if (valid) uri else null
        }

        /** The host a metadata-document client publishes from, or null for registered clients. */
        fun publisherOf(clientId: String): String? = metadataDocumentUrl(clientId)?.host
    }
}
