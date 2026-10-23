package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.DomainEvent
import java.time.Instant

/*
 * Published by SubscriptionService while applying a RevenueCat webhook; listeners run after the
 * webhook's transaction commits (analytics records them, notification pushes the billing issue
 * and tells the user's devices to refetch their subscription).
 */

/** Any change to what the user's subscription looks like to the app. */
sealed interface SubscriptionChange : DomainEvent {
    val userId: Long
}

/** How an activation came about, as RevenueCat reports it. */
enum class Activation { INITIAL_PURCHASE, RENEWAL, UNCANCELLATION, NON_RENEWING_PURCHASE }

data class SubscriptionActivated(
    override val userId: Long,
    val productId: String?,
    val isTrial: Boolean,
    val activation: Activation,
    override val occurredAt: Instant,
) : SubscriptionChange

data class SubscriptionCancelled(
    override val userId: Long,
    val productId: String?,
    val reason: String?,
    override val occurredAt: Instant,
) : SubscriptionChange

data class SubscriptionExpired(
    override val userId: Long,
    val productId: String?,
    override val occurredAt: Instant,
) : SubscriptionChange

/** Renewal payment failed; access continues through the store's grace period. */
data class SubscriptionBillingIssue(
    override val userId: Long,
    val productId: String?,
    override val occurredAt: Instant,
) : SubscriptionChange

/** Google Play pause scheduled or started; the subscription resumes on its own at [resumesAt]. */
data class SubscriptionPaused(
    override val userId: Long,
    val productId: String?,
    val resumesAt: Instant?,
    override val occurredAt: Instant,
) : SubscriptionChange
