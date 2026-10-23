package com.alirezaiyan.vokab.server.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class AuthScheduledTasks(
    private val refreshTokenCleanup: RefreshTokenCleanup,
) {
    @Scheduled(cron = "0 20 0 * * *")          // 00:20 UTC nightly
    fun purgeExpiredRefreshTokens() {
        try {
            val deleted = refreshTokenCleanup.purgeExpired()
            logger.info { "Expired refresh token purge complete: deleted=$deleted" }
        } catch (e: Exception) {
            logger.error(e) { "Error in expired refresh token purge" }
        }
    }
}
