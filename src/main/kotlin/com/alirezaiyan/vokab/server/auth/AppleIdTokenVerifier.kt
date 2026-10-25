package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.LocatorAdapter
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.ProtectedHeader
import io.jsonwebtoken.UnsupportedJwtException
import org.springframework.stereotype.Component
import java.security.Key
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/** Verified identity claims from a Sign in with Apple ID token. */
data class AppleIdClaims(
    /** Stable, team-scoped Apple user identifier (`sub`). */
    val subject: String,
    /** Absent when the user hides their email or on repeat sign-ins. */
    val email: String?,
    /** `email_verified`; Apple sends it as a boolean or as the string "true". */
    val emailVerified: Boolean = false,
    /** The client id of ours the token was minted for (`aud`); Apple's token endpoints need it. */
    val clientId: String? = null,
)

/**
 * Verifies Apple ID tokens: RS256 signature against Apple's JWKS (by `kid`), issuer, expiry,
 * and that the audience is one of our own client ids. Without the audience check, a token Apple
 * minted for any other app would be accepted.
 */
@Component
class AppleIdTokenVerifier(
    private val applePublicKeyService: ApplePublicKeyService,
    private val appProperties: AppProperties,
) {
    private val parser: JwtParser = Jwts.parser()
        .keyLocator(object : LocatorAdapter<Key>() {
            override fun locate(header: ProtectedHeader): Key {
                val kid = header.keyId ?: throw MalformedJwtException("Apple token header has no 'kid'")
                return applePublicKeyService.getPublicKey(kid)
                    ?: throw UnsupportedJwtException("Unknown Apple signing key: $kid")
            }
        })
        .requireIssuer(ISSUER)
        .build()

    /**
     * Verifies a Sign in with Apple ID token; `null` if it must not be trusted.
     *
     * @param rawNonce the nonce the client generated for this sign-in, if it sent one. The client
     *   passes `SHA-256(rawNonce)` as lowercase hex to Apple (`ASAuthorizationAppleIDRequest.nonce` on
     *   iOS, the `nonce` parameter of the web/Android flow) and Apple echoes it in the token's `nonce`
     *   claim, so a token minted for another sign-in can't be replayed here.
     */
    fun verify(idToken: String, rawNonce: String? = null): AppleIdClaims? {
        val claims = verifyClaims(idToken) ?: return null
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: run {
            logger.warn { "Apple token rejected: missing subject" }
            return null
        }
        if (rawNonce != null && !nonceMatches(claims.get("nonce", String::class.java), rawNonce)) {
            logger.warn { "Apple token rejected: nonce mismatch" }
            return null
        }
        return AppleIdClaims(
            subject = subject,
            email = claims.get("email", String::class.java)?.takeIf { it.isNotBlank() },
            emailVerified = when (val verified = claims["email_verified"]) {
                is Boolean -> verified
                is String -> verified.equals("true", ignoreCase = true)
                else -> false
            },
            clientId = claims.audience.orEmpty().firstOrNull { it in appProperties.apple.clientIdSet },
        )
    }

    /**
     * Verifies any Apple-issued JWT addressed to us (ID tokens and server-to-server notifications):
     * signature, issuer, expiry and audience. Returns the raw claims, or `null` if untrusted.
     */
    fun verifyClaims(token: String): Claims? {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (e: JwtException) {
            logger.warn { "Apple token rejected: ${e.message}" }
            return null
        } catch (e: IllegalArgumentException) {
            logger.warn { "Apple token rejected: ${e.message}" }
            return null
        }

        val audience = claims.audience.orEmpty()
        if (audience.none { it in appProperties.apple.clientIdSet }) {
            logger.warn { "Apple token rejected: audience $audience is not an allowed client id" }
            return null
        }
        return claims
    }

    private fun nonceMatches(tokenNonce: String?, rawNonce: String): Boolean {
        if (tokenNonce == null) return false
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), tokenNonce.lowercase().toByteArray())
    }

    private companion object {
        const val ISSUER = "https://appleid.apple.com"
    }
}
