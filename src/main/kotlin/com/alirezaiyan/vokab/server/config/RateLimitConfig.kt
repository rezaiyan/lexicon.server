package com.alirezaiyan.vokab.server.config

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Configuration
class RateLimitConfig {
    
    private val cache: MutableMap<String, Bucket> = ConcurrentHashMap()
    
    /**
     * Get rate limit bucket for AI endpoints
     * Limit: 10 requests per minute per user
     */
    fun getAiBucket(userId: String): Bucket {
        return cache.computeIfAbsent(userId) {
            val limit = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)))
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }
    
    /**
     * Get rate limit bucket for image processing
     * Limit: 5 images per minute per user (more restrictive due to higher cost)
     */
    fun getImageProcessingBucket(userId: String): Bucket {
        val key = "image_$userId"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)))
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }
    
    /**
     * Get rate limit bucket for subscription sync (calls RevenueCat's API)
     * Limit: 2 syncs per minute per user
     */
    fun getSubscriptionSyncBucket(userId: String): Bucket {
        val key = "subscription_sync_$userId"
        return cache.computeIfAbsent(key) {
            val limit = Bandwidth.classic(2, Refill.intervally(2, Duration.ofMinutes(1)))
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
            val limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)))
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
            val limit = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)))
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
            val limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)))
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }
}

