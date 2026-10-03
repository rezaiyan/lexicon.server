package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.describe
import com.alirezaiyan.vokab.server.exception.UpstreamServiceException
import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Apple's Sign in with Apple signing keys (JWKS), cached for [CACHE_TTL].
 *
 * An unknown `kid` usually means Apple rotated its keys, so it triggers a refetch — but at most
 * once per [MIN_REFRESH_INTERVAL], otherwise any token with a made-up `kid` would make us call
 * Apple on every request.
 */
@Service
class ApplePublicKeyService(
    restClientBuilder: RestClient.Builder,
    private val clock: Clock,
) {
    private val restClient = restClientBuilder.build()

    // Replaced as a whole on refresh, so readers never see a half-filled key set.
    @Volatile private var keys: Map<String, PublicKey> = emptyMap()
    @Volatile private var fetchedAt: Instant = Instant.EPOCH

    /**
     * The signing key with id [kid], or `null` when Apple's current key set doesn't contain it.
     *
     * @throws UpstreamServiceException when the keys can't be fetched and [kid] isn't cached, so
     *   an Apple outage surfaces as 502 instead of rejecting a valid token.
     */
    fun getPublicKey(kid: String): PublicKey? {
        val age = Duration.between(fetchedAt, clock.instant())
        val cached = keys[kid]
        if (cached != null && age < CACHE_TTL) return cached
        if (cached == null && age < MIN_REFRESH_INTERVAL) return null

        return if (refresh()) {
            keys[kid]
        } else {
            cached ?: throw UpstreamServiceException("Apple signing keys unavailable")
        }
    }

    /** Fetches the key set; true when [keys] is current. Concurrent callers share one fetch. */
    @Synchronized
    private fun refresh(): Boolean {
        val now = clock.instant()
        if (Duration.between(fetchedAt, now) < MIN_REFRESH_INTERVAL) return true

        val jwks = try {
            restClient.get().uri(APPLE_KEYS_URL).retrieve().body<JsonNode>()
        } catch (e: RestClientException) {
            logger.error { "Failed to fetch Apple signing keys: ${e.describe()}" }
            return false
        }

        val parsed = jwks?.get("keys")?.mapNotNull { it.toRsaKeyOrNull() }?.toMap().orEmpty()
        if (parsed.isEmpty()) {
            logger.error { "Apple signing key response contained no usable RSA keys" }
            return false
        }

        keys = parsed
        fetchedAt = now
        logger.info { "Cached ${parsed.size} Apple signing keys" }
        return true
    }

    private fun JsonNode.toRsaKeyOrNull(): Pair<String, PublicKey>? {
        if (get("kty")?.asText() != "RSA") return null
        val kid = get("kid")?.asText() ?: return null
        val modulus = get("n")?.asText() ?: return null
        val exponent = get("e")?.asText() ?: return null
        return try {
            kid to rsaPublicKey(modulus, exponent)
        } catch (e: IllegalArgumentException) {
            logger.warn { "Skipping malformed Apple signing key kid=$kid" }
            null
        } catch (e: GeneralSecurityException) {
            logger.warn { "Skipping invalid Apple signing key kid=$kid" }
            null
        }
    }

    /** RSA key from Base64URL-encoded modulus and exponent (JWK `n`, `e`). */
    private fun rsaPublicKey(modulusBase64: String, exponentBase64: String): PublicKey {
        val decoder = Base64.getUrlDecoder()
        val spec = RSAPublicKeySpec(
            BigInteger(1, decoder.decode(modulusBase64)),
            BigInteger(1, decoder.decode(exponentBase64)),
        )
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private companion object {
        const val APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys"
        val CACHE_TTL: Duration = Duration.ofHours(24)
        val MIN_REFRESH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
