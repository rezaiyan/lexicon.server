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
    fun `IPv6 addresses in the same 64 network share a bucket`() {
        val limited = RateLimitConfig(AppProperties(rateLimit = RateLimitProperties(authPerMinute = 2)))

        limited.getAuthBucket("2001:db8:1:2::1").consumeOrThrow()
        limited.getAuthBucket("2001:db8:1:2:ffff:ffff:ffff:ffff").consumeOrThrow()

        assertThrows<RateLimitExceededException> { limited.getAuthBucket("2001:db8:1:2::abcd").consumeOrThrow() }
        limited.getAuthBucket("2001:db8:1:3::1").consumeOrThrow()
    }

    @Test
    fun `ip keys group IPv6 by 64 network and leave IPv4 and junk alone`() {
        assertEquals("20010db800010002::/64", ipKey("2001:db8:1:2::5"))
        assertEquals("20010db800010002::/64", ipKey("2001:0db8:0001:0002:aaaa:bbbb:cccc:dddd"))
        assertEquals("203.0.113.7", ipKey("203.0.113.7"))
        assertEquals("203.0.113.7", ipKey("::ffff:203.0.113.7"))
        assertEquals("unknown", ipKey("unknown"))
        assertEquals("not:an:ip", ipKey("not:an:ip"))
    }

    @Test
    fun `the onboarding budget is shared by every IP`() {
        val limited = RateLimitConfig(AppProperties(rateLimit = RateLimitProperties(onboardingGlobalPerHour = 2)))

        repeat(2) { limited.getOnboardingGlobalBucket().consumeOrThrow() }

        assertThrows<RateLimitExceededException> { limited.getOnboardingGlobalBucket().consumeOrThrow() }
        // Per-IP buckets are unaffected
        limited.getOnboardingBucket("203.0.113.1").consumeOrThrow()
    }

    @Test
    fun `the onboarding daily budget applies even with hourly room left`() {
        val limited = RateLimitConfig(
            AppProperties(rateLimit = RateLimitProperties(onboardingGlobalPerHour = 10, onboardingGlobalPerDay = 1)),
        )

        limited.getOnboardingGlobalBucket().consumeOrThrow()

        assertThrows<RateLimitExceededException> { limited.getOnboardingGlobalBucket().consumeOrThrow() }
    }

    @Test
    fun `the message rounds the wait up to whole minutes`() {
        assertEquals("Too many requests. Try again in 1 minute.", RateLimitExceededException.retryIn(seconds = 20).message)
        assertEquals("Too many requests. Try again in 2 minutes.", RateLimitExceededException.retryIn(seconds = 61).message)
    }
}
