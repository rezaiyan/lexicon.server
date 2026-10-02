package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.CiAuthConfig
import com.alirezaiyan.vokab.server.config.JwtConfig
import com.alirezaiyan.vokab.server.config.SecurityConfig
import com.alirezaiyan.vokab.server.domain.event.UserSignedUpEvent
import com.alirezaiyan.vokab.server.service.event.DomainEventPublisher
import com.alirezaiyan.vokab.server.domain.entity.DailyActivity
import com.alirezaiyan.vokab.server.domain.entity.DailyInsight
import com.alirezaiyan.vokab.server.domain.entity.PushToken
import com.alirezaiyan.vokab.server.domain.entity.RefreshToken
import com.alirezaiyan.vokab.server.domain.entity.ReviewEvent
import com.alirezaiyan.vokab.server.domain.entity.StudySession
import com.alirezaiyan.vokab.server.domain.entity.Subscription
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.UserSettings
import com.alirezaiyan.vokab.server.domain.entity.Word
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.DailyInsightRepository
import com.alirezaiyan.vokab.server.domain.repository.PushTokenRepository
import com.alirezaiyan.vokab.server.domain.repository.RefreshTokenRepository
import com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository
import com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository
import com.alirezaiyan.vokab.server.domain.repository.SubscriptionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserPlatformRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.repository.UserSettingsRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import com.alirezaiyan.vokab.server.security.AppleIdClaims
import com.alirezaiyan.vokab.server.security.AppleIdTokenVerifier
import com.alirezaiyan.vokab.server.security.RS256JwtTokenProvider
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import io.mockk.every
import io.mockk.just
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Optional

class AuthServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var jwtTokenProvider: RS256JwtTokenProvider
    private lateinit var refreshTokenHashService: RefreshTokenHashService
    private lateinit var appleIdTokenVerifier: AppleIdTokenVerifier
    private lateinit var revenueCatClient: RevenueCatClient
    private lateinit var wordRepository: WordRepository
    private lateinit var userSettingsRepository: UserSettingsRepository
    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var subscriptionRepository: SubscriptionRepository
    private lateinit var pushTokenRepository: PushTokenRepository
    private lateinit var userPlatformRepository: UserPlatformRepository
    private lateinit var dailyInsightRepository: DailyInsightRepository
    private lateinit var reviewEventRepository: ReviewEventRepository
    private lateinit var studySessionRepository: StudySessionRepository
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var appProperties: AppProperties
    private lateinit var auditLogService: AuditLogService
    private lateinit var eventService: EventService
    private lateinit var appConfigService: AppConfigService
    private lateinit var domainEventPublisher: DomainEventPublisher
    private lateinit var geoLocationService: GeoLocationService

    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        refreshTokenRepository = mockk()
        jwtTokenProvider = mockk()
        refreshTokenHashService = mockk()
        appleIdTokenVerifier = mockk()
        revenueCatClient = mockk(relaxed = true)
        wordRepository = mockk()
        userSettingsRepository = mockk()
        dailyActivityRepository = mockk()
        subscriptionRepository = mockk()
        pushTokenRepository = mockk()
        userPlatformRepository = mockk(relaxed = true)
        dailyInsightRepository = mockk()
        reviewEventRepository = mockk()
        studySessionRepository = mockk()
        pushNotificationService = mockk()
        auditLogService = mockk(relaxed = true)
        eventService = mockk(relaxed = true)
        appConfigService = mockk()
        domainEventPublisher = mockk(relaxed = true)
        every { appConfigService.getTestEmails() } returns emptySet()
        geoLocationService = mockk(relaxed = true)

        appProperties = AppProperties(
            jwt = JwtConfig(refreshExpirationMs = 7_776_000_000L, refreshTokenGracePeriodMs = 30_000L),
            ciAuth = CiAuthConfig(enabled = true, secret = "ci-secret", testEmail = "ci@test.vokab.dev"),
            security = SecurityConfig(testEmails = "")
        )

        authService = buildServiceWith(appProperties)
        every { userRepository.updateSubscription(any(), any(), any(), any()) } returns 1
        every { userRepository.updateGrant(any(), any(), any(), any()) } returns 1
    }

    // ── authenticateForCi ─────────────────────────────────────────────────────

    @Test
    fun `authenticateForCi persists premium for existing user via targeted update`() {
        val existingUser = createUser(id = 61L, email = "ci@test.vokab.dev", subscriptionStatus = SubscriptionStatus.FREE)
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existingUser)

        authService.authenticateForCi(premium = false)

        // Subscription columns are not updatable through save(); the targeted update must run.
        verify { userRepository.updateSubscription(61L, SubscriptionStatus.FREE, null, any()) }
    }

    @Test
    fun `authenticateForCi should return auth response for existing CI user`() {
        // Arrange
        val existingUser = createUser(id = 10L, email = "ci@test.vokab.dev")
        val savedUser = existingUser.copy(lastLoginAt = Instant.now())
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns savedUser
        stubTokenGeneration(savedUser)

        // Act
        val result = authService.authenticateForCi()

        // Assert
        assertNotNull(result)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
        assertEquals("Bearer", result.tokenType)
        verify(exactly = 0) { eventService.trackAsync(any(), any(), any()) }
    }

    @Test
    fun `authenticateForCi should create new CI user when one does not exist`() {
        // Arrange
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.empty()
        val createdUser = createUser(id = 99L, email = "ci@test.vokab.dev", name = "CI Test User")
        every { userRepository.save(any()) } returns createdUser
        stubTokenGeneration(createdUser)

        // Act
        val result = authService.authenticateForCi()

        // Assert
        assertNotNull(result)
        assertEquals("access-token", result.accessToken)
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateForCi should return user DTO with correct fields`() {
        // Arrange
        val existingUser = createUser(id = 5L, email = "ci@test.vokab.dev", name = "CI User")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns existingUser
        stubTokenGeneration(existingUser)

        // Act
        val result = authService.authenticateForCi()

        // Assert
        assertEquals(5L, result.user.id)
        assertEquals("ci@test.vokab.dev", result.user.email)
        assertEquals("CI User", result.user.name)
    }

    @Test
    fun `authenticateForCi should grant premium access when CI user email is in test emails list`() {
        // Arrange
        val testEmail = "ci@test.vokab.dev"
        val propertiesWithTestEmail = appProperties.copy(
            security = SecurityConfig(testEmails = testEmail)
        )
        every { appConfigService.getTestEmails() } returns setOf(testEmail)
        val service = buildServiceWith(propertiesWithTestEmail)

        val existingUser = createUser(id = 20L, email = testEmail)
        every { userRepository.findByEmail(testEmail) } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers {
            savedUser = firstArg()
            firstArg()
        }
        stubTokenGeneration(existingUser)

        // Act
        service.authenticateForCi()

        // Assert
        assertNotNull(savedUser)
        assertEquals("test_email", savedUser!!.premiumGrantReason)
        assertNotNull(savedUser!!.premiumGrantUntil)
        assertEquals(SubscriptionStatus.FREE, savedUser!!.subscriptionStatus) // grants never touch store state
        verify { userRepository.updateGrant(20L, any(), "test_email", any()) }
    }

    @Test
    fun `authenticateForCi should not modify subscription when test emails list is blank`() {
        // Arrange — appProperties.security.testEmails is "" by default in setUp
        val existingUser = createUser(id = 21L, email = "ci@test.vokab.dev",
            subscriptionStatus = SubscriptionStatus.FREE)
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers {
            savedUser = firstArg()
            firstArg()
        }
        stubTokenGeneration(existingUser)

        // Act
        authService.authenticateForCi()

        // Assert
        assertNotNull(savedUser)
        assertEquals(SubscriptionStatus.FREE, savedUser!!.subscriptionStatus)
    }

    @Test
    fun `authenticateForCi should set subscription to FREE when premium is false`() {
        // Arrange — existing user with ACTIVE subscription; premium=false should strip it
        val existingUser = createUser(id = 60L, email = "ci@test.vokab.dev",
            subscriptionStatus = SubscriptionStatus.ACTIVE)
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers {
            savedUser = firstArg()
            firstArg()
        }
        stubTokenGeneration(existingUser)

        // Act
        authService.authenticateForCi(premium = false)

        // Assert
        assertNotNull(savedUser)
        assertEquals(SubscriptionStatus.FREE, savedUser!!.subscriptionStatus)
        assertNull(savedUser!!.subscriptionExpiresAt)
    }

    @Test
    fun `authenticateForCi should record platform when a known platform is provided`() {
        // Arrange
        val existingUser = createUser(id = 61L, email = "ci@test.vokab.dev")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns existingUser
        stubTokenGeneration(existingUser)

        // Act
        authService.authenticateForCi(platform = "ios", appVersion = "2.5.0")

        // Assert — platform recorded with resolved version
        verify { userPlatformRepository.upsertPlatform(existingUser.id!!, "IOS", "2.5.0") }
    }

    @Test
    fun `authenticateForCi should skip platform recording for unknown platform value`() {
        // Arrange
        val existingUser = createUser(id = 62L, email = "ci@test.vokab.dev")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns existingUser
        stubTokenGeneration(existingUser)

        // Act — unknown platform must not throw
        assertDoesNotThrow { authService.authenticateForCi(platform = "unknown_platform") }

        // Assert — upsertPlatform must NOT be called when platform is unrecognised
        verify(exactly = 0) { userPlatformRepository.upsertPlatform(any(), any(), any()) }
    }

    @Test
    fun `authenticateForCi should absorb exception thrown by upsertPlatform`() {
        val existingUser = createUser(id = 63L, email = "ci@test.vokab.dev")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns existingUser
        stubTokenGeneration(existingUser)
        every { userPlatformRepository.upsertPlatform(any(), any(), any()) } throws RuntimeException("db error")

        // Exception must be absorbed — auth response must still be returned
        assertDoesNotThrow { authService.authenticateForCi(platform = "ios") }
    }

    // ── authenticateWithApple ─────────────────────────────────────────────────

    @Test
    fun `authenticateWithApple rejects a token that fails verification`() {
        every { appleIdTokenVerifier.verify("bad") } returns null

        assertThrows<IllegalArgumentException> {
            authService.authenticateWithApple("bad", null, null)
        }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateWithApple rejects a client appleUserId that differs from the token subject`() {
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "real-sub", email = "a@b.com")

        assertThrows<IllegalArgumentException> {
            authService.authenticateWithApple("token", null, appleUserId = "victim-sub")
        }
        verify(exactly = 0) { userRepository.findByAppleId(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateWithApple signs in existing user found by token subject`() {
        val existing = createUser(id = 7L, email = "a@b.com", appleId = "real-sub")
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "real-sub", email = "a@b.com")
        every { userRepository.findByAppleId("real-sub") } returns Optional.of(existing)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existing)

        val result = authService.authenticateWithApple("token", null, appleUserId = "real-sub")

        assertEquals(7L, result.user.id)
        assertEquals("access-token", result.accessToken)
        verify(exactly = 0) { eventService.trackAsync(any(), "signup_completed", any()) }
    }

    @Test
    fun `authenticateWithApple with hidden email creates user with fallback email and never links by email`() {
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "abc.123", email = null)
        every { userRepository.findByAppleId("abc.123") } returns Optional.empty()
        every { userRepository.save(any()) } answers { firstArg<User>().copy(id = 42L) }
        every { jwtTokenProvider.generateAccessToken(42L, "apple_abc_123@apple.hidden", any()) } returns "access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "refresh-token"
        every { refreshTokenHashService.createLookupHash("refresh-token") } returns "lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L

        val result = authService.authenticateWithApple("token", "Jane Doe", null)

        assertEquals("apple_abc_123@apple.hidden", result.user.email)
        assertEquals("Jane Doe", result.user.name)
        verify(exactly = 0) { userRepository.findByEmail(any()) }
        verify { eventService.trackAsync(42L, "signup_completed", mapOf("provider" to "apple")) }
    }

    // ── refreshAccessToken ────────────────────────────────────────────────────

    @Test
    fun `refreshAccessToken should return new tokens when refresh token is valid`() {
        // Arrange
        val user = createUser(id = 1L)
        val token = "valid-refresh-token"
        val lookupHash = "hashed-token"
        val tokenEntity = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = Instant.now().plusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(1L, user.email, any()) } returns "new-access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh-token"
        every { refreshTokenHashService.createLookupHash("new-refresh-token") } returns "new-lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L

        // Act
        val result = authService.refreshAccessToken(token)

        // Assert
        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)
        assertEquals(86400L, result.expiresIn)
        verify(exactly = 2) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `refreshAccessToken should throw when refresh token is not found`() {
        // Arrange
        val token = "unknown-token"
        val lookupHash = "unknown-hash"
        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            authService.refreshAccessToken(token)
        }
        assertEquals("Refresh token not found", ex.message)
    }

    @Test
    fun `refreshAccessToken should throw when refresh token has been revoked`() {
        // Arrange
        val user = createUser(id = 2L)
        val token = "revoked-token"
        val lookupHash = "revoked-hash"
        val revokedToken = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = Instant.now().plusSeconds(3600), revoked = true)

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(revokedToken)

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            authService.refreshAccessToken(token)
        }
        assertEquals("Refresh token has been revoked", ex.message)
    }

    @Test
    fun `refreshAccessToken should throw when refresh token has expired`() {
        // Arrange
        val user = createUser(id = 3L)
        val token = "expired-token"
        val lookupHash = "expired-hash"
        val expiredToken = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = Instant.now().minusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(expiredToken)

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            authService.refreshAccessToken(token)
        }
        assertEquals("Refresh token has expired", ex.message)
    }

    @Test
    fun `refreshAccessToken should include user dto in response`() {
        // Arrange
        val user = createUser(id = 4L, email = "test@example.com", name = "Test User")
        val token = "valid-token"
        val lookupHash = "valid-hash"
        val tokenEntity = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = Instant.now().plusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(4L, user.email, any()) } returns "new-access"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh"
        every { refreshTokenHashService.createLookupHash("new-refresh") } returns "new-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 3600L

        // Act
        val result = authService.refreshAccessToken(token)

        // Assert
        assertEquals(4L, result.user.id)
        assertEquals("test@example.com", result.user.email)
        assertEquals("Test User", result.user.name)
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    fun `logout should revoke the refresh token by its lookup hash`() {
        // Arrange
        val userId = 10L
        val refreshToken = "my-refresh-token"
        val lookupHash = "my-hash"
        val user = createUser(id = userId)

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.of(user)

        // Act
        authService.logout(userId, refreshToken)

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeByTokenHash(lookupHash) }
    }

    @Test
    fun `logout should call audit log when user is found`() {
        // Arrange
        val userId = 11L
        val refreshToken = "audit-token"
        val lookupHash = "audit-hash"
        val user = createUser(id = userId)

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.of(user)

        // Act
        authService.logout(userId, refreshToken)

        // Assert
        verify(exactly = 1) { auditLogService.logLogout(userId, user.email, refreshToken, null) }
    }

    @Test
    fun `logout should still revoke token even when user is not found in repository`() {
        // Arrange
        val userId = 12L
        val refreshToken = "orphan-token"
        val lookupHash = "orphan-hash"

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act — must not throw even if the user row is gone
        assertDoesNotThrow { authService.logout(userId, refreshToken) }

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeByTokenHash(lookupHash) }
        verify(exactly = 0) { auditLogService.logLogout(any(), any(), any(), any()) }
    }

    // ── logoutAll ─────────────────────────────────────────────────────────────

    @Test
    fun `logoutAll should revoke all tokens for user`() {
        // Arrange
        val userId = 20L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 3

        // Act
        authService.logoutAll(userId)

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeAllByUser(user) }
    }

    @Test
    fun `logoutAll should call audit log after revoking all tokens`() {
        // Arrange
        val userId = 21L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 2

        // Act
        authService.logoutAll(userId)

        // Assert
        verify(exactly = 1) { auditLogService.logLogoutAll(userId, user.email, null) }
    }

    @Test
    fun `logoutAll should throw when user is not found`() {
        // Arrange
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            authService.logoutAll(userId)
        }
        assertEquals("User not found", ex.message)
    }

    @Test
    fun `logoutAll should still revoke tokens when push notification fails`() {
        // Arrange
        val userId = 22L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
        } throws RuntimeException("FCM is down")
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        // Act — push failure must not propagate
        assertDoesNotThrow { authService.logoutAll(userId) }

        // Assert — revocation still happened
        verify(exactly = 1) { refreshTokenRepository.revokeAllByUser(user) }
    }

    @Test
    fun `logoutAll should send push notification to all devices`() {
        // Arrange
        val userId = 23L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        // Act
        authService.logoutAll(userId)

        // Assert
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        }
    }

    // ── deleteAccount ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAccount should delete all user data and the user record`() {
        // Arrange
        val userId = 30L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        authService.deleteAccount(userId)

        // Assert — user row is deleted last
        verify(exactly = 1) { userRepository.delete(user) }
    }

    @Test
    fun `deleteAccount should revoke all refresh tokens`() {
        // Arrange
        val userId = 31L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        authService.deleteAccount(userId)

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeAllByUser(user) }
        verify(exactly = 1) { refreshTokenRepository.deleteAll(any<List<RefreshToken>>()) }
    }

    @Test
    fun `deleteAccount should delete words, push tokens, insights, activities, subscriptions, review events, study sessions`() {
        // Arrange
        val userId = 32L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        authService.deleteAccount(userId)

        // Assert
        verify(exactly = 1) { wordRepository.deleteAll(any<List<Word>>()) }
        verify(exactly = 1) { pushTokenRepository.deleteAll(any<List<PushToken>>()) }
        verify(exactly = 1) { dailyInsightRepository.deleteAll(any<List<DailyInsight>>()) }
        verify(exactly = 1) { dailyActivityRepository.deleteAll(any<List<DailyActivity>>()) }
        verify(exactly = 1) { subscriptionRepository.deleteAll(any<List<Subscription>>()) }
        verify(exactly = 1) { reviewEventRepository.deleteAll(any<List<ReviewEvent>>()) }
        verify(exactly = 1) { studySessionRepository.deleteAll(any<List<StudySession>>()) }
    }

    @Test
    fun `deleteAccount should delete user settings when present`() {
        // Arrange
        val userId = 33L
        val user = createUser(id = userId, googleId = null)
        val settings = mockk<UserSettings>()
        stubDeleteAccount(userId, user, userSettings = settings)

        // Act
        authService.deleteAccount(userId)

        // Assert
        verify(exactly = 1) { userSettingsRepository.delete(settings) }
    }

    @Test
    fun `deleteAccount should not call delete on settings when settings are absent`() {
        // Arrange
        val userId = 34L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user, userSettings = null)

        // Act
        authService.deleteAccount(userId)

        // Assert
        verify(exactly = 0) { userSettingsRepository.delete(any()) }
    }

    @Test
    fun `deleteAccount should throw when user is not found`() {
        // Arrange
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            authService.deleteAccount(userId)
        }
        assertEquals("User not found", ex.message)
    }

    @Test
    fun `deleteAccount should continue with deletion when push notification fails`() {
        // Arrange
        val userId = 35L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user, pushThrows = true)

        // Act — push failure must not propagate
        assertDoesNotThrow { authService.deleteAccount(userId) }

        // Assert — user is still deleted
        verify(exactly = 1) { userRepository.delete(user) }
    }

    @Test
    fun `deleteAccount should record audit log before deleting user row`() {
        // Arrange
        val userId = 36L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        authService.deleteAccount(userId)

        // Assert
        verify(exactly = 1) { auditLogService.logAccountDeletion(userId, user.email, null) }
    }

    @Test
    fun `deleteAccount should skip Firebase deletion when user has no googleId`() {
        // Arrange
        val userId = 37L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act — if FirebaseAuth were called on a null googleId this would throw
        assertDoesNotThrow { authService.deleteAccount(userId) }
    }

    @Test
    fun `deleteAccount should continue when Firebase deletion throws`() {
        // Arrange — user has a googleId so Firebase deletion is attempted, but Firebase is not
        // available in unit tests. The service wraps the call in try/catch so it must not throw.
        val userId = 38L
        // Providing a googleId triggers the Firebase block; FirebaseAuth.getInstance() will throw
        // an IllegalStateException (no app initialized) which is caught internally.
        val user = createUser(id = userId, googleId = "firebase-uid-123")
        stubDeleteAccount(userId, user)

        // Act
        assertDoesNotThrow { authService.deleteAccount(userId) }

        // Assert — despite Firebase failure, user is deleted
        verify(exactly = 1) { userRepository.delete(user) }
    }

    // ── applyTestUserPremiumAccess (via authenticateForCi) ────────────────────

    @Test
    fun `applyTestUserPremiumAccess should set subscription to ACTIVE with far-future expiry`() {
        // Arrange
        val testEmail = "premium@test.example"
        val propertiesWithTestEmail = appProperties.copy(
            ciAuth = CiAuthConfig(enabled = true, secret = "", testEmail = testEmail),
            security = SecurityConfig(testEmails = testEmail)
        )
        every { appConfigService.getTestEmails() } returns setOf(testEmail)
        val service = buildServiceWith(propertiesWithTestEmail)

        val existingUser = createUser(id = 50L, email = testEmail,
            subscriptionStatus = SubscriptionStatus.FREE)
        every { userRepository.findByEmail(testEmail) } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers {
            savedUser = firstArg()
            firstArg()
        }
        stubTokenGeneration(existingUser)

        // Act
        service.authenticateForCi()

        // Assert
        assertNotNull(savedUser)
        assertEquals("test_email", savedUser!!.premiumGrantReason)
        // Grant should last roughly 100 years — at least 50 years in the future
        val fiftyYearsFromNow = Instant.now().plusSeconds(50L * 365 * 24 * 3600)
        assert(savedUser!!.premiumGrantUntil!!.isAfter(fiftyYearsFromNow)) {
            "Expected grant to last far into the future"
        }
    }

    @Test
    fun `applyTestUserPremiumAccess should handle multiple test emails in comma-separated list`() {
        // Arrange
        val targetEmail = "ci@test.vokab.dev"
        val propertiesWithMultipleTestEmails = appProperties.copy(
            security = SecurityConfig(testEmails = "other@test.com, $targetEmail, another@test.com")
        )
        every { appConfigService.getTestEmails() } returns setOf("other@test.com", targetEmail, "another@test.com")
        val service = buildServiceWith(propertiesWithMultipleTestEmails)

        val existingUser = createUser(id = 51L, email = targetEmail,
            subscriptionStatus = SubscriptionStatus.FREE)
        every { userRepository.findByEmail(targetEmail) } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers {
            savedUser = firstArg()
            firstArg()
        }
        stubTokenGeneration(existingUser)

        // Act
        service.authenticateForCi()

        // Assert
        assertNotNull(savedUser)
        assertEquals("test_email", savedUser!!.premiumGrantReason)
    }

    @Test
    fun `test grant is revoked when email is removed from the test list`() {
        val existingUser = createUser(id = 52L, email = "ci@test.vokab.dev")
            .copy(premiumGrantUntil = Instant.now().plusSeconds(86400), premiumGrantReason = "test_email")
        every { appConfigService.getTestEmails() } returns emptySet()
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        var savedUser: User? = null
        every { userRepository.save(any()) } answers { savedUser = firstArg(); firstArg() }
        stubTokenGeneration(existingUser)

        authService.authenticateForCi()

        assertNull(savedUser!!.premiumGrantUntil)
        verify { userRepository.updateGrant(52L, null, null, any()) }
    }

    @Test
    fun `legacy or manual grants are not revoked by the test list`() {
        val existingUser = createUser(id = 53L, email = "ci@test.vokab.dev")
            .copy(premiumGrantUntil = Instant.now().plusSeconds(86400), premiumGrantReason = "legacy_grant")
        every { appConfigService.getTestEmails() } returns emptySet()
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existingUser)

        authService.authenticateForCi()

        verify(exactly = 0) { userRepository.updateGrant(any(), any(), any(), any()) }
    }

    @Test
    fun `authenticateForCi non-premium clears grant as well as subscription`() {
        val existingUser = createUser(id = 54L, email = "ci@test.vokab.dev")
            .copy(premiumGrantUntil = Instant.now().plusSeconds(86400), premiumGrantReason = "test_email")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existingUser)

        authService.authenticateForCi(premium = false)

        verify { userRepository.updateGrant(54L, null, null, any()) }
        verify { userRepository.updateSubscription(54L, SubscriptionStatus.FREE, null, any()) }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun stubTokenGeneration(user: User) {
        every { jwtTokenProvider.generateAccessToken(user.id!!, user.email, any()) } returns "access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "refresh-token"
        every { refreshTokenHashService.createLookupHash("refresh-token") } returns "lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L
    }

    private fun stubDeleteAccount(
        userId: Long,
        user: User,
        userSettings: UserSettings? = null,
        pushThrows: Boolean = false
    ) {
        every { userRepository.findById(userId) } returns Optional.of(user)

        if (pushThrows) {
            every {
                pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
            } throws RuntimeException("push failed")
        } else {
            every {
                pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
            } returns emptyList()
        }

        every { refreshTokenRepository.revokeAllByUser(user) } returns 1
        every { refreshTokenRepository.findByUser(user) } returns emptyList()
        every { refreshTokenRepository.deleteAll(any<List<RefreshToken>>()) } just Runs

        every { pushTokenRepository.findByUser(user) } returns emptyList()
        every { pushTokenRepository.deleteAll(any<List<PushToken>>()) } just Runs

        every { dailyInsightRepository.findByUser(user) } returns emptyList()
        every { dailyInsightRepository.deleteAll(any<List<DailyInsight>>()) } just Runs

        every { wordRepository.findAllByUser(user) } returns emptyList()
        every { wordRepository.deleteAll(any<List<Word>>()) } just Runs

        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns emptyList()
        every { dailyActivityRepository.deleteAll(any<List<DailyActivity>>()) } just Runs

        every { subscriptionRepository.findByUser(user) } returns emptyList()
        every { subscriptionRepository.deleteAll(any<List<Subscription>>()) } just Runs

        every { reviewEventRepository.findByUser(user) } returns emptyList()
        every { reviewEventRepository.deleteAll(any<List<ReviewEvent>>()) } just Runs

        every { studySessionRepository.findByUser(user) } returns emptyList()
        every { studySessionRepository.deleteAll(any<List<StudySession>>()) } just Runs

        every { userSettingsRepository.findByUser(user) } returns userSettings
        if (userSettings != null) {
            justRun { userSettingsRepository.delete(userSettings) }
        }

        justRun { userRepository.delete(user) }
    }

    private fun buildServiceWith(properties: AppProperties): AuthService {
        return AuthService(
            userRepository = userRepository,
            refreshTokenRepository = refreshTokenRepository,
            jwtTokenProvider = jwtTokenProvider,
            refreshTokenHashService = refreshTokenHashService,
            appleIdTokenVerifier = appleIdTokenVerifier,
            revenueCatClient = revenueCatClient,
            wordRepository = wordRepository,
            userSettingsRepository = userSettingsRepository,
            dailyActivityRepository = dailyActivityRepository,
            subscriptionRepository = subscriptionRepository,
            pushTokenRepository = pushTokenRepository,
            userPlatformRepository = userPlatformRepository,
            dailyInsightRepository = dailyInsightRepository,
            reviewEventRepository = reviewEventRepository,
            studySessionRepository = studySessionRepository,
            pushNotificationService = pushNotificationService,
            appProperties = properties,
            auditLogService = auditLogService,
            eventService = eventService,
            domainEventPublisher = domainEventPublisher,
            geoLocationService = geoLocationService,
            appConfigService = appConfigService
        )
    }

    private fun createUser(
        id: Long? = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        googleId: String? = null,
        appleId: String? = null,
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        currentStreak: Int = 0
    ): User = User(
        id = id,
        email = email,
        name = name,
        googleId = googleId,
        appleId = appleId,
        subscriptionStatus = subscriptionStatus,
        currentStreak = currentStreak,
        longestStreak = 0
    )

    private fun createRefreshToken(
        id: Long? = 1L,
        user: User,
        tokenHash: String = "hash",
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        revoked: Boolean = false
    ): RefreshToken = RefreshToken(
        id = id,
        tokenHash = tokenHash,
        user = user,
        expiresAt = expiresAt,
        revoked = revoked
    )
}
