package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.FeatureAccessResponse
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
        logger.debug { "userId=${user.id} premium source=$source" }
        return when (source) {
            PremiumSource.STORE -> UserFeatureAccess(
                hasPremiumAccess = true,
                source = source,
                expiresAt = user.subscriptionExpiresAt?.toString(),
                willRenew = user.subscriptionStatus != SubscriptionStatus.CANCELLED,
                isTrial = user.subscriptionStatus == SubscriptionStatus.TRIAL,
            )
            PremiumSource.GRANT -> UserFeatureAccess(
                hasPremiumAccess = true,
                source = source,
                expiresAt = user.premiumGrantUntil?.toString(),
            )
            PremiumSource.NONE -> UserFeatureAccess(hasPremiumAccess = false)
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
)
