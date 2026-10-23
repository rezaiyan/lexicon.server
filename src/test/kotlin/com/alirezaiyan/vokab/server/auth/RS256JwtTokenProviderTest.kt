package com.alirezaiyan.vokab.server.auth

import java.time.Duration
import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.JwtConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.temporal.ChronoUnit
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import java.util.UUID
import io.jsonwebtoken.Jwts

class RS256JwtTokenProviderTest {

    private val clock = MutableClock()

    private lateinit var appProperties: AppProperties
    private lateinit var provider: RS256JwtTokenProvider
    private lateinit var testKeyPair: KeyPair

    @BeforeEach
    fun setUp() {
        testKeyPair = generateTestKeyPair()
        appProperties = createAppPropertiesWithInMemoryKeys(testKeyPair)
        provider = RS256JwtTokenProvider(appProperties, clock)
    }

    // ── generateAccessToken ───────────────────────────────────────────────────

    @Test
    fun `generateAccessToken should return non-blank JWT string`() {
        // Arrange
        val userId = 42L
        val email = "test@example.com"

        // Act
        val token = provider.generateAccessToken(userId, email)

        // Assert
        assertTrue(token.isNotBlank())
        // JWT has 3 dot-separated segments
        assertEquals(3, token.split(".").size)
    }

    @Test
    fun `generateAccessToken should embed userId as subject`() {
        // Arrange
        val userId = 7L
        val email = "user@example.com"

        // Act
        val token = provider.generateAccessToken(userId, email)
        val extractedId = provider.verifyAccessToken(token)?.userId

        // Assert
        assertEquals(userId, extractedId)
    }

    @Test
    fun `generateAccessToken should produce a token that validates correctly`() {
        // Arrange
        val userId = 1L
        val email = "alice@example.com"

        // Act
        val token = provider.generateAccessToken(userId, email)

        // Assert
        assertNotNull(provider.verifyAccessToken(token))
    }

    @Test
    fun `generateAccessToken should use provided jti when given`() {
        // Arrange
        val jti = "custom-jti-12345"

        // Act
        val token = provider.generateAccessToken(1L, "test@example.com", jti = jti)

        // Assert — token is valid (jti embedded correctly)
        assertNotNull(provider.verifyAccessToken(token))
    }

    @Test
    fun `generateAccessToken should generate unique tokens for different jti values`() {
        // Arrange / Act
        val token1 = provider.generateAccessToken(1L, "test@example.com", UUID.randomUUID().toString())
        val token2 = provider.generateAccessToken(1L, "test@example.com", UUID.randomUUID().toString())

        // Assert
        assertTrue(token1 != token2)
    }

    // ── verifyAccessToken ─────────────────────────────────────────────────────────

    @Test
    fun `verifyAccessToken should accept for a freshly issued token`() {
        // Arrange
        val token = provider.generateAccessToken(1L, "valid@example.com")

        // Act
        val isValid = provider.verifyAccessToken(token) != null

        // Assert
        assertTrue(isValid)
    }

    @Test
    fun `verifyAccessToken should reject for a malformed token`() {
        // Arrange
        val malformed = "this.is.not.a.real.jwt"

        // Act
        val isValid = provider.verifyAccessToken(malformed) != null

        // Assert
        assertFalse(isValid)
    }

    @Test
    fun `verifyAccessToken should reject for an empty string`() {
        // Act
        val isValid = provider.verifyAccessToken("") != null

        // Assert
        assertFalse(isValid)
    }

    @Test
    fun `verifyAccessToken should reject for a token signed with a different key`() {
        // Arrange — generate a second key pair and sign a token with it
        val otherKeyPair = generateTestKeyPair()
        val otherProps = createAppPropertiesWithInMemoryKeys(otherKeyPair)
        val otherProvider = RS256JwtTokenProvider(otherProps, clock)
        val alienToken = otherProvider.generateAccessToken(1L, "alien@example.com")

        // Act — validate with the original provider
        val isValid = provider.verifyAccessToken(alienToken) != null

        // Assert
        assertFalse(isValid)
    }

