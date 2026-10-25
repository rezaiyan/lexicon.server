package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.describe
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.Jwts
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date

private val logger = KotlinLogging.logger {}

/** A user's Apple refresh token and the client id of ours it was issued to. */
data class AppleTokenGrant(val refreshToken: String, val clientId: String)

/**
 * Apple's Sign in with Apple token endpoints. A user's refresh token, kept from sign-in, lets
 * account deletion revoke it, which App Review requires and which unlinks the app from the user's
 * Apple ID: the next sign-up counts as a first sign-in, so Apple shares name and email again.
 *
 * Every call is best effort (`null`/`false` on failure) and does nothing until the Sign in with
 * Apple key is configured ([AppleConfig.canCallTokenApi]).
 */
@Service
class AppleTokenClient(
    restClientBuilder: RestClient.Builder,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val restClient = restClientBuilder.build()

    private val signingKey: PrivateKey? by lazy { parsePrivateKey(appProperties.apple.privateKey) }

    /** Exchanges a sign-in's one-time authorization code for the user's refresh token. */
    fun refreshTokenFor(authorizationCode: String, clientId: String): String? {
        val secret = clientSecret(clientId) ?: return null
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", secret)
            add("code", authorizationCode)
            add("grant_type", "authorization_code")
        }
        val response = try {
            restClient.post().uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body<JsonNode>()
        } catch (e: RestClientException) {
            logger.warn { "Apple authorization code exchange failed: ${e.describe()}" }
            return null
        }
        val refreshToken = response?.get("refresh_token")?.asString()?.takeIf { it.isNotBlank() }
        if (refreshToken == null) logger.warn { "Apple token response carried no refresh token" }
        return refreshToken
    }

    /** Revokes [refreshToken]; true when Apple accepted it. */
    fun revoke(refreshToken: String, clientId: String): Boolean {
        val secret = clientSecret(clientId) ?: return false
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", secret)
            add("token", refreshToken)
            add("token_type_hint", "refresh_token")
        }
        return try {
            restClient.post().uri(REVOKE_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: RestClientException) {
            logger.warn { "Apple token revocation failed: ${e.describe()}" }
            false
        }
    }

    /** The short-lived ES256 JWT Apple accepts in place of a static client secret. */
    private fun clientSecret(clientId: String): String? {
        val config = appProperties.apple
        if (!config.canCallTokenApi) {
            logger.info { "Sign in with Apple key not configured; skipping Apple token call" }
            return null
        }
        val key = signingKey ?: return null
        val now = clock.instant()
        return Jwts.builder()
            .header().keyId(config.keyId).and()
            .issuer(config.teamId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(CLIENT_SECRET_LIFETIME)))
            .audience().add(APPLE_ISSUER).and()
            .subject(clientId)
            .signWith(key, Jwts.SIG.ES256)
            .compact()
    }

    private fun parsePrivateKey(pem: String): PrivateKey? {
        if (pem.isBlank()) return null
        val body = pem
            .replace("\\n", "\n") // a PEM pasted into one env var line
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("") { it.trim() }
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Sign in with Apple key is not valid Base64 PEM" }
            null
        } catch (e: GeneralSecurityException) {
            logger.error(e) { "Sign in with Apple key is not a PKCS#8 EC key" }
            null
        }
    }

    private companion object {
        const val APPLE_ISSUER = "https://appleid.apple.com"
        const val TOKEN_URL = "$APPLE_ISSUER/auth/token"
        const val REVOKE_URL = "$APPLE_ISSUER/auth/revoke"
        val CLIENT_SECRET_LIFETIME: Duration = Duration.ofMinutes(5)
    }
}
