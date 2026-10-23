package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.JwtConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64

class JwksControllerTest {

    private val provider = RS256JwtTokenProvider(inMemoryKeyProperties(), MutableClock())

    @Test
    fun `publishes the same key id that issued tokens carry`() {
        val key = jwks().single()

        assertEquals(provider.keyId, key["kid"])
    }

    @Test
    fun `publishes the exponent without a sign byte`() {
        // 65537 = 0x010001, so a correct JWK exponent is "AQAB"
        assertEquals("AQAB", jwks().single()["e"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun jwks(): List<Map<String, String>> =
        JwksController(provider).getJwks()["keys"] as List<Map<String, String>>

    private fun inMemoryKeyProperties(): AppProperties {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        return AppProperties().apply {
            jwt = JwtConfig(
                privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded),
                publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            )
        }
    }
}
