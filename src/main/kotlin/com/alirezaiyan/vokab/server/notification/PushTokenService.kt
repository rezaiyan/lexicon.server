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

        pushTokenRepository.insertTokenIfAbsent(userId, token, platform.name, deviceId)
        pushTokenRepository.reassignToken(userId, token, platform.name, deviceId)
        logger.info { "Push token registered for user: $userId, platform: $platform" }
    }

    @Transactional
    fun deactivateToken(token: String) {
        pushTokenRepository.deactivateByToken(token)
        logger.info { "Push token deactivated: ${token.take(20)}..." }
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
