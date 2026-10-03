package com.alirezaiyan.vokab.server.health

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.OpenRouterConfig
import com.google.firebase.FirebaseApp
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.time.Duration

class HealthIndicatorsTest {

    // ── Firebase ──────────────────────────────────────────────────────────────

    @Test
    fun `firebase is UNKNOWN when not configured, so it never fails health on its own`() {
        assertEquals(Status.UNKNOWN, FirebaseHealthIndicator(null).health().status)
    }

    @Test
    fun `firebase is UP once the app is initialized`() {
        val app = mockk<FirebaseApp> { every { name } returns "[DEFAULT]" }

        assertEquals(Status.UP, FirebaseHealthIndicator(app).health().status)
    }

    // ── OpenRouter ────────────────────────────────────────────────────────────

    private val clock = MutableClock(TEST_NOW)
    private val tracker = AiCallTracker(clock)
    private fun indicator(apiKey: String = "key") =
        OpenRouterHealthIndicator(tracker, AppProperties(openrouter = OpenRouterConfig(apiKey = apiKey)))

    @Test
    fun `openrouter is UNKNOWN without an api key`() {
        assertEquals(Status.UNKNOWN, indicator(apiKey = "").health().status)
    }

    @Test
    fun `openrouter is UP before any call and after a success`() {
        assertEquals(Status.UP, indicator().health().status)

        tracker.record(success = true)
        assertEquals(Status.UP, indicator().health().status)
    }

    @Test
    fun `openrouter is DOWN while the latest call failed, UP again after the next success`() {
        tracker.record(success = true)
        clock.advance(Duration.ofMinutes(1))
        tracker.record(success = false)

        val down = indicator().health()
        assertEquals(Status.DOWN, down.status)
        assertEquals(TEST_NOW.plus(Duration.ofMinutes(1)).toString(), down.details["lastFailureAt"])

        clock.advance(Duration.ofMinutes(1))
        tracker.record(success = true)
        assertEquals(Status.UP, indicator().health().status)
    }
}
