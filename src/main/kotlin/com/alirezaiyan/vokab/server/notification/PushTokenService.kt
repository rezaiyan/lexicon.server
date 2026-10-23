package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class PushTokenService(
    private val pushTokenRepository: PushTokenRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun registerToken(
        userId: Long,
        token: String,
        platform: Platform,
        deviceId: String?
    ) {
        userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        if (pushTokenRepository.insertTokenIfAbsent(userId, token, platform.name, deviceId) == 0) {
            // A device token is unique to one app install, so a token already on file for another
            // account means the device switched accounts: it must move, or the previous account's
            // pushes would keep landing on a device now signed in as someone else. Tokens are
            // high-entropy and never returned by the API, so holding one implies holding the device.
            val previousOwner = pushTokenRepository.findByToken(token).map { it.user.id }.orElse(null)
            if (previousOwner != null && previousOwner != userId) {
                logger.warn { "Push token moved from userId=$previousOwner to userId=$userId, platform: $platform" }
            }
        }
        pushTokenRepository.reassignToken(userId, token, platform.name, deviceId)
        logger.info { "Push token registered for user: $userId, platform: $platform" }
    }

    /** FCM/APNs reported [token] as no longer registered; whoever owns it, it can't receive pushes. */
    @Transactional
    fun deactivateInvalidToken(token: String) {
        val owner = pushTokenRepository.findByToken(token).map { it.user.id }.orElse(null) ?: return
        pushTokenRepository.deactivateByUserIdAndToken(owner, token)
        logger.info { "Push token deactivated after provider rejection: ${token.take(8)}..." }
    }

    /** Deactivates one of the caller's own tokens; another user's token is left alone. */
    @Transactional
    fun deactivateToken(userId: Long, token: String) {
        val updated = pushTokenRepository.deactivateByUserIdAndToken(userId, token)
        logger.info { "Push token deactivation for userId=$userId: $updated row(s)" }
    }

    @Transactional
    fun deactivateAllUserTokens(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        pushTokenRepository.deactivateAllByUser(user)
        logger.info { "All push tokens deactivated for user: $userId" }
    }

    @Transactional(readOnly = true)
    fun getActiveTokensForUser(userId: Long): List<PushToken> {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        return pushTokenRepository.findByUserAndActiveTrue(user)
    }
}
