package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.analytics.NotificationOpenedEvent
import com.alirezaiyan.vokab.server.subscription.Activation
import com.alirezaiyan.vokab.server.subscription.SubscriptionActivated
import com.alirezaiyan.vokab.server.subscription.SubscriptionBillingIssue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class NotificationEventListenersTest {

    private lateinit var engagementService: NotificationEngagementService
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var listeners: NotificationEventListeners

    @BeforeEach
    fun setUp() {
        engagementService = mockk()
        pushNotificationService = mockk()
        listeners = NotificationEventListeners(engagementService, pushNotificationService)
    }

    @Test
    fun `notification open is recorded against the log`() {
        every { engagementService.recordOpen(1L, 99L) } returns true

        listeners.on(NotificationOpenedEvent(userId = 1L, notificationLogId = 99L, occurredAt = AT))

        verify(exactly = 1) { engagementService.recordOpen(1L, 99L) }
    }

    @Test
    fun `billing issue pushes a system notification tagged billing_issue`() {
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) } returns emptyList()

        listeners.on(SubscriptionBillingIssue(userId = 3L, productId = "annual", occurredAt = AT))

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 3L,
                title = any(),
                body = any(),
                data = mapOf("type" to NotificationEventListeners.BILLING_ISSUE_PUSH_TYPE),
                imageUrl = null,
                category = NotificationCategory.SYSTEM,
            )
        }
    }

    @Test
    fun `subscription change sends a silent subscription_updated push`() {
        every { pushNotificationService.sendSilentToUser(any(), any()) } returns emptyList()

        listeners.onSubscriptionChange(
            SubscriptionActivated(userId = 5L, productId = "annual", isTrial = false, Activation.RENEWAL, occurredAt = AT)
        )

        verify(exactly = 1) {
            pushNotificationService.sendSilentToUser(
                5L,
                mapOf("type" to NotificationEventListeners.SUBSCRIPTION_UPDATED_PUSH_TYPE),
            )
        }
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-04T10:00:00Z")
    }
}
