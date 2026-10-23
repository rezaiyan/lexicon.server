package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Single server-side answer to "is this user premium, and why?".
 *
 * Premium = an active store subscription (RevenueCat webhooks/sync) OR an active grant
 * (test users, comps). The two are stored separately so neither can overwrite the other.
 */
@Service
class FeatureAccessService(
    private val appProperties: AppProperties,
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val clock: Clock
) {

    fun hasActivePremiumAccess(user: User): Boolean = premiumSource(user, Instant.now(clock)) != PremiumSource.NONE

    /** For callers holding only the authenticated id; an unknown user has no access. */
    fun hasActivePremiumAccess(userId: Long): Boolean =
        userRepository.findById(userId).map(::hasActivePremiumAccess).orElse(false)

    /**
     * Store subscription is active: ACTIVE/TRIAL until their expiry (if any); CANCELLED means
     * auto-renew is off — the period is already paid, so access continues until the expiry date.
     */
    fun hasActiveStoreSubscription(user: User, now: Instant = Instant.now(clock)): Boolean {
        val expiresAt = user.subscriptionExpiresAt
        return when (user.subscriptionStatus) {
            SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL -> expiresAt == null || expiresAt.isAfter(now)
            SubscriptionStatus.CANCELLED -> expiresAt != null && expiresAt.isAfter(now)
            SubscriptionStatus.EXPIRED, SubscriptionStatus.FREE -> false
        }
    }

    fun hasActiveGrant(user: User, now: Instant = Instant.now(clock)): Boolean =
        user.premiumGrantUntil?.isAfter(now) == true

    /** Store wins over a grant, so the client can show renewal details and manage buttons. */
    fun premiumSource(user: User, now: Instant): PremiumSource = when {
        hasActiveStoreSubscription(user, now) -> PremiumSource.STORE
        hasActiveGrant(user, now) -> PremiumSource.GRANT
        else -> PremiumSource.NONE
    }

    /**
     * Coarse access level for usage quotas (AI credits). A grant counts as full premium even during
     * a store trial: grants are comps and test users. An unknown user is FREE.
     *
     * A cancelled trial is still a trial: turning auto-renew off moves the status to CANCELLED, but
     * nothing was paid, so it must not unlock the premium allowance (toggling auto-renew would
     * otherwise farm credits).
     */
    fun accessLevel(userId: Long): AccessLevel {
        val user = userRepository.findById(userId).orElse(null) ?: return AccessLevel.FREE
        val now = Instant.now(clock)
        return when {
            hasActiveGrant(user, now) -> AccessLevel.PREMIUM
            !hasActiveStoreSubscription(user, now) -> AccessLevel.FREE
            user.subscriptionStatus == SubscriptionStatus.TRIAL -> AccessLevel.TRIAL
            user.subscriptionStatus == SubscriptionStatus.CANCELLED && isCancelledTrial(user) -> AccessLevel.TRIAL
            else -> AccessLevel.PREMIUM
        }
    }

    /**
     * The cancelled period is a free trial when the user's latest store subscription (as recorded
     * by the webhooks) is a trial whose expiry covers the user's current access. A later paid
     * period (e.g. reported by a store sync before its RENEWAL webhook) extends the user's expiry
     * past the trial row, so a paying user isn't mistaken for a trial. Without any subscription
     * row (webhooks never arrived) the user keeps the paid allowance.
     */
    private fun isCancelledTrial(user: User): Boolean {
        val latest = subscriptionRepository.findByUser(user).maxByOrNull { it.expiresAt ?: Instant.MIN }
        val trialEnd = latest?.expiresAt?.takeIf { latest.isTrial } ?: return false
        val userExpiry = user.subscriptionExpiresAt ?: return false
        return !trialEnd.isBefore(userExpiry)
    }

    /**
     * Flags plus the user's access, read from the database so a subscription change made
     * earlier in the same request (e.g. a store sync) is reflected.
     */
    fun getFeatureAccess(userId: Long): FeatureAccessResponse {
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        return FeatureAccessResponse(
            featureFlags = getClientFeatureFlags(),
            userAccess = getUserFeatureAccess(user),
        )
    }

    /**
     * Get feature flags for client (doesn't include sensitive server config)
     */
    fun getClientFeatureFlags(): ClientFeatureFlags {
        return ClientFeatureFlags(
            pushNotificationsEnabled = appProperties.features.pushNotificationsEnabled
        )
    }

    fun getUserFeatureAccess(user: User): UserFeatureAccess {
        val now = Instant.now(clock)
        val source = premiumSource(user, now)
        // A pause date in the past means it already resumed (or lapsed); RENEWAL clears it anyway.
        val pauseResumesAt = user.subscriptionPauseResumesAt?.takeIf { it.isAfter(now) }?.toString()
        logger.debug { "userId=${user.id} premium source=$source" }
        return when (source) {
            PremiumSource.STORE -> UserFeatureAccess(
                hasPremiumAccess = true,
                source = source,
                expiresAt = user.subscriptionExpiresAt?.toString(),
                willRenew = user.subscriptionStatus != SubscriptionStatus.CANCELLED,
                isTrial = user.subscriptionStatus == SubscriptionStatus.TRIAL,
                pauseResumesAt = pauseResumesAt,
                hasBillingIssue = user.subscriptionBillingIssueAt != null,
            )
            PremiumSource.GRANT -> UserFeatureAccess(
                hasPremiumAccess = true,
                source = source,
                expiresAt = user.premiumGrantUntil?.toString(),
            )
            PremiumSource.NONE -> UserFeatureAccess(hasPremiumAccess = false, pauseResumesAt = pauseResumesAt)
        }
    }
}

/**
 * Feature flags safe to send to client
 */
data class ClientFeatureFlags(
    val pushNotificationsEnabled: Boolean
)

enum class PremiumSource { STORE, GRANT, NONE }

/** What the user pays for, ordered from least to most; drives quotas such as AI credits. */
enum class AccessLevel { FREE, TRIAL, PREMIUM }

/**
 * User's personal feature access status.
 * New fields are additive with defaults, so older app versions keep reading `hasPremiumAccess`.
 */
data class UserFeatureAccess(
    val hasPremiumAccess: Boolean,
    val source: PremiumSource = PremiumSource.NONE,
    /** ISO-8601; store expiry or grant end. null for lifetime purchases. */
    val expiresAt: String? = null,
    val willRenew: Boolean = false,
    val isTrial: Boolean = false,
    /** ISO-8601; set while a Google Play pause is scheduled or in effect. */
    val pauseResumesAt: String? = null,
    /** The store couldn't charge the renewal; access continues through its grace period. */
    val hasBillingIssue: Boolean = false,
)
