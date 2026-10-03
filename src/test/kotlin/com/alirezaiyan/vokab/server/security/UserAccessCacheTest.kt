package com.alirezaiyan.vokab.server.security

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.domain.repository.UserAccessView
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.service.AppConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class UserAccessCacheTest {

    private val clock = MutableClock()
    private val userRepository = mockk<UserRepository>()
    private val appConfigService = mockk<AppConfigService> { every { getTestEmails() } returns emptySet() }
    private val cache = UserAccessCache(userRepository, appConfigService, clock)

    private fun access(active: Boolean, email: String = "u@example.com"): UserAccessView = object : UserAccessView {
        override val active = active
        override val email = email
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
    fun `isAllowed when inactive user is a test account returns true`() {
        every { appConfigService.getTestEmails() } returns setOf("ci@example.com")
        every { userRepository.findAccessById(1L) } returns access(active = false, email = "ci@example.com")

        assertTrue(cache.isAllowed(1L))
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
}
