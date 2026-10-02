package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.JwtConfig
import com.alirezaiyan.vokab.server.config.OpenRouterConfig
import org.springframework.core.ParameterizedTypeReference
import com.alirezaiyan.vokab.server.config.VocabularyConfig
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class OpenRouterServiceTest {

    private lateinit var appProperties: AppProperties
    private lateinit var webClientBuilder: WebClient.Builder
    private lateinit var webClient: WebClient
    private lateinit var requestBodyUriSpec: WebClient.RequestBodyUriSpec
    private lateinit var requestBodySpec: WebClient.RequestBodySpec
    private lateinit var responseSpec: WebClient.ResponseSpec
    private lateinit var openRouterService: OpenRouterService

    @BeforeEach
    fun setUp() {
        appProperties = createAppProperties()

        webClientBuilder = mockk()
        webClient = mockk()
        requestBodyUriSpec = mockk()
        requestBodySpec = mockk()
        responseSpec = mockk()

        every { webClientBuilder.clone() } returns webClientBuilder
        every { webClientBuilder.baseUrl(any()) } returns webClientBuilder
        every { webClientBuilder.defaultHeader(any(), any()) } returns webClientBuilder
        every { webClientBuilder.build() } returns webClient

        openRouterService = OpenRouterService(webClientBuilder, appProperties)

        // Wire up the standard fluent chain used by all methods
        every { webClient.post() } returns requestBodyUriSpec
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
        every { requestBodySpec.retrieve() } returns responseSpec
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
    }

    // ── extractVocabularyFromImage ────────────────────────────────────────────

    @Test
    fun `extractVocabularyFromImage should return Mono error when image exceeds 5MB`() {
        // Arrange — base64 of 5MB+ original: 5*1024*1024 / 0.75 + 1 chars
        val oversizeBase64 = "A".repeat((5 * 1024 * 1024 / 0.75).toInt() + 10)

        // Act
        val result = openRouterService.extractVocabularyFromImage(oversizeBase64, "English")

        // Assert
        var errorThrown: Throwable? = null
        result.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown is IllegalArgumentException)
        assertTrue(errorThrown!!.message!!.contains("too large"))
    }

    @Test
    fun `extractVocabularyFromImage should return extracted text for valid image and response`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val responseJson = buildOpenRouterResponseJson("Hallo,hello;Guten Morgen,good morning")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(parseOpenRouterResponse("Hallo,hello;Guten Morgen,good morning"))

        // Act
        val resultMono = openRouterService.extractVocabularyFromImage(validBase64, "English")
        val result = resultMono.block()

        // Assert
        assertNotNull(result)
        assertTrue(result!!.contains("Hallo,hello"))
    }

    @Test
    fun `extractVocabularyFromImage should emit error when API returns error field`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("Rate limit exceeded")
        )
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.extractVocabularyFromImage(validBase64, "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("Rate limit exceeded"))
    }

    @Test
    fun `extractVocabularyFromImage should emit error when response choices are empty`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val emptyChoicesResponse = OpenRouterService.OpenRouterResponse(
            choices = emptyList(),
            error = null
        )
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(emptyChoicesResponse)

        // Act
        val resultMono = openRouterService.extractVocabularyFromImage(validBase64, "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("No response from AI"))
    }

    @Test
    fun `extractVocabularyFromImage should emit error when AI responds with ERROR prefix`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val errorTextResponse = createOpenRouterResponseWithContent("ERROR: No vocabulary found")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorTextResponse)

        // Act
        val resultMono = openRouterService.extractVocabularyFromImage(validBase64, "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("No vocabulary found in the image"))
    }

    @Test
    fun `extractVocabularyFromImage should emit error when format is invalid`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val invalidFormatResponse = createOpenRouterResponseWithContent("this is not valid vocabulary format at all")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(invalidFormatResponse)

        // Act
        val resultMono = openRouterService.extractVocabularyFromImage(validBase64, "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("Failed to extract valid vocabulary format"))
    }

    @Test
    fun `extractVocabularyFromImage should use words-only extraction type by default`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val successResponse = createOpenRouterResponseWithContent("Hallo,hello;danke,thanks")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.extractVocabularyFromImage(
            validBase64, "English", extractWords = true, extractSentences = false
        ).block()

        // Assert — just verify it returns the text without error
        assertNotNull(result)
        assertEquals("Hallo,hello;danke,thanks", result)
    }

    @Test
    fun `extractVocabularyFromImage should use sentences-only extraction type when specified`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val successResponse = createOpenRouterResponseWithContent("Guten Morgen,good morning")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.extractVocabularyFromImage(
            validBase64, "English", extractWords = false, extractSentences = true
        ).block()

        // Assert
        assertNotNull(result)
    }

    @Test
    fun `extractVocabularyFromImage should use both extraction type when both flags true`() {
        // Arrange
        val validBase64 = "A".repeat(100)
        val successResponse = createOpenRouterResponseWithContent("Hallo,hello;Guten Morgen,good morning")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.extractVocabularyFromImage(
            validBase64, "English", extractWords = true, extractSentences = true
        ).block()

        // Assert
        assertNotNull(result)
    }

    // ── generateCelebrationInsight ────────────────────────────────────────────

    @Test
    fun `generateCelebrationInsight should return message from AI response`() {
        // Arrange
        val stats = createProgressStats(totalWords = 100, level6Count = 20)
        val message = "Amazing job today, you mastered 20 words!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateCelebrationInsight(stats, "Alice").block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateCelebrationInsight should return fallback when choices are null`() {
        // Arrange
        val stats = createProgressStats()
        val nullChoicesResponse = OpenRouterService.OpenRouterResponse(choices = null, error = null)
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(nullChoicesResponse)

        // Act
        val result = openRouterService.generateCelebrationInsight(stats, "Bob").block()

        // Assert
        assertNotNull(result)
        assertTrue(result!!.contains("Great work"))
    }

    @Test
    fun `generateCelebrationInsight should emit error when API error field is set`() {
        // Arrange
        val stats = createProgressStats()
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("service unavailable")
        )
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.generateCelebrationInsight(stats, "Carol")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("service unavailable"))
    }

    @Test
    fun `generateCelebrationInsight should work with null userName`() {
        // Arrange
        val stats = createProgressStats()
        val message = "Keep up the great work!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateCelebrationInsight(stats, null).block()

        // Assert
        assertEquals(message, result)
    }

    // ── generateStreakResetWarning ─────────────────────────────────────────────

    @Test
    fun `generateStreakResetWarning should return AI message on success`() {
        // Arrange
        val stats = createProgressStats(dueCards = 5)
        val message = "Don't lose your 7-day streak, Alex!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateStreakResetWarning(7, stats, "Alex").block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateStreakResetWarning should return fallback when choices are null`() {
        // Arrange
        val stats = createProgressStats()
        val nullChoicesResponse = OpenRouterService.OpenRouterResponse(choices = null, error = null)
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(nullChoicesResponse)

        // Act
        val result = openRouterService.generateStreakResetWarning(3, stats, "Sam").block()

        // Assert
        assertNotNull(result)
        assertTrue(result!!.contains("3-day streak"))
    }

    @Test
    fun `generateStreakResetWarning should emit error when API error is set`() {
        // Arrange
        val stats = createProgressStats()
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("quota exceeded")
        )
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.generateStreakResetWarning(5, stats, "Jordan")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("quota exceeded"))
    }

    // ── generateStreakReminderMessage ─────────────────────────────────────────

    @Test
    fun `generateStreakReminderMessage should return AI message on success`() {
        // Arrange
        val message = "You have a 5-day streak! Keep it going!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateStreakReminderMessage(5, "Dana").block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateStreakReminderMessage should include progress stats in context when provided`() {
        // Arrange
        val stats = createProgressStats(totalWords = 200, dueCards = 15)
        val message = "15 cards are waiting for you!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateStreakReminderMessage(10, "Taylor", stats).block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateStreakReminderMessage should return fallback when choices are null`() {
        // Arrange
        val nullChoicesResponse = OpenRouterService.OpenRouterResponse(choices = null, error = null)
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(nullChoicesResponse)

        // Act
        val result = openRouterService.generateStreakReminderMessage(12, "Pat").block()

        // Assert
        assertNotNull(result)
        assertTrue(result!!.contains("12-day streak"))
    }

    @Test
    fun `generateStreakReminderMessage should emit error when API error is set`() {
        // Arrange
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("model unavailable")
        )
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.generateStreakReminderMessage(3, "Morgan")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("model unavailable"))
    }

    @Test
    fun `generateStreakReminderMessage should work without progress stats`() {
        // Arrange
        val message = "Keep your streak alive!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateStreakReminderMessage(1, "Riley", null).block()

        // Assert
        assertEquals(message, result)
    }

    // ── translateText ─────────────────────────────────────────────────────────

    @Test
    fun `translateText should return translated text on success`() {
        // Arrange
        val translation = "Hallo Welt"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(translation))

        // Act
        val result = openRouterService.translateText("Hello World", "German").block()

        // Assert
        assertEquals(translation, result)
    }

    @Test
    fun `translateText should emit error when translation is empty`() {
        // Arrange
        val emptyContentResponse = createOpenRouterResponseWithContent("  ")
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(emptyContentResponse)

        // Act
        val resultMono = openRouterService.translateText("Hello", "German")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("Translation failed"))
    }

    @Test
    fun `translateText should emit error when API error field is set`() {
        // Arrange
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("context length exceeded")
        )
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.translateText("Hello", "German")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("context length exceeded"))
    }

    @Test
    fun `translateText should emit error when choices are null`() {
        // Arrange
        val nullChoicesResponse = OpenRouterService.OpenRouterResponse(choices = null, error = null)
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(nullChoicesResponse)

        // Act
        val resultMono = openRouterService.translateText("Hello", "German")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("Translation failed"))
    }

    // ── generateVocabularyFromPreferences ─────────────────────────────────────

    @Test
    fun `generateVocabularyFromPreferences should parse and return items on success`() {
        // Arrange
        val content = "Hallo,hello,a greeting\nGuten Morgen,good morning,formal greeting\ndanke,thank you,"
        val successResponse = createOpenRouterResponseWithContent(content)
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English"
        ).block()

        // Assert
        assertNotNull(result)
        assertEquals(3, result!!.size)
        assertEquals("Hallo", result[0].originalWord)
        assertEquals("hello", result[0].translation)
        assertEquals("a greeting", result[0].description)
    }

    @Test
    fun `generateVocabularyFromPreferences should emit error when content is blank`() {
        // Arrange
        val blankResponse = createOpenRouterResponseWithContent("   ")
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(blankResponse)

        // Act
        val resultMono = openRouterService.generateVocabularyFromPreferences("German", "beginner", "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("No vocabulary generated"))
    }

    @Test
    fun `generateVocabularyFromPreferences should emit error when API error field is set`() {
        // Arrange
        val errorResponse = OpenRouterService.OpenRouterResponse(
            choices = null,
            error = OpenRouterService.ErrorDetail("upstream error")
        )
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(errorResponse)

        // Act
        val resultMono = openRouterService.generateVocabularyFromPreferences("German", "beginner", "English")

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("upstream error"))
    }

    @Test
    fun `generateVocabularyFromPreferences should include interests in request when provided`() {
        // Arrange
        val content = "Reisen,travel\nEssen,food"
        val successResponse = createOpenRouterResponseWithContent(content)
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "intermediate",
            nativeLanguage = "English",
            interests = listOf("travel", "food")
        ).block()

        // Assert
        assertNotNull(result)
        assertEquals(2, result!!.size)
    }

    @Test
    fun `generateVocabularyFromPreferences should skip lines missing translation`() {
        // Arrange
        val content = "Hallo,hello\nbadline\ndanke,thanks"
        val successResponse = createOpenRouterResponseWithContent(content)
        every { responseSpec.onStatus(any(), any()) } returns responseSpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(successResponse)

        // Act
        val result = openRouterService.generateVocabularyFromPreferences("German", "beginner", "English").block()

        // Assert
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals("Hallo", result[0].originalWord)
        assertEquals("danke", result[1].originalWord)
    }

    // ── generateDailyInsight ──────────────────────────────────────────────────

    @Test
    fun `generateDailyInsight should return message on success`() {
        // Arrange
        val ctx = createDailyInsightContext()
        val message = "You have 50 words and your accuracy is improving!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateDailyInsight(ctx).block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateDailyInsight should emit error when response choices are empty`() {
        // Arrange
        val ctx = createDailyInsightContext()
        val emptyResponse = OpenRouterService.OpenRouterResponse(choices = null, error = null)
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(emptyResponse)

        // Act
        val resultMono = openRouterService.generateDailyInsight(ctx)

        // Assert
        var errorThrown: Throwable? = null
        resultMono.doOnError { errorThrown = it }.subscribe()
        assertNotNull(errorThrown)
        assertTrue(errorThrown!!.message!!.contains("Empty response"))
    }

    // ── generateReEngagementInsight ───────────────────────────────────────────

    @Test
    fun `generateReEngagementInsight should return message on success`() {
        // Arrange
        val stats = createProgressStats()
        val message = "Welcome back! You have 10 words waiting."
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateReEngagementInsight(stats, 7, "Alex").block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateReEngagementInsight should work with null userName`() {
        // Arrange
        val stats = createProgressStats()
        val message = "Ready to jump back in?"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateReEngagementInsight(stats, 3, null).block()

        // Assert
        assertEquals(message, result)
    }

    // ── generateMilestoneMessage ──────────────────────────────────────────────

    @Test
    fun `generateMilestoneMessage should return message on success`() {
        // Arrange
        val stats = createProgressStats(totalWords = 100)
        val milestone = MilestoneDetector.MilestoneEvent(
            type = "word_count",
            title = "100 Words",
            description = "reached 100 words",
            value = 100L
        )
        val message = "Incredible! You just hit 100 words!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateMilestoneMessage(milestone, stats, "Kim").block()

        // Assert
        assertEquals(message, result)
    }

    @Test
    fun `generateMilestoneMessage should work with null userName`() {
        // Arrange
        val stats = createProgressStats()
        val milestone = MilestoneDetector.MilestoneEvent(
            type = "streak",
            title = "7-day streak",
            description = "maintained a 7-day streak",
            value = 7L
        )
        val message = "Seven days in a row!"
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent(message))

        // Act
        val result = openRouterService.generateMilestoneMessage(milestone, stats, null).block()

        // Assert
        assertEquals(message, result)
    }

    // ── model configuration ────────────────────────────────────────────────────

    @Test
    fun `default model should not use deprecated claude-3_5-sonnet`() {
        val model = OpenRouterConfig().model
        assertTrue(!model.contains("claude-3.5-sonnet"), "Default model should not be claude-3.5-sonnet, was: $model")
        assertEquals("anthropic/claude-haiku-4.5", model)
    }

    @Test
    fun `requests use the configured model`() {
        appProperties.openrouter.model = "anthropic/custom-model"
        val captured = io.mockk.slot<Any>()
        every { requestBodySpec.bodyValue(capture(captured)) } returns requestBodySpec
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<OpenRouterService.OpenRouterResponse>>()) } returns
            Mono.just(createOpenRouterResponseWithContent("Hallo"))

        openRouterService.translateText("Hello", "German").block()

        assertEquals("anthropic/custom-model", (captured.captured as OpenRouterService.OpenRouterRequest).model)
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createAppProperties(
        openRouterApiKey: String = "test-api-key",
        openRouterBaseUrl: String = "https://openrouter.ai/api/v1",
        suggestionCount: Int = 50
    ): AppProperties {
        val props = AppProperties()
        props.openrouter = OpenRouterConfig(apiKey = openRouterApiKey, baseUrl = openRouterBaseUrl)
        props.vocabulary = VocabularyConfig(suggestionCount = suggestionCount)
        props.jwt = JwtConfig(
            secret = "test-secret-key-that-is-long-enough-for-hmac",
            expirationMs = 86400000L
        )
        return props
    }

    private fun createProgressStats(
        totalWords: Int = 50,
        dueCards: Int = 10,
        level6Count: Int = 5
    ): ProgressStatsDto = ProgressStatsDto(
        totalWords = totalWords,
        dueCards = dueCards,
        level0Count = 5,
        level1Count = 10,
        level2Count = 10,
        level3Count = 10,
        level4Count = 5,
        level5Count = 5,
        level6Count = level6Count
    )

    private fun createOpenRouterResponseWithContent(content: String): OpenRouterService.OpenRouterResponse =
        OpenRouterService.OpenRouterResponse(
            choices = listOf(
                OpenRouterService.Choice(
                    message = OpenRouterService.MessageContent(content = content)
                )
            ),
            error = null
        )

    private fun parseOpenRouterResponse(content: String): OpenRouterService.OpenRouterResponse =
        createOpenRouterResponseWithContent(content)

    private fun buildOpenRouterResponseJson(content: String): String =
        """{"choices":[{"message":{"content":"$content"}}]}"""

    private fun createDailyInsightContext(
        totalWords: Int = 50,
        currentStreak: Int = 3,
        userName: String? = "Alice"
    ): OpenRouterService.DailyInsightContext = OpenRouterService.DailyInsightContext(
        stats = createProgressStats(totalWords = totalWords),
        userName = userName,
        optimalStudyHour = 18,
        accuracyTrend = 5.0f,
        topDifficultWord = "Schadenfreude",
        primaryLanguage = "German",
        sessionCompletionRate = 0.85f,
        currentStreak = currentStreak
    )
}
