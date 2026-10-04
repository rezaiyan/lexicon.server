package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.withAssignedId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.shared.CiAuthConfig
import com.alirezaiyan.vokab.server.shared.SecurityConfig
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class AuthServiceTest : AuthServiceTestFixture() {

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
        val savedUser = createUser(id = 10L, email = "ci@test.vokab.dev").also { it.lastLoginAt = TEST_NOW }
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
        verify(exactly = 0) { domainEventPublisher.publish(ofType<UserSignedUpEvent>()) }
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

        // Assert — platform row ensured, then touched with the resolved version
        verifyOrder {
            userPlatformRepository.insertPlatformIfAbsent(existingUser.id!!, "IOS", "2.5.0")
            userPlatformRepository.touchPlatform(existingUser.requireId(), "IOS", "2.5.0")
        }
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

        // Assert — nothing recorded when platform is unrecognised
        verify(exactly = 0) { userPlatformRepository.insertPlatformIfAbsent(any(), any(), any()) }
        verify(exactly = 0) { userPlatformRepository.touchPlatform(any(), any(), any()) }
    }

    @Test
    fun `authenticateForCi should absorb exception thrown while recording platform`() {
        val existingUser = createUser(id = 63L, email = "ci@test.vokab.dev")
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } returns existingUser
        stubTokenGeneration(existingUser)
        every { userPlatformRepository.insertPlatformIfAbsent(any(), any(), any()) } throws RuntimeException("db error")

        // Exception must be absorbed — auth response must still be returned
        assertDoesNotThrow { authService.authenticateForCi(platform = "ios") }
    }

    // ── authenticateWithGoogle ────────────────────────────────────────────────

    @Test
    fun `authenticateWithGoogle rejects a token that fails verification`() {
        every { firebaseIdTokenVerifier.verify("bad") } returns null

        assertThrows<AuthRejectedException> { authService.authenticateWithGoogle("bad") }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateWithGoogle rejects a token without an email`() {
        every { firebaseIdTokenVerifier.verify("token") } returns FirebaseIdClaims(uid = "g-1", email = null, name = null)

        assertThrows<AuthRejectedException> { authService.authenticateWithGoogle("token") }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateWithGoogle lets a Firebase outage propagate instead of rejecting the token`() {
        every { firebaseIdTokenVerifier.verify("token") } throws IllegalStateException("certificates unreachable")

        assertThrows<IllegalStateException> { authService.authenticateWithGoogle("token") }
    }

    @Test
    fun `authenticateWithGoogle signs in existing user found by Google id`() {
        val existing = createUser(id = 5L, email = "g@example.com", googleId = "g-5")
        every { firebaseIdTokenVerifier.verify("token") } returns FirebaseIdClaims(uid = "g-5", email = "g@example.com", name = "G")
        every { userRepository.findByGoogleId("g-5") } returns Optional.of(existing)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existing)

        val result = authService.authenticateWithGoogle("token")

        assertEquals(5L, result.user.id)
        assertEquals("access-token", result.accessToken)
    }

    // ── authenticateWithApple ─────────────────────────────────────────────────

    @Test
    fun `authenticateWithApple rejects a token that fails verification`() {
        every { appleIdTokenVerifier.verify("bad") } returns null

        assertThrows<AuthRejectedException> {
            authService.authenticateWithApple("bad", null, null)
        }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `authenticateWithApple rejects a client appleUserId that differs from the token subject`() {
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "real-sub", email = "a@b.com")

        assertThrows<AuthRejectedException> {
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
        verify(exactly = 0) { domainEventPublisher.publish(ofType<UserSignedUpEvent>()) }
    }

    @Test
    fun `authenticateWithApple with hidden email creates user with fallback email and never links by email`() {
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "abc.123", email = null)
        every { userRepository.findByAppleId("abc.123") } returns Optional.empty()
        every { userRepository.save(any()) } answers { firstArg<User>().withAssignedId(42L) }
        every { jwtTokenProvider.generateAccessToken(42L, "apple_abc_123@apple.hidden", any()) } returns "access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "refresh-token"
        every { refreshTokenHashService.createLookupHash("refresh-token") } returns "lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L

        val result = authService.authenticateWithApple("token", "Jane Doe", null)

        assertEquals("apple_abc_123@apple.hidden", result.user.email)
        assertEquals("Jane Doe", result.user.name)
        verify(exactly = 0) { userRepository.findByEmail(any()) }
        verify { domainEventPublisher.publish(match<UserSignedUpEvent> { it.userId == 42L && it.provider == "apple" }) }
    }

    @Test
    fun `authenticateWithApple without email claim keeps a returning user's real email`() {
        val existing = createUser(id = 8L, email = "real@example.com", appleId = "sub-8")
        every { appleIdTokenVerifier.verify("token") } returns AppleIdClaims(subject = "sub-8", email = null)
        every { userRepository.findByAppleId("sub-8") } returns Optional.of(existing)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existing)

        val result = authService.authenticateWithApple("token", null, null)

        assertEquals("real@example.com", result.user.email)
        verify { userRepository.save(match { it.email == "real@example.com" }) }
    }

    // ── TestAccessPolicy (via authenticateForCi) ──────────────────────────────

    @Test
    fun `test access policy should set subscription to ACTIVE with far-future expiry`() {
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
        val fiftyYearsFromNow = TEST_NOW.plusSeconds(50L * 365 * 24 * 3600)
        assert(savedUser!!.premiumGrantUntil!!.isAfter(fiftyYearsFromNow)) {
            "Expected grant to last far into the future"
        }
    }

    @Test
    fun `test access policy should handle multiple test emails in comma-separated list`() {
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
            .also { it.mirrorGrant(TEST_NOW.plusSeconds(86400), "test_email") }
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
            .also { it.mirrorGrant(TEST_NOW.plusSeconds(86400), "legacy_grant") }
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
            .also { it.mirrorGrant(TEST_NOW.plusSeconds(86400), "test_email") }
        every { userRepository.findByEmail("ci@test.vokab.dev") } returns Optional.of(existingUser)
        every { userRepository.save(any()) } answers { firstArg() }
        stubTokenGeneration(existingUser)

        authService.authenticateForCi(premium = false)

        verify { userRepository.updateGrant(54L, null, null, any()) }
        verify { userRepository.updateSubscription(54L, SubscriptionStatus.FREE, null, any()) }
    }
}
