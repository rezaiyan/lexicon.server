package com.alirezaiyan.vokab.server.shared

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.context.annotation.Configuration
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Duration

/** Most buckets kept at once; past it the least used go (an evicted bucket comes back full). */
private const val MAX_BUCKETS = 100_000L

/** Leading bytes of an IPv6 address that form its /64 network, what one subscriber usually holds. */
private const val IPV6_PREFIX_BYTES = 8

/** A bucket idle this long is dropped; never shorter than twice the longest refill window. */
private val MIN_BUCKET_IDLE: Duration = Duration.ofHours(2)

@Configuration
class RateLimitConfig(private val appProperties: AppProperties) {

    /**
     * Per-user and per-IP buckets. Bounded and expiring, so a flood of new IPs or users can't grow
     * memory without limit; an idle bucket only goes once its window would have refilled it anyway.
     */
    private val cache: Cache<String, Bucket> = Caffeine.newBuilder()
        .maximumSize(MAX_BUCKETS)
        .expireAfterAccess(bucketIdleTimeout(appProperties))
        .build()

    /** All unauthenticated onboarding AI calls together, from any IP (see [getOnboardingGlobalBucket]). */
    private val onboardingGlobal: Bucket by lazy {
        val limits = appProperties.rateLimit
        Bucket.builder()
            .addLimit(
                Bandwidth.builder().capacity(limits.onboardingGlobalPerHour)
                    .refillGreedy(limits.onboardingGlobalPerHour, Duration.ofHours(1)).build(),
            )
            .addLimit(
                Bandwidth.builder().capacity(limits.onboardingGlobalPerDay)
                    .refillGreedy(limits.onboardingGlobalPerDay, Duration.ofDays(1)).build(),
            )
            .build()
    }

