package com.alirezaiyan.vokab.server.auth

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

/** A signed-in user after find-or-create, saved; [isNew] when this sign-in created the account. */
data class ProvisionedUser(val user: User, val userId: Long, val isNew: Boolean)

/**
 * Finds, links or creates the account behind a verified identity and saves it, with the test-list
 * premium grant applied. Runs inside the caller's sign-in transaction.
 */
@Component
class UserProvisioning(
    private val userRepository: UserRepository,
    private val userPlatformRepository: UserPlatformRepository,
    private val testAccessPolicy: TestAccessPolicy,
    private val clock: Clock,
) {

    /** By Google id, else links the account with the same email, else creates one. */
    fun googleUser(claims: FirebaseIdClaims, email: String): ProvisionedUser {
        val now = Instant.now(clock)
        val name = claims.name ?: email
        val user = userRepository.findByGoogleId(claims.uid).orElse(null)
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
        return save(testAccessPolicy.applyTo(user))
    }

    /**
     * Apple id is the primary key for lookup. Email linking only happens when Apple shared the real
     * address ([email] non-null) — a fallback address must never match another account. A returning
     * user keeps their stored email: Apple omitting the claim must not replace a real address with
     * the fallback.
     */
    fun appleUser(appleId: String, email: String?, fullName: String?): ProvisionedUser {
        val now = Instant.now(clock)
        val user = userRepository.findByAppleId(appleId).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: email?.let { userRepository.findByEmail(it).orElse(null) }?.let { existing ->
                logger.info { "Linking Apple account to userId=${existing.id}" }
                existing.also {
                    it.appleId = appleId
                    if (fullName != null) it.name = fullName
                    it.recordLogin(now)
                }
            }
            ?: newAppleUser(appleId, email, fullName, now)
        return save(testAccessPolicy.applyTo(user))
    }

    /** The dedicated CI account, with premium granted or explicitly stripped. */
    fun ciUser(email: String, premium: Boolean): ProvisionedUser {
        val now = Instant.now(clock)
        val user = userRepository.findByEmail(email).orElse(null)
            ?.also { it.recordLogin(now) }
            ?: User(email = email, name = "CI Test User", lastLoginAt = now)
                .also { logger.info { "Creating new CI test user" } }
        return save(if (premium) testAccessPolicy.applyTo(user) else testAccessPolicy.stripPremium(user))
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

    private fun save(user: User): ProvisionedUser {
        val isNew = user.id == null
        val saved = userRepository.save(user)
        return ProvisionedUser(saved, saved.requireId(), isNew)
    }

    private fun User.recordLogin(now: Instant) {
        lastLoginAt = now
        updatedAt = now
    }

    private fun hiddenAppleEmail(appleId: String): String =
        "apple_${appleId.replace(".", "_").replace(" ", "_")}@apple.hidden"
}
