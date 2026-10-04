package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.admin.AppConfigService
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

private const val GRANT_REASON_TEST_EMAIL = "test_email"
private const val TEST_GRANT_SECONDS = 100L * 365 * 24 * 60 * 60

/** Premium access for accounts on the admin-managed test-email list, and for CI tests. */
@Component
class TestAccessPolicy(
    private val userRepository: UserRepository,
    private val appConfigService: AppConfigService,
    private val clock: Clock,
) {

    /**
     * Keeps a test user's premium grant in line with the test-email list on every login:
     * on the list -> 100-year `test_email` grant; removed from the list -> that grant is revoked.
     * Only `test_email` grants are revoked; `legacy_grant`/`manual` grants are left alone.
     * Grants live in their own columns, so they never touch store subscription state.
     */
    fun applyTo(user: User): User {
        if (user.email in appConfigService.getTestEmails()) {
            logger.info { "Granting premium access to test user: userId=${user.id}" }
            return withGrant(user, Instant.now(clock).plusSeconds(TEST_GRANT_SECONDS), GRANT_REASON_TEST_EMAIL)
        }
        if (user.premiumGrantReason == GRANT_REASON_TEST_EMAIL) {
            logger.info { "Revoking test premium grant (no longer a test email): userId=${user.id}" }
            return withGrant(user, null, null)
        }
        return user
    }

    /**
     * Explicitly removes premium access for non-premium CI tests: clears any grant and sets the
     * subscription to FREE, so the feature-access endpoint returns hasPremiumAccess=false.
     */
    fun stripPremium(user: User): User {
        logger.info { "Stripping premium access for non-premium CI test: userId=${user.id}" }
        return withSubscription(withGrant(user, null, null), SubscriptionStatus.FREE, null)
    }

    /**
     * Grant and subscription columns are `updatable = false` (see User), so a later `save(user)`
     * won't persist them for an existing row — write them with targeted updates here. New users
     * (id == null) get them through the INSERT.
     */
    private fun withGrant(user: User, until: Instant?, reason: String?): User {
        user.id?.let { userRepository.updateGrant(it, until, reason, Instant.now(clock)) }
        user.mirrorGrant(until, reason)
        return user
    }

    private fun withSubscription(user: User, status: SubscriptionStatus, expiresAt: Instant?): User {
        user.id?.let { userRepository.updateSubscription(it, status, expiresAt, Instant.now(clock)) }
        user.mirrorSubscription(status, expiresAt)
        return user
    }
}
