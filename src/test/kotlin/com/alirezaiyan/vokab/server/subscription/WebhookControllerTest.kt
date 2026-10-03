package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AppProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class WebhookControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var appProperties: AppProperties

    @MockitoBean
    private lateinit var subscriptionService: SubscriptionService

    private val testSecret = "test-webhook-secret-key"

    /** Shape of a real RevenueCat v1 webhook: everything lives under `event`. */
    private val samplePayload = """
        {
          "api_version": "1.0",
          "event": {
            "id": "CDD2A2A5-1234-4C1A-9F5B-000000000001",
            "type": "INITIAL_PURCHASE",
            "app_user_id": "42",
            "original_app_user_id": "${'$'}RCAnonymousID:abc",
            "aliases": ["${'$'}RCAnonymousID:abc", "42"],
            "product_id": "lexicon_premium_monthly",
            "period_type": "TRIAL",
            "purchased_at_ms": 1704067200000,
            "expiration_at_ms": 1704672000000,
            "environment": "PRODUCTION",
            "store": "PLAY_STORE",
            "transaction_id": "GPA.1111-2222",
            "original_transaction_id": "GPA.1111-2222",
            "entitlement_ids": ["premium"],
            "price": 4.99,
            "currency": "USD",
            "event_timestamp_ms": 1704067201000
          }
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        appProperties.revenuecat.webhookSecret = testSecret
    }

    @AfterEach
    fun tearDown() {
        appProperties.revenuecat.webhookSecret = ""
        appProperties.revenuecat.webhookSigningSecret = ""
    }

    private val signingSecret = "test-signing-secret"

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun postWebhook(
        body: String = samplePayload,
        authorization: String? = testSecret,
        signature: String? = null,
    ) = mockMvc.post("/api/v1/webhooks/revenuecat") {
        contentType = MediaType.APPLICATION_JSON
        authorization?.let { header("Authorization", it) }
        signature?.let { header("X-RevenueCat-Webhook-Signature", it) }
        content = body
    }

    /** `t=<unix>,v1=<hex>` as RevenueCat sends it: HMAC-SHA256 over "<t>.<raw body>". */
    private fun sign(body: String, at: Instant = Instant.now()): String {
        val t = at.epochSecond
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingSecret.toByteArray(), "HmacSHA256")) }
        val hex = mac.doFinal("$t.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$t,v1=$hex"
    }

    // ── authorization ──────────────────────────────────────────────────────────

    @Test
    fun `should return 200 when Authorization header matches secret`() {
        postWebhook().andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
        }

        verify(subscriptionService).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should accept Bearer prefixed Authorization header`() {
        postWebhook(authorization = "Bearer $testSecret").andExpect { status { isOk() } }
    }

    @Test
    fun `should return 401 when Authorization header is missing and secret is configured`() {
        postWebhook(authorization = null).andExpect { status { isUnauthorized() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should return 401 when Authorization header is wrong`() {
        postWebhook(authorization = "nope").andExpect { status { isUnauthorized() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should reject every webhook when no secret is configured`() {
        appProperties.revenuecat.webhookSecret = ""

        postWebhook(authorization = null).andExpect { status { isUnauthorized() } }
        postWebhook(authorization = "anything").andExpect { status { isUnauthorized() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    // ── HMAC signature ─────────────────────────────────────────────────────────

    @Test
    fun `should accept a correctly signed delivery when signing is enabled`() {
        appProperties.revenuecat.webhookSigningSecret = signingSecret

        postWebhook(signature = sign(samplePayload)).andExpect { status { isOk() } }

        verify(subscriptionService).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should return 401 for an unsigned delivery when signing is enabled`() {
        appProperties.revenuecat.webhookSigningSecret = signingSecret

        postWebhook().andExpect { status { isUnauthorized() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should return 401 when the signed body was altered in transit`() {
        appProperties.revenuecat.webhookSigningSecret = signingSecret
        val signature = sign(samplePayload)

        postWebhook(body = samplePayload.replace("\"42\"", "\"43\""), signature = signature)
            .andExpect { status { isUnauthorized() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should return 401 for a replayed delivery with an old signature timestamp`() {
        appProperties.revenuecat.webhookSigningSecret = signingSecret

        postWebhook(signature = sign(samplePayload, at = Instant.now().minusSeconds(3600)))
            .andExpect { status { isUnauthorized() } }
    }

    // ── payload parsing ────────────────────────────────────────────────────────

    @Test
    fun `should parse real RevenueCat payload and ignore unknown fields`() {
        var captured: RevenueCatWebhookEvent? = null
        doAnswer { invocation ->
            captured = invocation.getArgument(0)
            null
        }.`when`(subscriptionService).handleRevenueCatWebhook(anyArg())

        postWebhook().andExpect { status { isOk() } }

        val event = requireNotNull(captured).event
        assertEquals("INITIAL_PURCHASE", event.type)
        assertEquals("42", event.app_user_id)
        assertEquals("lexicon_premium_monthly", event.product_id)
        assertEquals(1704672000000, event.expiration_at_ms)
        assertTrue(event.isTrial)
        assertEquals("GPA.1111-2222", event.subscriptionKey)
        assertEquals(listOf("42"), event.candidateUserIds)
    }

    @Test
    fun `should accept minimal TEST event`() {
        postWebhook(body = """{"api_version":"1.0","event":{"id":"t1","type":"TEST"}}""")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `should return 400 for invalid JSON`() {
        postWebhook(body = "{ not json").andExpect { status { isBadRequest() } }

        verify(subscriptionService, never()).handleRevenueCatWebhook(anyArg())
    }

    @Test
    fun `should return 400 when event object is missing`() {
        postWebhook(body = """{"api_version":"1.0"}""").andExpect { status { isBadRequest() } }
    }

    // ── processing failures ────────────────────────────────────────────────────

    @Test
    fun `should return 500 without leaking exception message when service throws`() {
        doThrow(RuntimeException("db password=hunter2"))
            .`when`(subscriptionService).handleRevenueCatWebhook(anyArg())

        postWebhook().andExpect {
            status { isInternalServerError() }
            jsonPath("$.message") { value("Failed to process webhook") }
        }
    }
}
