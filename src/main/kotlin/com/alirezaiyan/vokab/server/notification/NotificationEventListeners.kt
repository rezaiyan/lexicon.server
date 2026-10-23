package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.analytics.NotificationOpenedEvent
import com.alirezaiyan.vokab.server.subscription.SubscriptionBillingIssue
import com.alirezaiyan.vokab.server.subscription.SubscriptionChange
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Notification side effects of other modules' events; each runs after the publisher commits. */
@Component
class NotificationEventListeners(
    private val notificationEngagementService: NotificationEngagementService,
    private val pushNotificationService: PushNotificationService,
) {

    @ApplicationModuleListener
    fun on(event: NotificationOpenedEvent) {
        notificationEngagementService.recordOpen(event.userId, event.notificationLogId)
    }

    /** Asks the user to fix their payment method before the store's grace period ends. */
    @ApplicationModuleListener
    fun on(event: SubscriptionBillingIssue) {
        pushNotificationService.sendNotificationToUser(
            userId = event.userId,
            title = BILLING_ISSUE_TITLE,
            body = BILLING_ISSUE_BODY,
            data = mapOf("type" to BILLING_ISSUE_PUSH_TYPE),
            category = NotificationCategory.SYSTEM,
        )
    }

    /**
     * Any subscription change from a store webhook: tell the user's devices to refetch it. The push
     * carries no subscription data; the app reads the authoritative state from the API.
     */
    @ApplicationModuleListener
    fun onSubscriptionChange(event: SubscriptionChange) {
        pushNotificationService.sendSilentToUser(event.userId, mapOf("type" to SUBSCRIPTION_UPDATED_PUSH_TYPE))
    }

    companion object {
        const val SUBSCRIPTION_UPDATED_PUSH_TYPE = "subscription_updated"
        const val BILLING_ISSUE_PUSH_TYPE = "billing_issue"
        private const val BILLING_ISSUE_TITLE = "Payment problem"
        private const val BILLING_ISSUE_BODY =
            "We couldn't renew your Lexicon Premium. Update your payment method in the store to keep it."
    }
}
