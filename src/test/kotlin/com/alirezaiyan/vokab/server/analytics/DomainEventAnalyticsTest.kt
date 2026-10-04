package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.subscription.Activation
import com.alirezaiyan.vokab.server.subscription.SubscriptionActivated
import com.alirezaiyan.vokab.server.subscription.SubscriptionBillingIssue
import com.alirezaiyan.vokab.server.subscription.SubscriptionCancelled
import com.alirezaiyan.vokab.server.subscription.SubscriptionExpired
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainEventAnalyticsTest {

    private lateinit var eventService: EventService
    private lateinit var analytics: DomainEventAnalytics

    @BeforeEach
    fun setUp() {
        eventService = mockk()
        every { eventService.record(any(), any(), any(), any(), any(), any()) } returns Unit
        analytics = DomainEventAnalytics(eventService)
    }

    @Test
    fun `sign up is recorded as signup_completed with the provider`() {
        analytics.on(UserSignedUpEvent(userId = 7L, name = "Ada", email = "ada@example.com", provider = "google", occurredAt = AT))

        verify { eventService.record(7L, "signup_completed", mapOf("provider" to "google"), AT) }
    }

    @Test
    fun `initial trial purchase is recorded as trial_started`() {
        analytics.on(activated(Activation.INITIAL_PURCHASE, isTrial = true))

        verify { eventService.record(7L, "trial_started", mapOf("product_id" to "annual", "is_trial" to "true"), AT) }
    }

    @Test
    fun `initial paid purchase is recorded as subscription_started`() {
        analytics.on(activated(Activation.INITIAL_PURCHASE, isTrial = false))

        verify { eventService.record(7L, "subscription_started", mapOf("product_id" to "annual", "is_trial" to "false"), AT) }
    }

    @Test
    fun `renewal is recorded as subscription_renewed`() {
        analytics.on(activated(Activation.RENEWAL, isTrial = false))

        verify { eventService.record(7L, "subscription_renewed", any(), AT) }
    }

    @Test
    fun `uncancellation and non renewing purchases are not recorded`() {
        analytics.on(activated(Activation.UNCANCELLATION, isTrial = false))
        analytics.on(activated(Activation.NON_RENEWING_PURCHASE, isTrial = false))

        verify(exactly = 0) { eventService.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation is recorded with product and reason`() {
        analytics.on(SubscriptionCancelled(7L, "annual", "UNSUBSCRIBE", AT))

        verify {
            eventService.record(7L, "subscription_cancelled", mapOf("product_id" to "annual", "reason" to "UNSUBSCRIBE"), AT)
        }
    }

    @Test
    fun `expiration and billing issue are recorded`() {
        analytics.on(SubscriptionExpired(7L, null, AT))
        analytics.on(SubscriptionBillingIssue(7L, "monthly", AT))

        verify { eventService.record(7L, "subscription_expired", mapOf("product_id" to ""), AT) }
        verify { eventService.record(7L, "subscription_billing_issue", mapOf("product_id" to "monthly"), AT) }
    }

    private fun activated(activation: Activation, isTrial: Boolean) =
        SubscriptionActivated(userId = 7L, productId = "annual", isTrial = isTrial, activation = activation, occurredAt = AT)

    private companion object {
        val AT: Instant = Instant.parse("2026-10-04T10:00:00Z")
    }
}
