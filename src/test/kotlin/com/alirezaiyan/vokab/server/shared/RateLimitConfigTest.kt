package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RateLimitConfigTest {

    private val config = RateLimitConfig(
        AppProperties(rateLimit = RateLimitProperties(windowMinutes = 5, photoCalls = 2, aiCalls = 3)),
    )

    @Test
    fun `ai bucket allows the configured number of calls per user then rejects`() {
        val bucket = config.getAiBucket("1")

        repeat(3) { bucket.consumeOrThrow() }

        assertThrows<RateLimitExceededException> { bucket.consumeOrThrow() }
    }

    @Test
    fun `photo bucket allows the configured number of calls and is separate from the ai bucket`() {
        val photo = config.getImageProcessingBucket("1")

        repeat(2) { photo.consumeOrThrow() }

        assertThrows<RateLimitExceededException> { photo.consumeOrThrow() }
        assertNotSame(photo, config.getAiBucket("1"))
        config.getAiBucket("1").consumeOrThrow()
    }

    @Test
    fun `users have their own buckets`() {
        repeat(3) { config.getAiBucket("1").consumeOrThrow() }

        config.getAiBucket("2").consumeOrThrow()
    }

    @Test
    fun `a rejected call says when the window resets`() {
        val bucket = config.getAiBucket("1")
        repeat(3) { bucket.consumeOrThrow() }

        val error = assertThrows<RateLimitExceededException> { bucket.consumeOrThrow() }

        val retryAfter = requireNotNull(error.retryAfterSeconds)
        assertTrue(retryAfter in 295..300, "retryAfter=$retryAfter")
        assertEquals("Too many requests. Try again in 5 minutes.", error.message)
    }

    @Test
    fun `the message rounds the wait up to whole minutes`() {
        assertEquals("Too many requests. Try again in 1 minute.", RateLimitExceededException.retryIn(seconds = 20).message)
        assertEquals("Too many requests. Try again in 2 minutes.", RateLimitExceededException.retryIn(seconds = 61).message)
    }
}
