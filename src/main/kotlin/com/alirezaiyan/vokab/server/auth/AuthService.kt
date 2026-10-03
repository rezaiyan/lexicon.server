package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.UserDto
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.notification.NotificationCategory
import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.shared.UserSignedInEvent
import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.user.UserPlatformRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import com.google.firebase.auth.FirebaseAuth
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.admin.AppConfigService
import com.alirezaiyan.vokab.server.analytics.EventService
import com.alirezaiyan.vokab.server.user.GeoLocationService
import com.alirezaiyan.vokab.server.subscription.RevenueCatClient
import com.alirezaiyan.vokab.server.user.UserDataPurger

private val logger = KotlinLogging.logger {}

private const val GRANT_REASON_TEST_EMAIL = "test_email"
private const val REFRESH_TOKEN_BYTES = 32

/**
 * User authentication (Google via Firebase, Sign in with Apple, CI), token rotation, logout and
 * account deletion.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: RS256JwtTokenProvider,
    private val refreshTokenHashService: RefreshTokenHashService,
    private val appleIdTokenVerifier: AppleIdTokenVerifier,
    private val firebaseIdTokenVerifier: FirebaseIdTokenVerifier,
    private val userPlatformRepository: UserPlatformRepository,
    private val userDataPurger: UserDataPurger,
    private val pushNotificationService: PushNotificationService,
    private val revenueCatClient: RevenueCatClient,
    private val appProperties: AppProperties,
    private val appConfigService: AppConfigService,
    private val auditLogService: AuditLogService,
    private val eventService: EventService,
    private val domainEventPublisher: DomainEventPublisher,
    private val geoLocationService: GeoLocationService,
    private val userAccessCache: UserAccessCache,
    private val clock: Clock,
) {

    /** Request metadata recorded on every interactive sign-in. */
    private data class SignInContext(
        val provider: SignInProvider,
        val platform: String?,
        val appVersion: String?,
        val ipAddress: String?,
    )

    private enum class SignInProvider(val auditName: String, val eventName: String) {
        GOOGLE("Google", "google"),
        APPLE("Apple", "apple"),
    }

    /**
     * Authenticates with a Firebase ID token from Google Sign-In. Finds the user by Google id,
     * links by email, or creates a new one.
     *
     * @throws AuthRejectedException if the token is invalid or carries no email
     */
    @Transactional
    fun authenticateWithGoogle(
        idToken: String,
        platform: String? = null,
        appVersion: String? = null,
        ipAddress: String? = null,
    ): AuthResponse {
        val claims = firebaseIdTokenVerifier.verify(idToken)
            ?: throw AuthRejectedException("Invalid Firebase ID token")
        val email = claims.email
            ?: throw AuthRejectedException("Email not found in Firebase token")

        logger.info { "Authenticating user" }
        val user = findOrCreateGoogleUser(claims, email)
        return completeSignIn(user, SignInContext(SignInProvider.GOOGLE, platform, appVersion, ipAddress))
    }

    /**
     * Authenticates with an Apple ID token. The verified token `sub` is the only identity trusted;
     * [appleUserId] from the client is accepted solely as a consistency check.
     * When the user hides their email, a stable fallback address derived from `sub` is used.
     *
     * @param fullName only sent by Apple on the very first sign-in
     * @throws AuthRejectedException if the token is invalid or doesn't match [appleUserId]
     */
    @Transactional
    fun authenticateWithApple(
        idToken: String,
        fullName: String?,
        appleUserId: String?,
        platform: String? = null,
        appVersion: String? = null,
        ipAddress: String? = null,
    ): AuthResponse {
        val claims = appleIdTokenVerifier.verify(idToken)
            ?: throw AuthRejectedException("Invalid Apple ID token")
        if (appleUserId != null && appleUserId != claims.subject) {
            logger.warn { "Apple sign-in rejected: client user identifier does not match token subject" }
            throw AuthRejectedException("Invalid Apple ID token")
        }

        val appleId = claims.subject
        val email = claims.email ?: hiddenAppleEmail(appleId)

        logger.info { "Authenticating user with Apple: appleId=$appleId" }
        val user = findOrCreateAppleUser(appleId, email, fullName, emailHidden = claims.email == null)
        return completeSignIn(user, SignInContext(SignInProvider.APPLE, platform, appVersion, ipAddress))
    }

    /**
     * Authenticates the dedicated CI test user without OAuth (the endpoint is gated by a shared
     * secret header).
     *
     * @param premium true (default) applies the test-user premium grant (the CI email must be on
     *   the test-email list); false clears grant and subscription so non-premium gating can be tested.
     */
    @Transactional
    fun authenticateForCi(premium: Boolean = true, platform: String? = null, appVersion: String? = null): AuthResponse {
        val email = appProperties.ciAuth.testEmail
        logger.info { "CI authentication for test user (premium=$premium)" }

        val now = Instant.now(clock)
        val user = userRepository.findByEmail(email).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: User(email = email, name = "CI Test User", lastLoginAt = now)
                .also { logger.info { "Creating new CI test user" } }

        val savedUser = userRepository.save(if (premium) applyTestUserPremiumAccess(user) else stripPremiumAccess(user))
        val userId = savedUser.requireId()
        recordPlatform(userId, platform, appVersion)
        val response = issueTokens(savedUser, userId)

        logger.info { "CI test user authenticated: userId=$userId" }
        auditLogService.logLogin(userId, savedUser.email, "CI", null, null)
        return response
    }

    /**
     * Rotates tokens: issues a new access + refresh token and shortens the old refresh token to a
     * grace window, so a client that crashes before persisting the new pair can retry.
     *
     * @throws AuthRejectedException if the refresh token is unknown, revoked or expired
     */
    @Transactional
    fun refreshAccessToken(refreshToken: String): AuthResponse {
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)
        val tokenEntity = refreshTokenRepository.findByTokenHash(lookupHash)
            .orElseThrow { AuthRejectedException("Refresh token not found") }

        if (tokenEntity.revoked) throw AuthRejectedException("Refresh token has been revoked")
        if (!tokenEntity.expiresAt.isAfter(Instant.now(clock))) throw AuthRejectedException("Refresh token has expired")

        val user = tokenEntity.user
        val userId = user.requireId()
        val response = issueTokens(user, userId)

        val graceUntil = Instant.now(clock).plusMillis(appProperties.jwt.refreshTokenGracePeriodMs)
        tokenEntity.expiresAt = graceUntil
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

        notifyDevicesToClearData(
            userId = userId,
            title = "Signed Out",
            body = "You have been signed out from all devices. Please sign in again.",
            type = "sign_out",
        )

        refreshTokenRepository.revokeAllByUser(user)
        auditLogService.logLogoutAll(userId, user.email, null)
    }

    /**
     * Permanently deletes the account: third-party identities (Firebase, RevenueCat), devices are
     * told to clear local data, then every owned row and finally the user row.
     *
     * Apple needs no call here — Apple notifies us via webhook when a user revokes consent.
     *
     * @throws IllegalArgumentException if the user doesn't exist
     */
    @Transactional
    fun deleteAccount(userId: Long) {
        logger.info { "Deleting account for user: $userId" }
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        // Delete the Firebase Auth account so the user cannot sign back in silently.
        user.googleId?.let { googleId ->
            runCatching { FirebaseAuth.getInstance().deleteUser(googleId) }
                .onSuccess { logger.info { "✅ Firebase Auth user deleted for: $userId" } }
                .onFailure { logger.warn(it) { "Failed to delete Firebase Auth user, continuing with deletion" } }
        }

        // GDPR: RevenueCat is a processor of this user's purchase data.
        user.revenueCatUserId?.let { rcUserId ->
            if (revenueCatClient.deleteSubscriber(rcUserId)) {
                logger.info { "✅ RevenueCat subscriber deleted for: $userId" }
            }
        }

        notifyDevicesToClearData(
            userId = userId,
            title = "Account Deleted",
            body = "Your account has been permanently deleted. Please restart the app.",
            type = "account_deleted",
        )

        val purged = userDataPurger.purge(userId)
        logger.info { "Purged data for userId=$userId: ${purged.filterValues { it > 0 }}" }

        // The audit log has no FK to users, so this record survives the deletion.
        auditLogService.logAccountDeletion(userId, user.email, null)
        userRepository.delete(user)
        userAccessCache.evict(userId)
        logger.info { "✅ Account deleted successfully for userId=$userId" }
    }

    // ── Sign-in pipeline ─────────────────────────────────────────────────────────

    /** Shared tail of every interactive sign-in: persist, record, issue tokens, publish events. */
    private fun completeSignIn(user: User, ctx: SignInContext): AuthResponse {
        val isNewUser = user.id == null
        val savedUser = userRepository.save(user)
        val userId = savedUser.requireId()
        recordPlatform(userId, ctx.platform, ctx.appVersion)

        val response = issueTokens(savedUser, userId)

        logger.info { "✅ User authenticated with ${ctx.provider.auditName}: userId=$userId" }
        auditLogService.logLogin(userId, savedUser.email, ctx.provider.auditName, ctx.ipAddress, null)

        val country = ctx.ipAddress?.let { ip ->
            geoLocationService.updateUserCountry(userId, ip, isNewUser)
            geoLocationService.resolveCountry(ip)
        }
        if (isNewUser) {
            eventService.trackAsync(userId, "signup_completed", mapOf("provider" to ctx.provider.eventName))
            domainEventPublisher.publish(
                UserSignedUpEvent(
                    userId = userId,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = ctx.provider.eventName,
                    platform = ctx.platform,
                    country = country,
                )
            )
        } else {
            domainEventPublisher.publish(
                UserSignedInEvent(
                    userId = userId,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = ctx.provider.eventName,
                    platform = ctx.platform,
                    country = country,
                )
            )
        }
        return response
    }

    /** Issues an RS256 access token plus an opaque refresh token, storing only the refresh token's hash. */
    private fun issueTokens(user: User, userId: Long): AuthResponse {
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

    private fun findOrCreateGoogleUser(token: FirebaseIdClaims, email: String): User {
        val now = Instant.now(clock)
        val name = token.name ?: email
        val user = userRepository.findByGoogleId(token.uid).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: userRepository.findByEmail(email).orElse(null)?.let { existing ->
                logger.info { "Linking Google account to existing userId=${existing.id}" }
                existing.also {
                    it.googleId = token.uid
                    it.name = name
                    it.recordLogin(now)
                }
            }
            ?: User(email = email, name = name, googleId = token.uid, lastLoginAt = now)
                .also { logger.info { "Creating new user" } }
        return applyTestUserPremiumAccess(user)
    }

    /**
     * Apple id is the primary key for lookup. Email linking only happens when Apple shared the real
     * address — a fallback address must never match another account. A returning user keeps their
     * stored email: Apple omitting the claim must not replace a real address with the fallback.
     */
    private fun findOrCreateAppleUser(appleId: String, email: String, fullName: String?, emailHidden: Boolean): User {
        val now = Instant.now(clock)
        val user = userRepository.findByAppleId(appleId).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: (if (emailHidden) null else userRepository.findByEmail(email).orElse(null))?.let { existing ->
                logger.info { "Linking Apple account to userId=${existing.id}" }
                existing.also {
                    it.appleId = appleId
                    if (fullName != null) it.name = fullName
                    it.recordLogin(now)
                }
            }
            ?: User(email = email, name = fullName ?: email.substringBefore("@"), appleId = appleId, lastLoginAt = now)
                .also { logger.info { "Creating new user with Apple (emailHidden=$emailHidden)" } }
        return applyTestUserPremiumAccess(user)
    }

    private fun User.recordLogin(now: Instant) {
        lastLoginAt = now
        updatedAt = now
    }

    private fun hiddenAppleEmail(appleId: String): String =
        "apple_${appleId.replace(".", "_").replace(" ", "_")}@apple.hidden"

    private fun recordPlatform(userId: Long, platform: String?, appVersion: String?) {
        if (platform.isNullOrBlank()) return
        val platformEnum = when (platform.trim().lowercase()) {
            "android" -> Platform.ANDROID
            "ios" -> Platform.IOS
            "web" -> Platform.WEB
            else -> {
                logger.warn { "Unknown platform value '$platform' for userId=$userId — skipping" }
                return
            }
        }
        val resolvedVersion = appVersion?.takeIf { it.isNotBlank() && it != "unknown" }
        runCatching {
            userPlatformRepository.insertPlatformIfAbsent(userId, platformEnum.name, resolvedVersion)
            userPlatformRepository.touchPlatform(userId, platformEnum.name, resolvedVersion)
        }
            .onFailure { logger.warn(it) { "Failed to record platform '$platformEnum' for userId=$userId" } }
    }

    /** Best-effort push telling every device to wipe local data; never blocks the caller's flow. */
    private fun notifyDevicesToClearData(userId: Long, title: String, body: String, type: String) {
        runCatching {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = title,
                body = body,
                data = mapOf(
                    "type" to type,
                    "action" to "clear_local_data",
                    "clear_daily_insights" to "true",
                ),
                category = NotificationCategory.SYSTEM,
            )
        }
            .onSuccess { logger.info { "Sent $type notification to ${it.size} devices" } }
            .onFailure { logger.warn(it) { "Failed to send $type notification, continuing" } }
    }

    // ── Premium grants ───────────────────────────────────────────────────────────

    private fun isTestUser(email: String): Boolean = email in appConfigService.getTestEmails()

    /**
     * Keeps a test user's premium grant in line with the test-email list on every login:
     * on the list -> 100-year `test_email` grant; removed from the list -> that grant is revoked.
     * Only `test_email` grants are revoked; `legacy_grant`/`manual` grants are left alone.
     * Grants live in their own columns, so they never touch store subscription state.
     */
    private fun applyTestUserPremiumAccess(user: User): User {
        if (isTestUser(user.email)) {
            logger.info { "Granting premium access to test user: userId=${user.id}" }
            val farFuture = Instant.now(clock).plusSeconds(100L * 365 * 24 * 60 * 60)
            return withGrant(user, farFuture, GRANT_REASON_TEST_EMAIL)
        }
        if (user.premiumGrantReason == GRANT_REASON_TEST_EMAIL) {
            logger.info { "Revoking test premium grant (no longer a test email): userId=${user.id}" }
            return withGrant(user, null, null)
        }
        return user
    }

    /**
     * Explicitly removes premium access for non-premium CI tests: clears any grant and sets the
     * subscription to FREE, so the feature-access endpoint returns hasPremiumAccess=false.
     */
    private fun stripPremiumAccess(user: User): User {
        logger.info { "Stripping premium access for non-premium CI test: userId=${user.id}" }
        return withSubscription(withGrant(user, null, null), SubscriptionStatus.FREE, null)
    }

    /**
     * Grant and subscription columns are `updatable = false` (see User), so a later `save(user)`
     * won't persist them for an existing row — write them with targeted updates here. New users
     * (id == null) get them through the INSERT.
     */
    private fun withGrant(user: User, until: Instant?, reason: String?): User {
        user.id?.let { userRepository.updateGrant(it, until, reason, Instant.now(clock)) }
        user.mirrorGrant(until, reason)
        return user
    }

    private fun withSubscription(user: User, status: SubscriptionStatus, expiresAt: Instant?): User {
        user.id?.let { userRepository.updateSubscription(it, status, expiresAt, Instant.now(clock)) }
        user.mirrorSubscription(status, expiresAt)
        return user
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
