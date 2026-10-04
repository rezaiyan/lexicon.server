package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.subscription.Activation
import com.alirezaiyan.vokab.server.subscription.SubscriptionActivated
import com.alirezaiyan.vokab.server.subscription.SubscriptionBillingIssue
import com.alirezaiyan.vokab.server.subscription.SubscriptionCancelled
import com.alirezaiyan.vokab.server.subscription.SubscriptionExpired
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * Records server-side domain events as app events, under the names the analytics dashboards use.
 * Runs after the publishing transaction commits; a failed insert leaves the publication incomplete
 * and it is retried, instead of being dropped like the old `@Async trackAsync`.
 */
@Component
class DomainEventAnalytics(private val eventService: EventService) {

    @ApplicationModuleListener
    fun on(event: UserSignedUpEvent) {
        eventService.record(event.userId, "signup_completed", mapOf("provider" to event.provider), event.occurredAt)
    }

    @ApplicationModuleListener
    fun on(event: SubscriptionActivated) {
        val name = when (event.activation) {
            Activation.INITIAL_PURCHASE -> if (event.isTrial) "trial_started" else "subscription_started"
            Activation.RENEWAL -> "subscription_renewed"
            Activation.UNCANCELLATION, Activation.NON_RENEWING_PURCHASE -> return
        }
        val properties = mapOf("product_id" to event.productId.orEmpty(), "is_trial" to event.isTrial.toString())
        eventService.record(event.userId, name, properties, event.occurredAt)
    }

    @ApplicationModuleListener
    fun on(event: SubscriptionCancelled) {
        val properties = mapOf("product_id" to event.productId.orEmpty(), "reason" to event.reason.orEmpty())
        eventService.record(event.userId, "subscription_cancelled", properties, event.occurredAt)
    }

    @ApplicationModuleListener
    fun on(event: SubscriptionExpired) {
        val properties = mapOf("product_id" to event.productId.orEmpty())
        eventService.record(event.userId, "subscription_expired", properties, event.occurredAt)
    }

    @ApplicationModuleListener
    fun on(event: SubscriptionBillingIssue) {
        val properties = mapOf("product_id" to event.productId.orEmpty())
        eventService.record(event.userId, "subscription_billing_issue", properties, event.occurredAt)
    }
}
