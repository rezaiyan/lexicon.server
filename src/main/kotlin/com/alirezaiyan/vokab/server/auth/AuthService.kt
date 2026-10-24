package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher
import com.alirezaiyan.vokab.server.shared.UserSignedInEvent
import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import com.alirezaiyan.vokab.server.user.GeoLocationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

private val logger = KotlinLogging.logger {}

private const val SHOWCASE_USER_NAME = "Alex"

/**
 * Sign-in (Google via Firebase, Sign in with Apple, CI): verifies the identity, provisions the
 * account through [UserProvisioning] and issues tokens through [TokenService].
 *
 * Google and Apple verify the ID token before opening a transaction (it may fetch signing keys), then
 * provision and issue tokens in one transaction, retried once if it loses a race to create the user.
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
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

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
     * @throws AuthRejectedException if the token is invalid, not from Google Sign-In, or carries no email
     */
    fun authenticateWithGoogle(
        idToken: String,
        platform: String? = null,
        appVersion: String? = null,
        ipAddress: String? = null,
    ): AuthResponse {
        val claims = firebaseIdTokenVerifier.verify(idToken)
        if (claims == null || !claims.isGoogleSignIn) {
            claims?.let { logger.warn { "Google sign-in rejected: Firebase sign-in provider is ${it.signInProvider}" } }
            throw AuthRejectedException("Invalid Firebase ID token")
        }
        val email = claims.email
            ?: throw AuthRejectedException("Email not found in Firebase token")

        logger.info { "Authenticating user" }
        val ctx = SignInContext(SignInProvider.GOOGLE, platform, appVersion, ipAddress)
        return inSignInTransaction { completeSignIn(userProvisioning.googleUser(claims, email), ctx) }
    }

    /**
     * Authenticates with an Apple ID token. The verified token `sub` is the only identity trusted;
     * [appleUserId] from the client is accepted solely as a consistency check.
     * When the user hides their email, a stable fallback address derived from `sub` is used.
     *
     * @param fullName only sent by Apple on the very first sign-in
     * @param nonce the raw nonce the client generated for this sign-in (see [AppleAuthRequest.nonce]);
     *   when present the token's `nonce` claim must be its SHA-256 hex. Optional until clients send it.
     * @throws AuthRejectedException if the token is invalid or doesn't match [appleUserId] / [nonce]
     */
    fun authenticateWithApple(
        idToken: String,
        fullName: String?,
        appleUserId: String?,
        platform: String? = null,
        appVersion: String? = null,
        ipAddress: String? = null,
        nonce: String? = null,
    ): AuthResponse {
        val claims = appleIdTokenVerifier.verify(idToken, rawNonce = nonce)
            ?: throw AuthRejectedException("Invalid Apple ID token")
        if (appleUserId != null && appleUserId != claims.subject) {
            logger.warn { "Apple sign-in rejected: client user identifier does not match token subject" }
            throw AuthRejectedException("Invalid Apple ID token")
        }

        logger.info { "Authenticating user with Apple: appleId=${claims.subject}" }
        val ctx = SignInContext(SignInProvider.APPLE, platform, appVersion, ipAddress)
        return inSignInTransaction {
            val provisioned = userProvisioning.appleUser(claims.subject, claims.email, claims.emailVerified, fullName)
            completeSignIn(provisioned, ctx)
        }
    }

    /**
     * Authenticates the dedicated CI test user without OAuth (the endpoint is gated by a shared
     * secret header).
     *
     * @param premium true (default) applies the test-user premium grant (the CI email must be on
     *   the test-email list); false clears grant and subscription so non-premium gating can be tested.
     * @param showcase true signs in the showcase account (marketing screenshots and recordings)
     *   instead of the CI user, so curated demo data never mixes with CI test data.
     */
    @Transactional
    fun authenticateForCi(
        premium: Boolean = true,
        platform: String? = null,
        appVersion: String? = null,
        showcase: Boolean = false,
    ): AuthResponse {
        logger.info { "CI authentication for ${if (showcase) "showcase" else "test"} user (premium=$premium)" }
        val (user, userId) = if (showcase) {
            userProvisioning.ciUser(appProperties.ciAuth.showcaseEmail, premium, name = SHOWCASE_USER_NAME)
        } else {
            userProvisioning.ciUser(appProperties.ciAuth.testEmail, premium)
        }
        userProvisioning.recordPlatform(userId, platform, appVersion)
        val response = tokenService.issue(user, userId)

        logger.info { "CI test user authenticated: userId=$userId" }
        auditLogService.logLogin(userId, user.email, "CI", null, null)
        return response
    }

    /**
     * Runs [block] in a transaction. Two first sign-ins of the same identity at once both try to
     * insert the user and one hits a unique constraint; its retry, in a fresh transaction, finds the
     * row the other created.
     */
    private fun inSignInTransaction(block: () -> AuthResponse): AuthResponse = try {
        requireNotNull(transactionTemplate.execute { block() })
    } catch (e: DataIntegrityViolationException) {
        logger.info(e) { "Sign-in lost a race with a concurrent sign-in of the same account; retrying once" }
        requireNotNull(transactionTemplate.execute { block() })
    }

    /** Shared tail of every interactive sign-in: record, issue tokens, publish events. */
    private fun completeSignIn(provisioned: ProvisionedUser, ctx: SignInContext): AuthResponse {
        val (user, userId, isNewUser) = provisioned
        userProvisioning.recordPlatform(userId, ctx.platform, ctx.appVersion)
        if (provisioned.reactivated) {
            auditLogService.logAccountReactivation(userId, user.email, ctx.provider.auditName, ctx.ipAddress)
        }

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
