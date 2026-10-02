package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.security.AppleIdTokenVerifier
import com.alirezaiyan.vokab.server.domain.entity.NotificationCategory
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class AppleNotificationServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var applePublicKeyService: ApplePublicKeyService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var authService: AuthService

    private lateinit var appleNotificationService: AppleNotificationService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        applePublicKeyService = mockk()
        objectMapper = jacksonObjectMapper()
        pushNotificationService = mockk()
        authService = mockk()

        appleNotificationService = AppleNotificationService(
            userRepository,
            AppleIdTokenVerifier(applePublicKeyService, AppProperties()),
            objectMapper,
            pushNotificationService,
            authService
        )
    }

    // ── processNotification: invalid JWT format ────────────────────────────────

    @Test
    fun `should return false when JWT has fewer than three parts`() {
        val result = appleNotificationService.processNotification("not.a.valid.jwt.with.too.many.dots")

        assertFalse(result)
    }

    @Test
    fun `should return false when JWT has only one part`() {
        val result = appleNotificationService.processNotification("onlyonepart")

        assertFalse(result)
    }

    @Test
    fun `should return false when JWT header is not valid Base64`() {
        val result = appleNotificationService.processNotification("!!!.payload.signature")

        assertFalse(result)
    }

    @Test
    fun `should return false when JWT header is valid Base64 but not JSON`() {
        // Base64URL of "not-json"
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("not-json".toByteArray())
        val result = appleNotificationService.processNotification("$header.payload.signature")

        assertFalse(result)
    }

    @Test
    fun `should return false when JWT header has no kid field`() {
        val headerJson = """{"alg":"RS256"}"""
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.toByteArray())
        val result = appleNotificationService.processNotification("$header.payload.signature")

        assertFalse(result)
    }

    @Test
    fun `should return false when public key for kid cannot be found`() {
        val headerJson = """{"kid":"test-key-id","alg":"RS256"}"""
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.toByteArray())
        every { applePublicKeyService.getPublicKey("test-key-id") } returns null

        val result = appleNotificationService.processNotification("$header.payload.signature")

        assertFalse(result)
    }

    // ── processNotification: unknown Apple user ────────────────────────────────

    @Test
    fun `should return true when Apple user is not found in database`() {
        val (token, rsaKey) = buildSignedJwt(appleUserId = "unknown-apple-id", eventType = "email-disabled")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey

        every { userRepository.findByAppleId("unknown-apple-id") } returns Optional.empty()

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    // ── processNotification: EMAIL_DISABLED ────────────────────────────────────

    @Test
    fun `should save user with updated timestamp on EMAIL_DISABLED event`() {
        val user = createUser(appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "email-disabled")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `should return true after successfully processing EMAIL_DISABLED event`() {
        val user = createUser(appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "email-disabled")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
    }

    // ── processNotification: EMAIL_ENABLED ────────────────────────────────────

    @Test
    fun `should update user email on EMAIL_ENABLED event when email is provided`() {
        val user = createUser(appleId = "apple-user-1", email = "old@example.com")
        val newEmail = "new@relay.appleid.com"
        val (token, rsaKey) = buildSignedJwt(
            appleUserId = "apple-user-1",
            eventType = "email-enabled",
            eventExtra = """"email-enabled":{"email":"$newEmail","is_private_email":false}"""
        )
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)

        val savedSlot = slot<User>()
        every { userRepository.save(capture(savedSlot)) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 1) { userRepository.save(match { it.email == newEmail }) }
    }

    @Test
    fun `should return true after successfully processing EMAIL_ENABLED event`() {
        val user = createUser(appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "email-enabled")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
    }

    // ── processNotification: CONSENT_REVOKED ──────────────────────────────────

    @Test
    fun `should deactivate user on CONSENT_REVOKED event`() {
        val user = createUser(appleId = "apple-user-1", active = true)
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "consent-revoked")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { userRepository.save(any()) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 1) { userRepository.save(match { !it.active }) }
    }

    @Test
    fun `should send push notification before deactivating account on CONSENT_REVOKED`() {
        val user = createUser(id = 42L, appleId = "apple-user-1", active = true)
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "consent-revoked")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } returns listOf(
            NotificationResponse(success = true, messageId = "msg-123")
        )
        every { userRepository.save(any()) } answers { firstArg() }

        appleNotificationService.processNotification(token)

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 42L,
                title = any(),
                body = any(),
                data = any(),
                category = NotificationCategory.SYSTEM
            )
        }
    }

    @Test
    fun `should still deactivate user when push notification throws on CONSENT_REVOKED`() {
        val user = createUser(appleId = "apple-user-1", active = true)
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "consent-revoked")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } throws RuntimeException("FCM unavailable")
        every { userRepository.save(any()) } answers { firstArg() }

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 1) { userRepository.save(match { !it.active }) }
    }

    // ── processNotification: ACCOUNT_DELETE ───────────────────────────────────

    @Test
    fun `should call deleteAccount on ACCOUNT_DELETE event`() {
        val user = createUser(id = 5L, appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "account-delete")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { authService.deleteAccount(5L) } just Runs

        val result = appleNotificationService.processNotification(token)

        assertTrue(result)
        verify(exactly = 1) { authService.deleteAccount(5L) }
    }

    @Test
    fun `should return false when deleteAccount throws on ACCOUNT_DELETE event`() {
        val user = createUser(id = 5L, appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "account-delete")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)
        every { authService.deleteAccount(5L) } throws RuntimeException("deletion failed")

        val result = appleNotificationService.processNotification(token)

        assertFalse(result)
    }

    // ── processNotification: JWT signature verification failure ───────────────

    @Test
    fun `should return false when JWT signature is verified with wrong public key`() {
        // Sign with key pair A but tell the service to verify with key pair B's public key
        val keyPairA = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val keyPairB = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val eventsJson = buildEventsJson("apple-user-1", "email-disabled", null)
        val tokenSignedByA = io.jsonwebtoken.Jwts.builder()
            .issuer("https://appleid.apple.com")
            .audience().add("com.alirezaiyan.vokab").and()
            .claim("events", objectMapper.readValue(eventsJson, Map::class.java))
            .header().keyId("test-key-id").and()
            .signWith(keyPairA.private as java.security.interfaces.RSAPrivateKey, io.jsonwebtoken.Jwts.SIG.RS256)
            .compact()

        // Return B's public key — signature verification must fail
        every { applePublicKeyService.getPublicKey("test-key-id") } returns keyPairB.public

        val result = appleNotificationService.processNotification(tokenSignedByA)

        assertFalse(result)
    }

    @Test
    fun `should ignore a notification addressed to another app`() {
        val user = createUser(id = 6L, appleId = "apple-user-1")
        val (token, rsaKey) = buildSignedJwt(appleUserId = "apple-user-1", eventType = "account-delete", audience = "com.other.app")
        every { applePublicKeyService.getPublicKey("test-key-id") } returns rsaKey
        every { userRepository.findByAppleId("apple-user-1") } returns Optional.of(user)

        val result = appleNotificationService.processNotification(token)

        assertFalse(result)
        verify(exactly = 0) { authService.deleteAccount(any()) }
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        appleId: String? = "apple-123",
        active: Boolean = true,
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        appleId = appleId,
        currentStreak = 0,
        longestStreak = 0,
        active = active,
        subscriptionStatus = SubscriptionStatus.FREE,
    )

    /**
     * Builds a real RS256-signed JWT with an "events" claim that the service can parse.
     * Returns the token string and the corresponding RSA public key.
     *
     * The public key returned can be fed directly to `applePublicKeyService.getPublicKey(...)`.
     */
    private fun buildSignedJwt(
        appleUserId: String,
        eventType: String,
        eventExtra: String? = null,
        audience: String = "com.alirezaiyan.vokab",
    ): Pair<String, java.security.PublicKey> {
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = keyPair.private as java.security.interfaces.RSAPrivateKey
        val publicKey = keyPair.public

        val eventsJson = buildEventsJson(appleUserId, eventType, eventExtra)

        // Build claims: issuer = "https://appleid.apple.com", aud = our client id, events claim
        val token = io.jsonwebtoken.Jwts.builder()
            .issuer("https://appleid.apple.com")
            .audience().add(audience).and()
            .claim("events", objectMapper.readValue(eventsJson, Map::class.java))
            .header().keyId("test-key-id").and()
            .signWith(privateKey, io.jsonwebtoken.Jwts.SIG.RS256)
            .compact()

        return Pair(token, publicKey)
    }

    private fun buildEventsJson(appleUserId: String, eventType: String, eventExtra: String?): String {
        val extras = if (eventExtra != null) ",$eventExtra" else ""
        return """{"type":"$eventType","sub":"$appleUserId","event_time":${Instant.now().toEpochMilli()}$extras}"""
    }
}
