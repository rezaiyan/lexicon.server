package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.subscription.ProcessedWebhookEvent
import com.alirezaiyan.vokab.server.subscription.SubscriptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.alirezaiyan.vokab.server.subscription.ProcessedWebhookEventRepository

/**
 * Real-database checks for the subscription write path: the columns are updatable = false,
 * so only the targeted queries may change them and stale whole-user saves can't revert them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserSubscriptionPersistenceTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var processedWebhookEventRepository: ProcessedWebhookEventRepository
    @Autowired private lateinit var entityManager: EntityManager

    private fun newUser(email: String = "sub-${System.nanoTime()}@example.com") =
        userRepository.saveAndFlush(User(email = email, name = "Sub User"))

    // Clear first so we read the database row, not a cached managed instance.
    private fun reload(id: Long): User {
        entityManager.flush()
        entityManager.clear()
        return userRepository.findById(id).orElseThrow()
    }

    @Test
    fun `targeted update changes subscription state`() {
        val user = newUser()
        val expiry = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS)

        userRepository.updateSubscription(user.id!!, SubscriptionStatus.ACTIVE, expiry, Instant.now())

        val reloaded = reload(user.requireId())
        assertEquals(SubscriptionStatus.ACTIVE, reloaded.subscriptionStatus)
        assertEquals(expiry, reloaded.subscriptionExpiresAt?.truncatedTo(ChronoUnit.MILLIS))
    }

    @Test
    fun `stale whole-user save cannot revert premium set by webhook or sync`() {
        val staleSnapshot = newUser() // loaded before the webhook, FREE
        userRepository.updateSubscription(
            staleSnapshot.id!!, SubscriptionStatus.ACTIVE, Instant.now().plus(Duration.ofDays(30)), Instant.now()
        )

        // e.g. login / streak update saving an old copy
        staleSnapshot.currentStreak = 7
        staleSnapshot.updatedAt = Instant.now()
        userRepository.saveAndFlush(staleSnapshot)

        val reloaded = reload(staleSnapshot.requireId())
        assertEquals(7, reloaded.currentStreak)
        assertEquals(SubscriptionStatus.ACTIVE, reloaded.subscriptionStatus)
    }

    @Test
    fun `insert still stores initial subscription state`() {
        val user = userRepository.saveAndFlush(
            User(email = "granted-${System.nanoTime()}@example.com", name = "G", subscriptionStatus = SubscriptionStatus.ACTIVE)
        )

        assertEquals(SubscriptionStatus.ACTIVE, reload(user.id!!).subscriptionStatus)
    }

    @Test
    fun `link sets revenueCat id once and never overwrites`() {
        val user = newUser()

        assertEquals(1, userRepository.linkRevenueCatUserId(user.id!!, user.id.toString(), Instant.now()))
        assertEquals(0, userRepository.linkRevenueCatUserId(user.requireId(), "other", Instant.now()))

        assertEquals(user.id.toString(), reload(user.requireId()).revenueCatUserId)
        assertTrue(userRepository.findIdsLinkedToRevenueCat().contains(user.id))
    }

    @Test
    fun `expireLapsedSubscriptions expires only store statuses past their expiry`() {
        val lapsed = newUser()
        val current = newUser()
        val grant = newUser()
        val now = Instant.now()
        userRepository.updateSubscription(lapsed.id!!, SubscriptionStatus.CANCELLED, now.minusSeconds(60), now)
        userRepository.updateSubscription(current.id!!, SubscriptionStatus.ACTIVE, now.plus(Duration.ofDays(3)), now)
        userRepository.updateSubscription(grant.id!!, SubscriptionStatus.ACTIVE, null, now)

        userRepository.expireLapsedSubscriptions(now, SubscriptionService.STORE_DERIVED_STATUSES, SubscriptionStatus.EXPIRED)

        assertEquals(SubscriptionStatus.EXPIRED, reload(lapsed.requireId()).subscriptionStatus)
        assertEquals(SubscriptionStatus.ACTIVE, reload(current.requireId()).subscriptionStatus)
        assertEquals(SubscriptionStatus.ACTIVE, reload(grant.requireId()).subscriptionStatus)
        assertNull(reload(grant.requireId()).subscriptionExpiresAt)
    }

    @Test
    fun `grant is written by targeted update and survives stale saves`() {
        val stale = newUser()
        val until = Instant.now().plus(Duration.ofDays(365)).truncatedTo(ChronoUnit.MILLIS)
        userRepository.updateGrant(stale.id!!, until, "manual", Instant.now())

        stale.currentStreak = 3
        stale.updatedAt = Instant.now()
        userRepository.saveAndFlush(stale)

        val reloaded = reload(stale.requireId())
        assertEquals("manual", reloaded.premiumGrantReason)
        assertEquals(until, reloaded.premiumGrantUntil?.truncatedTo(ChronoUnit.MILLIS))
        assertEquals(SubscriptionStatus.FREE, reloaded.subscriptionStatus)
    }

    @Test
    fun `grant can be revoked`() {
        val user = newUser()
        userRepository.updateGrant(user.id!!, Instant.now().plusSeconds(60), "test_email", Instant.now())

        userRepository.updateGrant(user.requireId(), null, null, Instant.now())

        assertNull(reload(user.requireId()).premiumGrantUntil)
        assertNull(reload(user.requireId()).premiumGrantReason)
    }

    @Test
    fun `V38 migration statement moves fake ACTIVE grants to grant columns`() {
        val legacy = newUser()
        val manual = newUser()
        val paying = newUser()
        val now = Instant.now()
        userRepository.updateSubscription(legacy.id!!, SubscriptionStatus.ACTIVE, now.plus(Duration.ofDays(36500)), now)
        userRepository.updateSubscription(manual.id!!, SubscriptionStatus.ACTIVE, null, now)
        userRepository.updateSubscription(paying.id!!, SubscriptionStatus.ACTIVE, now.plus(Duration.ofDays(30)), now)

        val sql = javaClass.getResource("/db/migration/V38__separate_premium_grants.sql")!!.readText()
        val update = sql.substring(sql.indexOf("UPDATE users"))
        entityManager.createNativeQuery(update.trimEnd().removeSuffix(";")).executeUpdate()

        reload(legacy.requireId()).let {
            assertEquals("legacy_grant", it.premiumGrantReason)
            assertEquals(SubscriptionStatus.FREE, it.subscriptionStatus)
            assertNull(it.subscriptionExpiresAt)
        }
        assertEquals("manual", reload(manual.requireId()).premiumGrantReason)
        reload(paying.requireId()).let {
            assertNull(it.premiumGrantReason)
            assertEquals(SubscriptionStatus.ACTIVE, it.subscriptionStatus)
        }
    }

    @Test
    fun `processed webhook receipts persist`() {
        processedWebhookEventRepository.saveAndFlush(ProcessedWebhookEvent(eventId = "evt-1", eventType = "RENEWAL"))

        assertTrue(processedWebhookEventRepository.existsById("evt-1"))
    }
}
