package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.consumeOrThrow
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

/**
 * Limits for `POST /api/v1/notifications/send`, where a signed-in user pushes a message to their own
 * devices (e.g. the app's "send a test notification"). The payload is user-controlled, so it gets the
 * same reserved-key and `type` blocklist as the admin manual push (a forged `subscription_updated` or
 * `account_deleted` would drive account/billing behaviour in the app), bounded sizes, an https-only
 * image, and a small per-user rate limit so it can't be used to hammer FCM/APNs.
 */
@Component
class SelfPushGuard {

    private val buckets = BoundedBuckets(MAX_TRACKED_USERS) {
        val limit = Bandwidth.builder()
            .capacity(SENDS_PER_HOUR)
            .refillGreedy(SENDS_PER_HOUR, Duration.ofHours(1))
            .build()
        Bucket.builder().addLimit(limit).build()
    }

    /** Throws IllegalArgumentException (400) for a bad payload, RateLimitExceededException (429) when over the limit. */
    fun check(userId: Long, request: SendNotificationRequest) {
        validate(request)
        buckets.get(userId.toString()).consumeOrThrow()
    }

    internal fun validate(request: SendNotificationRequest) {
        require(request.title.length <= MAX_TITLE) { "Title must be at most $MAX_TITLE characters" }
        require(request.body.length <= MAX_BODY) { "Body must be at most $MAX_BODY characters" }
        val data = request.data.orEmpty()
        require(data.size <= MAX_DATA_ENTRIES) { "At most $MAX_DATA_ENTRIES data entries" }
        require(data.all { (k, v) -> k.length <= MAX_DATA_KEY && v.length <= MAX_DATA_VALUE }) {
            "Data keys must be at most $MAX_DATA_KEY and values at most $MAX_DATA_VALUE characters"
        }
        val reserved = data.keys.filter { it in ManualPushService.RESERVED_DATA_KEYS }
        require(reserved.isEmpty()) { "Data keys set by the server can't be overridden: ${reserved.joinToString()}" }
        val type = data["type"]
        require(type !in ManualPushService.BLOCKED_TYPES) { "type=$type is reserved for the server" }
        request.imageUrl?.let { url ->
            require(url.length <= MAX_IMAGE_URL && isHttpsUrl(url)) { "imageUrl must be an https URL" }
        }
    }

    private fun isHttpsUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val SENDS_PER_HOUR = 5L
        const val MAX_TRACKED_USERS = 10_000
        const val MAX_TITLE = 200
        const val MAX_BODY = 1000
        const val MAX_DATA_ENTRIES = 10
        const val MAX_DATA_KEY = 64
        const val MAX_DATA_VALUE = 512
        const val MAX_IMAGE_URL = 2048
    }
}

/**
 * Per-key Bucket4j buckets with a size bound: the least recently used key is evicted first, so the
 * map can't grow without limit. An evicted key starts over with a full bucket, which only matters
 * for keys idle long enough to fall out of the most recent [maxEntries].
 */
internal class BoundedBuckets(private val maxEntries: Int, private val newBucket: () -> Bucket) {
    private val buckets = object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String): Bucket = buckets.getOrPut(key, newBucket)
}
