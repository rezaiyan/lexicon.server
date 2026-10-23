package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger
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

private const val ACCESS_TOKEN_TYPE = "access"

/** Base64URL of [value]'s unsigned big-endian bytes, as JWK `n` and `e` require. */
internal fun base64UrlUnsigned(value: BigInteger): String {
    val bytes = value.toByteArray()
    val unsigned = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
}

/**
 * JWT token provider using RS256 algorithm (asymmetric keys)
 * Automatically generates and persists RSA keys to ensure tokens remain valid across server restarts.
 * Issued tokens carry the key's [keyId] as `kid`, the same value the JWKS endpoint publishes.
 */
@Component
class RS256JwtTokenProvider(
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val keyPair: KeyPair = initializeKeyPair()

    /** RFC 7638 thumbprint of the public key: the `kid` of issued tokens and of the JWKS entry. */
    val keyId: String = run {
        val key = keyPair.public as RSAPublicKey
        // Required members only, in lexicographic order, no whitespace (RFC 7638 section 3)
        val e = base64UrlUnsigned(key.publicExponent)
        val n = base64UrlUnsigned(key.modulus)
        val canonical = """{"e":"$e","kty":"RSA","n":"$n"}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    // Built once: only access tokens (type=access) from our issuer, for our audience
    private val parser: JwtParser = Jwts.parser()
        .verifyWith(keyPair.public as RSAPublicKey)
        .clock { Date.from(clock.instant()) }
        .requireIssuer(appProperties.jwt.issuer)
        .requireAudience(appProperties.jwt.audience)
        .require("type", ACCESS_TOKEN_TYPE)
        .build()

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
            .header().keyId(keyId).and()
            .subject(userId.toString())
            .claim("email", email)
            .claim("type", ACCESS_TOKEN_TYPE)
            .id(jti)
            .issuer(appProperties.jwt.issuer)
            .audience().add(appProperties.jwt.audience).and()
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(privateKey)
            .compact()
    }
    
    /**
     * Verifies [token] once and returns its subject and `iat`, or null if it is invalid or its
     * subject isn't a user id.
     */
    fun verifyAccessToken(token: String): VerifiedAccessToken? {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (e: Exception) {
            logger.debug(e) { "Token validation failed" }
            return null
        }
        val userId = claims.subject?.toLongOrNull() ?: return null
        return VerifiedAccessToken(userId = userId, issuedAt = claims.issuedAt?.toInstant())
    }

    fun getExpirationTime(): Long = appProperties.jwt.expirationMs
}


