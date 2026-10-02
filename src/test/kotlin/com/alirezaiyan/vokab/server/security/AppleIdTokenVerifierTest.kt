package com.alirezaiyan.vokab.server.security

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.AppleConfig
import com.alirezaiyan.vokab.server.service.ApplePublicKeyService
import io.jsonwebtoken.Jwts
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Date

class AppleIdTokenVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private lateinit var applePublicKeyService: ApplePublicKeyService
    private lateinit var verifier: AppleIdTokenVerifier

    @BeforeEach
    fun setUp() {
        applePublicKeyService = mockk()
        every { applePublicKeyService.getPublicKey(KID) } returns keyPair.public
        every { applePublicKeyService.getPublicKey(neq(KID)) } returns null
        verifier = AppleIdTokenVerifier(
            applePublicKeyService,
            AppProperties(apple = AppleConfig(clientIds = "com.other.app, $BUNDLE_ID")),
        )
    }

    @Test
    fun `verify returns subject and email for a valid token`() {
        val claims = verifier.verify(token())

        assertEquals("sub-123", claims?.subject)
        assertEquals("user@example.com", claims?.email)
    }

    @Test
    fun `verify returns null email when token has no email`() {
        assertNull(verifier.verify(token(email = null))?.email)
    }

    @Test
    fun `verify rejects a token issued for another app`() {
        assertNull(verifier.verify(token(audience = "com.attacker.app")))
    }

    @Test
    fun `verify rejects a token from another issuer`() {
        assertNull(verifier.verify(token(issuer = "https://evil.example.com")))
    }

    @Test
    fun `verify rejects an expired token`() {
        assertNull(verifier.verify(token(expiresAt = Instant.now().minusSeconds(60))))
    }

    @Test
    fun `verify rejects a token signed with a different key`() {
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertNull(verifier.verify(token(signingKey = otherKey)))
    }

    @Test
    fun `verify rejects a token with an unknown key id`() {
        assertNull(verifier.verify(token(kid = "unknown")))
    }

    @Test
    fun `verify rejects a token without subject`() {
        assertNull(verifier.verify(token(subject = null)))
    }

    @Test
    fun `verify rejects malformed input`() {
        assertNull(verifier.verify("not-a-jwt"))
    }

    private fun token(
        subject: String? = "sub-123",
        email: String? = "user@example.com",
        audience: String = BUNDLE_ID,
        issuer: String = "https://appleid.apple.com",
        expiresAt: Instant = Instant.now().plusSeconds(600),
        kid: String = KID,
        signingKey: KeyPair = keyPair,
    ): String = Jwts.builder()
        .header().keyId(kid).and()
        .issuer(issuer)
        .audience().add(audience).and()
        .apply { subject?.let { subject(it) } }
        .apply { email?.let { claim("email", it) } }
        .issuedAt(Date())
        .expiration(Date.from(expiresAt))
        .signWith(signingKey.private, Jwts.SIG.RS256)
        .compact()

    private companion object {
        const val KID = "test-kid"
        const val BUNDLE_ID = "com.alirezaiyan.vokab"
    }
}
