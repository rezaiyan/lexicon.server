package com.alirezaiyan.vokab.server.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * Service for hashing and verifying refresh tokens using BCrypt
 * Uses SHA-256 for fast lookup and BCrypt for secure verification
 */
@Service
class RefreshTokenHashService {
    
    private val passwordEncoder = BCryptPasswordEncoder(12)
    private val secureRandom = SecureRandom()
    
    /**
     * Hashes a refresh token for storage in the database using BCrypt
     */
    fun hashToken(token: String): String {
        return passwordEncoder.encode(token)
    }
    
    /**
     * Creates a lookup hash (SHA-256) for efficient token lookup
     * This is used as the token_hash column for fast database queries
     */
    fun createLookupHash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
    }
    
    /**
     * Verifies a refresh token against a stored BCrypt hash
     */
    fun verifyToken(token: String, hashedToken: String): Boolean {
        return passwordEncoder.matches(token, hashedToken)
    }
    
    /**
     * Generates a secure random token for refresh token
     */
    fun generateSecureToken(length: Int = 32): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    
    /**
     * Generates a unique family ID for token rotation
     */
    fun generateFamilyId(): String {
        return generateSecureToken(32)
    }
}