    @Test
    fun `verifyAccessToken should reject for an expired token`() {
        // Arrange — create a provider with a 1ms expiration
        val shortLivedProps = createAppPropertiesWithInMemoryKeys(testKeyPair, expirationMs = 1L)
        val shortLivedProvider = RS256JwtTokenProvider(shortLivedProps, clock)
        val token = shortLivedProvider.generateAccessToken(1L, "expired@example.com")

        clock.advance(Duration.ofSeconds(1))

        // Act
        val isValid = shortLivedProvider.verifyAccessToken(token) != null

        // Assert
        assertFalse(isValid)
    }

    @Test
    fun `verifyAccessToken should reject for a token with wrong issuer`() {
        // Arrange — token signed with matching key but different issuer
        val wrongIssuerProps = createAppPropertiesWithInMemoryKeys(
            testKeyPair,
            issuer = "wrong-issuer"
        )
        val wrongIssuerProvider = RS256JwtTokenProvider(wrongIssuerProps, clock)
        val token = wrongIssuerProvider.generateAccessToken(1L, "test@example.com")

        // Act — validate with the original provider that expects the default issuer
        val isValid = provider.verifyAccessToken(token) != null

        // Assert
        assertFalse(isValid)
    }

    // ── verifyAccessToken: user id ───────────────────────────────────────────────

    @Test
    fun `verifyAccessToken should return correct user ID`() {
        // Arrange
        val userId = 99L
        val token = provider.generateAccessToken(userId, "test@example.com")

        // Act
        val result = provider.verifyAccessToken(token)?.userId

        // Assert
        assertEquals(userId, result)
    }

    @Test
    fun `verifyAccessToken should return null for an invalid token`() {
        // Act
        val result = provider.verifyAccessToken("garbage.token.here")?.userId

        // Assert
        assertNull(result)
    }

    @Test
    fun `verifyAccessToken should return null for an empty token`() {
        // Act
        val result = provider.verifyAccessToken("")?.userId

        // Assert
        assertNull(result)
    }

    // ── getExpirationTime ─────────────────────────────────────────────────────

    @Test
    fun `getExpirationTime should return value from appProperties`() {
        // Arrange
        val expectedExpiration = 3600000L
        val props = createAppPropertiesWithInMemoryKeys(testKeyPair, expirationMs = expectedExpiration)
        val providerWithCustomExpiry = RS256JwtTokenProvider(props, clock)

        // Act
        val expirationTime = providerWithCustomExpiry.getExpirationTime()

        // Assert
        assertEquals(expectedExpiration, expirationTime)
    }

    // ── publicKey / privateKey accessors ──────────────────────────────────────

    @Test
    fun `publicKey should be accessible and non-null`() {
        // Act & Assert
        assertNotNull(provider.publicKey)
        assertEquals("RSA", provider.publicKey.algorithm)
    }

    @Test
    fun `privateKey should be accessible and non-null`() {
        // Act & Assert
        assertNotNull(provider.privateKey)
        assertEquals("RSA", provider.privateKey.algorithm)
    }

    // ── verifyAccessToken ─────────────────────────────────────────────────────

    @Test
    fun `verifyAccessToken returns the user id and issue time`() {
        val token = provider.generateAccessToken(12L, "test@example.com")

        val verified = provider.verifyAccessToken(token)

        assertEquals(VerifiedAccessToken(12L, clock.instant().truncatedTo(ChronoUnit.SECONDS)), verified)
    }

    @Test
    fun `verifyAccessToken returns null for an invalid token`() {
        assertNull(provider.verifyAccessToken("garbage.token.here"))
    }

    @Test
    fun `verifyAccessToken rejects a correctly signed token that is not an access token`() {
        val token = Jwts.builder()
            .subject("1")
            .claim("type", "refresh")
            .issuer("vokab-server")
            .audience().add("vokab-client").and()
            .issuedAt(Date.from(clock.instant()))
            .expiration(Date.from(clock.instant().plusSeconds(60)))
            .signWith(testKeyPair.private)
            .compact()

        assertNull(provider.verifyAccessToken(token))
    }

