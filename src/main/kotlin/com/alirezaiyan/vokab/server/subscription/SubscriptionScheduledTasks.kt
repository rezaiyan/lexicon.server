package com.alirezaiyan.vokab.server.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class SubscriptionScheduledTasks(
    private val subscriptionService: SubscriptionService,
) {
    /** Catches missed RevenueCat webhooks and expires lapsed statuses before notification planning. */
    @Scheduled(cron = "0 10 0 * * *")          // 00:10 UTC nightly (before notification refresh)
    fun reconcileSubscriptions() {
        try {
            val report = subscriptionService.reconcile(ReconcileScope.LINKED)
            logger.info { "Subscription reconcile complete: $report" }
        } catch (e: Exception) {
            logger.error(e) { "Error in subscription reconcile" }
        }
    }
}
