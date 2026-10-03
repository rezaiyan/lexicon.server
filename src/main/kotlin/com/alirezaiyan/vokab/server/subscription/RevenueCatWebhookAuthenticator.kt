package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

sealed interface WebhookAuthResult {
    data object Authorized : WebhookAuthResult
    /** [reason] is for logs only; never echo it to the caller. */
    data class Rejected(val reason: String) : WebhookAuthResult
}

/**
 * Authenticates RevenueCat webhook deliveries. Fails closed:
 *
 * 1. The static `Authorization` value (`app.revenuecat.webhook-secret`) is **required**; when it
 *    isn't configured every delivery is rejected.
 * 2. When `app.revenuecat.webhook-signing-secret` is set, the delivery must also carry a valid
 *    `X-RevenueCat-Webhook-Signature: t=<unix>,v1=<hex>` — HMAC-SHA256 over `"<t>.<raw body>"` —
 *    with `t` within the configured tolerance (replay protection). RevenueCat re-signs every
 *    retry, so the tolerance only has to cover clock skew and request latency.
 *
 * https://www.revenuecat.com/docs/integrations/webhooks#webhook-signature-verification-hmac
 */
@Component
class RevenueCatWebhookAuthenticator(
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    init {
        if (appProperties.revenuecat.webhookSecret.isBlank()) {
            logger.error { "app.revenuecat.webhook-secret is not set — all RevenueCat webhooks will be rejected" }
        }
    }

    /** @param body the request body exactly as received; the signature covers these bytes. */
    fun authenticate(authorization: String?, signature: String?, body: ByteArray): WebhookAuthResult {
        val config = appProperties.revenuecat
        if (config.webhookSecret.isBlank()) {
            return WebhookAuthResult.Rejected("webhook secret not configured")
        }
        if (!authorizationMatches(authorization, config.webhookSecret)) {
            return WebhookAuthResult.Rejected("missing or invalid Authorization header")
        }
        if (config.webhookSigningSecret.isNotBlank()) {
            verifySignature(signature, body, config.webhookSigningSecret, config.webhookSignatureToleranceSeconds)
                ?.let { return WebhookAuthResult.Rejected(it) }
        }
        return WebhookAuthResult.Authorized
    }

    private fun authorizationMatches(header: String?, secret: String): Boolean {
        val provided = header?.trim()?.removePrefix("Bearer ")?.trim() ?: return false
        return constantTimeEquals(provided, secret)
    }

    /** Returns the rejection reason, or null when the signature is valid. */
    private fun verifySignature(header: String?, body: ByteArray, secret: String, toleranceSeconds: Long): String? {
        if (header.isNullOrBlank()) return "missing signature header"
        val parts = header.split(",").mapNotNull { part ->
            part.split("=", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() }
        }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second ?: return "signature header has no timestamp"
        val signatures = parts.filter { it.first == "v1" }.map { it.second.lowercase() }
        if (signatures.isEmpty()) return "signature header has no v1 signature"

        val signedAt = timestamp.toLongOrNull() ?: return "signature timestamp is not a number"
        if (abs(clock.instant().epochSecond - signedAt) > toleranceSeconds) {
            return "signature timestamp outside tolerance"
        }

        // Sign the header's timestamp text as-is: the signed payload is "<t>.<raw body>".
        val expected = hmacSha256Hex(secret, "$timestamp.".toByteArray(Charsets.UTF_8) + body)
        return if (signatures.any { constantTimeEquals(it, expected) }) null else "signature mismatch"
    }

    private fun hmacSha256Hex(secret: String, payload: ByteArray): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val SIGNATURE_HEADER = "X-RevenueCat-Webhook-Signature"
        private const val HMAC_SHA256 = "HmacSHA256"
    }
}
