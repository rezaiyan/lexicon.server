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

private val logger = KotlinLogging.logger {}

/** Verified identity claims from a Sign in with Apple ID token. */
data class AppleIdClaims(
    /** Stable, team-scoped Apple user identifier (`sub`). */
    val subject: String,
    /** Absent when the user hides their email or on repeat sign-ins. */
    val email: String?,
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

    /** Verifies a Sign in with Apple ID token; `null` if it must not be trusted. */
    fun verify(idToken: String): AppleIdClaims? {
        val claims = verifyClaims(idToken) ?: return null
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: run {
            logger.warn { "Apple token rejected: missing subject" }
            return null
        }
        return AppleIdClaims(
            subject = subject,
            email = claims.get("email", String::class.java)?.takeIf { it.isNotBlank() },
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

    private companion object {
        const val ISSUER = "https://appleid.apple.com"
    }
}
