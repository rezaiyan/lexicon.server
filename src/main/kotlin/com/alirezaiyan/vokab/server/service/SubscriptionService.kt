package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationCategory
import com.alirezaiyan.vokab.server.domain.entity.ProcessedWebhookEvent
import com.alirezaiyan.vokab.server.domain.entity.Subscription
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ProcessedWebhookEventRepository
import com.alirezaiyan.vokab.server.domain.repository.SubscriptionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatEvent
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatWebhookEvent
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import com.alirezaiyan.vokab.server.domain.entity.requireId

private val logger = KotlinLogging.logger {}

@Service
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val eventService: EventService,
    private val processedWebhookEventRepository: ProcessedWebhookEventRepository,
    private val revenueCatClient: RevenueCatClient,
    private val pushNotificationService: PushNotificationService,
    private val clock: Clock,
) {

    /**
     * Applies a RevenueCat webhook to our user/subscription state.
     *
     * The mobile client calls `Purchases.logIn(user.id)`, so `app_user_id` is our numeric user id.
     * Events for unknown or anonymous customers are acknowledged and ignored — creating placeholder
     * users would grant premium to an account nobody can sign in to.
     * Each event id is applied once (RevenueCat redelivers until it gets a 2xx).
     */
    @Transactional
    fun handleRevenueCatWebhook(webhook: RevenueCatWebhookEvent) {
        val event = webhook.event
        logger.info { "Processing RevenueCat webhook: type=${event.type}, env=${event.environment}" }

        if (event.type == "TEST") return
        if (processedWebhookEventRepository.existsById(event.id)) {
            logger.info { "Skipping already processed RevenueCat event ${event.id}" }
            return
        }

        if (event.type == "TRANSFER") {
            handleTransfer(event)
            processedWebhookEventRepository.save(ProcessedWebhookEvent(eventId = event.id, eventType = event.type))
            return
        }

        val user = resolveUser(event)
        if (user == null) {
            logger.warn { "RevenueCat webhook for unknown user ids=${event.candidateUserIds}, type=${event.type}" }
        } else {
            applyEvent(user, event)
        }
        processedWebhookEventRepository.save(ProcessedWebhookEvent(eventId = event.id, eventType = event.type))
    }

    private fun applyEvent(user: User, event: RevenueCatEvent) {
        when (event.type) {
            "INITIAL_PURCHASE" -> activate(user, event, autoRenew = true, analyticsEvent = initialPurchaseEvent(event))
            "RENEWAL" -> activate(user, event, autoRenew = true, analyticsEvent = "subscription_renewed")
            "UNCANCELLATION" -> activate(user, event, autoRenew = true, analyticsEvent = null)
            "NON_RENEWING_PURCHASE" -> activate(user, event, autoRenew = false, analyticsEvent = null)
            "CANCELLATION" -> handleCancellation(user, event)
            "EXPIRATION" -> handleExpiration(user, event)
            "BILLING_ISSUE" -> handleBillingIssue(user, event)
            // Entitlement is unchanged by these; RevenueCat follows up with the events above when it is.
            "PRODUCT_CHANGE", "SUBSCRIBER_ALIAS", "SUBSCRIPTION_PAUSED" ->
                logger.info { "RevenueCat ${event.type} for userId=${user.id}, no state change" }
            else -> logger.warn { "Unknown webhook event type: ${event.type}" }
        }
    }

    // ── Store sync (reconciliation path that doesn't depend on webhooks) ─────────────────────

    /**
     * Pulls the customer's entitlements from RevenueCat and makes our user state match.
     * Network/config failures never downgrade — the current state is kept. Premium grants live in
     * separate columns and are unaffected.
     */
    fun syncFromRevenueCat(userId: Long): SyncOutcome {
        val user = userRepository.findById(userId).orElse(null) ?: return SyncOutcome.UNKNOWN_USER
        val appUserId = user.revenueCatUserId ?: user.id.toString()
        val state = revenueCatClient.fetchEntitlementState(appUserId) ?: return SyncOutcome.UNAVAILABLE

        if (state.hasPurchaseHistory && user.revenueCatUserId == null) {
            userRepository.linkRevenueCatUserId(userId, appUserId, Instant.now(clock))
        }

        val target = targetState(user, state) ?: return SyncOutcome.UNCHANGED
        if (target.first == user.subscriptionStatus && target.second == user.subscriptionExpiresAt) {
            return SyncOutcome.UNCHANGED
        }

        userRepository.updateSubscription(userId, target.first, target.second, Instant.now(clock))
        logger.info {
            "RevenueCat sync updated userId=$userId: ${user.subscriptionStatus} -> ${target.first}, expiresAt=${target.second}"
        }
        return SyncOutcome.UPDATED
    }

    /** Status/expiry our DB should hold for this store state, or null to leave it alone. */
    private fun targetState(user: User, state: StoreEntitlementState): Pair<SubscriptionStatus, Instant?>? = when {
        state.isActive -> {
            val status = when {
                state.isTrial -> SubscriptionStatus.TRIAL
                !state.willRenew && state.expiresAt != null -> SubscriptionStatus.CANCELLED
                else -> SubscriptionStatus.ACTIVE
            }
            status to state.expiresAt
        }
        user.subscriptionStatus in STORE_DERIVED_STATUSES -> SubscriptionStatus.EXPIRED to null
        else -> null
    }

    /** Re-syncs every user in [scope]; see [ReconcileScope]. */
    fun reconcile(scope: ReconcileScope): ReconcileReport = reconcile(
        when (scope) {
            ReconcileScope.LINKED -> userRepository.findIdsLinkedToRevenueCat()
            ReconcileScope.ALL -> userRepository.findActiveUserIds()
        }
    )

    /**
     * Re-syncs the given users against RevenueCat, then expires lapsed statuses.
     * Used by the nightly job and the admin backfill.
     */
    fun reconcile(userIds: List<Long>, pause: Duration = Duration.ofMillis(200)): ReconcileReport {
        val outcomes = mutableMapOf<SyncOutcome, Int>()
        if (revenueCatClient.isConfigured) {
            userIds.forEach { id ->
                val outcome = syncFromRevenueCat(id)
                outcomes.merge(outcome, 1, Int::plus)
                if (!pause.isZero) Thread.sleep(pause.toMillis())
            }
        } else {
            logger.warn { "RevenueCat API key not configured; skipping store sync for ${userIds.size} users" }
        }
        val expired = expireLapsedSubscriptions()
        return ReconcileReport(checked = userIds.size, outcomes = outcomes, expired = expired)
    }

    fun expireLapsedSubscriptions(): Int =
        userRepository.expireLapsedSubscriptions(Instant.now(clock), STORE_DERIVED_STATUSES, SubscriptionStatus.EXPIRED)

    // ── Webhook handlers ─────────────────────────────────────────────────────────────────────

    /**
     * Finds the user by a previously linked RevenueCat id, or by our numeric user id
     * (which the client uses as the RevenueCat app user id) and links it on first sight.
     */
    private fun resolveUser(event: RevenueCatEvent): User? =
        event.candidateUserIds.firstNotNullOfOrNull(::resolveCandidate)

    private fun resolveCandidate(candidate: String): User? {
        userRepository.findByRevenueCatUserId(candidate).orElse(null)?.let { return it }

        val user = candidate.toLongOrNull()
            ?.let { userRepository.findById(it).orElse(null) }
            ?: return null

        if (user.revenueCatUserId == null) {
            logger.info { "Linking RevenueCat id to userId=${user.id}" }
            userRepository.linkRevenueCatUserId(user.requireId(), candidate, Instant.now(clock))
            user.mirrorRevenueCatUserId(candidate)
        }
        return user
    }

    /**
     * A restore on another account moved the entitlement. TRANSFER carries no app_user_id, only
     * both sides, so re-sync every known user involved: the source loses premium, the target gains it.
     */
    private fun handleTransfer(event: RevenueCatEvent) {
        val users = event.transferUserIds.mapNotNull(::resolveCandidate).distinctBy { it.id }
        if (users.isEmpty()) {
            logger.warn { "RevenueCat TRANSFER with no known users, ids=${event.transferUserIds}" }
            return
        }
        users.forEach { user ->
            val outcome = syncFromRevenueCat(user.requireId())
            logger.info { "RevenueCat TRANSFER re-synced userId=${user.id}: $outcome" }
        }
    }

    /**
     * Renewal payment failed. RevenueCat keeps the entitlement through the store's grace period,
     * so access doesn't change, but the user should fix their payment method before it lapses.
     * The push is sent after commit so a rolled-back (and later redelivered) event can't notify twice.
     */
    private fun handleBillingIssue(user: User, event: RevenueCatEvent) {
        val userId = user.requireId()
        eventService.trackAsync(userId, "subscription_billing_issue", mapOf("product_id" to event.product_id.orEmpty()))
        afterCommit {
            runCatching {
                pushNotificationService.sendNotificationToUser(
                    userId = userId,
                    title = BILLING_ISSUE_TITLE,
                    body = BILLING_ISSUE_BODY,
                    data = mapOf("type" to BILLING_ISSUE_PUSH_TYPE),
                    category = NotificationCategory.SYSTEM,
                )
            }.onFailure { logger.warn(it) { "Billing issue push failed for userId=$userId" } }
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    private fun activate(user: User, event: RevenueCatEvent, autoRenew: Boolean, analyticsEvent: String?) {
        val expiresAt = event.expiresAt()
        val productId = event.product_id

        if (productId != null) {
            val now = Instant.now(clock)
            val subscription = findSubscription(event)
                ?: Subscription(
                    user = user,
                    revenueCatSubscriptionId = event.subscriptionKey,
                    productId = productId,
                    status = SubscriptionStatus.ACTIVE,
                    startedAt = event.purchased_at_ms?.let { Instant.ofEpochMilli(it) } ?: now,
                )
            subscription.also {
                it.productId = productId
                it.status = SubscriptionStatus.ACTIVE
                it.expiresAt = expiresAt
                it.cancelledAt = null
                it.isTrial = event.isTrial
                it.autoRenew = autoRenew
                it.updatedAt = now
            }
            subscriptionRepository.save(subscription)
        } else {
            logger.warn { "RevenueCat ${event.type} without product_id for userId=${user.id}" }
        }

        val status = if (event.isTrial) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE
        updateUserSubscriptionStatus(user, status, expiresAt)

        if (analyticsEvent != null) {
            eventService.trackAsync(
                user.requireId(),
                analyticsEvent,
                mapOf("product_id" to productId.orEmpty(), "is_trial" to event.isTrial.toString())
            )
        }
        logger.info { "RevenueCat ${event.type} applied for userId=${user.id}, status=$status, expiresAt=$expiresAt" }
    }

    /**
     * CANCELLATION means auto-renew was turned off (or a refund happened). The user keeps
     * premium until [RevenueCatEvent.expiration_at_ms]; [FeatureAccessService] honours that.
     */
    private fun handleCancellation(user: User, event: RevenueCatEvent) {
        val now = Instant.now(clock)
        val expiresAt = event.expiresAt()

        findSubscription(event)?.let { subscription ->
            subscription.status = SubscriptionStatus.CANCELLED
            subscription.cancelledAt = now
            subscription.autoRenew = false
            if (expiresAt != null) subscription.expiresAt = expiresAt
            subscription.updatedAt = now
            subscriptionRepository.save(subscription)
        }

        val status = if (expiresAt != null && expiresAt.isAfter(now)) {
            SubscriptionStatus.CANCELLED
        } else {
            SubscriptionStatus.EXPIRED
        }
        updateUserSubscriptionStatus(user, status, expiresAt)
        eventService.trackAsync(
            user.requireId(),
            "subscription_cancelled",
            mapOf("product_id" to event.product_id.orEmpty(), "reason" to event.cancel_reason.orEmpty())
        )

        logger.info { "Subscription cancelled for userId=${user.id}, access until $expiresAt" }
    }

    private fun handleExpiration(user: User, event: RevenueCatEvent) {
        findSubscription(event)?.let { subscription ->
            subscription.status = SubscriptionStatus.EXPIRED
            subscription.updatedAt = Instant.now(clock)
            subscriptionRepository.save(subscription)
        }

        // Events can arrive out of order; don't let an old subscription's expiry revoke a newer one.
        val eventExpiry = event.expiresAt()
        val userExpiry = user.subscriptionExpiresAt
        if (eventExpiry != null && userExpiry != null && userExpiry.isAfter(eventExpiry)) {
            logger.info { "Ignoring stale EXPIRATION for userId=${user.id}: access runs until $userExpiry" }
            return
        }

        updateUserSubscriptionStatus(user, SubscriptionStatus.EXPIRED, null)
        eventService.trackAsync(
            user.requireId(),
            "subscription_expired",
            mapOf("product_id" to event.product_id.orEmpty())
        )

        logger.info { "Subscription expired for userId=${user.id}" }
    }

    private fun findSubscription(event: RevenueCatEvent): Subscription? =
        subscriptionRepository.findByRevenueCatSubscriptionId(event.subscriptionKey).orElse(null)

    private fun updateUserSubscriptionStatus(user: User, status: SubscriptionStatus, expiresAt: Instant?) {
        userRepository.updateSubscription(user.requireId(), status, expiresAt, Instant.now(clock))
    }

    private fun initialPurchaseEvent(event: RevenueCatEvent): String =
        if (event.isTrial) "trial_started" else "subscription_started"

    private fun RevenueCatEvent.expiresAt(): Instant? = expiration_at_ms?.let { Instant.ofEpochMilli(it) }

    companion object {
        const val BILLING_ISSUE_PUSH_TYPE = "billing_issue"
        private const val BILLING_ISSUE_TITLE = "Payment problem"
        private const val BILLING_ISSUE_BODY =
            "We couldn't renew your Lexicon Premium. Update your payment method in the store to keep it."

        /** Statuses that come from the store (and so may be lapsed/expired by it). */
        val STORE_DERIVED_STATUSES = listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL, SubscriptionStatus.CANCELLED)
    }
}

enum class ReconcileScope {
    /** Users already linked to RevenueCat — the nightly job. */
    LINKED,

    /** Every active user; recovers purchases whose webhooks never arrived. */
    ALL,
}

enum class SyncOutcome { UPDATED, UNCHANGED, UNAVAILABLE, UNKNOWN_USER }

data class ReconcileReport(
    val checked: Int,
    val outcomes: Map<SyncOutcome, Int>,
    val expired: Int,
)
