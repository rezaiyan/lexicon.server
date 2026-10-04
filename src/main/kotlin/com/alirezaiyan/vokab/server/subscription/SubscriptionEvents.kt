package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.DomainEvent
import java.time.Instant

/*
 * Published by SubscriptionService while applying a RevenueCat webhook; listeners run after the
 * webhook's transaction commits (analytics records them, notification pushes the billing issue).
 */

/** How an activation came about, as RevenueCat reports it. */
enum class Activation { INITIAL_PURCHASE, RENEWAL, UNCANCELLATION, NON_RENEWING_PURCHASE }

data class SubscriptionActivated(
    val userId: Long,
    val productId: String?,
    val isTrial: Boolean,
    val activation: Activation,
    override val occurredAt: Instant,
) : DomainEvent

data class SubscriptionCancelled(
    val userId: Long,
    val productId: String?,
    val reason: String?,
    override val occurredAt: Instant,
) : DomainEvent

data class SubscriptionExpired(
    val userId: Long,
    val productId: String?,
    override val occurredAt: Instant,
) : DomainEvent

/** Renewal payment failed; access continues through the store's grace period. */
data class SubscriptionBillingIssue(
    val userId: Long,
    val productId: String?,
    override val occurredAt: Instant,
) : DomainEvent
