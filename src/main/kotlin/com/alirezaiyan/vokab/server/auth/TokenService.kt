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
import java.time.temporal.ChronoUnit
import java.util.UUID

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
    private val userAccessCache: UserAccessCache,
    private val clock: Clock,
) {

    /**
     * Issues an RS256 access token plus an opaque refresh token for a new sign-in, starting a new
     * token family and storing only the refresh token's hash. Runs inside the caller's sign-in
     * transaction.
     */
    fun issue(user: User, userId: Long): AuthResponse = issue(user, userId, UUID.randomUUID().toString()).first

    /**
     * Rotates tokens: issues a new access + refresh token in the same family and retires the
     * presented refresh token.
     *
     * - First use: the token is marked used and its successor recorded.
     * - Reuse within the grace window, counted from the first use (the client crashed before
     *   persisting the new pair): a fresh pair in the same family; the window is not extended.
     * - Reuse after the grace window: treated as theft. The whole family is revoked and the reuse
     *   audited, so both the attacker's and the victim's copies stop working.
     *
     * @throws AuthRejectedException if the refresh token is unknown, revoked, expired or reused
     */
    @Transactional(noRollbackFor = [AuthRejectedException::class])
    fun rotate(refreshToken: String): AuthResponse {
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)
        val tokenEntity = refreshTokenRepository.findForRotationByTokenHash(lookupHash)
            .orElseThrow { AuthRejectedException("Refresh token not found") }
        val now = Instant.now(clock)

        unusableReason(tokenEntity, now)?.let { throw AuthRejectedException(it) }

        val user = tokenEntity.user
        val userId = user.requireId()
        val familyId = tokenEntity.familyId

        val usedAt = tokenEntity.usedAt
        if (usedAt != null && !now.isBefore(usedAt.plusMillis(appProperties.jwt.refreshTokenGracePeriodMs))) {
            revokeReusedFamily(userId, familyId)
        }
        if (!user.active) throw AuthRejectedException("User account is deactivated")

        val (response, successor) = issue(user, userId, familyId)
        if (usedAt == null) tokenEntity.usedAt = now
        tokenEntity.replacedBy = successor.id
        refreshTokenRepository.save(tokenEntity)

        if (usedAt == null) {
            logger.info { "✅ Tokens rotated for userId=$userId" }
        } else {
            logger.warn { "Refresh token re-used within grace window for userId=$userId (client retry)" }
        }
        auditLogService.logRefresh(userId, user.email, familyId, null, null)
        return response
    }

    private fun unusableReason(token: RefreshToken, now: Instant): String? = when {
        token.revoked -> "Refresh token has been revoked"
        !token.expiresAt.isAfter(now) -> "Refresh token has expired"
        else -> null
    }

    /** Reuse after the grace window: revokes the whole family, audits it and rejects the request. */
    private fun revokeReusedFamily(userId: Long, familyId: String): Nothing {
        // Revocation must commit even though the request fails (rotate's noRollbackFor)
        val revoked = refreshTokenRepository.revokeFamily(familyId)
        logger.error { "Refresh token reuse detected for userId=$userId; revoked $revoked tokens in its family" }
        auditLogService.logTokenReuse(userId, familyId, null)
        throw AuthRejectedException("Refresh token has been revoked")
    }

    /** Revokes one of [userId]'s refresh tokens; a token belonging to anyone else is left alone. */
    @Transactional
    fun logout(userId: Long, refreshToken: String) {
        logger.info { "Logging out user: $userId" }
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)
        if (refreshTokenRepository.revokeByTokenHash(lookupHash, userId) == 0) {
            logger.warn { "Logout for userId=$userId matched none of their refresh tokens" }
        }

        userRepository.findById(userId).orElse(null)?.let { user ->
            auditLogService.logLogout(userId, user.email, lookupHash, null)
        }
    }

    /**
     * Revokes every refresh token of the user, invalidates every access token issued so far and
     * tells their devices to clear local data.
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

        revokeAllSessions(user, userId)
        auditLogService.logLogoutAll(userId, user.email, null)
    }

    /**
     * Revokes every refresh token of the user and every access token issued so far, without
     * notifying devices (logout-all, Apple consent revocation).
     */
    @Transactional
    fun revokeAllSessions(user: User, userId: Long) {
        refreshTokenRepository.revokeAllByUser(user)
        revokeAccessTokens(user, userId)
    }

    /**
     * Rejects every access token issued up to now. JWT `iat` has whole-second precision, so the
     * cutoff is truncated to the second: tokens issued later in this same second stay valid rather
     * than a new sign-in right after being rejected.
     */
    private fun revokeAccessTokens(user: User, userId: Long) {
        val cutoff = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)
        userRepository.updateTokensValidAfter(userId, cutoff)
        user.mirrorTokensValidAfter(cutoff)
        userAccessCache.evict(userId)
    }

    private fun issue(user: User, userId: Long, familyId: String): Pair<AuthResponse, RefreshToken> {
        val accessToken = jwtTokenProvider.generateAccessToken(userId, user.email)
        val refreshToken = refreshTokenHashService.generateSecureToken(REFRESH_TOKEN_BYTES)
        val saved = refreshTokenRepository.save(
            RefreshToken(
                tokenHash = refreshTokenHashService.createLookupHash(refreshToken),
                user = user,
                expiresAt = Instant.now(clock).plusMillis(appProperties.jwt.refreshExpirationMs),
                familyId = familyId,
            )
        )
        val response = AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = user.toDto(userId),
        )
        return response to saved
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
