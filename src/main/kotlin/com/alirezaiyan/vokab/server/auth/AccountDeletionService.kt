package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.subscription.RevenueCatClient
import com.alirezaiyan.vokab.server.user.UserDataPurger
import com.alirezaiyan.vokab.server.user.UserRepository
import com.google.firebase.auth.FirebaseAuth
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

/** Permanent account deletion (user request, or Apple consent revocation). */
@Service
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val userDataPurger: UserDataPurger,
    private val revenueCatClient: RevenueCatClient,
    private val appleTokenClient: AppleTokenClient,
    private val deviceDataWipe: DeviceDataWipe,
    private val auditLogService: AuditLogService,
    private val userAccessCache: UserAccessCache,
) {

    /**
     * Deletes third-party identities (Firebase, RevenueCat), revokes the user's Apple tokens, tells
     * devices to clear local data, then deletes every owned row and finally the user row.
     *
     * Revoking the Apple token is what App Review requires of account deletion, and it unlinks the app
     * from the user's Apple ID, so signing up again shares name and email again. Only possible for a
     * token kept from a sign-in (see [AppleTokenClient]); without one the user can still unlink the
     * app in their Apple ID settings.
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

        val appleToken = user.appleRefreshToken
        val appleClientId = user.appleTokenClientId
        if (appleToken != null && appleClientId != null) {
            if (appleTokenClient.revoke(appleToken, appleClientId)) {
                logger.info { "✅ Apple tokens revoked for: $userId" }
            }
        } else if (user.appleId != null) {
            logger.info { "No Apple token kept for userId=$userId; Apple sign-in stays linked to the app" }
        }

        deviceDataWipe.notify(
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
}
