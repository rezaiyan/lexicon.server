package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.service.ReconcileReport
import com.alirezaiyan.vokab.server.service.SubscriptionService
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
    private val userRepository: UserRepository,
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
        val ids = when {
            !userIds.isNullOrEmpty() -> userIds
            scope == "all" -> userRepository.findActiveUserIds()
            scope == "linked" -> userRepository.findIdsLinkedToRevenueCat()
            else -> return ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = "scope must be 'linked' or 'all'"))
        }
        logger.info { "Admin subscription reconcile: scope=$scope, users=${ids.size}" }
        return ResponseEntity.ok(ApiResponse(success = true, data = subscriptionService.reconcile(ids)))
    }
}
