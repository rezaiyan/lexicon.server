package com.alirezaiyan.vokab.server.mcp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpHardeningTest {

    // ── delete limit ─────────────────────────────────────────────────────────

    @Test
    fun `deleting more than the hourly allowance is refused with a clear message`() {
        val limiter = McpDeleteLimiter()
        repeat(5) { limiter.acquire(USER, 100) }

        val error = assertThrows<IllegalStateException> { limiter.acquire(USER, 1) }
        assertTrue("Deletion limit reached" in error.message.orEmpty())
    }

    @Test
    fun `each learner has their own delete allowance`() {
        val limiter = McpDeleteLimiter()
        repeat(5) { limiter.acquire(USER, 100) }

        assertDoesNotThrow { limiter.acquire(OTHER_USER, 100) }
    }

    // ── signing key ──────────────────────────────────────────────────────────

    @Test
    fun `a new signing key file is readable by its owner only`(@TempDir dir: Path) {
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        val path = dir.resolve("keys/mcp.json")

        McpSigningKey.loadOrCreate(path.toString())

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    }

    @Test
    fun `an existing signing key is loaded, not replaced`(@TempDir dir: Path) {
        val path = dir.resolve("mcp.json").toString()
        val first = McpSigningKey.loadOrCreate(path)

        assertEquals(first.keyID, McpSigningKey.loadOrCreate(path).keyID)
    }

    @Test
    fun `creating a signing key leaves no temp files behind`(@TempDir dir: Path) {
        McpSigningKey.loadOrCreate(dir.resolve("mcp.json").toString())

        assertEquals(listOf("mcp.json"), Files.list(dir).map { it.fileName.toString() }.toList())
    }

    // ── consent page ─────────────────────────────────────────────────────────

    @Test
    fun `a dynamically registered client is labelled unverified and shows its full redirect`() {
        val client = McpConsentClients.describe(registered("mcp_abc", "Claude", "https://evil.example.net/cb?x=1"))

        val page = render(client)

        assertTrue("Unverified app" in page, page)
        assertTrue("https://evil.example.net/cb?x=1" in page, page)
    }

    @Test
    fun `a metadata document client redirecting to another site gets a warning`() {
        val client = McpConsentClients.describe(
            registered("https://apps.example.com/client.json", "Example", "https://other.example.org/cb")
        )

        assertTrue(client.redirectsElsewhere)
        assertTrue("sends you back to a different site" in render(client))
    }

    @Test
    fun `a metadata document client redirecting to its own site or a loopback is not warned about`() {
        val redirects = listOf("https://auth.apps.example.com/cb", "http://localhost/callback")

        assertFalse(McpConsentClients.redirectsAwayFrom("apps.example.com", redirects))
    }

    @Test
    fun `consent page escapes redirect URIs`() {
        val client = McpConsentPage.Client(
            name = "x", returnHosts = emptyList(), redirectUris = listOf("https://a.example.com/<b>"), unverified = true,
        )

        assertFalse("<b>" in render(client))
    }

    // ── refresh token reuse ──────────────────────────────────────────────────

    @Test
    fun `refresh tokens name their authorization`() {
        assertEquals("auth-1", McpRefreshTokenGenerator.authorizationIdOf("randomPart.auth-1"))
        assertNull(McpRefreshTokenGenerator.authorizationIdOf("legacyTokenWithoutFamily"))
    }

    @Test
    fun `presenting a superseded refresh token revokes the whole authorization`() {
        val authorizations = mockk<OAuth2AuthorizationService>(relaxed = true)
        val authorization = authorization(currentRefreshToken = "new.auth-1")
        every { authorizations.findByToken("old.auth-1", OAuth2TokenType.REFRESH_TOKEN) } returns null
        every { authorizations.findById("auth-1") } returns authorization

        McpRefreshTokenReuseDetection(authorizations, "/oauth2/token").revokeIfSuperseded("old.auth-1")

        verify { authorizations.remove(authorization) }
    }

    @Test
    fun `the current refresh token is left alone`() {
        val authorizations = mockk<OAuth2AuthorizationService>(relaxed = true)
        val authorization = authorization(currentRefreshToken = "new.auth-1")
        every { authorizations.findByToken("new.auth-1", OAuth2TokenType.REFRESH_TOKEN) } returns authorization

        McpRefreshTokenReuseDetection(authorizations, "/oauth2/token").revokeIfSuperseded("new.auth-1")

        verify(exactly = 0) { authorizations.remove(any()) }
    }

    private fun render(client: McpConsentPage.Client) =
        McpConsentPage.render(client, "client", "state", listOf(McpScopes.WORDS_READ), "/oauth2/authorize")

    private fun registered(clientId: String, name: String, redirect: String) = RegisteredClient.withId("id-$clientId")
        .clientId(clientId)
        .clientName(name)
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(redirect)
        .build()

    private fun authorization(currentRefreshToken: String): OAuth2Authorization {
        val client = registered("mcp_abc", "App", "https://app.example.com/cb")
        return OAuth2Authorization.withRegisteredClient(client)
            .id("auth-1")
            .principalName("42")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .refreshToken(OAuth2RefreshToken(currentRefreshToken, Instant.now()))
            .build()
    }

    private companion object {
        const val USER = 1L
        const val OTHER_USER = 2L
    }
}
