package com.alirezaiyan.vokab.server.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.OpenRouterConfig
import com.alirezaiyan.vokab.server.config.VocabularyConfig
import com.alirezaiyan.vokab.server.exception.UpstreamServiceException
import com.alirezaiyan.vokab.server.exception.UserFacingException
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException

class OpenRouterServiceTest {

    private val chatUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val mapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val service = OpenRouterService(builder, appProperties(), meterRegistry)

    // ── transport ─────────────────────────────────────────────────────────────

    @Test
    fun `requests post the configured model with auth and attribution headers`() {
        val custom = RestClient.builder()
        val customServer = MockRestServiceServer.bindTo(custom).build()
        val customService = OpenRouterService(custom, meterRegistry = meterRegistry, appProperties = appProperties(model = "anthropic/custom-model"))
        customServer.expect(requestTo(chatUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
            .andExpect(header("X-Title", "Vokab"))
            .andExpect(jsonPath("$.model").value("anthropic/custom-model"))
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andRespond(answer("Hallo"))

        assertEquals("Hallo", customService.translateText("Hello", "German"))
        customServer.verify()
    }

    @Test
    fun `complete returns the trimmed answer`() {
        expectChat().andRespond(answer("  {\"action\":\"send\"}  \n"))

        assertEquals("{\"action\":\"send\"}", service.complete("prompt", "test"))
        assertEquals(1.0, meterRegistry.counter("ai.requests", "operation", "test", "outcome", "success").count())
    }

    @Test
    fun `complete returns null for a blank or missing answer`() {
        expectChat().andRespond(answer("   "))
        expectChat().andRespond(json("""{"choices":[{"message":{"content":null}}]}"""))
        expectChat().andRespond(json("""{"choices":[]}"""))

        repeat(3) { assertNull(service.complete("prompt", "test")) }
    }

    @Test
    fun `an HTTP error status becomes UpstreamServiceException`() {
        expectChat().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThrows<UpstreamServiceException> { service.complete("prompt", "test") }
        assertEquals(1.0, meterRegistry.counter("ai.requests", "operation", "test", "outcome", "error").count())
    }

    @Test
    fun `a network failure becomes UpstreamServiceException`() {
        expectChat().andRespond(withException(IOException("read timed out")))

        assertThrows<UpstreamServiceException> { service.complete("prompt", "test") }
    }

    @Test
    fun `an API error field becomes UpstreamServiceException`() {
        expectChat().andRespond(json("""{"error":{"message":"model overloaded"}}"""))

        assertThrows<UpstreamServiceException> { service.complete("prompt", "test") }
    }

    // ── extractVocabularyFromImage ────────────────────────────────────────────

    @Test
    fun `extractVocabularyFromImage rejects images over 5MB without calling the API`() {
        val oversize = "A".repeat((5 * 1024 * 1024 / 0.75).toInt() + 10)

        val error = assertThrows<IllegalArgumentException> { service.extractVocabularyFromImage(oversize, "English") }

        assertTrue(error.message.orEmpty().contains("too large"))
        server.verify()
    }

    @Test
    fun `extractVocabularyFromImage returns valid vocabulary text and sends the image`() {
        expectChat()
            .andExpect(jsonPath("$.messages[0].content[0].image_url.url").value("data:image/jpeg;base64,AAAA"))
            .andRespond(answer("Hallo,hello;Guten Morgen,good morning"))

        assertEquals("Hallo,hello;Guten Morgen,good morning", service.extractVocabularyFromImage("AAAA", "English"))
    }

    @Test
    fun `extractVocabularyFromImage reports an image without vocabulary to the user`() {
        expectChat().andRespond(answer("ERROR: No vocabulary found"))

        assertThrows<UserFacingException> { service.extractVocabularyFromImage("AAAA", "English") }
    }

    @Test
    fun `extractVocabularyFromImage reports an unparseable answer to the user`() {
        expectChat().andRespond(answer("Here are some words you might like"))

        assertThrows<UserFacingException> { service.extractVocabularyFromImage("AAAA", "English") }
    }

    @Test
    fun `extractVocabularyFromImage reports an empty answer to the user`() {
        expectChat().andRespond(json("""{"choices":[]}"""))

        assertThrows<UserFacingException> { service.extractVocabularyFromImage("AAAA", "English") }
    }

    @Test
    fun `extractVocabularyFromImage prompt follows the extraction flags`() {
        expectChat().andExpect(promptContains("individual vocabulary words only")).andRespond(answer("a,b"))
        expectChat().andExpect(promptContains("example sentences only")).andRespond(answer("a,b"))
        expectChat().andExpect(promptContains("both individual vocabulary words AND example sentences")).andRespond(answer("a,b"))

        service.extractVocabularyFromImage("AAAA", "English")
        service.extractVocabularyFromImage("AAAA", "English", extractWords = false, extractSentences = true)
        service.extractVocabularyFromImage("AAAA", "English", extractWords = true, extractSentences = true)
        server.verify()
    }

    // ── notification copy: fallback on an empty answer ───────────────────────

    @Test
    fun `celebration, streak warning and streak reminder fall back to fixed copy on an empty answer`() {
        repeat(3) { expectChat().andRespond(json("""{"choices":[]}""")) }

        assertEquals("Great work today! 🎉 You're building something real.", service.generateCelebrationInsight(stats(), null))
        assertEquals(
            "Don't lose your 7-day streak! 🔥 Log in now to keep it going!",
            service.generateStreakResetWarning(7, stats(), "Ali"),
        )
        assertEquals(
            "You have a 7-day streak! 🔥 Complete your review today to keep it going!",
            service.generateStreakReminderMessage(7, "Ali"),
        )
    }

    @Test
    fun `streak reminder includes progress stats only when provided`() {
        expectChat().andExpect(promptContains("Due for review today: 10 cards")).andRespond(answer("Go!"))
        expectChat().andExpect(content().string(not(containsString("Due for review today")))).andRespond(answer("Go!"))

        assertEquals("Go!", service.generateStreakReminderMessage(7, "Ali", stats()))
        assertEquals("Go!", service.generateStreakReminderMessage(7, "Ali"))
        server.verify()
    }

    // ── insights: fail on an empty answer ─────────────────────────────────────

    @Test
    fun `daily insight and milestone message return the answer`() {
        expectChat().andExpect(promptContains("Schadenfreude")).andRespond(answer("Nice work on Schadenfreude 🎯"))
        expectChat().andExpect(promptContains("100 words")).andRespond(answer("Big milestone 🏆"))

        assertEquals("Nice work on Schadenfreude 🎯", service.generateDailyInsight(insightContext()))
        assertEquals("Big milestone 🏆", service.generateMilestoneMessage(milestone(), stats(), null))
    }

    @Test
    fun `daily insight and milestone message throw on an empty answer`() {
        repeat(2) { expectChat().andRespond(json("""{"choices":[]}""")) }

        assertThrows<UpstreamServiceException> { service.generateDailyInsight(insightContext()) }
        assertThrows<UpstreamServiceException> { service.generateMilestoneMessage(milestone(), stats(), "Ali") }
    }

    // ── translateText ─────────────────────────────────────────────────────────

    @Test
    fun `translateText reports an empty answer to the user`() {
        expectChat().andRespond(answer(""))

        assertThrows<UserFacingException> { service.translateText("Hello", "German") }
    }

    // ── generateVocabularyFromPreferences ─────────────────────────────────────

    @Test
    fun `generateVocabularyFromPreferences parses lines and skips entries without a translation`() {
        expectChat().andRespond(answer("Hallo,hello,a greeting\nkaputt\nBrot,bread,\n\ndanke,thank you"))

        val items = service.generateVocabularyFromPreferences("German", "beginner", "English")

        assertEquals(listOf("Hallo", "Brot", "danke"), items.map { it.originalWord })
        assertEquals("a greeting", items.first().description)
    }

    @Test
    fun `generateVocabularyFromPreferences asks for headroom and includes interests`() {
        expectChat()
            .andExpect(promptContains("Generate exactly 60 vocabulary items"))
            .andExpect(promptContains("travel, food"))
            .andRespond(answer("Hallo,hello"))

        service.generateVocabularyFromPreferences("German", "beginner", "English", listOf("travel", "food"))
        server.verify()
    }

    @Test
    fun `generateVocabularyFromPreferences reports an empty answer to the user`() {
        expectChat().andRespond(answer("  "))

        assertThrows<UserFacingException> { service.generateVocabularyFromPreferences("German", "beginner", "English") }
    }

    @Test
    fun `default model is the configured Haiku model`() {
        assertEquals("anthropic/claude-haiku-4.5", OpenRouterConfig().model)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun expectChat() = server.expect(requestTo(chatUrl))

    private fun answer(text: String) =
        json(mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text))))))

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)

    private fun promptContains(text: String): RequestMatcher = content().string(containsString(text))

    private fun appProperties(model: String = "anthropic/claude-haiku-4.5") = AppProperties(
        openrouter = OpenRouterConfig(apiKey = "test-api-key", baseUrl = "https://openrouter.ai/api/v1", model = model),
        vocabulary = VocabularyConfig(suggestionCount = 50),
    )

    private fun stats() = ProgressStatsDto(
        totalWords = 50, dueCards = 10,
        level0Count = 5, level1Count = 10, level2Count = 10, level3Count = 10,
        level4Count = 5, level5Count = 5, level6Count = 5,
    )

    private fun insightContext() = OpenRouterService.DailyInsightContext(
        stats = stats(),
        userName = "Alice",
        optimalStudyHour = 18,
        accuracyTrend = 5.0f,
        topDifficultWord = "Schadenfreude",
        primaryLanguage = "German",
        sessionCompletionRate = 0.85f,
        currentStreak = 3,
    )

    private fun milestone() = MilestoneDetector.MilestoneEvent(
        type = "words", title = "100 words!", description = "100 words", value = 100,
    )
}
