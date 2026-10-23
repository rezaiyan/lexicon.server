package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AppleConfig
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import io.jsonwebtoken.Jwts
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
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
        assertNull(verifier.verify(token(key = SigningKey(KID, otherKey))))
    }

    @Test
    fun `verify rejects a token with an unknown key id`() {
        assertNull(verifier.verify(token(key = SigningKey("unknown", keyPair))))
    }

    @Test
    fun `verify propagates an Apple key outage instead of rejecting the token`() {
        every { applePublicKeyService.getPublicKey(KID) } throws UpstreamServiceException("Apple signing keys unavailable")

        assertThrows<UpstreamServiceException> { verifier.verify(token()) }
    }

    @Test
    fun `verify rejects a token without subject`() {
        assertNull(verifier.verify(token(subject = null)))
    }

    @Test
    fun `verify rejects malformed input`() {
        assertNull(verifier.verify("not-a-jwt"))
    }

    @Test
    fun `verify accepts a token whose nonce is the SHA-256 hex of the raw nonce`() {
        val claims = verifier.verify(token(nonce = sha256Hex("raw-nonce")), rawNonce = "raw-nonce")

        assertEquals("sub-123", claims?.subject)
    }

    @Test
    fun `verify rejects a token whose nonce does not match the raw nonce`() {
        assertNull(verifier.verify(token(nonce = sha256Hex("other-nonce")), rawNonce = "raw-nonce"))
    }

    @Test
    fun `verify rejects a token without nonce when the client sent one`() {
        assertNull(verifier.verify(token(nonce = null), rawNonce = "raw-nonce"))
    }

    @Test
    fun `verify rejects a token carrying the raw nonce instead of its hash`() {
        assertNull(verifier.verify(token(nonce = "raw-nonce"), rawNonce = "raw-nonce"))
    }

    @Test
    fun `verify skips the nonce check when the client sent none`() {
        assertEquals("sub-123", verifier.verify(token(nonce = sha256Hex("anything")))?.subject)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun token(
        subject: String? = "sub-123",
        email: String? = "user@example.com",
        nonce: String? = null,
        audience: String = BUNDLE_ID,
        issuer: String = "https://appleid.apple.com",
        expiresAt: Instant = Instant.now().plusSeconds(600),
        key: SigningKey = SigningKey(KID, keyPair),
    ): String = Jwts.builder()
        .header().keyId(key.kid).and()
        .issuer(issuer)
        .audience().add(audience).and()
        .apply { subject?.let { subject(it) } }
        .apply { email?.let { claim("email", it) } }
        .apply { nonce?.let { claim("nonce", it) } }
        .issuedAt(Date())
        .expiration(Date.from(expiresAt))
        .signWith(key.keyPair.private, Jwts.SIG.RS256)
        .compact()

    /** The key a test token is signed with, and the `kid` its header names. */
    private data class SigningKey(val kid: String, val keyPair: KeyPair)

    private companion object {
        const val KID = "test-kid"
        const val BUNDLE_ID = "com.alirezaiyan.vokab"
    }
}