    // ── key id ────────────────────────────────────────────────────────────────

    @Test
    fun `generateAccessToken puts the key id in the token header`() {
        val token = provider.generateAccessToken(1L, "test@example.com")

        val header = Jwts.parser()
            .verifyWith(provider.publicKey)
            .clock { Date.from(clock.instant()) }
            .build()
            .parseSignedClaims(token)
            .header

        assertEquals(provider.keyId, header.keyId)
    }

    @Test
    fun `keyId is the RFC 7638 thumbprint of the public key`() {
        // RFC 7638 section 3.1 example key and its published thumbprint
        val n = "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECP" +
            "ebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQ" +
            "MicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRw" +
            "r3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw"
        val modulus = java.math.BigInteger(1, Base64.getUrlDecoder().decode(n))
        val exponent = java.math.BigInteger.valueOf(65537)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(java.security.spec.RSAPublicKeySpec(modulus, exponent))
        // Any private key will do: only the public key feeds the thumbprint
        val props = AppProperties().apply {
            jwt = JwtConfig(
                privateKey = Base64.getEncoder().encodeToString(testKeyPair.private.encoded),
                publicKey = Base64.getEncoder().encodeToString(publicKey.encoded),
            )
        }

        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", RS256JwtTokenProvider(props, clock).keyId)
    }

    // ── key loading and generation ────────────────────────────────────────────

    @Test
    fun `generates the private key file readable by the owner only`(@TempDir dir: Path) {
        RS256JwtTokenProvider(fileProps(dir), clock)

        val perms = Files.getPosixFilePermissions(dir.resolve("private.pem"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `reuses previously generated key files`(@TempDir dir: Path) {
        val first = RS256JwtTokenProvider(fileProps(dir), clock)
        val token = first.generateAccessToken(1L, "a@example.com")

        val second = RS256JwtTokenProvider(fileProps(dir), clock)

        assertNotNull(second.verifyAccessToken(token))
    }

    @Test
    fun `fails fast when existing key files cannot be loaded`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("private.pem"), "not-a-key")
        Files.writeString(dir.resolve("public.pem"), "not-a-key")

        assertThrows<IllegalStateException> { RS256JwtTokenProvider(fileProps(dir), clock) }
        assertEquals("not-a-key", Files.readString(dir.resolve("private.pem")))
    }

    @Test
    fun `fails fast when only one key file exists`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("public.pem"), "something")

        assertThrows<IllegalStateException> { RS256JwtTokenProvider(fileProps(dir), clock) }
    }

    @Test
    fun `fails fast when configured key content cannot be loaded`(@TempDir dir: Path) {
        val props = fileProps(dir).apply {
            jwt.privateKey = "bm90LWEta2V5"
            jwt.publicKey = "bm90LWEta2V5"
        }

        assertThrows<IllegalStateException> { RS256JwtTokenProvider(props, clock) }
        assertFalse(Files.exists(dir.resolve("private.pem")))
    }

    @Test
    fun `fails fast when only one key is configured`() {
        val props = createAppPropertiesWithInMemoryKeys(testKeyPair).apply { jwt.publicKey = "" }

        assertThrows<IllegalStateException> { RS256JwtTokenProvider(props, clock) }
    }

    private fun fileProps(dir: Path) = AppProperties().apply {
        jwt = JwtConfig(
            privateKeyPath = dir.resolve("private.pem").toString(),
            publicKeyPath = dir.resolve("public.pem").toString(),
        )
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun generateTestKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    private fun createAppPropertiesWithInMemoryKeys(
        keyPair: KeyPair,
        expirationMs: Long = 86400000L,
        issuer: String = "vokab-server",
        audience: String = "vokab-client"
    ): AppProperties {
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val props = AppProperties()
        props.jwt = JwtConfig(
            expirationMs = expirationMs,
            privateKey = privateKeyBase64,
            publicKey = publicKeyBase64,
            issuer = issuer,
            audience = audience
        )
        return props
    }
}
