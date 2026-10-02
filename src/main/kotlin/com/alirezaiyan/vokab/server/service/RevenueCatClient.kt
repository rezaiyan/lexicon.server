package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Minimal RevenueCat REST v1 client — reads a customer's entitlements so the server can reconcile
 * premium without relying on webhooks alone.
 * https://www.revenuecat.com/docs/api-v1#tag/customers/operation/subscribers
 */
@Component
class RevenueCatClient(
    webClientBuilder: WebClient.Builder,
    private val appProperties: AppProperties,
) {
    // clone(): the injected builder is a shared singleton; don't leak our baseUrl into it.
    private val webClient = webClientBuilder.clone().baseUrl(BASE_URL).build()

    val isConfigured: Boolean get() = appProperties.revenuecat.apiKey.isNotBlank()

    /**
     * Returns the customer's current entitlement state, or `null` when the API isn't configured
     * or the request failed (caller keeps the existing state — never downgrade on an error).
     */
    fun fetchEntitlementState(appUserId: String): StoreEntitlementState? {
        if (!isConfigured) return null
        return try {
            val response = webClient.get()
                .uri("/subscribers/{id}", UriUtils.encodePathSegment(appUserId, StandardCharsets.UTF_8))
                .header("Authorization", "Bearer ${appProperties.revenuecat.apiKey}")
                .retrieve()
                .bodyToMono(SubscriberResponse::class.java)
                .timeout(TIMEOUT)
                .block()
            response?.subscriber?.toEntitlementState(Instant.now())
        } catch (e: WebClientResponseException) {
            logger.warn { "RevenueCat subscriber lookup failed: status=${e.statusCode}" }
            null
        } catch (e: Exception) {
            logger.warn(e) { "RevenueCat subscriber lookup failed" }
            null
        }
    }

    companion object {
        private const val BASE_URL = "https://api.revenuecat.com/v1"
        private val TIMEOUT = Duration.ofSeconds(10)
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
