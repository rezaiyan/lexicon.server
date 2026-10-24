package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val CHALLENGE_BYTES = 32

/** Bounds memory when the public sign-in options endpoint is hammered (it is also rate-limited per IP). */
private const val MAX_PENDING = 50_000L

/**
 * Pending passkey challenges, single use and short-lived. Kept in memory: the server runs as one
 * instance, and a restart only costs an in-flight sign-in a retry.
 */
@Component
class PasskeyChallenges(
    appProperties: AppProperties,
    private val clock: Clock,
) {
    enum class Ceremony { REGISTRATION, SIGN_IN }

    /** What a challenge was issued for. [userHandle] is the handle the creation options carried. */
    data class Pending(val ceremony: Ceremony, val userId: Long?, val userHandle: String?, val expiresAt: Instant)

    private val ttl = Duration.ofSeconds(appProperties.passkey.challengeTtlSeconds)
    private val random = SecureRandom()

    // Caffeine's own expiry only frees memory; validity is checked against the injected clock
    private val pending: Cache<String, Pending> = Caffeine.newBuilder()
        .maximumSize(MAX_PENDING)
        .expireAfterWrite(ttl.multipliedBy(2))
        .build()

    /** A new base64url challenge for [ceremony]. */
    fun issue(ceremony: Ceremony, userId: Long? = null, userHandle: String? = null): String {
        val challenge = base64Url(ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
        pending.put(challenge, Pending(ceremony, userId, userHandle, Instant.now(clock).plus(ttl)))
        return challenge
    }

    /**
     * Removes [challenge] and returns what it was issued for, or null when it is unknown, already
     * used, expired, or was issued for another ceremony or user.
     */
    fun consume(challenge: ByteArray?, ceremony: Ceremony, userId: Long? = null): Pending? {
        if (challenge == null) return null
        val entry = pending.asMap().remove(base64Url(challenge)) ?: return null
        val valid = entry.ceremony == ceremony && entry.userId == userId && Instant.now(clock).isBefore(entry.expiresAt)
        return entry.takeIf { valid }
    }
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
