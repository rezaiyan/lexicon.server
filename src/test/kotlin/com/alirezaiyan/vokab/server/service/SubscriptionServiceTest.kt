package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.ProcessedWebhookEvent
import com.alirezaiyan.vokab.server.domain.entity.Subscription
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ProcessedWebhookEventRepository
import com.alirezaiyan.vokab.server.domain.repository.SubscriptionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatEvent
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatWebhookEvent
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.Optional

class SubscriptionServiceTest {

    private lateinit var subscriptionRepository: SubscriptionRepository
    private lateinit var userRepository: UserRepository
    private lateinit var eventService: EventService
    private lateinit var processedEvents: ProcessedWebhookEventRepository
    private lateinit var revenueCatClient: RevenueCatClient
    private lateinit var pushNotificationService: PushNotificationService

    private lateinit var subscriptionService: SubscriptionService

    private data class StatusUpdate(val userId: Long, val status: SubscriptionStatus, val expiresAt: Instant?)

    private val statusUpdates = mutableListOf<StatusUpdate>()
    private val links = mutableListOf<Pair<Long, String>>()
    private val savedSubscriptions = mutableListOf<Subscription>()
    private val receipts = mutableListOf<ProcessedWebhookEvent>()

    @BeforeEach
    fun setUp() {
        subscriptionRepository = mockk()
        userRepository = mockk()
        eventService = mockk()
        processedEvents = mockk()
        revenueCatClient = mockk()
        pushNotificationService = mockk()

        subscriptionService = SubscriptionService(
            subscriptionRepository, userRepository, eventService, processedEvents, revenueCatClient,
            pushNotificationService,
            clock = fixedClock(),
        )

        every { userRepository.updateSubscription(any(), any(), any(), any()) } answers {
            statusUpdates += StatusUpdate(firstArg(), secondArg(), thirdArg())
            1
        }
        every { userRepository.linkRevenueCatUserId(any(), any(), any()) } answers {
            links += firstArg<Long>() to secondArg<String>()
            1
        }
        every { userRepository.expireLapsedSubscriptions(any(), any(), any()) } returns 0
        every { subscriptionRepository.save(any()) } answers { firstArg<Subscription>().also { savedSubscriptions += it } }
        every { subscriptionRepository.findByRevenueCatSubscriptionId(any()) } returns Optional.empty()
        every { userRepository.findByRevenueCatUserId(any()) } returns Optional.empty()
        every { userRepository.findById(any()) } returns Optional.empty()
        every { eventService.trackAsync(any(), any(), any()) } just Runs
        every { processedEvents.existsById(any()) } returns false
        every { processedEvents.save(any()) } answers { firstArg<ProcessedWebhookEvent>().also { receipts += it } }
        every { revenueCatClient.isConfigured } returns true
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } returns emptyList()
    }

    private fun lastUpdate() = statusUpdates.last()

    // ── user resolution ────────────────────────────────────────────────────────

    @Test
    fun `should use user already linked by revenueCatUserId`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        assertEquals(StatusUpdate(1L, SubscriptionStatus.ACTIVE, lastUpdate().expiresAt), lastUpdate())
        assertTrue(links.isEmpty())
    }

    @Test
    fun `should resolve user by numeric app_user_id and link revenueCatUserId`() {
        every { userRepository.findById(1L) } returns Optional.of(createUser(revenueCatUserId = null))

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        assertEquals(listOf(1L to "1"), links)
        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
    }

    @Test
    fun `should resolve user via alias when app_user_id is anonymous`() {
        every { userRepository.findById(1L) } returns Optional.of(createUser(revenueCatUserId = null))

        subscriptionService.handleRevenueCatWebhook(
            webhook(type = "INITIAL_PURCHASE", appUserId = "\$RCAnonymousID:abc", aliases = listOf("1"))
        )

        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
    }

    @Test
    fun `should not create placeholder user when no user matches but still record receipt`() {
        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE", appUserId = "999"))

        verify(exactly = 0) { userRepository.save(any()) }
        assertTrue(statusUpdates.isEmpty())
        assertTrue(savedSubscriptions.isEmpty())
        assertEquals(1, receipts.size)
    }

    @Test
    fun `should ignore anonymous-only customers`() {
        subscriptionService.handleRevenueCatWebhook(
            webhook(type = "INITIAL_PURCHASE", appUserId = "\$RCAnonymousID:abc")
        )

        verify(exactly = 0) { userRepository.findByRevenueCatUserId(any()) }
        assertTrue(statusUpdates.isEmpty())
    }

    @Test
    fun `should ignore TEST events without receipt`() {
        subscriptionService.handleRevenueCatWebhook(webhook(type = "TEST"))

        verify(exactly = 0) { userRepository.findByRevenueCatUserId(any()) }
        assertTrue(receipts.isEmpty())
    }

    // ── redelivery ─────────────────────────────────────────────────────────────

    @Test
    fun `should record receipt for applied event`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        assertEquals("event-initial_purchase", receipts.single().eventId)
        assertEquals("INITIAL_PURCHASE", receipts.single().eventType)
    }

    @Test
    fun `should skip already processed event so analytics fire once`() {
        linkedUser()
        every { processedEvents.existsById("event-initial_purchase") } returns true

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        assertTrue(statusUpdates.isEmpty())
        verify(exactly = 0) { eventService.trackAsync(any(), any(), any()) }
    }

    // ── INITIAL_PURCHASE ───────────────────────────────────────────────────────

    @Test
    fun `should save subscription keyed by original transaction and activate user on INITIAL_PURCHASE`() {
        linkedUser()
        val expiry = TEST_NOW.plusSeconds(86400).toEpochMilli()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE", expirationAtMs = expiry))

        val sub = savedSubscriptions.single()
        assertEquals("orig-tx-1", sub.revenueCatSubscriptionId)
        assertEquals("premium_monthly", sub.productId)
        assertTrue(sub.autoRenew)
        assertEquals(StatusUpdate(1L, SubscriptionStatus.ACTIVE, Instant.ofEpochMilli(expiry)), lastUpdate())
        verify { eventService.trackAsync(1L, "subscription_started", any()) }
    }

    @Test
    fun `should never write subscription fields through a whole-user save`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `should set user TRIAL and track trial_started when period_type is TRIAL`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE", periodType = "TRIAL"))

        assertEquals(SubscriptionStatus.TRIAL, lastUpdate().status)
        assertTrue(savedSubscriptions.single().isTrial)
        verify { eventService.trackAsync(1L, "trial_started", any()) }
    }

    @Test
    fun `should update existing row when INITIAL_PURCHASE row already exists`() {
        val user = linkedUser()
        every { subscriptionRepository.findByRevenueCatSubscriptionId("orig-tx-1") } returns
            Optional.of(createSubscription(user, id = 7L))

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE"))

        assertEquals(7L, savedSubscriptions.single().id)
    }

    @Test
    fun `should still activate user when product_id is missing`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "INITIAL_PURCHASE", productId = null))

        assertTrue(savedSubscriptions.isEmpty())
        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
    }

    // ── RENEWAL / UNCANCELLATION / NON_RENEWING_PURCHASE ───────────────────────

    @Test
    fun `should extend existing subscription on RENEWAL`() {
        val user = linkedUser()
        every { subscriptionRepository.findByRevenueCatSubscriptionId("orig-tx-1") } returns
            Optional.of(createSubscription(user, id = 7L, status = SubscriptionStatus.EXPIRED))
        val newExpiry = TEST_NOW.plusSeconds(30L * 86400).toEpochMilli()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "RENEWAL", expirationAtMs = newExpiry))

        val sub = savedSubscriptions.single()
        assertEquals(7L, sub.id)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(Instant.ofEpochMilli(newExpiry), sub.expiresAt)
        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
        verify { eventService.trackAsync(1L, "subscription_renewed", any()) }
    }

    @Test
    fun `should convert trial to ACTIVE on RENEWAL with NORMAL period`() {
        linkedUser(status = SubscriptionStatus.TRIAL)

        subscriptionService.handleRevenueCatWebhook(webhook(type = "RENEWAL", periodType = "NORMAL"))

        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
        assertFalse(savedSubscriptions.single().isTrial)
    }

    @Test
    fun `should restore autoRenew on UNCANCELLATION`() {
        val user = linkedUser(status = SubscriptionStatus.CANCELLED)
        every { subscriptionRepository.findByRevenueCatSubscriptionId("orig-tx-1") } returns
            Optional.of(createSubscription(user, status = SubscriptionStatus.CANCELLED).also { it.autoRenew = false })

        subscriptionService.handleRevenueCatWebhook(webhook(type = "UNCANCELLATION"))

        assertTrue(savedSubscriptions.single().autoRenew)
        assertNull(savedSubscriptions.single().cancelledAt)
        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
    }

    @Test
    fun `should save non renewing purchase with autoRenew false`() {
        linkedUser()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "NON_RENEWING_PURCHASE"))

        assertFalse(savedSubscriptions.single().autoRenew)
        assertEquals(SubscriptionStatus.ACTIVE, lastUpdate().status)
    }

    // ── CANCELLATION ───────────────────────────────────────────────────────────

    @Test
    fun `should keep paid period on CANCELLATION with future expiry`() {
        val user = linkedUser(status = SubscriptionStatus.ACTIVE)
        every { subscriptionRepository.findByRevenueCatSubscriptionId("orig-tx-1") } returns
            Optional.of(createSubscription(user))
        val expiry = TEST_NOW.plusSeconds(86400).toEpochMilli()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "CANCELLATION", expirationAtMs = expiry))

        assertEquals(StatusUpdate(1L, SubscriptionStatus.CANCELLED, Instant.ofEpochMilli(expiry)), lastUpdate())
        assertFalse(savedSubscriptions.single().autoRenew)
        assertNotNull(savedSubscriptions.single().cancelledAt)
    }

    @Test
    fun `should expire user on CANCELLATION with past expiry like a refund`() {
        linkedUser(status = SubscriptionStatus.ACTIVE)
        val past = TEST_NOW.minusSeconds(60).toEpochMilli()

        subscriptionService.handleRevenueCatWebhook(webhook(type = "CANCELLATION", expirationAtMs = past))

        assertEquals(SubscriptionStatus.EXPIRED, lastUpdate().status)
    }

    // ── EXPIRATION ─────────────────────────────────────────────────────────────

    @Test
    fun `should expire subscription and user on EXPIRATION`() {
        val expiredAtMs = TEST_NOW.minusSeconds(10).toEpochMilli()
        val user = linkedUser(status = SubscriptionStatus.CANCELLED, expiresAt = Instant.ofEpochMilli(expiredAtMs))
        every { subscriptionRepository.findByRevenueCatSubscriptionId("orig-tx-1") } returns
            Optional.of(createSubscription(user))

        subscriptionService.handleRevenueCatWebhook(webhook(type = "EXPIRATION", expirationAtMs = expiredAtMs))

        assertEquals(SubscriptionStatus.EXPIRED, savedSubscriptions.single().status)
        assertEquals(StatusUpdate(1L, SubscriptionStatus.EXPIRED, null), lastUpdate())
        verify { eventService.trackAsync(1L, "subscription_expired", any()) }
    }

    @Test
    fun `should not downgrade user on stale EXPIRATION when newer access exists`() {
        linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = TEST_NOW.plusSeconds(30L * 86400))

        subscriptionService.handleRevenueCatWebhook(
            webhook(type = "EXPIRATION", expirationAtMs = TEST_NOW.minusSeconds(86400).toEpochMilli())
        )

        assertTrue(statusUpdates.isEmpty())
    }

    // ── no-op / sync-triggering events ─────────────────────────────────────────

    @Test
    fun `should not change state on PRODUCT_CHANGE alias or unknown types`() {
        linkedUser()

        listOf("PRODUCT_CHANGE", "SUBSCRIBER_ALIAS", "SOMETHING_NEW").forEach { type ->
            subscriptionService.handleRevenueCatWebhook(webhook(type = type))
        }

        assertTrue(statusUpdates.isEmpty())
        assertTrue(savedSubscriptions.isEmpty())
    }

    @Test
    fun `BILLING_ISSUE keeps access and pushes a payment reminder`() {
        linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = TEST_NOW.plusSeconds(86400))

        subscriptionService.handleRevenueCatWebhook(webhook(type = "BILLING_ISSUE"))

        assertTrue(statusUpdates.isEmpty())
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                1L, any(), any(), mapOf("type" to SubscriptionService.BILLING_ISSUE_PUSH_TYPE), any(), any()
            )
        }
        verify { eventService.trackAsync(1L, "subscription_billing_issue", any()) }
    }

    @Test
    fun `BILLING_ISSUE push failure does not fail the webhook`() {
        linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = TEST_NOW.plusSeconds(86400))
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("FCM down")

        subscriptionService.handleRevenueCatWebhook(webhook(type = "BILLING_ISSUE"))

        assertEquals(1, receipts.size)
    }

    @Test
    fun `BILLING_ISSUE redelivery does not push twice`() {
        linkedUser()
        every { processedEvents.existsById("event-billing_issue") } returnsMany listOf(false, true)

        repeat(2) { subscriptionService.handleRevenueCatWebhook(webhook(type = "BILLING_ISSUE")) }

        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `TRANSFER re-syncs both the source and the target user`() {
        val source = createUser(id = 1L, revenueCatUserId = "1", subscriptionStatus = SubscriptionStatus.ACTIVE,
            expiresAt = TEST_NOW.plusSeconds(86400))
        val target = createUser(id = 2L, revenueCatUserId = "2")
        every { userRepository.findByRevenueCatUserId("1") } returns Optional.of(source)
        every { userRepository.findByRevenueCatUserId("2") } returns Optional.of(target)
        every { userRepository.findById(1L) } returns Optional.of(source)
        every { userRepository.findById(2L) } returns Optional.of(target)
        val targetExpiry = Instant.ofEpochMilli(TEST_NOW.plusSeconds(86400).toEpochMilli())
        every { revenueCatClient.fetchEntitlementState("1") } returns inactiveState(hasHistory = true)
        every { revenueCatClient.fetchEntitlementState("2") } returns activeState(expiresAt = targetExpiry)

        subscriptionService.handleRevenueCatWebhook(transferWebhook(from = listOf("1"), to = listOf("2")))

        assertTrue(StatusUpdate(1L, SubscriptionStatus.EXPIRED, null) in statusUpdates)
        assertTrue(StatusUpdate(2L, SubscriptionStatus.ACTIVE, targetExpiry) in statusUpdates)
        assertEquals(listOf("event-transfer"), receipts.map { it.eventId })
    }

    @Test
    fun `TRANSFER ignores anonymous and unknown ids`() {
        subscriptionService.handleRevenueCatWebhook(
            transferWebhook(from = listOf("\$RCAnonymousID:abc"), to = listOf("999"))
        )

        assertTrue(statusUpdates.isEmpty())
        verify(exactly = 0) { revenueCatClient.fetchEntitlementState(any()) }
        assertEquals(1, receipts.size)
    }

    // ── syncFromRevenueCat ─────────────────────────────────────────────────────

    @Nested
    inner class Sync {

        @Test
        fun `activates FREE user whose purchase the store knows about`() {
            unlinkedUser()
            val expiry = TEST_NOW.plus(Duration.ofDays(30))
            every { revenueCatClient.fetchEntitlementState("1") } returns activeState(expiresAt = expiry)

            val outcome = subscriptionService.syncFromRevenueCat(1L)

            assertEquals(SyncOutcome.UPDATED, outcome)
            assertEquals(StatusUpdate(1L, SubscriptionStatus.ACTIVE, expiry), lastUpdate())
            assertEquals(listOf(1L to "1"), links)
        }

        @Test
        fun `maps store trial to TRIAL and auto-renew off to CANCELLED`() {
            unlinkedUser()
            val expiry = TEST_NOW.plus(Duration.ofDays(7))
            every { revenueCatClient.fetchEntitlementState("1") } returns activeState(expiresAt = expiry, isTrial = true)
            subscriptionService.syncFromRevenueCat(1L)
            assertEquals(SubscriptionStatus.TRIAL, lastUpdate().status)

            every { revenueCatClient.fetchEntitlementState("1") } returns activeState(expiresAt = expiry, willRenew = false)
            subscriptionService.syncFromRevenueCat(1L)
            assertEquals(SubscriptionStatus.CANCELLED, lastUpdate().status)
        }

        @Test
        fun `expires store-derived status the store no longer backs`() {
            linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = TEST_NOW.plus(Duration.ofDays(3)))
            every { revenueCatClient.fetchEntitlementState("1") } returns inactiveState(hasHistory = true)

            assertEquals(SyncOutcome.UPDATED, subscriptionService.syncFromRevenueCat(1L))
            assertEquals(StatusUpdate(1L, SubscriptionStatus.EXPIRED, null), lastUpdate())
        }

        @Test
        fun `leaves FREE user alone and does not link when store has no history`() {
            unlinkedUser()
            every { revenueCatClient.fetchEntitlementState("1") } returns inactiveState(hasHistory = false)

            assertEquals(SyncOutcome.UNCHANGED, subscriptionService.syncFromRevenueCat(1L))
            assertTrue(statusUpdates.isEmpty())
            assertTrue(links.isEmpty())
        }

        @Test
        fun `never downgrades when the store is unreachable`() {
            linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = TEST_NOW.plus(Duration.ofDays(3)))
            every { revenueCatClient.fetchEntitlementState("1") } returns null

            assertEquals(SyncOutcome.UNAVAILABLE, subscriptionService.syncFromRevenueCat(1L))
            assertTrue(statusUpdates.isEmpty())
        }

        @Test
        fun `reports unchanged when state already matches`() {
            val expiry = TEST_NOW.plus(Duration.ofDays(30))
            linkedUser(status = SubscriptionStatus.ACTIVE, expiresAt = expiry)
            every { revenueCatClient.fetchEntitlementState("1") } returns activeState(expiresAt = expiry)

            assertEquals(SyncOutcome.UNCHANGED, subscriptionService.syncFromRevenueCat(1L))
            assertTrue(statusUpdates.isEmpty())
        }

        @Test
        fun `reports unknown user`() {
            assertEquals(SyncOutcome.UNKNOWN_USER, subscriptionService.syncFromRevenueCat(42L))
        }
    }

    // ── reconcile ──────────────────────────────────────────────────────────────

    @Test
    fun `reconcile syncs each user and expires lapsed statuses`() {
        unlinkedUser()
        every { revenueCatClient.fetchEntitlementState("1") } returns activeState(expiresAt = TEST_NOW.plus(Duration.ofDays(30)))
        every { userRepository.expireLapsedSubscriptions(any(), any(), any()) } returns 3

        val report = subscriptionService.reconcile(listOf(1L, 2L), pause = Duration.ZERO)

        assertEquals(2, report.checked)
        assertEquals(mapOf(SyncOutcome.UPDATED to 1, SyncOutcome.UNKNOWN_USER to 1), report.outcomes)
        assertEquals(3, report.expired)
        verify {
            userRepository.expireLapsedSubscriptions(
                any(),
                SubscriptionService.STORE_DERIVED_STATUSES,
                SubscriptionStatus.EXPIRED
            )
        }
    }

    @Test
    fun `reconcile only expires lapsed statuses when store API is not configured`() {
        every { revenueCatClient.isConfigured } returns false

        val report = subscriptionService.reconcile(listOf(1L), pause = Duration.ZERO)

        assertTrue(report.outcomes.isEmpty())
        verify(exactly = 0) { revenueCatClient.fetchEntitlementState(any()) }
        verify { userRepository.expireLapsedSubscriptions(any(), any(), any()) }
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun linkedUser(
        status: SubscriptionStatus = SubscriptionStatus.FREE,
        expiresAt: Instant? = null,
    ): User {
        val user = createUser(revenueCatUserId = "1", subscriptionStatus = status, expiresAt = expiresAt)
        every { userRepository.findByRevenueCatUserId("1") } returns Optional.of(user)
        every { userRepository.findById(1L) } returns Optional.of(user)
        return user
    }

    private fun unlinkedUser(
        status: SubscriptionStatus = SubscriptionStatus.FREE,
        expiresAt: Instant? = null,
    ): User {
        val user = createUser(revenueCatUserId = null, subscriptionStatus = status, expiresAt = expiresAt)
        every { userRepository.findById(1L) } returns Optional.of(user)
        return user
    }

    private fun activeState(expiresAt: Instant?, isTrial: Boolean = false, willRenew: Boolean = true) =
        StoreEntitlementState(
            hasPurchaseHistory = true,
            isActive = true,
            expiresAt = expiresAt,
            productId = "premium_monthly",
            isTrial = isTrial,
            willRenew = willRenew,
        )

    private fun inactiveState(hasHistory: Boolean) = StoreEntitlementState(
        hasPurchaseHistory = hasHistory,
        isActive = false,
        expiresAt = null,
        productId = null,
        isTrial = false,
        willRenew = false,
    )

    private fun createUser(
        id: Long = 1L,
        revenueCatUserId: String? = "1",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        expiresAt: Instant? = null,
    ): User = User(
        id = id,
        email = "test@example.com",
        name = "Test User",
        revenueCatUserId = revenueCatUserId,
        subscriptionStatus = subscriptionStatus,
        subscriptionExpiresAt = expiresAt,
        currentStreak = 0,
        longestStreak = 0,
    )

    private fun createSubscription(
        user: User,
        id: Long = 1L,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ): Subscription = Subscription(
        id = id,
        user = user,
        revenueCatSubscriptionId = "orig-tx-1",
        productId = "premium_monthly",
        status = status,
        startedAt = TEST_NOW.minusSeconds(3600),
        expiresAt = TEST_NOW.plusSeconds(86400),
    )

    private fun webhook(
        type: String,
        appUserId: String = "1",
        aliases: List<String>? = null,
        productId: String? = "premium_monthly",
        periodType: String = "NORMAL",
        expirationAtMs: Long? = TEST_NOW.plusSeconds(86400).toEpochMilli(),
    ): RevenueCatWebhookEvent = RevenueCatWebhookEvent(
        api_version = "1.0",
        event = RevenueCatEvent(
            id = "event-${type.lowercase()}",
            type = type,
            app_user_id = appUserId,
            aliases = aliases,
            product_id = productId,
            period_type = periodType,
            purchased_at_ms = TEST_NOW.toEpochMilli(),
            expiration_at_ms = expirationAtMs,
            original_transaction_id = "orig-tx-1",
        ),
    )

    /** Shaped like RevenueCat's TRANSFER payload: no app_user_id, only both sides. */
    private fun transferWebhook(from: List<String>, to: List<String>) = RevenueCatWebhookEvent(
        api_version = "1.0",
        event = RevenueCatEvent(
            id = "event-transfer",
            type = "TRANSFER",
            transferred_from = from,
            transferred_to = to,
        ),
    )
}
