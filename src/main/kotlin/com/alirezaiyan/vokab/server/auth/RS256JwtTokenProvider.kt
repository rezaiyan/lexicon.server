package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.*
import java.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** A signature-checked access token: its user and `iat` (null if the token carries none). */
data class VerifiedAccessToken(val userId: Long, val issuedAt: Instant?)

/**
 * JWT token provider using RS256 algorithm (asymmetric keys)
 * Automatically generates and persists RSA keys to ensure tokens remain valid across server restarts.
 * Supports key rotation and JWKS endpoint.
 */
@Component
class RS256JwtTokenProvider(
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val keyPair: KeyPair = initializeKeyPair()

    /**
     * Initialize key pair with the following strategy:
     * 1. If raw key content is provided via environment variables (JWT_PRIVATE_KEY / JWT_PUBLIC_KEY), use them
     * 2. If key files exist on disk, load them
     * 3. Only if neither is present, generate new keys and save to files
     *
     * Strategy 1 is critical for deployments with ephemeral filesystems (Railway, Fly.io, Render, etc.)
     * where files don't survive restarts/redeploys.
     *
     * Keys that are configured or present but can't be loaded fail startup: silently generating a new
     * pair would invalidate every issued token and hide the misconfiguration.
     */
    private fun initializeKeyPair(): KeyPair {
        val rawPrivateKey = appProperties.jwt.privateKey
        val rawPublicKey = appProperties.jwt.publicKey

        // Strategy 1: Load from environment variables (Base64-encoded key content)
        if (rawPrivateKey.isNotBlank() || rawPublicKey.isNotBlank()) {
            check(rawPrivateKey.isNotBlank() && rawPublicKey.isNotBlank()) {
                "Only one of JWT_PRIVATE_KEY / JWT_PUBLIC_KEY is set; both are required"
            }
            logger.info { "Loading RSA key pair from environment variables..." }
            return try {
                loadKeyPairFromContent(rawPrivateKey, rawPublicKey)
            } catch (e: Exception) {
                throw IllegalStateException("JWT_PRIVATE_KEY / JWT_PUBLIC_KEY are set but could not be loaded", e)
            }
        }

        // Strategy 2 & 3: Load from files or generate
        return loadOrGenerateFromFiles()
    }

    private fun loadOrGenerateFromFiles(): KeyPair {
        val privateKeyPath = appProperties.jwt.privateKeyPath.ifBlank { "./keys/jwt-private-key.pem" }
        val publicKeyPath = appProperties.jwt.publicKeyPath.ifBlank { "./keys/jwt-public-key.pem" }

        logger.info { "JWT Key Paths - Private: $privateKeyPath, Public: $publicKeyPath" }

        val privateExists = File(privateKeyPath).exists()
        val publicExists = File(publicKeyPath).exists()

        return when {
            privateExists && publicExists -> {
                logger.info { "Loading existing RSA key pair from files..." }
                try {
                    loadKeyPairFromFiles(privateKeyPath, publicKeyPath)
                } catch (e: Exception) {
                    throw IllegalStateException("JWT key files exist but could not be loaded", e)
                }
            }
            privateExists || publicExists -> error(
                "Only one JWT key file exists (private=$privateExists, public=$publicExists); refusing to overwrite it"
            )
            else -> {
                logger.info { "Key files not found, generating and saving new RSA key pair..." }
                generateAndSaveKeyPair(privateKeyPath, publicKeyPath)
            }
        }
    }

    /**
     * Load RSA key pair from Base64-encoded key content (provided via environment variables)
     */
    private fun loadKeyPairFromContent(privateKeyBase64: String, publicKeyBase64: String): KeyPair {
        val privateKeySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
        val publicKeySpec = X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))

        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(privateKeySpec) as RSAPrivateKey
        val publicKey = keyFactory.generatePublic(publicKeySpec) as RSAPublicKey

        logger.info { "Successfully loaded RSA key pair from environment variables" }
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Load RSA key pair from PEM files
     */
    private fun loadKeyPairFromFiles(privateKeyPath: String, publicKeyPath: String): KeyPair {
        val privateKeyBytes = File(privateKeyPath).readBytes()
        val publicKeyBytes = File(publicKeyPath).readBytes()

        val privateKeySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBytes))
        val publicKeySpec = X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBytes))

        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(privateKeySpec) as RSAPrivateKey
        val publicKey = keyFactory.generatePublic(publicKeySpec) as RSAPublicKey

        logger.info { "✅ Successfully loaded RSA key pair from files" }
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Generate a new RSA key pair and save it to files
     * This ensures the same keys are used across server restarts
     */
    private fun generateAndSaveKeyPair(privateKeyPath: String, publicKeyPath: String): KeyPair {
        // Generate key pair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val pair = keyPairGenerator.generateKeyPair()

        logger.info { "Generated new RSA key pair (2048 bits)" }

        // Create keys directory if it doesn't exist
        val privateFile = File(privateKeyPath)
        val publicFile = File(publicKeyPath)

        privateFile.parentFile?.mkdirs()
        publicFile.parentFile?.mkdirs()

        // Save private key (PKCS8 format, Base64 encoded), owner read/write only from the moment it exists
        val privateKeyBase64 = Base64.getEncoder().encodeToString(pair.private.encoded)
        createKeyFile(Paths.get(privateKeyPath), privateKeyBase64.toByteArray(), ownerOnly = true)

        // Save public key (X509 format, Base64 encoded)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        createKeyFile(Paths.get(publicKeyPath), publicKeyBase64.toByteArray(), ownerOnly = false)

        logger.info { "✅ RSA key pair saved to files:" }
        logger.info { "   Private key: $privateKeyPath" }
        logger.info { "   Public key: $publicKeyPath" }
        logger.warn { "⚠️  IMPORTANT: Keep the private key secure and back it up!" }
        logger.warn { "⚠️  If you lose the private key, all existing tokens will be invalidated!" }

        return pair
    }

    /**
     * Creates [path] (failing if it already exists) and writes [content]. With [ownerOnly] the file is
     * created atomically with POSIX 600 permissions; on a non-POSIX filesystem it falls back to the
     * platform default.
     */
    private fun createKeyFile(path: Path, content: ByteArray, ownerOnly: Boolean) {
        val posix = "posix" in path.toAbsolutePath().fileSystem.supportedFileAttributeViews()
        if (ownerOnly && posix) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            if (ownerOnly) {
                logger.warn { "Filesystem has no POSIX permissions; private key file uses default permissions" }
            }
            Files.createFile(path)
        }
        Files.write(path, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey
    val privateKey: RSAPrivateKey get() = keyPair.private as RSAPrivateKey
    
    fun generateAccessToken(userId: Long, email: String, jti: String = UUID.randomUUID().toString()): String {
        val now = Date.from(clock.instant())
        val expiryDate = Date(now.time + appProperties.jwt.expirationMs)
        
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .claim("type", "access")
            .id(jti)
            .issuer(appProperties.jwt.issuer)
            .audience().add(appProperties.jwt.audience).and()
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(privateKey)
            .compact()
    }
    
    fun getUserIdFromToken(token: String): Long? {
        return try {
            val claims = getClaims(token)
            claims.subject.toLong()
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract user ID from token" }
            null
        }
    }
    
    fun validateToken(token: String): Boolean {
        return try {
            getClaims(token)
            true
        } catch (e: Exception) {
            logger.debug(e) { "Token validation failed" }
            false
        }
    }
    
    /**
     * Verifies [token] once and returns its subject and `iat`, or null if it is invalid or its
     * subject isn't a user id.
     */
    fun verifyAccessToken(token: String): VerifiedAccessToken? {
        val claims = try {
            getClaims(token)
        } catch (e: Exception) {
            logger.debug(e) { "Token validation failed" }
            return null
        }
        val userId = claims.subject?.toLongOrNull() ?: return null
        return VerifiedAccessToken(userId = userId, issuedAt = claims.issuedAt?.toInstant())
    }

    fun getExpirationTime(): Long = appProperties.jwt.expirationMs
    
    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(publicKey)
            .clock { Date.from(clock.instant()) }
            .requireIssuer(appProperties.jwt.issuer)
            .requireAudience(appProperties.jwt.audience)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}


