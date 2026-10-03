package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.RevenueCatConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    private fun state(json: String) = mapper.readValue<SubscriberResponse>(json).subscriber!!.toEntitlementState(now)

    @Test
    fun `active monthly subscription that will renew`() {
        val s = state(
            """
            {"request_date":"2026-10-02T12:00:00Z","subscriber":{
              "original_app_user_id":"42",
              "entitlements":{"premium":{"expires_date":"2026-11-02T12:00:00Z","grace_period_expires_date":null,
                "product_identifier":"lexicon_monthly","purchase_date":"2026-10-02T12:00:00Z"}},
              "subscriptions":{"lexicon_monthly":{"expires_date":"2026-11-02T12:00:00Z","period_type":"normal",
                "unsubscribe_detected_at":null,"refunded_at":null,"store":"play_store","is_sandbox":false}},
              "non_subscriptions":{}, "other_purchases":{}
            }}
            """
        )

        assertTrue(s.isActive)
        assertTrue(s.hasPurchaseHistory)
        assertTrue(s.willRenew)
        assertFalse(s.isTrial)
        assertEquals(Instant.parse("2026-11-02T12:00:00Z"), s.expiresAt)
        assertEquals("lexicon_monthly", s.productId)
    }

    @Test
    fun `trial with auto-renew turned off`() {
        val s = state(
            """
            {"subscriber":{
              "entitlements":{"premium":{"expires_date":"2026-10-09T12:00:00Z","product_identifier":"lexicon_annual"}},
              "subscriptions":{"lexicon_annual":{"expires_date":"2026-10-09T12:00:00Z","period_type":"trial",
                "unsubscribe_detected_at":"2026-10-03T08:00:00Z"}}
            }}
            """
        )

        assertTrue(s.isActive)
        assertTrue(s.isTrial)
        assertFalse(s.willRenew)
    }

    @Test
    fun `expired entitlement still counts as purchase history`() {
        val s = state(
            """
            {"subscriber":{
              "entitlements":{"premium":{"expires_date":"2026-09-01T12:00:00Z","product_identifier":"lexicon_monthly"}},
              "subscriptions":{"lexicon_monthly":{"expires_date":"2026-09-01T12:00:00Z","period_type":"normal"}}
            }}
            """
        )

        assertFalse(s.isActive)
        assertTrue(s.hasPurchaseHistory)
        assertNull(s.expiresAt)
    }

    @Test
    fun `billing grace period keeps access`() {
        val s = state(
            """
            {"subscriber":{
              "entitlements":{"premium":{"expires_date":"2026-10-01T12:00:00Z",
                "grace_period_expires_date":"2026-10-08T12:00:00Z","product_identifier":"lexicon_monthly"}},
              "subscriptions":{}
            }}
            """
        )

        assertTrue(s.isActive)
        assertEquals(Instant.parse("2026-10-08T12:00:00Z"), s.expiresAt)
    }

    @Test
    fun `lifetime purchase has no expiry`() {
        val s = state(
            """
            {"subscriber":{
              "entitlements":{"premium":{"expires_date":null,"product_identifier":"lexicon_lifetime"}},
              "subscriptions":{},
              "non_subscriptions":{"lexicon_lifetime":[{"id":"x","purchase_date":"2026-01-01T00:00:00Z"}]}
            }}
            """
        )

        assertTrue(s.isActive)
        assertNull(s.expiresAt)
        assertFalse(s.willRenew)
    }

    @Test
    fun `brand new customer has no history`() {
        val s = state("""{"subscriber":{"entitlements":{},"subscriptions":{},"non_subscriptions":{}}}""")

        assertFalse(s.isActive)
        assertFalse(s.hasPurchaseHistory)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private val subscriberUrl = "https://api.revenuecat.com/v1/subscribers/rc%20user%2F42"

    @Test
    fun `fetchEntitlementState sends an authorized GET with the id encoded once and maps the body`() {
        val (client, server) = client()
        server.expect(requestTo(URI(subscriberUrl)))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test"))
            .andRespond(
                withSuccess(
                    """{"subscriber":{"entitlements":{"premium":{"expires_date":"2099-01-01T00:00:00Z",
                      "product_identifier":"lexicon_monthly"}},"subscriptions":{}}}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val state = requireNotNull(client.fetchEntitlementState("rc user/42"))

        assertTrue(state.isActive)
        assertEquals("lexicon_monthly", state.productId)
        server.verify()
    }

    @Test
    fun `fetchEntitlementState returns null on an error status`() {
        val (client, server) = client()
        server.expect(requestTo(URI(subscriberUrl))).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertNull(client.fetchEntitlementState("rc user/42"))
    }

    @Test
    fun `fetchEntitlementState returns null when RevenueCat is unreachable`() {
        val (client, server) = client()
        server.expect(requestTo(URI(subscriberUrl))).andRespond(withException(IOException("connection refused")))

        assertNull(client.fetchEntitlementState("rc user/42"))
    }

    @Test
    fun `deleteSubscriber sends authorized DELETE for the customer and returns true`() {
        val (client, server) = client()
        server.expect(requestTo(URI(subscriberUrl)))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk_test"))
            .andRespond(withSuccess())

        assertTrue(client.deleteSubscriber("rc user/42"))
        server.verify()
    }

    @Test
    fun `deleteSubscriber returns false on an error status without throwing`() {
        val (client, server) = client()
        server.expect(requestTo(URI("https://api.revenuecat.com/v1/subscribers/42")))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFalse(client.deleteSubscriber("42"))
    }

    @Test
    fun `deleteSubscriber makes no request when the API key is not configured`() {
        val (client, server) = client(apiKey = "")

        assertFalse(client.deleteSubscriber("42"))
        server.verify() // no expectations: any request would have failed
    }

    private fun client(apiKey: String = "sk_test"): Pair<RevenueCatClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return RevenueCatClient(builder, AppProperties(revenuecat = RevenueCatConfig(apiKey = apiKey)), clock = fixedClock()) to server
    }
}
