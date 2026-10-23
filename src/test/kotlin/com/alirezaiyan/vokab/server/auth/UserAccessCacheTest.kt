package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.user.UserAccessView
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class UserAccessCacheTest {

    private val clock = MutableClock()
    private val userRepository = mockk<UserRepository>()
    private val cache = UserAccessCache(userRepository, clock)

    private fun access(active: Boolean, tokensValidAfter: Instant? = null): UserAccessView = object : UserAccessView {
        override val active = active
        override val tokensValidAfter = tokensValidAfter
    }

    @Test
    fun `isAllowed when user is active returns true`() {
        every { userRepository.findAccessById(1L) } returns access(active = true)

        assertTrue(cache.isAllowed(1L))
    }

    @Test
    fun `isAllowed when user is inactive or unknown returns false`() {
        every { userRepository.findAccessById(1L) } returns access(active = false)
        every { userRepository.findAccessById(2L) } returns null

        assertFalse(cache.isAllowed(1L))
        assertFalse(cache.isAllowed(2L))
    }

    @Test
    fun `isAllowed within the TTL answers from the cache`() {
        every { userRepository.findAccessById(1L) } returns access(active = true)

        repeat(5) { cache.isAllowed(1L) }
        clock.advance(Duration.ofSeconds(UserAccessCache.TTL_SECONDS - 1))
        cache.isAllowed(1L)

        verify(exactly = 1) { userRepository.findAccessById(1L) }
    }

    @Test
    fun `isAllowed after the TTL sees a deactivation`() {
        every { userRepository.findAccessById(1L) } returns access(active = true)
        assertTrue(cache.isAllowed(1L))

        every { userRepository.findAccessById(1L) } returns access(active = false)
        clock.advance(Duration.ofSeconds(UserAccessCache.TTL_SECONDS + 1))

        assertFalse(cache.isAllowed(1L))
    }

    @Test
    fun `evict makes a deactivation take effect immediately`() {
        every { userRepository.findAccessById(1L) } returns access(active = true)
        assertTrue(cache.isAllowed(1L))

        every { userRepository.findAccessById(1L) } returns null
        cache.evict(1L)

        assertFalse(cache.isAllowed(1L))
    }

    // ── isTokenAllowed (logout-all cutoff) ────────────────────────────────────

    @Test
    fun `isTokenAllowed accepts any token when the user has no cutoff`() {
        every { userRepository.findAccessById(1L) } returns access(active = true)

        assertTrue(cache.isTokenAllowed(1L, TEST_NOW.minusSeconds(3600)))
    }

    @Test
    fun `isTokenAllowed rejects a token issued before the cutoff`() {
        every { userRepository.findAccessById(1L) } returns access(active = true, tokensValidAfter = TEST_NOW)

        assertFalse(cache.isTokenAllowed(1L, TEST_NOW.minusSeconds(1)))
    }

    @Test
    fun `isTokenAllowed accepts a token issued at or after the cutoff`() {
        every { userRepository.findAccessById(1L) } returns access(active = true, tokensValidAfter = TEST_NOW)

        assertTrue(cache.isTokenAllowed(1L, TEST_NOW))
    }

    @Test
    fun `isTokenAllowed rejects a token without iat once a cutoff exists`() {
        every { userRepository.findAccessById(1L) } returns access(active = true, tokensValidAfter = TEST_NOW)

        assertFalse(cache.isTokenAllowed(1L, null))
    }

    @Test
    fun `isTokenAllowed rejects any token of an inactive user`() {
        every { userRepository.findAccessById(1L) } returns access(active = false)

        assertFalse(cache.isTokenAllowed(1L, TEST_NOW))
    }
}
