package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.exception.UpstreamServiceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApplePublicKeyServiceTest {

    private val keysUrl = "https://appleid.apple.com/auth/keys"
    private val key1 = newRsaKey()
    private val key2 = newRsaKey()

    private val clock = MutableClock(Instant.parse("2026-10-03T12:00:00Z"))
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val service = ApplePublicKeyService(builder, clock)

    @Test
    fun `fetches Apple's key set on first use and returns the matching key`() {
        server.expect(once(), requestTo(keysUrl)).andRespond(jwks("kid-1" to key1, "kid-2" to key2))

        assertEquals(key2, service.getPublicKey("kid-2"))
        server.verify()
    }

    @Test
    fun `concurrent callers on a cold cache share a single fetch`() {
        server.expect(once(), requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))
        val start = CountDownLatch(1)

        val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (1..32).map { executor.submit<Any?> { start.await(); service.getPublicKey("kid-1") } }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }

        assertTrue(results.all { it == key1 })
        server.verify()
    }

    @Test
    fun `serves cached keys without refetching within the cache lifetime`() {
        server.expect(once(), requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))

        service.getPublicKey("kid-1")
        clock.advance(Duration.ofHours(23))
        assertEquals(key1, service.getPublicKey("kid-1"))
        server.verify()
    }

    @Test
    fun `refetches once the cache lifetime has passed`() {
        server.expect(requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))
        server.expect(requestTo(keysUrl)).andRespond(jwks("kid-2" to key2))

        service.getPublicKey("kid-1")
        clock.advance(Duration.ofHours(25))
        assertEquals(key2, service.getPublicKey("kid-2"))
        server.verify()
    }

    @Test
    fun `unknown kid right after a fetch returns null without calling Apple again`() {
        server.expect(once(), requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))

        service.getPublicKey("kid-1")
        repeat(5) { assertNull(service.getPublicKey("random-kid-$it")) }
        server.verify()
    }

    @Test
    fun `unknown kid refetches after the throttle interval, picking up rotated keys`() {
        server.expect(requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))
        server.expect(requestTo(keysUrl)).andRespond(jwks("kid-1" to key1, "kid-2" to key2))

        service.getPublicKey("kid-1")
        clock.advance(Duration.ofMinutes(2))
        assertEquals(key2, service.getPublicKey("kid-2"))
        server.verify()
    }

    @Test
    fun `throws UpstreamServiceException when Apple is unreachable and nothing is cached`() {
        server.expect(requestTo(keysUrl)).andRespond(withException(IOException("connection refused")))

        assertThrows<UpstreamServiceException> { service.getPublicKey("kid-1") }
    }

    @Test
    fun `throws UpstreamServiceException when Apple answers with an error status`() {
        server.expect(requestTo(keysUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThrows<UpstreamServiceException> { service.getPublicKey("kid-1") }
    }

    @Test
    fun `keeps serving a cached key when a refresh fails after the cache lifetime`() {
        server.expect(requestTo(keysUrl)).andRespond(jwks("kid-1" to key1))
        server.expect(requestTo(keysUrl)).andRespond(withException(IOException("connection refused")))

        service.getPublicKey("kid-1")
        clock.advance(Duration.ofHours(25))
        assertEquals(key1, service.getPublicKey("kid-1"))
    }

    @Test
    fun `skips malformed and non-RSA entries in the key set`() {
        val body = """{"keys":[
            {"kid":"ec","kty":"EC","crv":"P-256","x":"AA","y":"AA"},
            {"kid":"broken","kty":"RSA"},
            ${jwk("kid-1", key1)}
        ]}"""
        server.expect(requestTo(keysUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        assertEquals(key1, service.getPublicKey("kid-1"))
        assertNull(service.getPublicKey("ec"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun jwks(vararg keys: Pair<String, RSAPublicKey>) = withSuccess(
        """{"keys":[${keys.joinToString(",") { (kid, key) -> jwk(kid, key) }}]}""",
        MediaType.APPLICATION_JSON,
    )

    private fun jwk(kid: String, key: RSAPublicKey): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: java.math.BigInteger) = encoder.encodeToString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"kid":"$kid","kty":"RSA","alg":"RS256","use":"sig","n":"${encode(key.modulus)}","e":"${encode(key.publicExponent)}"}"""
    }

    private fun newRsaKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
}
