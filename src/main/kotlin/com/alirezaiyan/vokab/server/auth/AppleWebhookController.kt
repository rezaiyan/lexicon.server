package com.alirezaiyan.vokab.server.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

/**
 * Controller for Apple Server-to-Server notifications
 * 
 * Apple sends notifications to this endpoint when:
 * - User changes email sharing preferences
 * - User revokes app consent
 * - User deletes their Apple ID
 * 
 * Configuration in Apple Developer Portal:
 * 1. Go to Certificates, Identifiers & Profiles
 * 2. Select your App ID
 * 3. Configure "Sign in with Apple"
 * 4. Add Server-to-Server Notification Endpoint URL:
 *    https://your-domain.com/api/v1/webhooks/apple
 * 
 * Reference: https://developer.apple.com/documentation/sign_in_with_apple/processing_changes_for_sign_in_with_apple_accounts
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class AppleWebhookController(
    private val appleNotificationService: AppleNotificationService
) {
    
    /**
     * Receive Apple server-to-server notifications
     * 
     * Apple sends a POST request with the notification payload
     * The payload is a JWT that must be verified
     * 
     * We must respond with 200 OK within a few seconds
     */
    @PostMapping("/apple")
    fun handleAppleNotification(
        @RequestBody notification: AppleServerNotification
    ): ResponseEntity<String> {
        return try {
            logger.info { "Received Apple server-to-server notification" }
            
            // Process the notification (verify JWT and handle events)
            val success = appleNotificationService.processNotification(notification.payload)
            
            if (success) {
                logger.info { "✅ Successfully processed Apple notification" }
                ResponseEntity.ok("OK")
            } else {
                logger.error { "❌ Failed to process Apple notification" }
                // Still return 200 to prevent Apple from retrying
                ResponseEntity.ok("FAILED")
            }
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Error processing Apple notification: ${e.message}" }
            // Return 200 even on error to prevent Apple from retrying indefinitely
            // We log the error for manual investigation
            ResponseEntity.ok("ERROR")
        }
    }
    
    /**
     * Health check endpoint for Apple webhook
     * Useful for testing that the webhook URL is accessible
     */
    @GetMapping("/apple")
    fun healthCheck(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(
            mapOf(
                "status" to "active",
                "service" to "Apple Server-to-Server Notifications",
                "message" to "Webhook endpoint is ready to receive notifications"
            )
        )
    }
}

