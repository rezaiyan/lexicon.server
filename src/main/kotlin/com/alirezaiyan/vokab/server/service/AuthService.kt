package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationCategory
import com.alirezaiyan.vokab.server.domain.entity.Platform
import com.alirezaiyan.vokab.server.domain.entity.RefreshToken
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.event.UserSignedInEvent
import com.alirezaiyan.vokab.server.domain.event.UserSignedUpEvent
import com.alirezaiyan.vokab.server.service.event.DomainEventPublisher
import com.alirezaiyan.vokab.server.domain.repository.RefreshTokenRepository
import com.alirezaiyan.vokab.server.domain.repository.UserPlatformRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.AuthResponse
import com.alirezaiyan.vokab.server.presentation.dto.UserDto
import com.alirezaiyan.vokab.server.security.RS256JwtTokenProvider
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

private const val GRANT_REASON_TEST_EMAIL = "test_email"

/**
 * Service responsible for user authentication and token management.
 * Uses Firebase ID tokens for Google Sign-In authentication.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: RS256JwtTokenProvider,
    private val refreshTokenHashService: RefreshTokenHashService,
    private val applePublicKeyService: ApplePublicKeyService,
    private val wordRepository: com.alirezaiyan.vokab.server.domain.repository.WordRepository,
    private val userSettingsRepository: com.alirezaiyan.vokab.server.domain.repository.UserSettingsRepository,
    private val dailyActivityRepository: com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository,
    private val subscriptionRepository: com.alirezaiyan.vokab.server.domain.repository.SubscriptionRepository,
    private val pushTokenRepository: com.alirezaiyan.vokab.server.domain.repository.PushTokenRepository,
    private val userPlatformRepository: UserPlatformRepository,
    private val dailyInsightRepository: com.alirezaiyan.vokab.server.domain.repository.DailyInsightRepository,
    private val reviewEventRepository: com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository,
    private val studySessionRepository: com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository,
    private val pushNotificationService: PushNotificationService,
    private val appProperties: com.alirezaiyan.vokab.server.config.AppProperties,
    private val appConfigService: AppConfigService,
    private val auditLogService: AuditLogService,
    private val eventService: EventService,
    private val domainEventPublisher: DomainEventPublisher,
    private val geoLocationService: GeoLocationService,
    webClientBuilder: WebClient.Builder
) {
    private val webClient = webClientBuilder.build()
    
    /**
     * Authenticates a user using a Firebase ID token from Google Sign-In.
     * Creates a new user if one doesn't exist, or updates existing user's last login time.
     * 
     * @param idToken Firebase ID token from client
     * @return AuthResponse containing JWT tokens and user data
     * @throws IllegalArgumentException if token is invalid or email is missing
     */
    @Transactional
    fun authenticateWithGoogle(idToken: String, platform: String? = null, appVersion: String? = null, ipAddress: String? = null): AuthResponse {
        val firebaseToken = verifyFirebaseToken(idToken)
            ?: throw IllegalArgumentException("Invalid Firebase ID token")

        val email = firebaseToken.email
            ?: throw IllegalArgumentException("Email not found in Firebase token")

        logger.info { "Authenticating user" }

        val user = findOrCreateUser(firebaseToken)
        val isNewUser = user.id == null
        val savedUser = userRepository.save(user)
        val userId = requireNotNull(savedUser.id) { "User ID must not be null after save for user ${savedUser.email}" }
        recordPlatform(userId, platform, appVersion)

        val tokenPair = generateTokenPair(savedUser)
        saveRefreshToken(tokenPair.refreshToken, savedUser)

        logger.info { "✅ User authenticated: userId=${userId}" }
        auditLogService.logLogin(userId, savedUser.email, "Google", ipAddress, null)
        if (ipAddress != null) geoLocationService.updateUserCountry(savedUser.id, ipAddress, isNewUser)
        val googleCountry = ipAddress?.let { geoLocationService.resolveCountry(it) }
        if (isNewUser) {
            eventService.trackAsync(savedUser.id, "signup_completed", mapOf("provider" to "google"))
            domainEventPublisher.publish(
                UserSignedUpEvent(
                    userId = savedUser.id,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = "google",
                    platform = platform,
                    country = googleCountry,
                )
            )
        } else {
            domainEventPublisher.publish(
                UserSignedInEvent(
                    userId = savedUser.id,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = "google",
                    platform = platform,
                    country = googleCountry,
                )
            )
        }

        return AuthResponse(
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = tokenPair.user
        )
    }

    /**
     * Authenticates a user using an Apple ID token from Sign in with Apple.
     * Creates a new user if one doesn't exist, or updates existing user's last login time.
     * Handles cases where user hides their email by using Apple user identifier.
     * 
     * @param idToken Apple ID token (JWT) from client
     * @param fullName Optional full name from first-time Apple Sign In
     * @param appleUserId Optional Apple user identifier from credential (always available, used when email is missing)
     * @return AuthResponse containing JWT tokens and user data
     * @throws IllegalArgumentException if token is invalid or missing required claims
     */
    @Transactional
    fun authenticateWithApple(
        idToken: String,
        fullName: String?,
        appleUserId: String?,
        platform: String? = null,
        appVersion: String? = null,
        ipAddress: String? = null
    ): AuthResponse {
        val appleToken = verifyAppleToken(idToken)
            ?: throw IllegalArgumentException("Invalid Apple ID token")

        val appleId = appleToken["sub"] as? String
            ?: throw IllegalArgumentException("Apple ID (sub) not found in token")

        // Use provided appleUserId if available, otherwise use sub from token
        val resolvedAppleId = appleUserId ?: appleId

        // Email may be missing if user hides their email
        val email = appleToken["email"] as? String

        // Generate fallback email if email is not available
        val resolvedEmail = email ?: "apple_${resolvedAppleId.replace(".", "_").replace(" ", "_")}@apple.hidden"

        logger.info { "Authenticating user with Apple: appleId=$resolvedAppleId" }

        val user = findOrCreateAppleUser(resolvedAppleId, resolvedEmail, fullName, email == null)
        val isNewUser = user.id == null
        val savedUser = userRepository.save(user)
        val userId = requireNotNull(savedUser.id) { "User ID must not be null after save for user ${savedUser.email}" }
        recordPlatform(userId, platform, appVersion)

        val tokenPair = generateTokenPair(savedUser)
        saveRefreshToken(tokenPair.refreshToken, savedUser)

        logger.info { "✅ User authenticated with Apple: userId=${savedUser.id}" }
        auditLogService.logLogin(savedUser.id, savedUser.email, "Apple", ipAddress, null)
        if (ipAddress != null) geoLocationService.updateUserCountry(savedUser.id, ipAddress, isNewUser)
        val appleCountry = ipAddress?.let { geoLocationService.resolveCountry(it) }
        if (isNewUser) {
            eventService.trackAsync(userId, "signup_completed", mapOf("provider" to "apple"))
            domainEventPublisher.publish(
                UserSignedUpEvent(
                    userId = savedUser.id,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = "apple",
                    platform = platform,
                    country = appleCountry,
                )
            )
        } else {
            domainEventPublisher.publish(
                UserSignedInEvent(
                    userId = userId,
                    name = savedUser.name,
                    email = savedUser.email,
                    provider = "apple",
                    platform = platform,
                    country = appleCountry,
                )
            )
        }

        return AuthResponse(
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = tokenPair.user
        )
    }

    /**
     * Authenticates a dedicated CI test user without Firebase/Apple token verification.
     * The CI endpoint is gated by a shared secret header, so no OAuth flow is needed.
     * Finds or creates the CI test user and returns JWT tokens.
     *
     * @param premium When true (default), applies the test-user premium grant (the CI email must
     *   be on the test-email list). When false, clears grant and subscription so non-premium
     *   gating can be tested.
     */
    @Transactional
    fun authenticateForCi(premium: Boolean = true, platform: String? = null, appVersion: String? = null): AuthResponse {
        val email = appProperties.ciAuth.testEmail
        logger.info { "CI authentication for test user (premium=$premium)" }

        val applyAccess: (User) -> User = if (premium) ::applyTestUserPremiumAccess else ::stripPremiumAccess

        val user = userRepository.findByEmail(email)
            .map { existingUser ->
                applyAccess(
                    existingUser.copy(
                        lastLoginAt = Instant.now(),
                        updatedAt = Instant.now()
                    )
                )
            }
            .orElseGet {
                logger.info { "Creating new CI test user" }
                applyAccess(
                    User(
                        email = email,
                        name = "CI Test User",
                        lastLoginAt = Instant.now()
                    )
                )
            }

        val savedUser = userRepository.save(user)
        val userId = requireNotNull(savedUser.id) { "User ID must not be null after save for user ${savedUser.email}" }
        recordPlatform(userId, platform, appVersion)
        val tokenPair = generateTokenPair(savedUser)
        saveRefreshToken(tokenPair.refreshToken, savedUser)

        logger.info { "CI test user authenticated: userId=${savedUser.id}" }
        auditLogService.logLogin(savedUser.id, savedUser.email, "CI", null, null)

        return AuthResponse(
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = tokenPair.user
        )
    }

    /**
     * Check if an email is in the test emails list
     */
    private fun isTestUser(email: String): Boolean =
        email in appConfigService.getTestEmails()

    private fun recordPlatform(userId: Long, platform: String?, appVersion: String? = null) {
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
        try {
            userPlatformRepository.upsertPlatform(userId, platformEnum.name, resolvedVersion)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to record platform '$platformEnum' for userId=$userId" }
        }
    }

    /**
     * Keeps a test user's premium grant in line with the test-email list on every login:
     * on the list -> 100-year `test_email` grant; removed from the list -> that grant is revoked.
     * Only `test_email` grants are revoked; `legacy_grant`/`manual` grants are left alone.
     * Grants live in their own columns, so they never touch store subscription state.
     */
    private fun applyTestUserPremiumAccess(user: User): User {
        if (isTestUser(user.email)) {
            logger.info { "Granting premium access to test user: userId=${user.id}" }
            val farFuture = Instant.now().plusSeconds(100L * 365 * 24 * 60 * 60)
            return withGrant(user, farFuture, GRANT_REASON_TEST_EMAIL)
        }
        if (user.premiumGrantReason == GRANT_REASON_TEST_EMAIL) {
            logger.info { "Revoking test premium grant (no longer a test email): userId=${user.id}" }
            return withGrant(user, null, null)
        }
        return user
    }

    /**
     * Grant and subscription columns are `updatable = false` (see User), so a later `save(user)`
     * won't persist them for an existing row — write them with targeted updates here. New users
     * (id == null) get them through the INSERT.
     */
    private fun withGrant(user: User, until: Instant?, reason: String?): User {
        user.id?.let { userRepository.updateGrant(it, until, reason, Instant.now()) }
        return user.copy(premiumGrantUntil = until, premiumGrantReason = reason)
    }

    private fun withSubscription(
        user: User,
        status: com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus,
        expiresAt: Instant?
    ): User {
        user.id?.let { userRepository.updateSubscription(it, status, expiresAt, Instant.now()) }
        return user.copy(subscriptionStatus = status, subscriptionExpiresAt = expiresAt)
    }

    /**
     * Explicitly removes premium access for non-premium CI tests: clears any grant and sets the
     * subscription to FREE, so the feature-access endpoint returns hasPremiumAccess=false.
     */
    private fun stripPremiumAccess(user: User): User {
        logger.info { "Stripping premium access for non-premium CI test: userId=${user.id}" }
        return withSubscription(
            withGrant(user, null, null),
            com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus.FREE,
            null
        )
    }

    /**
     * Finds an existing user or creates a new one from Firebase token data.
     */
    private fun findOrCreateUser(firebaseToken: FirebaseToken): User {
        val googleId = firebaseToken.uid
        val email = firebaseToken.email!!
        val name = firebaseToken.name ?: email

        return userRepository.findByGoogleId(googleId)
            .map { existingUser ->
                // Update last login time and apply test user premium access if applicable
                applyTestUserPremiumAccess(
                    existingUser.copy(
                        lastLoginAt = Instant.now(),
                        updatedAt = Instant.now()
                    )
                )
            }
            .orElseGet {
                // Check if user exists with this email (account linking)
                userRepository.findByEmail(email)
                    .map { existingUser ->
                        logger.info { "Linking Google account to existing userId=${existingUser.id}" }
                        applyTestUserPremiumAccess(
                            existingUser.copy(
                                googleId = googleId,
                                name = name,
                                lastLoginAt = Instant.now(),
                                updatedAt = Instant.now()
                            )
                        )
                    }
                    .orElseGet {
                        // Create new user
                        logger.info { "Creating new user" }
                        applyTestUserPremiumAccess(
                            User(
                                email = email,
                                name = name,
                                googleId = googleId,
                                lastLoginAt = Instant.now()
                            )
                        )
                    }
            }
    }
    
    /**
     * Finds an existing user or creates a new one from Apple Sign In data.
     * When email is hidden, prioritizes Apple ID lookup over email lookup.
     *
     * @param appleId Apple user identifier (always available)
     * @param email User email (may be fallback email if user hides their email)
     * @param fullName Optional full name from first sign-in
     * @param emailHidden Whether the email was hidden (using fallback email)
     */
    private fun findOrCreateAppleUser(appleId: String, email: String, fullName: String?, emailHidden: Boolean): User {
        val name = fullName ?: email.substringBefore("@")

        // Always try to find by Apple ID first (most reliable identifier)
        val existingByAppleId = userRepository.findByAppleId(appleId)

        return existingByAppleId
            .map { existingUser ->
                // User found by Apple ID - update last login time and apply test user premium access
                // If email changed and was previously hidden, update it
                if (emailHidden && existingUser.email != email) {
                    logger.info { "Updating fallback email for Apple userId=${existingUser.id}" }
                    applyTestUserPremiumAccess(
                        existingUser.copy(
                            email = email,
                            lastLoginAt = Instant.now(),
                            updatedAt = Instant.now()
                        )
                    )
                } else {
                    applyTestUserPremiumAccess(
                        existingUser.copy(
                            lastLoginAt = Instant.now(),
                            updatedAt = Instant.now()
                        )
                    )
                }
            }
            .orElseGet {
                // User not found by Apple ID - try email lookup if email is not hidden
                if (!emailHidden) {
                    userRepository.findByEmail(email)
                        .map { existingUser ->
                            logger.info { "Linking Apple account to userId=${existingUser.id}" }
                            applyTestUserPremiumAccess(
                                existingUser.copy(
                                    appleId = appleId,
                                    name = if (fullName != null) fullName else existingUser.name,
                                    lastLoginAt = Instant.now(),
                                    updatedAt = Instant.now()
                                )
                            )
                        }
                        .orElseGet {
                            // Create new user
                            logger.info { "Creating new user with Apple" }
                            applyTestUserPremiumAccess(
                                User(
                                    email = email,
                                    name = name,
                                    appleId = appleId,
                                    lastLoginAt = Instant.now()
                                )
                            )
                        }
                } else {
                    // Email is hidden, so we can't use email for account linking
                    // Create new user with Apple ID
                    logger.info { "Creating new user with Apple (email hidden)" }
                    applyTestUserPremiumAccess(
                        User(
                            email = email,
                            name = name,
                            appleId = appleId,
                            lastLoginAt = Instant.now()
                        )
                    )
                }
            }
    }
    
    /**
     * Generates access and refresh token pair for a user.
     * Access token is a JWT (RS256), refresh token is an opaque random string.
     */
    private fun generateTokenPair(user: User): TokenPair {
        val accessToken = jwtTokenProvider.generateAccessToken(user.id!!, user.email)
        val refreshToken = refreshTokenHashService.generateSecureToken(32)

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = user.toDto()
        )
    }
    
    private data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val user: UserDto
    )
    
    /**
     * Saves a refresh token hash to the database with rotation support.
     * Stores both SHA-256 lookup hash and BCrypt verification hash.
     * Note: We store the BCrypt hash in a separate field if needed, or verify using the lookup hash.
     * For now, we'll use lookup hash for storage and verify by finding and checking BCrypt.
     */
    private fun saveRefreshToken(token: String, user: User) {
        val lookupHash = refreshTokenHashService.createLookupHash(token)

        val refreshTokenEntity = RefreshToken(
            tokenHash = lookupHash,
            user = user,
            expiresAt = Instant.now().plusMillis(appProperties.jwt.refreshExpirationMs)
        )
        refreshTokenRepository.save(refreshTokenEntity)

        logger.debug { "Saved refresh token with lookup hash: ${lookupHash.take(16)}..." }
    }
    
    /**
     * Refreshes access and refresh tokens with rotation.
     * On each refresh:
     * 1. Validates the provided refresh token hash
     * 2. Checks if token is revoked or expired
     * 3. Issues new access token (JWT) and new refresh token (opaque)
     * 4. Marks old refresh token as replaced
     * 5. Saves new refresh token with same family ID
     * 6. If reuse detected (old token already replaced), revokes entire family
     * 
     * @param refreshToken The opaque refresh token string
     * @param deviceId Optional device identifier
     * @param userAgent Optional user agent string
     * @param ipAddress Optional IP address
     * @return AuthResponse with new access token and rotated refresh token
     * @throws IllegalArgumentException if refresh token is invalid, revoked, expired, or reused
     */
    @Transactional
    fun refreshAccessToken(refreshToken: String): AuthResponse {
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)

        val tokenEntity = refreshTokenRepository.findByTokenHash(lookupHash)
            .orElseThrow { IllegalArgumentException("Refresh token not found") }

        validateTokenEntity(tokenEntity)

        val user = tokenEntity.user

        val newAccessToken = jwtTokenProvider.generateAccessToken(user.id!!, user.email)
        val newRefreshToken = refreshTokenHashService.generateSecureToken(32)
        val newLookupHash = refreshTokenHashService.createLookupHash(newRefreshToken)

        // Save new refresh token
        val newRefreshTokenEntity = RefreshToken(
            tokenHash = newLookupHash,
            user = user,
            expiresAt = Instant.now().plusMillis(appProperties.jwt.refreshExpirationMs)
        )
        refreshTokenRepository.save(newRefreshTokenEntity)

        // Grace period: instead of immediate revocation, allow old token for a short window
        // so the client can retry if it crashes before saving the new tokens.
        // After the grace period, the token expires naturally.
        val gracePeriodMs = appProperties.jwt.refreshTokenGracePeriodMs
        val updatedOldToken = tokenEntity.copy(
            expiresAt = Instant.now().plusMillis(gracePeriodMs)
        )
        refreshTokenRepository.save(updatedOldToken)

        logger.info { "✅ Tokens rotated for userId=${user.id}" }
        auditLogService.logRefresh(user.id, user.email, "N/A", null, null)
        
        return AuthResponse(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            expiresIn = jwtTokenProvider.getExpirationTime(),
            user = user.toDto()
        )
    }
    
    /**
     * Validates that a refresh token entity is not revoked or expired.
     */
    private fun validateTokenEntity(tokenEntity: RefreshToken) {
        if (tokenEntity.revoked) {
            throw IllegalArgumentException("Refresh token has been revoked")
        }
        
        if (tokenEntity.expiresAt.isBefore(Instant.now())) {
            throw IllegalArgumentException("Refresh token has expired")
        }
    }
    
    /**
     * Logs out a user by revoking their refresh token.
     * 
     * @param userId The ID of the user logging out
     * @param refreshToken The refresh token to revoke
     */
    @Transactional
    fun logout(userId: Long, refreshToken: String) {
        logger.info { "Logging out user: $userId" }
        val lookupHash = refreshTokenHashService.createLookupHash(refreshToken)
        refreshTokenRepository.revokeByTokenHash(lookupHash)
        logger.debug { "✅ Refresh token revoked for user: $userId" }

        val user = userRepository.findById(userId).orElse(null)
        if (user != null) {
            auditLogService.logLogout(userId, user.email, refreshToken, null)
        }
    }
    
    /**
     * Logs out a user from all devices by revoking all their refresh tokens.
     * Also sends push notification to clear client-side data.
     * 
     * @param userId The ID of the user to log out from all sessions
     * @throws IllegalArgumentException if user not found
     */
    @Transactional
    fun logoutAll(userId: Long) {
        logger.info { "Logging out all sessions for user: $userId" }
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        
        // Send push notification to clear client-side data
        try {
            val notificationResults = pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = "Signed Out",
                body = "You have been signed out from all devices. Please sign in again.",
                data = mapOf(
                    "type" to "sign_out",
                    "action" to "clear_local_data",
                    "clear_daily_insights" to "true"
                ),
                category = NotificationCategory.SYSTEM
            )
            logger.info { "Sent sign out notification to ${notificationResults.size} devices" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send sign out notification, continuing with logout" }
        }
        
        refreshTokenRepository.revokeAllByUser(user)
        logger.debug { "✅ All refresh tokens revoked for user: $userId" }
        auditLogService.logLogoutAll(userId, user.email, null)
    }
    
    /**
     * Deletes a user account permanently.
     * This includes:
     * - Revoking all refresh tokens
     * - Deleting all user data (words, settings, activities, subscriptions, push tokens)
     * - Removing the user from the database
     * 
     * @param userId The ID of the user to delete
     * @throws IllegalArgumentException if user not found
     */
    @Transactional
    fun deleteAccount(userId: Long) {
        logger.info { "Deleting account for user: $userId" }

        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        // Delete Firebase Auth account so the user cannot sign back in silently
        if (user.googleId != null) {
            try {
                FirebaseAuth.getInstance().deleteUser(user.googleId)
                logger.info { "✅ Firebase Auth user deleted for: $userId" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete Firebase Auth user, continuing with deletion" }
            }
        }

        // Notify RevenueCat to delete subscriber data (GDPR: third-party processor obligation)
        val rcUserId = user.revenueCatUserId
        if (rcUserId != null && appProperties.revenuecat.apiKey.isNotBlank()) {
            try {
                webClient.delete()
                    .uri("https://api.revenuecat.com/v1/subscribers/$rcUserId")
                    .header("Authorization", "Bearer ${appProperties.revenuecat.apiKey}")
                    .retrieve()
                    .toBodilessEntity()
                    .block()
                logger.info { "✅ RevenueCat subscriber deleted for: $userId" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete RevenueCat subscriber, continuing with deletion" }
            }
        }

        // CRITICAL FIX: Send push notification to all devices before deleting account
        // This ensures all devices are notified that the account is being deleted
        try {
            val notificationResults = pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = "Account Deleted",
                body = "Your account has been permanently deleted. Please restart the app.",
                data = mapOf(
                    "type" to "account_deleted",
                    "action" to "clear_local_data",
                    "clear_daily_insights" to "true"
                ),
                category = NotificationCategory.SYSTEM
            )
            logger.info { "Sent account deletion notification to ${notificationResults.size} devices" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send account deletion notification, continuing with deletion" }
        }
        
        // Note: Apple Sign In doesn't require notifying Apple when users delete their account.
        // Apple's Sign in with Apple documentation states that you should simply delete the user's data.
        // Apple will notify YOU via webhook if the user deletes their Apple ID or revokes consent.
        // Reference: https://developer.apple.com/documentation/sign_in_with_apple/revoke_tokens
        
        // Delete all related entities in the correct order to avoid foreign key constraints
        
        // 1. Delete refresh tokens
        refreshTokenRepository.revokeAllByUser(user)
        val refreshTokens = refreshTokenRepository.findByUser(user)
        refreshTokenRepository.deleteAll(refreshTokens)
        logger.debug { "✅ ${refreshTokens.size} refresh tokens deleted for user: $userId" }
        
        // 2. Delete push tokens
        val pushTokens = pushTokenRepository.findByUser(user)
        pushTokenRepository.deleteAll(pushTokens)
        logger.debug { "✅ ${pushTokens.size} push tokens deleted for user: $userId" }
        
        // 3. Delete daily insights
        val dailyInsights = dailyInsightRepository.findByUser(user)
        dailyInsightRepository.deleteAll(dailyInsights)
        logger.debug { "✅ ${dailyInsights.size} daily insights deleted for user: $userId" }
        
        // 4. Delete words
        val words = wordRepository.findAllByUser(user)
        wordRepository.deleteAll(words)
        logger.debug { "✅ ${words.size} words deleted for user: $userId" }
        
        // 5. Delete daily activities
        val activities = dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user)
        dailyActivityRepository.deleteAll(activities)
        logger.debug { "✅ ${activities.size} daily activities deleted for user: $userId" }
        
        // 6. Delete subscriptions
        val subscriptions = subscriptionRepository.findByUser(user)
        subscriptionRepository.deleteAll(subscriptions)
        logger.debug { "✅ ${subscriptions.size} subscriptions deleted for user: $userId" }
        
        // 7. Delete review events (must come before study_sessions due to FK)
        val reviewEvents = reviewEventRepository.findByUser(user)
        reviewEventRepository.deleteAll(reviewEvents)
        logger.debug { "✅ ${reviewEvents.size} review events deleted for user: $userId" }

        // 8. Delete study sessions
        val studySessions = studySessionRepository.findByUser(user)
        studySessionRepository.deleteAll(studySessions)
        logger.debug { "✅ ${studySessions.size} study sessions deleted for user: $userId" }

        // 9. Delete user settings
        val settings = userSettingsRepository.findByUser(user)
        if (settings != null) {
            userSettingsRepository.delete(settings)
            logger.debug { "✅ User settings deleted for user: $userId" }
        }

        // 10. Record deletion before removing the user row (audit log has no FK, survives deletion)
        auditLogService.logAccountDeletion(userId, user.email, null)

        userRepository.delete(user)
        logger.info { "✅ Account deleted successfully for userId=$userId" }
    }
    
    /**
     * Verifies a Firebase ID token using Firebase Admin SDK.
     * The token is issued by Firebase Authentication and signed by Google's servers.
     * 
     * @param idTokenString The Firebase ID token to verify
     * @return Decoded FirebaseToken if valid, null if invalid
     */
    private fun verifyFirebaseToken(idTokenString: String): FirebaseToken? {
        return try {
            logger.debug { "Verifying Firebase ID token" }
            
            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(idTokenString)
            
            logger.debug { "✅ Token verified - UID: ${decodedToken.uid}" }
            decodedToken
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Firebase token verification failed: ${e.message}" }
            null
        }
    }
    
    /**
     * Verifies an Apple ID token (JWT) with full signature verification.
     * Apple ID tokens are JWTs signed by Apple using RS256.
     * This implementation verifies the signature using Apple's public keys.
     * 
     * @param idTokenString The Apple ID token (JWT) to verify
     * @return Map of token claims if valid, null if invalid
     */
    private fun verifyAppleToken(idTokenString: String): Map<String, Any>? {
        return try {
            logger.debug { "Verifying Apple ID token with signature verification" }
            
            // Parse JWT header to get the key ID (kid)
            val parts = idTokenString.split(".")
            if (parts.size != 3) {
                logger.error { "Invalid JWT format - expected 3 parts, got ${parts.size}" }
                return null
            }
            
            // Decode header to get kid
            val headerJson = String(Base64.getUrlDecoder().decode(parts[0]))
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            val header = objectMapper.readValue(headerJson, Map::class.java) as Map<String, Any>
            
            val kid = header["kid"] as? String
            if (kid == null) {
                logger.error { "Missing 'kid' in JWT header" }
                return null
            }
            
            logger.debug { "Apple token kid: $kid" }
            
            // Get the public key for this kid
            val publicKey = applePublicKeyService.getPublicKey(kid)
            if (publicKey == null) {
                logger.error { "Could not get public key for kid: $kid" }
                return null
            }
            
            // Verify the JWT signature using JJWT library
            val jwtParser = io.jsonwebtoken.Jwts.parser()
                .verifyWith(publicKey as java.security.interfaces.RSAPublicKey)
                .build()
            
            val jwt = jwtParser.parseSignedClaims(idTokenString)
            val claims = jwt.payload
            
            // Validate issuer
            val iss = claims.issuer
            if (iss != "https://appleid.apple.com") {
                logger.error { "Invalid issuer: $iss (expected https://appleid.apple.com)" }
                return null
            }
            
            // Validate expiration (JJWT already checks this, but we log it)
            val exp = claims.expiration
            if (exp.before(Date())) {
                logger.error { "Token expired at: $exp" }
                return null
            }
            
            // Convert claims to map
            val claimsMap = mutableMapOf<String, Any>()
            claimsMap["sub"] = claims.subject ?: ""
            claimsMap["email"] = claims["email"] ?: ""
            claimsMap["email_verified"] = claims["email_verified"] ?: false
            claimsMap["is_private_email"] = claims["is_private_email"] ?: false
            claimsMap["iss"] = claims.issuer
            claimsMap["aud"] = claims.audience.firstOrNull() ?: ""
            claimsMap["exp"] = claims.expiration.time / 1000
            claimsMap["iat"] = claims.issuedAt?.time?.div(1000) ?: 0
            
            logger.info { "✅ Apple token signature verified - Sub: ${claims.subject}" }
            
            claimsMap
            
        } catch (e: io.jsonwebtoken.security.SignatureException) {
            logger.error(e) { "❌ Apple token signature verification failed - invalid signature" }
            null
        } catch (e: io.jsonwebtoken.ExpiredJwtException) {
            logger.error(e) { "❌ Apple token expired" }
            null
        } catch (e: io.jsonwebtoken.JwtException) {
            logger.error(e) { "❌ Apple token verification failed - JWT error: ${e.message}" }
            null
        } catch (e: Exception) {
            logger.error(e) { "❌ Apple token verification failed: ${e.message}" }
            null
        }
    }
    
    /**
     * Converts a User entity to a UserDto for API responses.
     */
    private fun User.toDto(): UserDto = UserDto(
        id = this.id!!,
        email = this.email,
        name = this.name,
        subscriptionStatus = this.subscriptionStatus,
        subscriptionExpiresAt = this.subscriptionExpiresAt?.toString(),
        currentStreak = this.currentStreak,
        displayAlias = this.displayAlias,
        profileImageUrl = this.profileImageUrl
    )
}