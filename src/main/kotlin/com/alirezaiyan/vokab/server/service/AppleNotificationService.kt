package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationCategory
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.security.AppleIdTokenVerifier
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Service to handle Apple Server-to-Server notifications
 * These notifications inform us of changes to user Apple accounts
 * 
 * Reference: https://developer.apple.com/documentation/sign_in_with_apple/processing_changes_for_sign_in_with_apple_accounts
 */
@Service
class AppleNotificationService(
    private val userRepository: UserRepository,
    private val appleIdTokenVerifier: AppleIdTokenVerifier,
    private val objectMapper: ObjectMapper,
    private val pushNotificationService: PushNotificationService,
    private val authService: AuthService
) {
    
    /**
     * Process incoming Apple server notification
     * The notification is a JWT signed by Apple that must be verified
     */
    @Transactional
    fun processNotification(notificationPayload: String): Boolean {
        try {
            logger.info { "Processing Apple server-to-server notification" }
            
            // Verify and decode the JWT payload
            val claims = verifyAppleNotificationToken(notificationPayload)
            if (claims == null) {
                logger.error { "Failed to verify Apple notification token" }
                return false
            }
            
            // Parse the events
            val eventsJson = objectMapper.writeValueAsString(claims["events"])
            val events = objectMapper.readValue(eventsJson, AppleNotificationEvents::class.java)
            
            val appleUserId = events.sub
            logger.info { "Processing event type: ${events.type} for user: $appleUserId" }
            
            // Find user by Apple ID
            val userOptional = userRepository.findByAppleId(appleUserId)
            
            if (userOptional.isEmpty) {
                logger.warn { "Received notification for unknown Apple user: $appleUserId" }
                return true // Still return success to Apple
            }
            
            val user = userOptional.get()
            
            // Handle different event types
            when (events.type) {
                AppleNotificationEventType.EMAIL_DISABLED -> handleEmailDisabled(user, events.emailDisabled)
                AppleNotificationEventType.EMAIL_ENABLED -> handleEmailEnabled(user, events.emailEnabled)
                AppleNotificationEventType.CONSENT_REVOKED -> handleConsentRevoked(user, events.consentRevoked)
                AppleNotificationEventType.ACCOUNT_DELETE -> handleAccountDelete(user, events.accountDelete)
            }
            
            return true
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to process Apple notification: ${e.message}" }
            return false
        }
    }
    
    /** Signature, issuer, expiry and audience are checked by [AppleIdTokenVerifier]. */
    private fun verifyAppleNotificationToken(tokenString: String): Map<String, Any?>? =
        appleIdTokenVerifier.verifyClaims(tokenString)?.toMap()
    
    /**
     * Handle email-disabled event
     * User stopped sharing their email or switched to private relay
     */
    private fun handleEmailDisabled(user: com.alirezaiyan.vokab.server.domain.entity.User, event: EmailDisabledEvent?) {
        logger.info { "Email disabled for userId=${user.id}" }

        if (event != null) {
            logger.info { "  Was private relay: ${event.is_private_email}" }
        }
        
        // Update user record to note the change
        val updatedUser = user.copy(
            updatedAt = Instant.now()
            // Note: We keep the original email for account continuity
            // Apple will provide the new relay email on next sign-in
        )
        userRepository.save(updatedUser)
        
        logger.info { "✅ Processed email-disabled event for user: ${user.id}" }
    }
    
    /**
     * Handle email-enabled event
     * User started sharing their real email
     */
    private fun handleEmailEnabled(user: com.alirezaiyan.vokab.server.domain.entity.User, event: EmailEnabledEvent?) {
        logger.info { "Email enabled for userId=${user.id}" }

        if (event != null) {
            logger.info { "  Is private relay: ${event.is_private_email}" }

            // Update user with the new email
            val updatedUser = user.copy(
                email = event.email,
                updatedAt = Instant.now()
            )
            userRepository.save(updatedUser)
            logger.info { "✅ Updated user email" }
        }
    }
    
    /**
     * Handle consent-revoked event
     * User revoked app's access - we should deactivate their account
     */
    private fun handleConsentRevoked(user: com.alirezaiyan.vokab.server.domain.entity.User, event: ConsentRevokedEvent?) {
        logger.info { "Consent revoked for userId=${user.id}" }
        logger.info { "  Reason: ${event?.reason ?: "Not provided"}" }
        
        // CRITICAL FIX: Send push notification to all devices before deactivating account
        // This ensures all devices are notified that the account access has been revoked
        try {
            val notificationResults = pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = "Account Access Revoked",
                body = "Your account access has been revoked. Please sign in again.",
                data = mapOf(
                    "type" to "sign_out",
                    "action" to "clear_local_data",
                    "reason" to "consent_revoked"
                ),
                category = NotificationCategory.SYSTEM
            )
            logger.info { "Sent consent revocation notification to ${notificationResults.size} devices" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send consent revocation notification, continuing with deactivation" }
        }
        
        // Mark user as inactive but don't delete data
        val updatedUser = user.copy(
            active = false,
            updatedAt = Instant.now()
        )
        userRepository.save(updatedUser)
        
        logger.info { "✅ Deactivated user account: ${user.id}" }
    }
    
    /**
     * Handle account-delete event
     * User permanently deleted their Apple ID
     * According to Apple's guidelines, we should delete user data
     */
    private fun handleAccountDelete(user: com.alirezaiyan.vokab.server.domain.entity.User, event: AccountDeleteEvent?) {
        logger.info { "Account deletion requested for userId=${user.id}, reason: ${event?.reason ?: "Not provided"}" }

        // Delegate to the shared hard-delete flow (push notification + Firebase + RevenueCat + all local data)
        authService.deleteAccount(user.id!!)

        logger.info { "✅ Processed account deletion for user: ${user.id}" }
    }
}