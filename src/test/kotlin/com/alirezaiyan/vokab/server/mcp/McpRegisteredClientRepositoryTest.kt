package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.AppProperties
import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.net.InetAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRegisteredClientRepositoryTest {

    private val objectMapper = ObjectMapper()
    private var now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val documents = mutableMapOf<URI, String>()
    private var fetches = 0
    private val repository = McpRegisteredClientRepository(
        stored = InMemoryRegisteredClientRepository(placeholder()),
        fetcher = { url -> fetches++; documents[url] },
        defaults = McpClientDefaults(AppProperties()),
        objectMapper = objectMapper,
        clock = clock,
    )

    @Test
    fun `only clean https URLs with a path count as metadata document client ids`() {
        assertNotNull(McpRegisteredClientRepository.metadataDocumentUrl(CLIENT))
        listOf(
            "http://apps.example.com/client.json",
            "https://apps.example.com",
            "https://apps.example.com/",
            "https://apps.example.com/client.json?x=1",
            "https://apps.example.com/client.json#frag",
            "https://user@apps.example.com/client.json",
            "https://apps.example.com/a/../client.json",
            "https://apps.example.com:8443/client.json",
            "https://apps.example.com:22/client.json",
            "mcp_abc123",
        ).forEach { assertNull(McpRegisteredClientRepository.metadataDocumentUrl(it), it) }
    }

    @Test
    fun `loopback redirects match on any port, everything else exactly`() {
        val registered = listOf("http://localhost/callback", "http://127.0.0.1/callback", "https://claude.ai/api/mcp/auth_callback")

        listOf(
            "http://localhost:57499/callback",
            "http://127.0.0.1:8123/callback",
            "http://localhost/callback",
            "https://claude.ai/api/mcp/auth_callback",
        ).forEach { assertTrue(McpRedirectUris.matches(it, registered), it) }
        listOf(
            "http://localhost:57499/other",
            "http://localhost:57499/callback?x=1",
            "http://[::1]:57499/callback",
            "https://localhost:57499/callback",
            "https://claude.ai:8443/api/mcp/auth_callback",
            "http://evil.example.com:57499/callback",
            "http://user@localhost:57499/callback",
        ).forEach { assertFalse(McpRedirectUris.matches(it, registered), it) }
    }

    @Test
    fun `a valid document becomes a public client with refresh and PKCE`() {
        documents[URI(CLIENT)] = document()

        val client = requireNotNull(repository.findByClientId(CLIENT))

        assertEquals("Metadata client", client.clientName)
        assertTrue(AuthorizationGrantType.REFRESH_TOKEN in client.authorizationGrantTypes)
        assertTrue(client.clientSettings.isRequireProofKey)
    }

    @Test
    fun `grant types we don't offer are ignored rather than refused`() {
        // Claude's real document (2026-10-15) also lists the JWT bearer grant.
        documents[URI(CLIENT)] = document(
            extra = mapOf(
                "grant_types" to listOf(
                    "authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:jwt-bearer",
                ),
            ),
        )

        val client = requireNotNull(repository.findByClientId(CLIENT))

        assertEquals(
            setOf(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN),
            client.authorizationGrantTypes,
        )
    }

    @Test
    fun `documents that don't hold up are refused`() {
        listOf(
            document(extra = mapOf("grant_types" to listOf("client_credentials"))),
            document(clientId = "https://other.example.com/client.json"),
            document(redirects = listOf("http://evil.example.com/cb")),
            document(redirects = emptyList()),
            document(extra = mapOf("token_endpoint_auth_method" to "client_secret_basic")),
            document(extra = mapOf("client_secret" to "s3cret")),
            "not json",
        ).forEach { body ->
            documents[URI(CLIENT)] = body
            assertNull(repository.findByClientId(CLIENT), body)
        }
    }

    @Test
    fun `a known client is reused until it is a day old, then fetched again`() {
        documents[URI(CLIENT)] = document()
        val first = requireNotNull(repository.findByClientId(CLIENT))

        now = now.plus(Duration.ofHours(23))
        repository.findByClientId(CLIENT)
        assertEquals(1, fetches)

        now = now.plus(Duration.ofHours(2))
        documents[URI(CLIENT)] = document(name = "Renamed")
        val refreshed = requireNotNull(repository.findByClientId(CLIENT))
        assertEquals(2, fetches)
        assertEquals("Renamed", refreshed.clientName)
        assertEquals(first.id, refreshed.id, "same client row, so existing grants stay attached")
    }

    @Test
    fun `when a refresh fails the known client keeps working`() {
        documents[URI(CLIENT)] = document()
        repository.findByClientId(CLIENT)
        now = now.plus(Duration.ofDays(2))
        documents.clear()

        assertEquals("Metadata client", repository.findByClientId(CLIENT)?.clientName)
    }

    @Test
    fun `the default https port may be spelled out`() {
        assertNotNull(McpRegisteredClientRepository.metadataDocumentUrl("https://apps.example.com:443/client.json"))
    }

    @Test
    fun `a failed fetch is not retried for ten minutes`() {
        repeat(5) { assertNull(repository.findByClientId(CLIENT)) }
        assertEquals(1, fetches)

        now = now.plus(Duration.ofMinutes(11))
        documents[URI(CLIENT)] = document()
        assertNotNull(repository.findByClientId(CLIENT))
        assertEquals(2, fetches)
    }

    @Test
    fun `a failed refresh of a known client is not retried on every lookup`() {
        documents[URI(CLIENT)] = document()
        repository.findByClientId(CLIENT)
        now = now.plus(Duration.ofDays(2))
        documents.clear()

        repeat(3) { repository.findByClientId(CLIENT) }

        assertEquals(2, fetches)
    }

    @Test
    fun `IPv6 transition prefixes that embed IPv4 addresses are not public`() {
        listOf("64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b:1::a00:1", "2002:7f00:1::1", "2001:0:4136:e378::1").forEach {
            assertFalse(HttpMcpClientMetadataFetcher.isPublic(InetAddress.getByName(it)), it)
        }
        // Teredo is 2001::/32 only; the rest of 2001::/16 is ordinary global unicast
        assertTrue(HttpMcpClientMetadataFetcher.isPublic(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test
    fun `the fetcher refuses a non-default port without connecting`() {
        assertNull(HttpMcpClientMetadataFetcher().fetch(URI("https://apps.example.com:8443/client.json")))
    }

    @Test
    fun `metadata fetches never reach private, loopback or link-local addresses`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1").forEach {
            assertFalse(HttpMcpClientMetadataFetcher.isPublic(InetAddress.getByName(it)), it)
        }
        listOf("93.184.216.34", "2606:4700:4700::1111").forEach {
            assertTrue(HttpMcpClientMetadataFetcher.isPublic(InetAddress.getByName(it)), it)
        }
    }

    @Test
    fun `the fetcher refuses plain http without connecting`() {
        assertNull(HttpMcpClientMetadataFetcher().fetch(URI("http://apps.example.com/client.json")))
    }

    private fun document(
        clientId: String = CLIENT,
        name: String = "Metadata client",
        redirects: List<String> = listOf("https://apps.example.com/callback"),
        extra: Map<String, Any> = emptyMap(),
    ): String = objectMapper.writeValueAsString(
        mapOf("client_id" to clientId, "client_name" to name, "redirect_uris" to redirects) + extra
    )

    /** InMemoryRegisteredClientRepository needs at least one client to start with. */
    private fun placeholder() = RegisteredClient.withId("placeholder")
        .clientId("placeholder")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://placeholder.example.com/cb")
        .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
        .build()

    private companion object {
        const val CLIENT = "https://apps.example.com/lexicon/client.json"
    }
}
