package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Component
import com.alirezaiyan.vokab.server.shared.describe
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import org.springframework.web.util.UriBuilder
import java.net.URI
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Minimal RevenueCat REST v1 client — reads a customer's entitlements so the server can reconcile
 * premium without relying on webhooks alone.
 * https://www.revenuecat.com/docs/api-v1#tag/customers/operation/subscribers
 */
@Component
class RevenueCatClient(
    restClientBuilder: RestClient.Builder,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val restClient = restClientBuilder.baseUrl(BASE_URL).build()

    val isConfigured: Boolean get() = appProperties.revenuecat.apiKey.isNotBlank()

    /**
     * Returns the customer's current entitlement state, or `null` when the API isn't configured
     * or the request failed (caller keeps the existing state — never downgrade on an error).
     */
    fun fetchEntitlementState(appUserId: String): StoreEntitlementState? {
        if (!isConfigured) return null
        return try {
            restClient.get()
                .uri { it.subscriberPath(appUserId) }
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${appProperties.revenuecat.apiKey}")
                .retrieve()
                .body<SubscriberResponse>()
                ?.subscriber
                ?.toEntitlementState(Instant.now(clock))
        } catch (e: RestClientException) {
            logger.warn { "RevenueCat subscriber lookup failed: ${e.describe()}" }
            null
        }
    }

    /**
     * Deletes the customer and their purchase history at RevenueCat (GDPR erasure).
     * Returns false when the API isn't configured or the request failed; never throws.
     */
    fun deleteSubscriber(appUserId: String): Boolean {
        if (!isConfigured) return false
        return try {
            restClient.delete()
                .uri { it.subscriberPath(appUserId) }
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${appProperties.revenuecat.apiKey}")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: RestClientException) {
            logger.warn { "RevenueCat subscriber deletion failed: ${e.describe()}" }
            false
        }
    }

    /**
     * `/subscribers/{appUserId}` with the id encoded exactly once, as a single path segment
     * (a `/` or space in the id must not change the path).
     */
    private fun UriBuilder.subscriberPath(appUserId: String): URI =
        pathSegment("subscribers", appUserId).build()

    private companion object {
        const val BASE_URL = "https://api.revenuecat.com/v1"
    }
}

/** What the store says about one customer right now. */
data class StoreEntitlementState(
    /** RevenueCat knows any purchase for this customer (active or not). */
    val hasPurchaseHistory: Boolean,
    val isActive: Boolean,
    /** null for lifetime purchases or when inactive. */
    val expiresAt: Instant?,
    val productId: String?,
    val isTrial: Boolean,
    val willRenew: Boolean,
)

// ── Response DTOs (only the fields we use) ─────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubscriberResponse(val subscriber: Subscriber? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subscriber(
    val entitlements: Map<String, EntitlementDto> = emptyMap(),
    val subscriptions: Map<String, SubscriptionInfoDto> = emptyMap(),
    @JsonProperty("non_subscriptions") val nonSubscriptions: Map<String, Any> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EntitlementDto(
    @JsonProperty("expires_date") val expiresDate: Instant? = null,
    @JsonProperty("grace_period_expires_date") val gracePeriodExpiresDate: Instant? = null,
    @JsonProperty("product_identifier") val productIdentifier: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubscriptionInfoDto(
    @JsonProperty("expires_date") val expiresDate: Instant? = null,
    @JsonProperty("period_type") val periodType: String? = null,
    @JsonProperty("unsubscribe_detected_at") val unsubscribeDetectedAt: Instant? = null,
    @JsonProperty("refunded_at") val refundedAt: Instant? = null,
)

internal fun Subscriber.toEntitlementState(now: Instant): StoreEntitlementState {
    // Access lasts until the later of expiry and grace period; null expiry = lifetime.
    fun EntitlementDto.accessUntil(): Instant? =
        listOfNotNull(expiresDate, gracePeriodExpiresDate).maxOrNull()

    val active = entitlements.values
        .filter { e -> e.expiresDate == null || (e.accessUntil()?.isAfter(now) == true) }
        .maxByOrNull { it.accessUntil() ?: Instant.MAX }

    val subscription = active?.productIdentifier?.let { subscriptions[it] }
    return StoreEntitlementState(
        hasPurchaseHistory = entitlements.isNotEmpty() || subscriptions.isNotEmpty() || nonSubscriptions.isNotEmpty(),
        isActive = active != null,
        expiresAt = active?.accessUntil(),
        productId = active?.productIdentifier,
        isTrial = subscription?.periodType.equals("trial", ignoreCase = true),
        willRenew = subscription != null && subscription.unsubscribeDetectedAt == null && subscription.refundedAt == null,
    )
}
