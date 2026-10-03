package com.alirezaiyan.vokab.server.auth

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Expired refresh tokens can never be used again; without this the table only grows. */
@Service
class RefreshTokenCleanup(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {
    /** @return number of deleted tokens */
    @Transactional
    fun purgeExpired(): Int = refreshTokenRepository.deleteExpiredTokens(Instant.now(clock))
}
