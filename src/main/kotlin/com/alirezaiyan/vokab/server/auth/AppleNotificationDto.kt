package com.alirezaiyan.vokab.server.auth

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * DTOs for Apple Server-to-Server Notifications
 * Reference: https://developer.apple.com/documentation/sign_in_with_apple/processing_changes_for_sign_in_with_apple_accounts
 */

/**
 * Root notification payload from Apple
 */
data class AppleServerNotification(
    val payload: String // JWT signed by Apple
)


/**
 * Events contained in the notification
 */
data class AppleNotificationEvents(
    val type: AppleNotificationEventType,
    val sub: String, // Apple user identifier
    val event_time: Long,
    
    // For email-disabled event
    @JsonProperty("email-disabled")
    val emailDisabled: EmailDisabledEvent? = null,
    
    // For email-enabled event
    @JsonProperty("email-enabled")
    val emailEnabled: EmailEnabledEvent? = null,
    
    // For consent-revoked event
    @JsonProperty("consent-revoked")
    val consentRevoked: ConsentRevokedEvent? = null,
    
    // For account-delete event
    @JsonProperty("account-delete")
    val accountDelete: AccountDeleteEvent? = null
)

/**
 * Email disabled event - user stopped sharing email
 */
data class EmailDisabledEvent(
    val email: String,
    val is_private_email: Boolean
)

/**
 * Email enabled event - user started sharing email again
 */
data class EmailEnabledEvent(
    val email: String,
    val is_private_email: Boolean
)

/**
 * Consent revoked event - user revoked app access
 */
data class ConsentRevokedEvent(
    val reason: String? = null
)

/**
 * Account delete event - user deleted their Apple ID
 */
data class AccountDeleteEvent(
    val reason: String? = null
)

/**
 * Event types from Apple
 */
enum class AppleNotificationEventType {
    @JsonProperty("email-disabled")
    EMAIL_DISABLED,
    
    @JsonProperty("email-enabled")
    EMAIL_ENABLED,
    
    @JsonProperty("consent-revoked")
    CONSENT_REVOKED,
    
    @JsonProperty("account-delete")
    ACCOUNT_DELETE
}

