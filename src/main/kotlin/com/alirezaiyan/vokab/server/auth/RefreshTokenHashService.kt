package com.alirezaiyan.vokab.server.auth

import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Generates refresh tokens and their lookup hashes. A token is 256 random bits, so a plain SHA-256
 * is enough to store it: there is nothing to brute-force, and the hash can be looked up directly.
 */
@Service
class RefreshTokenHashService {

    private val secureRandom = SecureRandom()

    /** SHA-256 of [token], stored in (and looked up by) the token_hash column. */
    fun createLookupHash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
    }

    /** A Base64URL-encoded token of [length] secure random bytes. */
    fun generateSecureToken(length: Int = 32): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
