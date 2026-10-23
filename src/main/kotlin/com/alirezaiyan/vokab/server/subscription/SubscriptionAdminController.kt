package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/** Admin-only: requires X-Admin-Key (SecurityConfig guards the admin path prefix). */
@RestController
@RequestMapping("/admin/subscriptions")
class SubscriptionAdminController(
    private val subscriptionService: SubscriptionService,
) {
    /**
     * Re-syncs premium state from RevenueCat.
     *
     * - `scope=linked` (default): users already linked to RevenueCat — same as the nightly job.
     * - `scope=all`: every active user. Use once to recover purchases whose webhooks never
     *   arrived (users are linked on first sight). Note: RevenueCat creates an empty customer
     *   record for ids it hasn't seen.
     * - `userIds=1,2,3`: just these users.
     */
    @PostMapping("/reconcile")
    fun reconcile(
        @RequestParam(defaultValue = "linked") scope: String,
        @RequestParam(required = false) userIds: List<Long>?,
    ): ResponseEntity<ApiResponse<ReconcileReport>> {
        logger.info { "Admin subscription reconcile: scope=$scope, userIds=$userIds" }
        val report = when {
            !userIds.isNullOrEmpty() -> subscriptionService.reconcile(userIds)
            scope == "linked" -> subscriptionService.reconcile(ReconcileScope.LINKED)
            scope == "all" -> subscriptionService.reconcile(ReconcileScope.ALL)
            else -> throw IllegalArgumentException("scope must be 'linked' or 'all'")
        }
        return ResponseEntity.ok(ApiResponse(success = true, data = report))
    }
}
