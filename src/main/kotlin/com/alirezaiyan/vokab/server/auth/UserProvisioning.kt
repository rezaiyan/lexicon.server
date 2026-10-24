package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserPlatformRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * A signed-in user after find-or-create, saved; [isNew] when this sign-in created the account,
 * [reactivated] when it brought back a deactivated one.
 */
data class ProvisionedUser(val user: User, val userId: Long, val isNew: Boolean, val reactivated: Boolean = false)

/**
 * Finds, links or creates the account behind a verified identity and saves it, with the test-list
 * premium grant applied. Runs inside the caller's sign-in transaction.
 *
 * A Google or Apple sign-in reactivates a deactivated account: deactivation only comes from Apple
 * consent revocation, and signing in again is renewed consent. Without it the user would get tokens
 * that every endpoint refuses, with no way back.
 */
@Component
class UserProvisioning(
    private val userRepository: UserRepository,
    private val userPlatformRepository: UserPlatformRepository,
    private val testAccessPolicy: TestAccessPolicy,
    private val userAccessCache: UserAccessCache,
    private val clock: Clock,
) {

    /**
     * By Google id, else links the account with the same email, else creates one. Linking or creating
     * by email requires a verified address: an unverified one could take over (or pre-claim) the
     * account of whoever really owns it.
     *
     * @throws AuthRejectedException if the account would be linked or created from an unverified email
     */
    fun googleUser(claims: FirebaseIdClaims, email: String): ProvisionedUser {
        val now = Instant.now(clock)
        val name = claims.name ?: email
        val byGoogleId = userRepository.findByGoogleId(claims.uid).orElse(null)
        if (byGoogleId == null && !claims.emailVerified) {
            logger.warn { "Google sign-in rejected: unverified email for an unknown Google account" }
            throw AuthRejectedException("Email not verified")
        }
        val user = byGoogleId
            ?.also { it.recordLogin(now) }
            ?: userRepository.findByEmail(email).orElse(null)?.let { existing ->
                logger.info { "Linking Google account to existing userId=${existing.id}" }
                existing.also {
                    it.googleId = claims.uid
                    it.name = name
                    it.recordLogin(now)
                }
            }
            ?: User(email = email, name = name, googleId = claims.uid, lastLoginAt = now)
                .also { logger.info { "Creating new user" } }
        val reactivated = user.reactivate()
        return save(testAccessPolicy.applyTo(user), reactivated)
    }

    /**
     * Apple id is the primary key for lookup. Email linking only happens when Apple shared the real
     * address and marked it verified — a fallback or unverified address must never match another
     * account, so an unverified [email] is treated as absent. A returning user keeps their stored
     * email: Apple omitting the claim must not replace a real address with the fallback.
     */
    fun appleUser(appleId: String, email: String?, emailVerified: Boolean, fullName: String?): ProvisionedUser {
        val now = Instant.now(clock)
        val trustedEmail = email?.takeIf { emailVerified }
        if (email != null && trustedEmail == null) {
            logger.warn { "Apple sign-in: email not marked verified; not using it for linking" }
        }
        val user = userRepository.findByAppleId(appleId).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: trustedEmail?.let { userRepository.findByEmail(it).orElse(null) }?.let { existing ->
                logger.info { "Linking Apple account to userId=${existing.id}" }
                existing.also {
                    it.appleId = appleId
                    if (fullName != null) it.name = fullName
                    it.recordLogin(now)
                }
            }
            ?: newAppleUser(appleId, trustedEmail, fullName, now)
        val reactivated = user.reactivate()
        return save(testAccessPolicy.applyTo(user), reactivated)
    }

    /**
     * The owner of a verified passkey. Unlike Google and Apple, a passkey doesn't reactivate a
     * deactivated account: deactivation means Apple consent was revoked, and only signing in with
     * Apple again renews it.
     *
     * @throws AuthRejectedException if the account is deactivated
     */
    fun passkeyUser(user: User): ProvisionedUser {
        if (!user.active) {
            logger.warn { "Passkey sign-in rejected: account userId=${user.id} is deactivated" }
            throw AuthRejectedException("User account is deactivated")
        }
        user.recordLogin(Instant.now(clock))
        return save(testAccessPolicy.applyTo(user))
    }

    /**
     * A dedicated CI account (test or showcase), with premium granted or explicitly stripped.
     *
     * @throws AuthRejectedException if the CI email belongs to a real (Google/Apple) account, so a
     *   misconfigured CI_TEST_EMAIL or CI_SHOWCASE_EMAIL can never hand out tokens for a real user
     */
    fun ciUser(email: String, premium: Boolean, name: String = "CI Test User"): ProvisionedUser {
        val now = Instant.now(clock)
        val existing = userRepository.findByEmail(email).orElse(null)
        if (existing != null && (existing.googleId != null || existing.appleId != null)) {
            logger.error { "CI token refused: CI test email belongs to a real account userId=${existing.id}" }
            throw AuthRejectedException("CI test email belongs to a real account")
        }
        val user = existing
            ?.also { it.recordLogin(now) }
            ?: User(email = email, name = name, lastLoginAt = now)
                .also { logger.info { "Creating new CI test user" } }
        return save(if (premium) testAccessPolicy.grantForCi(user) else testAccessPolicy.stripPremium(user))
    }

    /** Best effort: an unknown platform or a failed write never blocks sign-in. */
    fun recordPlatform(userId: Long, platform: String?, appVersion: String?) {
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

    private fun newAppleUser(appleId: String, email: String?, fullName: String?, now: Instant): User {
        val address = email ?: hiddenAppleEmail(appleId)
        logger.info { "Creating new user with Apple (emailHidden=${email == null})" }
        return User(
            email = address,
            name = fullName ?: address.substringBefore("@"),
            appleId = appleId,
            lastLoginAt = now,
        )
    }

    private fun save(user: User, reactivated: Boolean = false): ProvisionedUser {
        val isNew = user.id == null
        val saved = userRepository.save(user)
        // The cached "inactive" answer would otherwise refuse the new tokens until it expires
        if (reactivated) userAccessCache.evict(saved.requireId())
        return ProvisionedUser(saved, saved.requireId(), isNew, reactivated)
    }

    /** Marks a deactivated account active again; true if it was deactivated. */
    private fun User.reactivate(): Boolean {
        if (active) return false
        active = true
        logger.info { "Reactivating deactivated account userId=$id on sign-in" }
        return true
    }

    private fun User.recordLogin(now: Instant) {
        lastLoginAt = now
        updatedAt = now
    }

    private fun hiddenAppleEmail(appleId: String): String =
        "apple_${appleId.replace(".", "_").replace(" ", "_")}@apple.hidden"
}
