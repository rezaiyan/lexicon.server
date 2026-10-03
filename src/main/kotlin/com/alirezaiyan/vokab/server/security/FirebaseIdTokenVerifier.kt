package com.alirezaiyan.vokab.server.security

import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/** Verified identity claims from a Firebase ID token (Google Sign-In). */
data class FirebaseIdClaims(
    val uid: String,
    val email: String?,
    val name: String?,
)

/**
 * Verifies Firebase ID tokens and tells a bad token apart from a Firebase outage.
 *
 * Returns `null` for a token that must not be trusted (bad signature, expired, wrong project).
 * Throws when verification could not run (signing certificates unreachable, Firebase not
 * initialised), so the caller answers 5xx and the client retries instead of discarding its session.
 */
@Component
class FirebaseIdTokenVerifier {

    fun verify(idToken: String): FirebaseIdClaims? {
        val token = try {
            FirebaseAuth.getInstance().verifyIdToken(idToken)
        } catch (e: FirebaseAuthException) {
            if (e.isTransient()) throw e
            logger.warn { "Firebase token rejected: ${e.authErrorCode ?: e.errorCode}" }
            return null
        } catch (e: IllegalArgumentException) {
            logger.warn { "Firebase token rejected: ${e.message}" }
            return null
        }
        return FirebaseIdClaims(uid = token.uid, email = token.email, name = token.name)
    }

    private fun FirebaseAuthException.isTransient(): Boolean =
        authErrorCode == AuthErrorCode.CERTIFICATE_FETCH_FAILED || errorCode in TRANSIENT_ERROR_CODES

    private companion object {
        val TRANSIENT_ERROR_CODES = setOf(ErrorCode.UNAVAILABLE, ErrorCode.DEADLINE_EXCEEDED, ErrorCode.INTERNAL)
    }
}
