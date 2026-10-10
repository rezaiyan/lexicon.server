package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import com.alirezaiyan.vokab.server.fixedClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.OpenRouterConfig
import com.alirezaiyan.vokab.server.shared.VocabularyConfig
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.shared.UserFacingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import com.alirezaiyan.vokab.server.study.MilestoneDetector

class AiServiceTest {

    private val chatUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val mapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val service = AiService(
        OpenRouterClient(builder, appProperties(), meterRegistry, AiCallTracker(fixedClock())) { _, _, _ -> },
        PromptTemplates(),
        appProperties(),
    )

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
    fun `celebration and streak reminder fall back to fixed copy on an empty answer`() {
        repeat(2) { expectChat().andRespond(json("""{"choices":[]}""")) }

        assertEquals("Great work today! 🎉 You're building something real.", service.generateCelebrationInsight(stats(), null))
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
        assertEquals("Big milestone 🏆", service.generateMilestoneMessage(milestone(), null))
    }

    @Test
    fun `daily insight and milestone message throw on an empty answer`() {
        repeat(2) { expectChat().andRespond(json("""{"choices":[]}""")) }

        assertThrows<UpstreamServiceException> { service.generateDailyInsight(insightContext()) }
        assertThrows<UpstreamServiceException> { service.generateMilestoneMessage(milestone(), "Ali") }
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

    // ── extractWordsFromImage (v2) ────────────────────────────────────────────

    @Test
    fun `extractWordsFromImage returns clean de-duplicated items from the JSON answer`() {
        expectChat()
            .andExpect(promptContains("learner of German whose native language is English"))
            .andRespond(
                answer(
                    "```json\n{\"items\":[" +
                        "{\"term\":\" gehen \",\"translation\":\"to go, to walk\",\"note\":\"verb\"}," +
                        "{\"term\":\"Gehen\",\"translation\":\"TO GO, TO WALK\"}," +
                        "{\"term\":\"\",\"translation\":\"empty\"}," +
                        "{\"term\":\"Haus\",\"translation\":\"house\",\"extra\":1}" +
                        "]}\n```",
                ),
            )

        val items = service.extractWordsFromImage("AAAA", "German", "English")

        assertEquals(
            listOf(ExtractedWordItem("gehen", "to go, to walk", "verb"), ExtractedWordItem("Haus", "house", "")),
            items,
        )
    }

    @Test
    fun `extractWordsFromImage returns an empty list for an image without vocabulary`() {
        expectChat().andRespond(answer("{\"items\":[]}"))

        assertEquals(emptyList<ExtractedWordItem>(), service.extractWordsFromImage("AAAA", "German", "English"))
    }

    @Test
    fun `extractWordsFromImage reports an unparseable answer to the user`() {
        expectChat().andRespond(answer("Hallo,hello;Welt,world"))

        assertThrows<UserFacingException> { service.extractWordsFromImage("AAAA", "German", "English") }
    }

    @Test
    fun `extractWordsFromImage rejects images over 5MB without calling the API`() {
        val oversize = "A".repeat((5 * 1024 * 1024 / 0.75).toInt() + 10)

        assertThrows<IllegalArgumentException> { service.extractWordsFromImage(oversize, "German", "English") }
        server.verify()
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

    private fun insightContext() = AiService.DailyInsightContext(
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
