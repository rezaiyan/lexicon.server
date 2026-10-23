package com.alirezaiyan.vokab.server.mcp

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Caps how many words an AI client can permanently delete per learner: [WORDS_PER_HOUR] an hour
 * across all their apps. The general MCP call limit (300 calls a minute, 100 ids each) is far too
 * loose for an irreversible action; a runaway or hijacked agent could wipe a whole vocabulary in
 * seconds. A learner clearing a few hundred words still gets through within the hour.
 */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpDeleteLimiter {

    private val buckets = object : LinkedHashMap<Long, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bucket>?) = size > MAX_TRACKED_USERS
    }

    /** Takes [count] deletions from [userId]'s allowance, or refuses all of them with a tool error. */
    fun acquire(userId: Long, count: Int) {
        val allowed = bucketFor(userId).tryConsume(count.toLong())
        check(allowed) {
            "Deletion limit reached: at most $WORDS_PER_HOUR words can be deleted per hour through connected apps. " +
                "Try again later, or delete them in the Lexicon app."
        }
    }

    private fun bucketFor(userId: Long): Bucket = synchronized(buckets) {
        buckets.getOrPut(userId) {
            val limit = Bandwidth.builder()
                .capacity(WORDS_PER_HOUR)
                .refillGreedy(WORDS_PER_HOUR, Duration.ofHours(1))
                .build()
            Bucket.builder().addLimit(limit).build()
        }
    }

    internal companion object {
        const val WORDS_PER_HOUR = 500L
        const val MAX_TRACKED_USERS = 10_000
    }
}
