package com.alirezaiyan.vokab.server.mcp

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class McpRateLimitFilterTest {

    private val buckets = mutableMapOf<String, Bucket>()
    private val filter = McpRateLimitFilter { key ->
        buckets.getOrPut(key) {
            Bucket.builder().addLimit(Bandwidth.builder().capacity(2).refillIntervally(2, Duration.ofMinutes(1)).build()).build()
        }
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `calls beyond the ceiling get 429 with Retry-After`() {
        signIn(user = "7", client = "claude")

        val statuses = List(3) { call().status }

        assertEquals(listOf(200, 200, 429), statuses)
        assertEquals("60", call().getHeader("Retry-After"))
    }

    @Test
    fun `each learner and app has its own allowance`() {
        signIn(user = "7", client = "claude")
        repeat(2) { call() }

        signIn(user = "7", client = "chatgpt")
        assertEquals(200, call().status)
        signIn(user = "8", client = "claude")
        assertEquals(200, call().status)
    }

    private fun call() = MockHttpServletResponse().also {
        filter.doFilter(MockHttpServletRequest("POST", "/mcp"), it, MockFilterChain())
    }

    private fun signIn(user: String, client: String) {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(user).claim("client_id", client)
            .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }
}
