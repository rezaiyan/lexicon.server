package com.alirezaiyan.vokab.server.subscription

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * RevenueCat webhook envelope. All subscription data lives inside [event];
 * the top level only carries [api_version].
 * See https://www.revenuecat.com/docs/integrations/webhooks/sample-events
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevenueCatWebhookEvent(
    val api_version: String? = null,
    val event: RevenueCatEvent
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RevenueCatEvent(
    val id: String,
    val type: String,
    val app_user_id: String? = null,
    val original_app_user_id: String? = null,
    val aliases: List<String>? = null,
    val product_id: String? = null,
    /** NORMAL, TRIAL, INTRO, PROMOTIONAL or PREPAID. */
    val period_type: String? = null,
    val purchased_at_ms: Long? = null,
    val expiration_at_ms: Long? = null,
    /** Stable across renewals of the same subscription; used as our subscription key. */
    val original_transaction_id: String? = null,
    val transaction_id: String? = null,
    val environment: String? = null,
    val cancel_reason: String? = null,
    /** EXPIRATION only; SUBSCRIPTION_PAUSED when a Google Play pause took effect. */
    @JsonProperty("expiration_reason") val expirationReason: String? = null,
    /** BILLING_ISSUE only: when the store's grace period ends (access continues until then). */
    @JsonProperty("grace_period_expiration_at_ms") val gracePeriodExpirationAtMs: Long? = null,
    /** SUBSCRIPTION_PAUSED only: when the paused subscription resumes. */
    @JsonProperty("auto_resume_at_ms") val autoResumeAtMs: Long? = null,
    /** TRANSFER only: customers the entitlement moved away from / to. TRANSFER has no app_user_id. */
    val transferred_from: List<String>? = null,
    val transferred_to: List<String>? = null,
) {
    val isTrial: Boolean get() = period_type == "TRIAL"

    /** Key that identifies one subscription across all its lifecycle events. */
    val subscriptionKey: String get() = original_transaction_id ?: transaction_id ?: id

    /** All non-anonymous user ids RevenueCat knows this customer by, most specific first. */
    val candidateUserIds: List<String>
        get() = (listOfNotNull(app_user_id, original_app_user_id) + aliases.orEmpty())
            .filterNot { it.startsWith(ANONYMOUS_ID_PREFIX) }
            .distinct()

    /** TRANSFER only: every non-anonymous user id on either side of the transfer. */
    val transferUserIds: List<String>
        get() = (transferred_from.orEmpty() + transferred_to.orEmpty())
            .filterNot { it.startsWith(ANONYMOUS_ID_PREFIX) }
            .distinct()

    companion object {
        const val ANONYMOUS_ID_PREFIX = "\$RCAnonymousID:"
    }
}