    /** Failed admin key attempts from every IP together (see [getAdminAuthGlobalBucket]). */
    private val adminAuthGlobal: Bucket by lazy {
        val failures = appProperties.rateLimit.adminFailuresGlobalPerHour
        Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(failures).refillIntervally(failures, Duration.ofHours(1)).build())
            .build()
    }

    /** Text AI calls (insight, translation, suggestions) per user: `app.rate-limit.ai-calls` per window. */
    fun getAiBucket(userId: String): Bucket =
        cache.get(userId) { windowBucket(appProperties.rateLimit.aiCalls) }

    /** Photo extraction calls per user: `app.rate-limit.photo-calls` per window (each call costs more). */
    fun getImageProcessingBucket(userId: String): Bucket =
        cache.get("image_$userId") { windowBucket(appProperties.rateLimit.photoCalls) }

    /**
     * Refused AI instructions per user. Only refusals consume; an empty bucket pauses the user's
     * instructions until the window ends (plain AI requests keep working).
     */
    fun getInstructionRejectionBucket(userId: Long): Bucket =
        cache.get("instruction_rejections_$userId") {
            val rejections = appProperties.aiInstructions.maxRejections
            val window = Duration.ofMinutes(appProperties.aiInstructions.rejectionWindowMinutes)
            val limit = Bandwidth.builder().capacity(rejections).refillIntervally(rejections, window).build()
            Bucket.builder().addLimit(limit).build()
        }

    /** [calls] tokens that all come back at once at the end of each `app.rate-limit.window-minutes`. */
    private fun windowBucket(calls: Long): Bucket {
        val window = Duration.ofMinutes(appProperties.rateLimit.windowMinutes)
        val limit = Bandwidth.builder().capacity(calls).refillIntervally(calls, window).build()
        return Bucket.builder().addLimit(limit).build()
    }

    /** Support chat messages per user: 10 per minute, enough to chat, too few to flood the admin. */
    fun getSupportBucket(userId: Long): Bucket =
        cache.get("support_$userId") {
            val limit = Bandwidth.builder().capacity(10).refillIntervally(10, Duration.ofMinutes(1)).build()
            Bucket.builder().addLimit(limit).build()
        }

    /**
     * Get rate limit bucket for subscription sync (calls RevenueCat's API)
     * Limit: 2 syncs per minute per user
     */
    fun getSubscriptionSyncBucket(userId: String): Bucket {
        val key = "subscription_sync_$userId"
        return cache.get(key) {
            val limit = Bandwidth.builder().capacity(2).refillIntervally(2, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }

    /**
     * Sign-in attempts (Google, Apple, CI token, MCP sign-in) per IP: `app.rate-limit.auth-per-minute`.
     * Roomy enough for many users behind one carrier NAT; every attempt also needs a valid ID token.
     */
    fun getAuthBucket(ipAddress: String): Bucket {
        val perMinute = appProperties.rateLimit.authPerMinute
        return cache.get("auth_${ipKey(ipAddress)}") { perMinuteBucket(perMinute) }
    }

    /**
     * Failed admin key attempts (dashboard sign-in and `X-Admin-Key`) per IP: 5 per 15 minutes.
     * Only failures consume; an empty bucket locks the IP out of admin auth until it refills.
     */
    fun getAdminAuthBucket(ipAddress: String): Bucket =
        cache.get("admin_auth_${ipKey(ipAddress)}") {
            val limit = Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(15)).build()
            Bucket.builder().addLimit(limit).build()
        }

    /**
     * Failed admin key attempts from all IPs together: `app.rate-limit.admin-failures-global-per-hour`.
     * Only failures consume; while it is empty admin key auth is refused for everyone, so guessing
     * from many addresses can't go faster than this.
     */
    fun getAdminAuthGlobalBucket(): Bucket = adminAuthGlobal

    /** Token refreshes per IP: `app.rate-limit.refresh-per-minute` (an app start may refresh). */
    fun getRefreshBucket(ipAddress: String): Bucket {
        val perMinute = appProperties.rateLimit.refreshPerMinute
        return cache.get("refresh_${ipKey(ipAddress)}") { perMinuteBucket(perMinute) }
    }

    /** Open MCP client registration (IP-based): 10 per hour, so nobody can fill the client table. */
    fun getMcpClientRegistrationBucket(ipAddress: String): Bucket =
        cache.get("mcp_register_${ipKey(ipAddress)}") {
            val limit = Bandwidth.builder().capacity(10).refillIntervally(10, Duration.ofHours(1)).build()
            Bucket.builder().addLimit(limit).build()
        }

    /** MCP tool calls per learner and app: an abuse ceiling (300 a minute), not a usage limit. */
    fun getMcpCallBucket(key: String): Bucket =
        cache.get("mcp_calls_$key") {
            val limit = Bandwidth.builder().capacity(300).refillGreedy(300, Duration.ofMinutes(1)).build()
            Bucket.builder().addLimit(limit).build()
        }

    /**
     * Get rate limit bucket for public onboarding vocabulary (IP-based)
     * Limit: 5 requests per minute per IP to prevent abuse
     */
    fun getOnboardingBucket(ipAddress: String): Bucket {
        val key = "onboarding_${ipKey(ipAddress)}"
        return cache.get(key) {
            val limit = Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build()
            Bucket.builder()
                .addLimit(limit)
                .build()
        }
    }

    /**
     * Unauthenticated onboarding AI calls from all IPs together: `app.rate-limit.onboarding-global-per-hour`
     * and `-per-day`. Caps the bill when someone rotates IPs around [getOnboardingBucket].
     */
    fun getOnboardingGlobalBucket(): Bucket = onboardingGlobal
}

/** Twice the longest refill window (at least [MIN_BUCKET_IDLE]): expiry never resets a bucket early. */
private fun bucketIdleTimeout(appProperties: AppProperties): Duration = listOf(
    MIN_BUCKET_IDLE,
    Duration.ofMinutes(appProperties.rateLimit.windowMinutes).multipliedBy(2),
    Duration.ofMinutes(appProperties.aiInstructions.rejectionWindowMinutes).multipliedBy(2),
).max()

/** [perMinute] tokens that all come back at the end of each minute. */
private fun perMinuteBucket(perMinute: Long): Bucket = Bucket.builder()
    .addLimit(Bandwidth.builder().capacity(perMinute).refillIntervally(perMinute, Duration.ofMinutes(1)).build())
    .build()

/**
 * The rate-limit key for [ip]: IPv4 as is, IPv6 by its /64 network, because one subscriber
 * usually holds a whole /64 and could otherwise take a fresh bucket per address. Anything that
 * isn't an IP literal is used unchanged; nothing is ever resolved through DNS.
 */
internal fun ipKey(ip: String): String {
    if (':' !in ip) return ip
    // Brackets make getByName parse an IPv6 literal or fail, never look a name up; ::ffff:a.b.c.d comes back as IPv4
    val address = runCatching { InetAddress.getByName("[${ip.substringBefore('%')}]") }.getOrNull()
    if (address !is Inet6Address) return address?.hostAddress ?: ip
    val prefix = address.address.copyOf(IPV6_PREFIX_BYTES).joinToString("") { "%02x".format(it) }
    return "$prefix::/64"
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
