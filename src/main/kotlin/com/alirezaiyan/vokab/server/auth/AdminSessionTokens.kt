package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * The admin key check and the admin dashboard's session tokens.
 *
 * A session token is `<expiresEpochSeconds>.<nonce>.<hmac>`, signed with a key derived from
 * `app.security.admin-api-key`: stateless, and rotating the admin key ends every session.
 */
@Component
class AdminSessionTokens(
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @PostConstruct
    fun warnOnWeakKey() {
        val key = appProperties.security.adminApiKey
        if (key.isNotEmpty() && key.length < MIN_DASHBOARD_KEY_LENGTH) {
            logger.warn {
                "app.security.admin-api-key is shorter than $MIN_DASHBOARD_KEY_LENGTH characters: " +
                    "admin dashboard sign-in is disabled until it is rotated to a longer random key"
            }
        }
    }

    /** Constant-time: digests first, so neither content nor length leaks through timing. */
    fun keyMatches(candidate: String?): Boolean {
        val configured = appProperties.security.adminApiKey
        if (candidate.isNullOrEmpty() || configured.isEmpty()) return false
        return MessageDigest.isEqual(sha256(candidate), sha256(configured))
    }

    /** Browser sign-in is exposed to the internet, so it needs a key too long to guess. */
    fun dashboardSignInEnabled(): Boolean =
        appProperties.security.adminApiKey.length >= MIN_DASHBOARD_KEY_LENGTH

    fun issue(): AdminSession {
        val expiresAt = Instant.now(clock).plus(SESSION_TTL)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val payload = "${expiresAt.epochSecond}.${base64Url(nonce)}"
        return AdminSession(token = "$payload.${sign(payload)}", expiresAt = expiresAt)
    }

    /** @return the session's expiry, or null when the token is missing, forged or expired. */
    fun validate(token: String?): Instant? {
        if (token.isNullOrEmpty() || !dashboardSignInEnabled()) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = "${parts[0]}.${parts[1]}"
        val signatureOk = MessageDigest.isEqual(sign(payload).toByteArray(), parts[2].toByteArray())
        val expiresAt = parts[0].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        return expiresAt.takeIf { signatureOk && it.isAfter(Instant.now(clock)) }
    }

    private fun sign(payload: String): String {
        val signingKey = hmac(appProperties.security.adminApiKey.toByteArray(), SIGNING_KEY_LABEL.toByteArray())
        return base64Url(hmac(signingKey, payload.toByteArray()))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    data class AdminSession(val token: String, val expiresAt: Instant)

    companion object {
        const val MIN_DASHBOARD_KEY_LENGTH = 32
        val SESSION_TTL: Duration = Duration.ofHours(4)
        private const val NONCE_BYTES = 16
        private const val HMAC = "HmacSHA256"
        private const val SIGNING_KEY_LABEL = "lexicon-admin-session-v1"
    }
}
