package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.RateLimitExceededException
import com.alirezaiyan.vokab.server.shared.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/subscriptions")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val featureAccessService: FeatureAccessService,
    private val rateLimitConfig: RateLimitConfig,
) {

    /**
     * Reconciles the caller's premium state with RevenueCat and returns fresh feature access.
     * The app calls this right after a purchase/restore (and when the store says "subscribed"
     * but the server doesn't), so server-enforced premium doesn't wait on webhook delivery.
     */
    @PostMapping("/sync")
    fun syncWithStore(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<FeatureAccessResponse>> {
        val userId = user.id
        if (!rateLimitConfig.getSubscriptionSyncBucket(userId.toString()).tryConsume(1)) {
            throw RateLimitExceededException("Too many sync requests. Try again shortly.")
        }

        val outcome = subscriptionService.syncFromRevenueCat(userId)
        logger.info { "Subscription sync for userId=$userId: $outcome" }
        return ResponseEntity.ok(ApiResponse(success = true, data = featureAccessService.getFeatureAccess(userId)))
    }
}
