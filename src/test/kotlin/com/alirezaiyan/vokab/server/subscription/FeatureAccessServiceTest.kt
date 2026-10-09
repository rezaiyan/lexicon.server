package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.FeatureFlagsConfig
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class FeatureAccessServiceTest {

    private lateinit var appProperties: AppProperties
    private lateinit var userRepository: UserRepository
    private lateinit var featureAccessService: FeatureAccessService

    @BeforeEach
    fun setUp() {
        appProperties = mockk()
        userRepository = mockk()
        featureAccessService = FeatureAccessService(appProperties, userRepository, clock = fixedClock())
    }

    // --- hasActivePremiumAccess ---

    @Test
    fun `hasActivePremiumAccess should return true when status is ACTIVE and not expired`() {
        // Arrange
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400)
        )

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `hasActivePremiumAccess should return true when status is TRIAL and not expired`() {
        // Arrange
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.TRIAL,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400)
        )

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `hasActivePremiumAccess should return false when status is ACTIVE but expired`() {
        // Arrange
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = TEST_NOW.minusSeconds(1)
        )

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasActivePremiumAccess should return false when status is FREE`() {
        // Arrange
        val user = createUser(subscriptionStatus = SubscriptionStatus.FREE)

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasActivePremiumAccess should return false when status is EXPIRED`() {
        // Arrange
        val user = createUser(subscriptionStatus = SubscriptionStatus.EXPIRED)

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasActivePremiumAccess should return false when status is CANCELLED without expiry`() {
        // Arrange
        val user = createUser(subscriptionStatus = SubscriptionStatus.CANCELLED)

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasActivePremiumAccess should return true when status is CANCELLED and paid period not over`() {
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.CANCELLED,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400)
        )

        assertTrue(featureAccessService.hasActivePremiumAccess(user))
    }

    @Test
    fun `hasActivePremiumAccess should return false when status is CANCELLED and paid period over`() {
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.CANCELLED,
            subscriptionExpiresAt = TEST_NOW.minusSeconds(60)
        )

        assertFalse(featureAccessService.hasActivePremiumAccess(user))
    }

    @Test
    fun `hasActivePremiumAccess should return true when status is ACTIVE and expiresAt is null`() {
        // Arrange
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = null
        )

        // Act
        val result = featureAccessService.hasActivePremiumAccess(user)

        // Assert
        assertTrue(result)
    }

    // --- getClientFeatureFlags ---

    @Test
    fun `getClientFeatureFlags should return push notifications enabled from app properties`() {
        // Arrange
        every { appProperties.features } returns FeatureFlagsConfig(pushNotificationsEnabled = true)

        // Act
        val result = featureAccessService.getClientFeatureFlags()

        // Assert
        assertTrue(result.pushNotificationsEnabled)
    }

    @Test
    fun `getClientFeatureFlags should return push notifications disabled from app properties`() {
        // Arrange
        every { appProperties.features } returns FeatureFlagsConfig(pushNotificationsEnabled = false)

        // Act
        val result = featureAccessService.getClientFeatureFlags()

        // Assert
        assertFalse(result.pushNotificationsEnabled)
    }

    // --- getUserFeatureAccess ---

    @Test
    fun `getUserFeatureAccess should return premium access status as true for active subscription`() {
        // Arrange
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400)
        )

        // Act
        val result = featureAccessService.getUserFeatureAccess(user)

        // Assert
        assertTrue(result.hasPremiumAccess)
    }

    @Test
    fun `getUserFeatureAccess should return premium access status as false for free user`() {
        // Arrange
        val user = createUser(subscriptionStatus = SubscriptionStatus.FREE)

        // Act
        val result = featureAccessService.getUserFeatureAccess(user)

        // Assert
        assertFalse(result.hasPremiumAccess)
    }

    @Test
    fun `store subscription with a billing issue reports it`() {
        val user = createUser(subscriptionStatus = SubscriptionStatus.ACTIVE, subscriptionExpiresAt = TEST_NOW.plusSeconds(86400))
        user.mirrorSubscriptionIssues(billingIssueAt = TEST_NOW, pauseResumesAt = null)

        val access = featureAccessService.getUserFeatureAccess(user)

        assertTrue(access.hasPremiumAccess)
        assertTrue(access.hasBillingIssue)
    }

    @Test
    fun `paused subscription reports the resume date without premium`() {
        val resumesAt = TEST_NOW.plusSeconds(30L * 86400)
        val user = createUser(subscriptionStatus = SubscriptionStatus.EXPIRED)
        user.mirrorSubscriptionIssues(billingIssueAt = null, pauseResumesAt = resumesAt)

        val access = featureAccessService.getUserFeatureAccess(user)

        assertFalse(access.hasPremiumAccess)
        assertEquals(resumesAt.toString(), access.pauseResumesAt)
    }

    @Test
    fun `scheduled pause is reported while premium still runs`() {
        val resumesAt = TEST_NOW.plusSeconds(60L * 86400)
        val user = createUser(subscriptionStatus = SubscriptionStatus.ACTIVE, subscriptionExpiresAt = TEST_NOW.plusSeconds(86400))
        user.mirrorSubscriptionIssues(billingIssueAt = null, pauseResumesAt = resumesAt)

        val access = featureAccessService.getUserFeatureAccess(user)

        assertTrue(access.hasPremiumAccess)
        assertEquals(resumesAt.toString(), access.pauseResumesAt)
    }

    @Test
    fun `past pause date is not reported`() {
        val user = createUser(subscriptionStatus = SubscriptionStatus.EXPIRED)
        user.mirrorSubscriptionIssues(billingIssueAt = null, pauseResumesAt = TEST_NOW.minusSeconds(60))

        assertEquals(null, featureAccessService.getUserFeatureAccess(user).pauseResumesAt)
    }

    // --- Grants and premium source ---

    @Test
    fun `active grant gives premium with GRANT source`() {
        val until = TEST_NOW.plusSeconds(86400)
        val user = createUser(premiumGrantUntil = until, premiumGrantReason = "test_email")

        val access = featureAccessService.getUserFeatureAccess(user)

        assertTrue(access.hasPremiumAccess)
        assertEquals(PremiumSource.GRANT, access.source)
        assertEquals(until.toString(), access.expiresAt)
        assertFalse(access.willRenew)
    }

    @Test
    fun `expired grant gives no premium`() {
        val user = createUser(premiumGrantUntil = TEST_NOW.minusSeconds(60), premiumGrantReason = "manual")

        assertFalse(featureAccessService.hasActivePremiumAccess(user))
        assertEquals(PremiumSource.NONE, featureAccessService.getUserFeatureAccess(user).source)
    }

    @Test
    fun `store subscription wins over grant and reports renewal details`() {
        val expiry = TEST_NOW.plusSeconds(86400)
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.TRIAL,
            subscriptionExpiresAt = expiry,
            premiumGrantUntil = TEST_NOW.plusSeconds(999_999),
        )

        val access = featureAccessService.getUserFeatureAccess(user)

        assertEquals(PremiumSource.STORE, access.source)
        assertEquals(expiry.toString(), access.expiresAt)
        assertTrue(access.isTrial)
        assertTrue(access.willRenew)
    }

    @Test
    fun `cancelled store subscription reports willRenew false`() {
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.CANCELLED,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400),
        )

        val access = featureAccessService.getUserFeatureAccess(user)

        assertEquals(PremiumSource.STORE, access.source)
        assertFalse(access.willRenew)
    }

    @Test
    fun `free user without grant has NONE source`() {
        val access = featureAccessService.getUserFeatureAccess(createUser())

        assertFalse(access.hasPremiumAccess)
        assertEquals(PremiumSource.NONE, access.source)
    }

    // --- getFeatureAccess ---

    @Test
    fun `getFeatureAccess reads the user from the database so a fresh subscription is reflected`() {
        every { appProperties.features } returns FeatureFlagsConfig(pushNotificationsEnabled = true)
        val stored = createUser(
            subscriptionStatus = SubscriptionStatus.ACTIVE,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400)
        )
        every { userRepository.findById(1L) } returns Optional.of(stored)

        val response = featureAccessService.getFeatureAccess(1L)

        assertTrue(response.userAccess.hasPremiumAccess)
        assertTrue(response.featureFlags.pushNotificationsEnabled)
    }

    @Test
    fun `getFeatureAccess throws NoSuchElementException for an unknown user`() {
        every { userRepository.findById(404L) } returns Optional.empty()

        assertThrows(NoSuchElementException::class.java) { featureAccessService.getFeatureAccess(404L) }
    }

    // --- accessLevel ---

    @Test
    fun `accessLevel is FREE without a subscription or grant`() {
        assertEquals(AccessLevel.FREE, accessLevelOf(createUser()))
    }

    @Test
    fun `accessLevel is FREE once the store subscription has expired`() {
        val user = createUser(subscriptionStatus = SubscriptionStatus.ACTIVE, subscriptionExpiresAt = TEST_NOW.minusSeconds(1))
        assertEquals(AccessLevel.FREE, accessLevelOf(user))
    }

    @Test
    fun `accessLevel is TRIAL during a store trial`() {
        val user = createUser(subscriptionStatus = SubscriptionStatus.TRIAL, subscriptionExpiresAt = TEST_NOW.plusSeconds(86400))
        assertEquals(AccessLevel.TRIAL, accessLevelOf(user))
    }

    @Test
    fun `accessLevel is PREMIUM for a paid subscription, including a cancelled one still in its period`() {
        val active = createUser(subscriptionStatus = SubscriptionStatus.ACTIVE, subscriptionExpiresAt = TEST_NOW.plusSeconds(86400))
        val cancelled = createUser(subscriptionStatus = SubscriptionStatus.CANCELLED, subscriptionExpiresAt = TEST_NOW.plusSeconds(86400))
        assertEquals(AccessLevel.PREMIUM, accessLevelOf(active))
        assertEquals(AccessLevel.PREMIUM, accessLevelOf(cancelled))
    }

    @Test
    fun `accessLevel is PREMIUM for a grant, even during a store trial`() {
        val user = createUser(
            subscriptionStatus = SubscriptionStatus.TRIAL,
            subscriptionExpiresAt = TEST_NOW.plusSeconds(86400),
            premiumGrantUntil = TEST_NOW.plusSeconds(86400),
        )
        assertEquals(AccessLevel.PREMIUM, accessLevelOf(user))
    }

    @Test
    fun `accessLevel is FREE for an unknown user`() {
        every { userRepository.findById(404L) } returns Optional.empty()
        assertEquals(AccessLevel.FREE, featureAccessService.accessLevel(404L))
    }

    private fun accessLevelOf(user: User): AccessLevel {
        every { userRepository.findById(1L) } returns Optional.of(user)
        return featureAccessService.accessLevel(1L)
    }

    // --- Factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        subscriptionExpiresAt: Instant? = null,
        premiumGrantUntil: Instant? = null,
        premiumGrantReason: String? = null,
    ): User = User(
        premiumGrantUntil = premiumGrantUntil,
        premiumGrantReason = premiumGrantReason,
        id = id,
        email = email,
        name = "Test User",
        subscriptionStatus = subscriptionStatus,
        subscriptionExpiresAt = subscriptionExpiresAt,
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )
}
