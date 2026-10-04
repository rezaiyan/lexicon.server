package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserDto
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

private const val REFRESH_TOKEN_BYTES = 32

/** Issues, rotates and revokes the access/refresh token pair. */
@Service
class TokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: RS256JwtTokenProvider,
    private val refreshTokenHashService: RefreshTokenHashService,
    private val userRepository: UserRepository,
    private val appProperties: AppProperties,
    private val auditLogService: AuditLogService,
    private val deviceDataWipe: DeviceDataWipe,
    private val clock: Clock,
) {

    /**
     * Issues an RS256 access token plus an opaque refresh token, storing only the refresh token's
     * hash. Runs inside the caller's sign-in transaction.
     */
    fun issue(user: User, userId: Long): AuthResponse {
        val accessToken = jwtTokenProvider.generateAccessToken(userId, user.email)
        val refreshToken = refreshTokenHashService.generateSecureToken(REFRESH_TOKEN_BYTES)
        refreshTokenRepository.save(
            RefreshToken(
                tokenHash = refreshTokenHashService.createLookupHash(refreshToken),
                user = user,
                expiresAt = Instant.now(clock).plusMillis(appProperties.jwt.refreshExpirationMs),
            )
        )
        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = user.toDto(userId),
        )
    }

    /**
     * Rotates tokens: issues a new access + refresh token and shortens the old refresh token to a
     * grace window, so a client that crashes before persisting the new pair can retry.
     *
     * @throws AuthRejectedException if the refresh token is unknown, revoked or expired
     */
    @Transactional
    fun rotate(refreshToken: String): AuthResponse {
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)
        val tokenEntity = refreshTokenRepository.findByTokenHash(lookupHash)
            .orElseThrow { AuthRejectedException("Refresh token not found") }

        if (tokenEntity.revoked) throw AuthRejectedException("Refresh token has been revoked")
        if (!tokenEntity.expiresAt.isAfter(Instant.now(clock))) throw AuthRejectedException("Refresh token has expired")

        val user = tokenEntity.user
        val userId = user.requireId()
        val response = issue(user, userId)

        tokenEntity.expiresAt = Instant.now(clock).plusMillis(appProperties.jwt.refreshTokenGracePeriodMs)
        refreshTokenRepository.save(tokenEntity)

        logger.info { "✅ Tokens rotated for userId=$userId" }
        auditLogService.logRefresh(userId, user.email, "N/A", null, null)
        return response
    }

    /** Revokes one refresh token. */
    @Transactional
    fun logout(userId: Long, refreshToken: String) {
        logger.info { "Logging out user: $userId" }
        refreshTokenRepository.revokeByTokenHash(refreshTokenHashService.createLookupHash(refreshToken))

        userRepository.findById(userId).orElse(null)?.let { user ->
            auditLogService.logLogout(userId, user.email, refreshToken, null)
        }
    }

    /**
     * Revokes every refresh token of the user and tells their devices to clear local data.
     *
     * @throws IllegalArgumentException if the user doesn't exist
     */
    @Transactional
    fun logoutAll(userId: Long) {
        logger.info { "Logging out all sessions for user: $userId" }
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        deviceDataWipe.notify(
            userId = userId,
            title = "Signed Out",
            body = "You have been signed out from all devices. Please sign in again.",
            type = "sign_out",
        )

        refreshTokenRepository.revokeAllByUser(user)
        auditLogService.logLogoutAll(userId, user.email, null)
    }

    private fun User.toDto(userId: Long): UserDto = UserDto(
        id = userId,
        email = email,
        name = name,
        subscriptionStatus = subscriptionStatus,
        subscriptionExpiresAt = subscriptionExpiresAt?.toString(),
        currentStreak = currentStreak,
        displayAlias = displayAlias,
        profileImageUrl = profileImageUrl,
    )
}
