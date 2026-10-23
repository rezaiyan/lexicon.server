package com.alirezaiyan.vokab.server.auth

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class JwksController(
    private val jwtTokenProvider: RS256JwtTokenProvider
) {
    
    /**
     * JWKS endpoint for JWT public key verification
     * Returns the public key in JWKS format for RS256 tokens; `kid` matches the header of issued tokens.
     */
    @GetMapping("/jwks")
    fun getJwks(): Map<String, Any> {
        val publicKey = jwtTokenProvider.publicKey
        return mapOf(
            "keys" to listOf(
                mapOf(
                    "kty" to "RSA",
                    "use" to "sig",
                    "alg" to "RS256",
                    "kid" to jwtTokenProvider.keyId,
                    "n" to base64UrlUnsigned(publicKey.modulus),
                    "e" to base64UrlUnsigned(publicKey.publicExponent)
                )
            )
        )
    }
}
