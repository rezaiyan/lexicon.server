package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class AdminAuthenticationFilterTest {

    private val key = "a".repeat(40)
    private val props = AppProperties().apply { security.adminApiKey = key }
    private val tokens = AdminSessionTokens(props, fixedClock(TEST_NOW))
    private val filter = AdminAuthenticationFilter(tokens, RateLimitConfig(props))

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun adminRequest(ip: String = "10.0.0.1", block: MockHttpServletRequest.() -> Unit = {}) =
        MockHttpServletRequest("GET", "/admin/notifications/stats").apply {
            remoteAddr = ip
            block()
        }

    private fun run(request: MockHttpServletRequest): Pair<MockHttpServletResponse, MockFilterChain> {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    private fun isAdmin() =
        SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_ADMIN" } == true

    @Test
    fun `the right admin key header grants ROLE_ADMIN`() {
        val (_, chain) = run(adminRequest { addHeader(ADMIN_KEY_HEADER, key) })

        assertNotNull(chain.request)
        assertTrue(isAdmin())
    }

    @Test
    fun `a wrong key is rejected with 401`() {
        val (response, chain) = run(adminRequest { addHeader(ADMIN_KEY_HEADER, "nope") })

        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    @Test
    fun `after five wrong keys the IP is locked out, even with the right key`() {
        repeat(5) { run(adminRequest(ip = "10.0.0.9") { addHeader(ADMIN_KEY_HEADER, "guess-$it") }) }

        val (response, chain) = run(adminRequest(ip = "10.0.0.9") { addHeader(ADMIN_KEY_HEADER, key) })

        assertEquals(429, response.status)
        assertNull(chain.request)
        // Another client is unaffected
        assertNotNull(run(adminRequest(ip = "10.0.0.10") { addHeader(ADMIN_KEY_HEADER, key) }).second.request)
    }

    @Test
    fun `a valid session cookie with the request header grants ROLE_ADMIN`() {
        val session = tokens.issue()
        val (_, chain) = run(adminRequest {
            setCookies(Cookie(ADMIN_SESSION_COOKIE, session.token))
            addHeader(ADMIN_REQUEST_HEADER, "1")
        })

        assertNotNull(chain.request)
        assertTrue(isAdmin())
    }

    @Test
    fun `a session cookie without the request header is refused`() {
        val session = tokens.issue()
        val (response, chain) = run(adminRequest { setCookies(Cookie(ADMIN_SESSION_COOKIE, session.token)) })

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun `a forged cookie leaves the request unauthenticated`() {
        val (_, chain) = run(adminRequest {
            setCookies(Cookie(ADMIN_SESSION_COOKIE, "1.2.3"))
            addHeader(ADMIN_REQUEST_HEADER, "1")
        })

        assertNotNull(chain.request) // Spring Security then denies the admin path
        assertTrue(!isAdmin())
    }

    @Test
    fun `non-admin paths and the sign-in endpoint are skipped`() {
        val api = MockHttpServletRequest("GET", "/api/v1/words")
        val signIn = MockHttpServletRequest("POST", ADMIN_SESSION_PATH)
        assertTrue(filter.shouldNotFilterPublic(api))
        assertTrue(filter.shouldNotFilterPublic(signIn))
    }

    private fun AdminAuthenticationFilter.shouldNotFilterPublic(request: MockHttpServletRequest): Boolean {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        request.addHeader(ADMIN_KEY_HEADER, "wrong")
        doFilter(request, response, chain)
        return chain.request != null && response.status == 200
    }
}
