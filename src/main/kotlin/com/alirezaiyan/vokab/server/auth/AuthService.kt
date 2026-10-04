package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher
import com.alirezaiyan.vokab.server.shared.UserSignedInEvent
import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.user.GeoLocationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

/**
 * Sign-in (Google via Firebase, Sign in with Apple, CI): verifies the identity, provisions the
 * account through [UserProvisioning] and issues tokens through [TokenService].
 */
@Service
class AuthService(
    private val firebaseIdTokenVerifier: FirebaseIdTokenVerifier,
    private val appleIdTokenVerifier: AppleIdTokenVerifier,
    private val userProvisioning: UserProvisioning,
    private val tokenService: TokenService,
    private val appProperties: AppProperties,
    private val auditLogService: AuditLogService,
    private val domainEventPublisher: DomainEventPublisher,
    private val geoLocationService: GeoLocationService,
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
        val provisioned = userProvisioning.googleUser(claims, email)
        return completeSignIn(provisioned, SignInContext(SignInProvider.GOOGLE, platform, appVersion, ipAddress))
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

        logger.info { "Authenticating user with Apple: appleId=${claims.subject}" }
        val provisioned = userProvisioning.appleUser(claims.subject, claims.email, fullName)
        return completeSignIn(provisioned, SignInContext(SignInProvider.APPLE, platform, appVersion, ipAddress))
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
        logger.info { "CI authentication for test user (premium=$premium)" }
        val (user, userId) = userProvisioning.ciUser(appProperties.ciAuth.testEmail, premium)
        userProvisioning.recordPlatform(userId, platform, appVersion)
        val response = tokenService.issue(user, userId)

        logger.info { "CI test user authenticated: userId=$userId" }
        auditLogService.logLogin(userId, user.email, "CI", null, null)
        return response
    }

    /** Shared tail of every interactive sign-in: record, issue tokens, publish events. */
    private fun completeSignIn(provisioned: ProvisionedUser, ctx: SignInContext): AuthResponse {
        val (user, userId, isNewUser) = provisioned
        userProvisioning.recordPlatform(userId, ctx.platform, ctx.appVersion)

        val response = tokenService.issue(user, userId)

        logger.info { "✅ User authenticated with ${ctx.provider.auditName}: userId=$userId" }
        auditLogService.logLogin(userId, user.email, ctx.provider.auditName, ctx.ipAddress, null)

        val country = ctx.ipAddress?.let { ip ->
            geoLocationService.updateUserCountry(userId, ip, isNewUser)
            geoLocationService.resolveCountry(ip)
        }
        val event = if (isNewUser) {
            UserSignedUpEvent(userId, user.name, user.email, ctx.provider.eventName, ctx.platform, country)
        } else {
            UserSignedInEvent(userId, user.name, user.email, ctx.provider.eventName, ctx.platform, country)
        }
        domainEventPublisher.publish(event)
        return response
    }
}
