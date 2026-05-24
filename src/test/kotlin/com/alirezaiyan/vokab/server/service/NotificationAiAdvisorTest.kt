package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.OpenRouterConfig
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotificationAiAdvisorTest {

    private lateinit var advisor: NotificationAiAdvisor
    private lateinit var spy: NotificationAiAdvisor

    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        val appProperties = AppProperties().apply {
            openrouter = OpenRouterConfig(apiKey = "test-key", baseUrl = "https://openrouter.ai/api/v1")
        }
        advisor = NotificationAiAdvisor(appProperties, objectMapper)
        spy    = spyk(advisor)
    }

    // ── parseResponse: valid JSON ─────────────────────────────────────────────────

    @Test
    fun `parseResponse should return AiAdvice for valid motivate response`() {
        val raw = """{"action":"motivate","intervalDays":4,"contentHint":"loss_aversion"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("motivate", result.action)
        assertEquals(4, result.intervalDays)
        assertEquals("loss_aversion", result.contentHint)
    }

    @Test
    fun `parseResponse should return AiAdvice for valid pause response`() {
        val raw = """{"action":"pause","intervalDays":7,"contentHint":null}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("pause", result.action)
        assertEquals(7, result.intervalDays)
        assertNull(result.contentHint)
    }

    @Test
    fun `parseResponse should return AiAdvice for valid send response`() {
        val raw = """{"action":"send","intervalDays":2,"contentHint":null}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("send", result.action)
        assertEquals(2, result.intervalDays)
        assertNull(result.contentHint)
    }

    @Test
    fun `parseResponse should normalise action to lowercase`() {
        val raw = """{"action":"MOTIVATE","intervalDays":3,"contentHint":"curiosity"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("motivate", result.action)
    }

    @Test
    fun `parseResponse should normalise contentHint to lowercase`() {
        val raw = """{"action":"motivate","intervalDays":3,"contentHint":"LOSS_AVERSION"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("loss_aversion", result.contentHint)
    }

    // ── parseResponse: interval clamping ─────────────────────────────────────────

    @Test
    fun `parseResponse should clamp intervalDays below 1 to 1`() {
        val raw = """{"action":"pause","intervalDays":0,"contentHint":null}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals(1, result.intervalDays)
    }

    @Test
    fun `parseResponse should clamp intervalDays above 14 to 14`() {
        val raw = """{"action":"pause","intervalDays":30,"contentHint":null}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals(14, result.intervalDays)
    }

    // ── parseResponse: unknown values fall back ───────────────────────────────────

    @Test
    fun `parseResponse should return defaultAdvice when action is unknown`() {
        val raw = """{"action":"explode","intervalDays":5,"contentHint":null}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("send", result.action)
        assertEquals(3, result.intervalDays)
        assertNull(result.contentHint)
    }

    @Test
    fun `parseResponse should fall back to curiosity when contentHint is unknown`() {
        val raw = """{"action":"motivate","intervalDays":4,"contentHint":"bribe_them"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("motivate", result.action)
        assertEquals("curiosity", result.contentHint)
    }

    @Test
    fun `parseResponse should set contentHint to null for pause action regardless of AI response`() {
        val raw = """{"action":"pause","intervalDays":5,"contentHint":"loss_aversion"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("pause", result.action)
        assertNull(result.contentHint)
    }

    @Test
    fun `parseResponse should set contentHint to null for send action`() {
        val raw = """{"action":"send","intervalDays":2,"contentHint":"curiosity"}"""

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("send", result.action)
        assertNull(result.contentHint)
    }

    // ── parseResponse: malformed JSON ────────────────────────────────────────────

    @Test
    fun `parseResponse should return defaultAdvice when JSON is invalid`() {
        val raw = "not json at all"

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("send", result.action)
        assertEquals(3, result.intervalDays)
        assertNull(result.contentHint)
    }

    @Test
    fun `parseResponse should return defaultAdvice when JSON is empty object`() {
        // empty object → AiResponse defaults: action="send", intervalDays=3
        val raw = "{}"

        val result = advisor.parseResponse(raw, userId = 1L)

        assertEquals("send", result.action)
        assertEquals(3, result.intervalDays)
    }

    // ── advise: fallback on callOpenRouter error ──────────────────────────────────

    @Test
    fun `advise should return defaultAdvice when callOpenRouter throws`() {
        every { spy.callOpenRouter(any()) } throws RuntimeException("Network error")

        val context = testContext()
        val result = spy.advise(context)

        assertEquals("send", result.action)
        assertEquals(3, result.intervalDays)
        assertNull(result.contentHint)
    }

    @Test
    fun `advise should return valid advice when callOpenRouter returns good JSON`() {
        every { spy.callOpenRouter(any()) } returns """{"action":"motivate","intervalDays":5,"contentHint":"fresh_start"}"""

        val result = spy.advise(testContext())

        assertEquals("motivate", result.action)
        assertEquals(5, result.intervalDays)
        assertEquals("fresh_start", result.contentHint)
    }

    // ── Factory ───────────────────────────────────────────────────────────────────

    private fun testContext(
        segment: String = "COLD",
        openRate7d: Int = 0,
        openRate30d: Int = 10,
        daysSinceLastOpen: Long? = 12,
        currentStreak: Int = 0,
        longestStreak: Int = 14,
        dueCards: Int = 8,
        accountAgeDays: Long = 90
    ) = NotificationAiAdvisor.UserNotificationContext(
        userId             = 1L,
        segment            = segment,
        openRate7dPercent  = openRate7d,
        openRate30dPercent = openRate30d,
        daysSinceLastOpen  = daysSinceLastOpen,
        currentStreak      = currentStreak,
        longestStreak      = longestStreak,
        dueCards           = dueCards,
        accountAgeDays     = accountAgeDays
    )
}
