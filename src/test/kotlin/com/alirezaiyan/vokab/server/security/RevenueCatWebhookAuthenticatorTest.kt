package com.alirezaiyan.vokab.server.security

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.RevenueCatConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RevenueCatWebhookAuthenticatorTest {

    // Known-answer vector computed independently (Python hmac) per RevenueCat's documented scheme:
    // HMAC-SHA256(secret, "<t>.<raw body>"). The body has non-ASCII text to prove byte exactness.
    private val signingSecret = "rc-signing-test-secret"
    private val timestamp = 1_790_000_000L
    private val body = """{"api_version":"1.0","event":{"id":"t1","type":"TEST","note":"café ✓"}}""".toByteArray()
    private val validSignature = "t=$timestamp,v1=a83cc982d91186b0fc753f7599eeeb4b2bdae23844cfc8b441406cfc7807c42c"

    private fun authenticator(
        secret: String = "shared",
        signing: String = "",
        now: Long = timestamp,
        tolerance: Long = 300,
    ) = RevenueCatWebhookAuthenticator(
        AppProperties(
            revenuecat = RevenueCatConfig(
                webhookSecret = secret,
                webhookSigningSecret = signing,
                webhookSignatureToleranceSeconds = tolerance,
            )
        ),
        Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
    )

    private fun rejected(result: WebhookAuthResult, reason: String) {
        assertTrue(result is WebhookAuthResult.Rejected, "expected rejection, got $result")
        assertEquals(reason, (result as WebhookAuthResult.Rejected).reason)
    }

    // ── Authorization header ─────────────────────────────────────────────────

    @Test
    fun `rejects everything when no webhook secret is configured`() {
        rejected(authenticator(secret = "").authenticate("anything", null, body), "webhook secret not configured")
        rejected(authenticator(secret = "").authenticate(null, null, body), "webhook secret not configured")
    }

    @Test
    fun `accepts matching Authorization value, bare or Bearer-prefixed`() {
        assertEquals(WebhookAuthResult.Authorized, authenticator().authenticate("shared", null, body))
        assertEquals(WebhookAuthResult.Authorized, authenticator().authenticate("Bearer shared", null, body))
    }

    @Test
    fun `rejects missing or wrong Authorization value`() {
        rejected(authenticator().authenticate(null, null, body), "missing or invalid Authorization header")
        rejected(authenticator().authenticate("nope", null, body), "missing or invalid Authorization header")
    }

    // ── HMAC signature ───────────────────────────────────────────────────────

    @Test
    fun `accepts the known-answer signature`() {
        assertEquals(
            WebhookAuthResult.Authorized,
            authenticator(signing = signingSecret).authenticate("shared", validSignature, body),
        )
    }

    @Test
    fun `signature is still required to pass the Authorization check`() {
        rejected(
            authenticator(signing = signingSecret).authenticate("nope", validSignature, body),
            "missing or invalid Authorization header",
        )
    }

    @Test
    fun `rejects missing signature when signing is enabled`() {
        rejected(authenticator(signing = signingSecret).authenticate("shared", null, body), "missing signature header")
    }

    @Test
    fun `rejects a tampered body`() {
        val tampered = body.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        rejected(authenticator(signing = signingSecret).authenticate("shared", validSignature, tampered), "signature mismatch")
    }

    @Test
    fun `rejects a signature made with another secret`() {
        rejected(authenticator(signing = "other").authenticate("shared", validSignature, body), "signature mismatch")
    }

    @Test
    fun `rejects a replayed delivery outside the tolerance window`() {
        rejected(
            authenticator(signing = signingSecret, now = timestamp + 301).authenticate("shared", validSignature, body),
            "signature timestamp outside tolerance",
        )
        rejected(
            authenticator(signing = signingSecret, now = timestamp - 301).authenticate("shared", validSignature, body),
            "signature timestamp outside tolerance",
        )
    }

    @Test
    fun `accepts a delivery at the edge of the tolerance window`() {
        assertEquals(
            WebhookAuthResult.Authorized,
            authenticator(signing = signingSecret, now = timestamp + 300).authenticate("shared", validSignature, body),
        )
    }

    @Test
    fun `rejects malformed signature headers`() {
        val auth = authenticator(signing = signingSecret)
        rejected(auth.authenticate("shared", "v1=abc", body), "signature header has no timestamp")
        rejected(auth.authenticate("shared", "t=$timestamp", body), "signature header has no v1 signature")
        rejected(auth.authenticate("shared", "t=soon,v1=abc", body), "signature timestamp is not a number")
        rejected(auth.authenticate("shared", "garbage", body), "signature header has no timestamp")
    }

    @Test
    fun `tolerates spaces and uppercase hex in the signature header`() {
        val spaced = "t=$timestamp, v1=${validSignature.substringAfter("v1=").uppercase()}"
        assertEquals(WebhookAuthResult.Authorized, authenticator(signing = signingSecret).authenticate("shared", spaced, body))
    }
}
