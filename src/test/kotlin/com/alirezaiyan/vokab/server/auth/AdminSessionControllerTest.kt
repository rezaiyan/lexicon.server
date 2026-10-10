package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest

class AdminSessionControllerTest {

    private val key = "s".repeat(40)

    private fun controller(adminKey: String = key): Pair<AdminSessionController, AdminSessionTokens> {
        val props = AppProperties().apply { security.adminApiKey = adminKey }
        val tokens = AdminSessionTokens(props, fixedClock(TEST_NOW))
        return AdminSessionController(tokens, RateLimitConfig(props)) to tokens
    }

    private fun request(ip: String = "10.1.1.1") = MockHttpServletRequest().apply { remoteAddr = ip }

    @Test
    fun `signing in sets a locked-down session cookie`() {
        val (controller, tokens) = controller()

        val response = controller.signIn(AdminSignInRequest(key), "1", request())

        assertEquals(200, response.statusCode.value())
        val cookie = response.headers.getFirst(HttpHeaders.SET_COOKIE).orEmpty()
        assertTrue(cookie.startsWith("$ADMIN_SESSION_COOKIE="))
        listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/admin", "Max-Age=14400").forEach {
            assertTrue(cookie.contains(it), "cookie lacks $it: $cookie")
        }
        val token = cookie.substringAfter("=").substringBefore(";")
        assertEquals(response.body?.data?.expiresAt, tokens.validate(token))
    }

    @Test
    fun `a wrong key is refused and five failures lock the IP`() {
        val (controller, _) = controller()

        repeat(5) {
            assertEquals(401, controller.signIn(AdminSignInRequest("wrong"), "1", request()).statusCode.value())
        }
        val locked = controller.signIn(AdminSignInRequest(key), "1", request())

        assertEquals(429, locked.statusCode.value())
        assertNull(locked.headers.getFirst(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `sign-in requires the admin request header`() {
        val (controller, _) = controller()
        assertEquals(403, controller.signIn(AdminSignInRequest(key), null, request()).statusCode.value())
    }

    @Test
    fun `sign-in is disabled while the admin key is too short`() {
        val (controller, _) = controller(adminKey = "short")
        assertEquals(503, controller.signIn(AdminSignInRequest("short"), "1", request()).statusCode.value())
    }

    @Test
    fun `current session reflects the cookie and sign-out clears it`() {
        val (controller, tokens) = controller()
        val session = tokens.issue()

        assertTrue(controller.current(session.token).body?.data?.authenticated == true)
        assertFalse(controller.current("forged.token.value").body?.data?.authenticated == true)

        val cleared = controller.signOut().headers.getFirst(HttpHeaders.SET_COOKIE).orEmpty()
        assertTrue(cleared.contains("Max-Age=0"))
    }
}
