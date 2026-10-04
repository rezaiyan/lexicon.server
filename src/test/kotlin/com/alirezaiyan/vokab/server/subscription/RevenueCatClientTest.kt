package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RevenueCatConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.URI
import java.time.Instant

class RevenueCatClientTest {

    private val periodEnd = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()

    // ── Mapping (shapes as returned by the v2 API) ──────────────────────────────

    private fun subscription(
        status: String = "active",
        renewal: String = "will_renew",
        givesAccess: Boolean = true,
        endsAt: Long = periodEnd,
    ) = SubscriptionDto(
        status = status,
        autoRenewalStatus = renewal,
        givesAccess = givesAccess,
        currentPeriodEndsAt = endsAt,
        productId = "prod_monthly",
    )

    private fun access(expiresAt: Long? = periodEnd) = listOf(ActiveEntitlementDto(expiresAt))

    @Test
    fun `active subscription that will renew`() {
        val s = toEntitlementState(access(), listOf(subscription()), hasOneTimePurchases = false)

        assertTrue(s.isActive)
        assertTrue(s.hasPurchaseHistory)
        assertTrue(s.willRenew)
        assertFalse(s.isTrial)
        assertFalse(s.isCanceled)
        assertEquals(Instant.ofEpochMilli(periodEnd), s.expiresAt)
        assertEquals("prod_monthly", s.productId)
    }

    @Test
    fun `trial with auto-renew turned off is a canceled trial`() {
        val s = toEntitlementState(access(), listOf(subscription(status = "trialing", renewal = "will_not_renew")), false)

        assertTrue(s.isTrial)
        assertFalse(s.willRenew)
        assertTrue(s.isCanceled)
    }

    @Test
    fun `scheduled pause does not renew but is not a cancellation`() {
        val s = toEntitlementState(access(), listOf(subscription(renewal = "will_pause")), false)

        assertFalse(s.willRenew)
        assertFalse(s.isCanceled)
    }

    @Test
    fun `grace period and billing retry are billing issues`() {
        listOf("in_grace_period", "in_billing_retry").forEach { status ->
            val s = toEntitlementState(access(), listOf(subscription(status = status)), false)
            assertTrue(s.hasBillingIssue, status)
        }
    }

    @Test
    fun `the subscription giving access wins over older expired ones`() {
        val expired = subscription(status = "expired", renewal = "will_not_renew", givesAccess = false, endsAt = 1L)

        val s = toEntitlementState(access(), listOf(expired, subscription(renewal = "will_pause")), false)

        assertFalse(s.isCanceled)
    }

    @Test
    fun `expired subscriptions still count as purchase history`() {
        val expired = subscription(status = "expired", givesAccess = false)

        val s = toEntitlementState(emptyList(), listOf(expired), false)

        assertFalse(s.isActive)
        assertTrue(s.hasPurchaseHistory)
        assertNull(s.expiresAt)
    }

    @Test
    fun `lifetime access has no expiry`() {
        val s = toEntitlementState(access(expiresAt = null), emptyList(), hasOneTimePurchases = true)

        assertTrue(s.isActive)
        assertNull(s.expiresAt)
        assertFalse(s.willRenew)
    }

    @Test
    fun `customer without purchases has no history`() {
        val s = toEntitlementState(emptyList(), emptyList(), hasOneTimePurchases = false)

        assertFalse(s.isActive)
        assertFalse(s.hasPurchaseHistory)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private val base = "https://api.revenuecat.com/v2"
    private val customerUrl = "$base/projects/proj1/customers/rc%20user%2F42"

    private fun MockRestServiceServer.expectProject() {
        expect(ExpectedCount.once(), requestTo(URI("$base/projects")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test"))
            .andRespond(json("""{"items":[{"id":"proj1","object":"project"}]}"""))
    }

    @Test
    fun `fetchEntitlementState reads customer and subscriptions with ids encoded once`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl)))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test"))
            .andRespond(json("""{"object":"customer","active_entitlements":{"items":[{"expires_at":$periodEnd}]}}"""))
        server.expect(requestTo(URI("$customerUrl/subscriptions?limit=50")))
            .andRespond(
                json(
                    """{"items":[{"status":"active","auto_renewal_status":"will_not_renew","gives_access":true,
                      "current_period_ends_at":$periodEnd,"product_id":"prod_monthly"}]}"""
                )
            )

        val state = requireNotNull(client.fetchEntitlementState("rc user/42"))

        assertTrue(state.isActive)
        assertTrue(state.isCanceled)
        assertEquals(Instant.ofEpochMilli(periodEnd), state.expiresAt)
        server.verify()
    }

    @Test
    fun `fetchEntitlementState checks one-time purchases only when nothing else is known`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl))).andRespond(json("""{"active_entitlements":{"items":[]}}"""))
        server.expect(requestTo(URI("$customerUrl/subscriptions?limit=50"))).andRespond(json("""{"items":[]}"""))
        server.expect(requestTo(URI("$customerUrl/purchases?limit=1"))).andRespond(json("""{"items":[{"id":"p1"}]}"""))

        val state = requireNotNull(client.fetchEntitlementState("rc user/42"))

        assertTrue(state.hasPurchaseHistory)
        assertFalse(state.isActive)
        server.verify()
    }

    @Test
    fun `fetchEntitlementState treats an unknown customer as having no history`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl))).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertEquals(StoreEntitlementState.NONE, client.fetchEntitlementState("rc user/42"))
    }

    @Test
    fun `fetchEntitlementState returns null on an auth error so nothing is downgraded`() {
        val (client, server) = client()
        server.expect(requestTo(URI("$base/projects"))).andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertNull(client.fetchEntitlementState("rc user/42"))
    }

    @Test
    fun `fetchEntitlementState returns null when RevenueCat is unreachable`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl))).andRespond(withException(IOException("connection refused")))

        assertNull(client.fetchEntitlementState("rc user/42"))
    }

    @Test
    fun `project id is looked up once and reused`() {
        val (client, server) = client()
        server.expectProject()
        repeat(2) {
            server.expect(requestTo(URI("$base/projects/proj1/customers/42"))).andRespond(withStatus(HttpStatus.NOT_FOUND))
        }

        repeat(2) { client.fetchEntitlementState("42") }

        server.verify()
    }

    @Test
    fun `deleteSubscriber sends authorized DELETE for the customer and returns true`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl)))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test"))
            .andRespond(withSuccess())

        assertTrue(client.deleteSubscriber("rc user/42"))
        server.verify()
    }

    @Test
    fun `deleteSubscriber counts an unknown customer as deleted`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI(customerUrl))).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertTrue(client.deleteSubscriber("rc user/42"))
    }

    @Test
    fun `deleteSubscriber returns false on an error status without throwing`() {
        val (client, server) = client()
        server.expectProject()
        server.expect(requestTo(URI("$base/projects/proj1/customers/42")))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFalse(client.deleteSubscriber("42"))
    }

    @Test
    fun `deleteSubscriber makes no request when the API key is not configured`() {
        val (client, server) = client(apiKey = "")

        assertFalse(client.deleteSubscriber("42"))
        server.verify() // no expectations: any request would have failed
    }

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)

    private fun client(apiKey: String = "sk_test"): Pair<RevenueCatClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return RevenueCatClient(builder, AppProperties(revenuecat = RevenueCatConfig(apiKey = apiKey))) to server
    }
}
