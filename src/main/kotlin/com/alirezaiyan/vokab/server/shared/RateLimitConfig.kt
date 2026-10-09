package com.alirezaiyan.vokab.server.shared

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Configuration
class RateLimitConfig(private val appProperties: AppProperties) {

    private val cache: MutableMap<String, Bucket> = ConcurrentHashMap()
    
    /** Text AI calls (insight, translation, suggestions) per user: `app.rate-limit.ai-calls` per window. */
    fun getAiBucket(userId: String): Bucket =
        cache.computeIfAbsent(userId) { windowBucket(appProperties.rateLimit.aiCalls) }

    /** Photo extraction calls per user: `app.rate-limit.photo-calls` per window (each call costs more). */
    fun getImageProcessingBucket(userId: String): Bucket =
        cache.computeIfAbsent("image_$userId") { windowBucket(appProperties.rateLimit.photoCalls) }

    /** [calls] tokens that all come back at once at the end of each `app.rate-limit.window-minutes`. */
    private fun windowBucket(calls: Long): Bucket {
        val window = Duration.ofMinutes(appProperties.rateLimit.windowMinutes)
        val limit = Bandwidth.builder().capacity(calls).refillIntervally(calls, window).build()
        return Bucket.builder().addLimit(limit).build()
    }
    
    /**
     * Get rate limit bucket for subscription sync (calls RevenueCat's API)
     * Limit: 2 syncs per minute per user
     */
    fun getSubscriptionSyncBucket(userId: String): Bucket {
        val key = "subscription_sync_$userId"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.builder().capacity(2).refillIntervally(2, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }

    /**
     * Get rate limit bucket for authentication endpoints (IP-based)
     * Limit: 5 login attempts per minute per IP
     */
    fun getAuthBucket(ipAddress: String): Bucket {
        val key = "auth_$ipAddress"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }
    
    /**
     * Get rate limit bucket for token refresh endpoint (IP-based)
     * Limit: 10 refresh requests per minute per IP
     */
    fun getRefreshBucket(ipAddress: String): Bucket {
        val key = "refresh_$ipAddress"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.builder().capacity(10).refillIntervally(10, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }

    /**
     * Get rate limit bucket for public onboarding vocabulary (IP-based)
     * Limit: 5 requests per minute per IP to prevent abuse
     */
    fun getOnboardingBucket(ipAddress: String): Bucket {
        val key = "onboarding_$ipAddress"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }
}


/**
 * Takes one token, or calls [onRejected] and throws [RateLimitExceededException] saying how long
 * until the next token.
 */
fun Bucket.consumeOrThrow(onRejected: () -> Unit = {}) {
    val probe = tryConsumeAndReturnRemaining(1)
    if (probe.isConsumed) return
    onRejected()
    val seconds = (probe.nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
    throw RateLimitExceededException.retryIn(seconds)
}

private const val NANOS_PER_SECOND = 1_000_000_000L
