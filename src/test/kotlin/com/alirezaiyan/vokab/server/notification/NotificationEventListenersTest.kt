package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.analytics.NotificationOpenedEvent
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

    private companion object {
        val AT: Instant = Instant.parse("2026-10-04T10:00:00Z")
    }
}
