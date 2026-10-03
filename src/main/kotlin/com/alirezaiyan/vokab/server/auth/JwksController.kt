package com.alirezaiyan.vokab.server.auth

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger
import java.util.*

@RestController
@RequestMapping("/api/v1/auth")
class JwksController(
    private val jwtTokenProvider: RS256JwtTokenProvider
) {
    
    /**
     * JWKS endpoint for JWT public key verification
     * Returns the public key in JWKS format for RS256 tokens
     */
    @GetMapping("/jwks")
    fun getJwks(): Map<String, Any> {
        val publicKey = jwtTokenProvider.publicKey
        
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        
        val encodedModulus = Base64.getUrlEncoder().withoutPadding().encodeToString(modulusToBytes(modulus))
        val encodedExponent = Base64.getUrlEncoder().withoutPadding().encodeToString(exponentToBytes(exponent))
        
        return mapOf(
            "keys" to listOf(
                mapOf(
                    "kty" to "RSA",
                    "use" to "sig",
                    "alg" to "RS256",
                    "kid" to "vokab-rsa-key-1",
                    "n" to encodedModulus,
                    "e" to encodedExponent
                )
            )
        )
    }
    
    private fun modulusToBytes(modulus: BigInteger): ByteArray {
        val bytes = modulus.toByteArray()
        if (bytes[0] == 0.toByte()) {
            return bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }
    
    private fun exponentToBytes(exponent: BigInteger): ByteArray {
        return exponent.toByteArray()
    }
}

