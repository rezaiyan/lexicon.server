package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.describe
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import org.springframework.web.util.UriBuilder
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Minimal RevenueCat REST v2 client: reads a customer's access and subscription state so the
 * server can reconcile premium without relying on webhooks alone, and erases customers.
 * The secret key is project-scoped, so the project id is looked up from the key once.
 * https://www.revenuecat.com/docs/api-v2
 */
@Component
class RevenueCatClient(
    restClientBuilder: RestClient.Builder,
    private val appProperties: AppProperties,
) {
    private val restClient = restClientBuilder
        .baseUrl(BASE_URL)
        .defaultHeaders { it.setBearerAuth(appProperties.revenuecat.apiKey) }
        .build()

    @Volatile
    private var projectId: String? = null

    val isConfigured: Boolean get() = appProperties.revenuecat.apiKey.isNotBlank()

    /**
     * Returns the customer's current state, or `null` when the API isn't configured or a request
     * failed (caller keeps the existing state: never downgrade on an error). A customer RevenueCat
     * has never seen has no purchase history.
     */
    fun fetchEntitlementState(appUserId: String): StoreEntitlementState? {
        if (!isConfigured) return null
        return try {
            val project = project()
            val customer = try {
                get<CustomerDto>(project) { pathSegment("customers", appUserId) }
            } catch (_: HttpClientErrorException.NotFound) {
                return StoreEntitlementState.NONE
            }
            val subscriptions = get<ListDto<SubscriptionDto>>(project) {
                pathSegment("customers", appUserId, "subscriptions").queryParam("limit", PAGE_SIZE)
            }.items
            val hasOneTimePurchases = customer.activeEntitlements.items.isEmpty() && subscriptions.isEmpty() &&
                get<ListDto<Any>>(project) { pathSegment("customers", appUserId, "purchases").queryParam("limit", 1) }
                    .items.isNotEmpty()
            toEntitlementState(customer.activeEntitlements.items, subscriptions, hasOneTimePurchases)
        } catch (e: RestClientException) {
            logger.warn { "RevenueCat customer lookup failed: ${e.describe()}" }
            null
        }
    }

    /**
     * Deletes the customer and their purchase history at RevenueCat (GDPR erasure). A customer
     * RevenueCat doesn't know counts as deleted. Returns false when the API isn't configured or
     * the request failed; never throws.
     */
    fun deleteSubscriber(appUserId: String): Boolean {
        if (!isConfigured) return false
        return try {
            val project = project()
            restClient.delete()
                .uri { it.projectPath(project).pathSegment("customers", appUserId).build() }
                .retrieve()
                .onStatus({ it.value() == HttpStatus.NOT_FOUND.value() }) { _, _ -> }
                .toBodilessEntity()
            true
        } catch (e: RestClientException) {
            logger.warn { "RevenueCat customer deletion failed: ${e.describe()}" }
            false
        }
    }

    private fun project(): String = projectId ?: restClient.get()
        .uri("/projects")
        .retrieve()
        .body<ListDto<ProjectDto>>()
        ?.items?.singleOrNull()?.id
        ?.also { projectId = it }
        ?: throw RestClientException("RevenueCat key must be scoped to exactly one project")

    private inline fun <reified T : Any> get(project: String, crossinline segments: UriBuilder.() -> UriBuilder): T =
        restClient.get()
            .uri { it.projectPath(project).segments().build() }
            .retrieve()
            .body<T>()
            ?: throw RestClientException("Empty RevenueCat response")

    /** Every id is a single path segment, encoded exactly once (a `/` or space can't change the path). */
    private fun UriBuilder.projectPath(project: String): UriBuilder = pathSegment("projects", project)

    private companion object {
        const val BASE_URL = "https://api.revenuecat.com/v2"
        /** A user has a handful of subscriptions; one page covers them. */
        const val PAGE_SIZE = 50
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
    /** Auto-renew is on. False for a cancellation and for a scheduled pause. */
    val willRenew: Boolean,
    /** The store couldn't charge the latest renewal (access may continue in a grace period). */
    val hasBillingIssue: Boolean = false,
    /** The user turned auto-renew off (unlike a scheduled pause, the subscription won't come back). */
    val isCanceled: Boolean = false,
) {
    companion object {
        val NONE = StoreEntitlementState(
            hasPurchaseHistory = false,
            isActive = false,
            expiresAt = null,
            productId = null,
            isTrial = false,
            willRenew = false,
        )
    }
}

// ── Response DTOs (only the fields we use) ─────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListDto<T>(val items: List<T> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectDto(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerDto(
    @JsonProperty("active_entitlements") val activeEntitlements: ListDto<ActiveEntitlementDto> = ListDto(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActiveEntitlementDto(
    /** Epoch millis; null for lifetime access. */
    @JsonProperty("expires_at") val expiresAt: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubscriptionDto(
    /** trialing, active, expired, in_grace_period, in_billing_retry, paused, unknown, incomplete. */
    val status: String? = null,
    /** will_renew, will_not_renew, will_change_product, will_pause, requires_price_increase_consent, has_already_renewed. */
    @JsonProperty("auto_renewal_status") val autoRenewalStatus: String? = null,
    @JsonProperty("gives_access") val givesAccess: Boolean = false,
    @JsonProperty("current_period_ends_at") val currentPeriodEndsAt: Long? = null,
    @JsonProperty("product_id") val productId: String? = null,
)

internal fun toEntitlementState(
    activeEntitlements: List<ActiveEntitlementDto>,
    subscriptions: List<SubscriptionDto>,
    hasOneTimePurchases: Boolean,
): StoreEntitlementState {
    // Access lasts until the latest entitlement expiry (grace period included); any null = lifetime.
    val expiresAt = if (activeEntitlements.any { it.expiresAt == null }) {
        null
    } else {
        activeEntitlements.mapNotNull { it.expiresAt }.maxOrNull()?.let(Instant::ofEpochMilli)
    }
    val subscription = subscriptions.filter { it.givesAccess }.maxByOrNull { it.currentPeriodEndsAt ?: 0L }
    val renewal = subscription?.autoRenewalStatus
    return StoreEntitlementState(
        hasPurchaseHistory = activeEntitlements.isNotEmpty() || subscriptions.isNotEmpty() || hasOneTimePurchases,
        isActive = activeEntitlements.isNotEmpty(),
        expiresAt = expiresAt,
        productId = subscription?.productId,
        isTrial = subscription?.status == "trialing",
        willRenew = subscription != null && renewal !in NOT_RENEWING,
        hasBillingIssue = subscription?.status in BILLING_ISSUE_STATUSES,
        isCanceled = renewal == "will_not_renew",
    )
}

private val NOT_RENEWING = setOf("will_not_renew", "will_pause")
private val BILLING_ISSUE_STATUSES = setOf("in_grace_period", "in_billing_retry")
