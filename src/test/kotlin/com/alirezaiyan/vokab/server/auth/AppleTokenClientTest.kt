package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AppleConfig
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.never
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AppleTokenClientTest {

    private val tokenUrl = "https://appleid.apple.com/auth/token"
    private val revokeUrl = "https://appleid.apple.com/auth/revoke"
    private val bundleId = "com.alirezaiyan.vokab"
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    private fun client(apple: AppleConfig = configured()) =
        AppleTokenClient(builder, AppProperties(apple = apple), fixedClock())

    private fun configured(pem: String = pem(keyPair)) =
        AppleConfig(clientIds = bundleId, teamId = "TEAM123456", keyId = "KEY1234567", privateKey = pem)

    @Test
    fun `exchanges an authorization code for the user's refresh token`() {
        var sentForm = ""
        server.expect(once(), requestTo(tokenUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect { sentForm = it.bodyText() }
            .andRespond(withSuccess("""{"access_token":"a","refresh_token":"r-123","id_token":"i"}""", MediaType.APPLICATION_JSON))

        val token = client().refreshTokenFor("code-1", bundleId)

        assertEquals("r-123", token)
        val form = sentForm.toForm()
        assertEquals("code-1", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(bundleId, form["client_id"])
        server.verify()
    }

    @Test
    fun `the client secret is a short-lived ES256 token from our team for the app`() {
        var sentForm = ""
        server.expect(once(), requestTo(tokenUrl))
            .andExpect { sentForm = it.bodyText() }
            .andRespond(withSuccess("""{"refresh_token":"r"}""", MediaType.APPLICATION_JSON))

        client().refreshTokenFor("code", bundleId)

        val secret = requireNotNull(sentForm.toForm()["client_secret"])
        val jws = Jwts.parser().verifyWith(keyPair.public).clock { java.util.Date.from(TEST_NOW) }.build()
            .parseSignedClaims(secret)
        assertEquals("ES256", jws.header.algorithm)
        assertEquals("KEY1234567", jws.header.keyId)
        assertEquals("TEAM123456", jws.payload.issuer)
        assertEquals(bundleId, jws.payload.subject)
        assertEquals(setOf("https://appleid.apple.com"), jws.payload.audience)
        assertTrue(jws.payload.expiration.toInstant().isAfter(TEST_NOW))
    }

    @Test
    fun `a key pasted on one env line still works`() {
        server.expect(once(), requestTo(tokenUrl))
            .andRespond(withSuccess("""{"refresh_token":"r"}""", MediaType.APPLICATION_JSON))

        val token = client(configured(pem(keyPair).replace("\n", "\\n"))).refreshTokenFor("code", bundleId)

        assertEquals("r", token)
    }

    @Test
    fun `a refused code yields no token`() {
        server.expect(once(), requestTo(tokenUrl))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":"invalid_grant"}"""))

        assertNull(client().refreshTokenFor("used-code", bundleId))
    }

    @Test
    fun `nothing is sent to Apple until the key is configured`() {
        server.expect(never(), requestTo(tokenUrl))

        assertNull(client(AppleConfig(clientIds = bundleId)).refreshTokenFor("code", bundleId))
        assertFalse(client(AppleConfig(clientIds = bundleId)).revoke("r", bundleId))
        server.verify()
    }

    @Test
    fun `an unreadable key sends nothing`() {
        server.expect(never(), requestTo(tokenUrl))

        assertNull(client(configured(pem = "not a key")).refreshTokenFor("code", bundleId))
        server.verify()
    }

    @Test
    fun `revokes a refresh token`() {
        var sentForm = ""
        server.expect(once(), requestTo(revokeUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect { sentForm = it.bodyText() }
            .andRespond(withSuccess())

        assertTrue(client().revoke("r-123", bundleId))
        val form = sentForm.toForm()
        assertEquals("r-123", form["token"])
        assertEquals("refresh_token", form["token_type_hint"])
        server.verify()
    }

    @Test
    fun `a refused revocation reports false`() {
        server.expect(once(), requestTo(revokeUrl)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertFalse(client().revoke("r-123", bundleId))
    }

    private fun pem(keyPair: KeyPair): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

    private fun org.springframework.http.client.ClientHttpRequest.bodyText(): String =
        (this as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString

    private fun String.toForm(): Map<String, String> = split("&").associate { pair ->
        val (key, value) = pair.split("=", limit = 2)
        URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }
}
