package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.fixedClock
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RefreshTokenCleanupTest {

    @Autowired lateinit var refreshTokenRepository: RefreshTokenRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var em: EntityManager

    private fun token(user: User, expiresAt: java.time.Instant) = refreshTokenRepository.save(
        RefreshToken(tokenHash = "hash-${System.nanoTime()}", user = user, expiresAt = expiresAt)
    )

    @Test
    fun `purgeExpired deletes tokens past their expiry and keeps live ones`() {
        val user = userRepository.save(User(email = "purge-${System.nanoTime()}@example.com", name = "P"))
        val expired = token(user, TEST_NOW.minusSeconds(1))
        val inGraceWindow = token(user, TEST_NOW.plusSeconds(20))
        val live = token(user, TEST_NOW.plusSeconds(86_400))
        em.flush()

        val deleted = RefreshTokenCleanup(refreshTokenRepository, fixedClock()).purgeExpired()
        em.clear()

        assertEquals(1, deleted)
        val remaining = refreshTokenRepository.findAllById(listOf(expired.id!!, inGraceWindow.id!!, live.id!!))
            .map { it.id }.toSet()
        assertEquals(setOf(inGraceWindow.id, live.id), remaining)
    }
}
