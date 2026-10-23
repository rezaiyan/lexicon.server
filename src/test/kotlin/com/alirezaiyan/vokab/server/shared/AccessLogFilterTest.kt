package com.alirezaiyan.vokab.server.shared

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class AccessLogFilterTest {

    private val filter = AccessLogFilter(AppProperties())
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = LoggerFactory.getLogger(AccessLogFilter::class.java) as Logger

    @BeforeEach
    fun attach() {
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun request(uri: String = "/api/v1/words/42") = MockHttpServletRequest("GET", uri)

    @Test
    fun `logs one line with method, path template, status, duration and user id`() {
        val request = request()
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { req, res ->
            // What Spring MVC and JwtAuthenticationFilter leave behind during the real dispatch
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/words/{id}")
            req.setAttribute(AccessLogFilter.USER_ID_ATTRIBUTE, 7L)
            (res as MockHttpServletResponse).status = 404
        }

        val line = appender.list.single().formattedMessage
        assertTrue(line.startsWith("GET /api/v1/words/{id} 404 "), line)
        assertTrue(line.contains("user=7"), line)
        assertTrue(line.contains("ms"), line)
    }

    @Test
    fun `falls back to the raw path and anonymous when nothing matched or authenticated`() {
        filter.doFilter(request("/api/v1/nope"), MockHttpServletResponse(), MockFilterChain())

        val line = appender.list.single().formattedMessage
        assertTrue(line.startsWith("GET /api/v1/nope 200 "), line)
        assertTrue(line.contains("user=anonymous"), line)
    }

    @Test
    fun `echoes a valid incoming request id and exposes it to the chain via MDC`() {
        val request = request().apply { addHeader("X-Request-Id", "abc-123") }
        val response = MockHttpServletResponse()
        var seenInChain: String? = null

        filter.doFilter(request, response) { _, _ -> seenInChain = MDC.get("requestId") }

        assertEquals("abc-123", response.getHeader("X-Request-Id"))
        assertEquals("abc-123", seenInChain)
        assertNull(MDC.get("requestId"), "MDC must be cleared after the request")
    }

    @Test
    fun `replaces an unsafe incoming request id with a generated one`() {
        val request = request().apply { addHeader("X-Request-Id", "bad id\nwith newline") }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val id = requireNotNull(response.getHeader("X-Request-Id"))
        assertNotEquals("bad id\nwith newline", id)
        assertTrue(id.matches(Regex("[0-9a-f-]{36}")), id)
    }

    @Test
    fun `never logs the user's email`() {
        val request = request()
        filter.doFilter(request, MockHttpServletResponse()) { req, _ ->
            req.setAttribute(AccessLogFilter.USER_ID_ATTRIBUTE, 7L)
        }

        assertFalse(appender.list.any { it.formattedMessage.contains("@") })
    }

    @Test
    fun `skips excluded paths such as the health check`() {
        filter.doFilter(request("/api/v1/health"), MockHttpServletResponse(), MockFilterChain())

        assertTrue(appender.list.isEmpty())
    }
}
