package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.words.WordService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bucket4j.Bucket
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(AiController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class, VocabularySuggestionService::class)
class AiControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var aiService: AiService

    @MockitoBean
    private lateinit var rateLimitConfig: RateLimitConfig

    @MockitoBean
    private lateinit var featureAccessService: FeatureAccessService


    @MockitoBean
    private lateinit var dailyInsightService: DailyInsightService

    @MockitoBean
    private lateinit var wordService: WordService

    private val mockUser = createUser()
    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    // ── POST /api/v1/ai/extract-vocabulary ────────────────────────────────────

    @Test
    fun `POST extract-vocabulary should return 402 PREMIUM_REQUIRED when user lacks premium access`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(false)

        val request = createExtractVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/extract-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PREMIUM_REQUIRED"))
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST extract-vocabulary should return 429 when rate limit exceeded`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createRateLimitedBucket()
        `when`(rateLimitConfig.getImageProcessingBucket(mockUser.id.toString())).thenReturn(bucket)

        val request = createExtractVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/extract-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `POST extract-vocabulary should return 200 with extracted text when premium user and rate not exceeded`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getImageProcessingBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(aiService.extractVocabularyFromImage(
            imageBase64 = "dGVzdA==",
            targetLanguage = "German",
            extractWords = true,
            extractSentences = false
        )).thenReturn("Hallo,hello;Welt,world")

        val request = createExtractVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/extract-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.extractedText").value("Hallo,hello;Welt,world"))
            .andExpect(jsonPath("$.data.wordCount").value(2))
    }

    @Test
    fun `POST extract-vocabulary should return 500 when extraction throws exception`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getImageProcessingBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(aiService.extractVocabularyFromImage(
            imageBase64 = "dGVzdA==",
            targetLanguage = "German",
            extractWords = true,
            extractSentences = false
        )).thenThrow(RuntimeException("AI service unavailable"))

        val request = createExtractVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/extract-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST extract-vocabulary should return 502 UPSTREAM_UNAVAILABLE when OpenRouter fails`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getImageProcessingBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(aiService.extractVocabularyFromImage(
            imageBase64 = "dGVzdA==",
            targetLanguage = "German",
            extractWords = true,
            extractSentences = false
        )).thenThrow(UpstreamServiceException("OpenRouter image extraction failed: status=503"))

        mockMvc.perform(
            post("/api/v1/ai/extract-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createExtractVocabularyRequest()))
        )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("A service we depend on is unavailable. Please try again shortly."))
    }

    // ── GET /api/v1/ai/generate-insight ──────────────────────────────────────

    @Test
    fun `GET generate-insight should return 402 PREMIUM_REQUIRED when user lacks premium access`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(false)

        mockMvc.perform(
            get("/api/v1/ai/generate-insight")
                .with(authentication(auth))
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PREMIUM_REQUIRED"))
    }

    @Test
    fun `GET generate-insight should return 429 when rate limit exceeded`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createRateLimitedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        mockMvc.perform(
            get("/api/v1/ai/generate-insight")
                .with(authentication(auth))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `GET generate-insight should return 200 with today's insight`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        `when`(dailyInsightService.getOrGenerateTodaysInsight(mockUser.requireId())).thenReturn(
            DailyInsightService.TodaysInsight("You've mastered 10 words today!", Instant.now())
        )

        mockMvc.perform(
            get("/api/v1/ai/generate-insight")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.insight").value("You've mastered 10 words today!"))
    }

    @Test
    fun `GET generate-insight should return 500 when insight generation fails`() {
        `when`(featureAccessService.hasActivePremiumAccess(mockUser.requireId())).thenReturn(true)
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(dailyInsightService.getOrGenerateTodaysInsight(mockUser.requireId()))
            .thenThrow(RuntimeException("AI insight generation failed"))

        mockMvc.perform(
            get("/api/v1/ai/generate-insight")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── POST /api/v1/ai/translate-text ────────────────────────────────────────

    @Test
    fun `POST translate-text should return 400 when text is empty`() {
        val request = TranslateTextRequest(text = "   ", targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST translate-text should return 400 when text exceeds 200 characters`() {
        val longText = "a".repeat(201)
        val request = TranslateTextRequest(text = longText, targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST translate-text should return 400 when text has more than 2 lines`() {
        val multiLineText = "line one\nline two\nline three"
        val request = TranslateTextRequest(text = multiLineText, targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST translate-text should return 429 when rate limit exceeded`() {
        val bucket = createRateLimitedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        val request = TranslateTextRequest(text = "Hello", targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `POST translate-text should return 200 with translation on success`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(aiService.translateText("Hello", "German")).thenReturn("Hallo")

        val request = TranslateTextRequest(text = "Hello", targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.originalText").value("Hello"))
            .andExpect(jsonPath("$.data.translation").value("Hallo"))
    }

    @Test
    fun `POST translate-text should return 500 when translation throws exception`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)
        `when`(aiService.translateText("Hello", "German")).thenThrow(RuntimeException("Translation service unavailable"))

        val request = TranslateTextRequest(text = "Hello", targetLanguage = "German")

        mockMvc.perform(
            post("/api/v1/ai/translate-text")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── POST /api/v1/ai/suggest-vocabulary ───────────────────────────────────

    @Test
    fun `POST suggest-vocabulary should return 429 when rate limit exceeded`() {
        val bucket = createRateLimitedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        val request = createSuggestVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/suggest-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `POST suggest-vocabulary should return 200 with de-duplicated suggestions`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        val existingKeys = setOf("hallo")
        `when`(wordService.getExistingTranslationKeys(mockUser.requireId(), "German")).thenReturn(existingKeys)

        val rawItems = listOf(
            SuggestVocabularyItemResponse(originalWord = "Hallo", translation = "hello"),
            SuggestVocabularyItemResponse(originalWord = "Welt", translation = "world"),
            SuggestVocabularyItemResponse(originalWord = "Welt", translation = "world duplicate"),
        )
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(rawItems)

        val request = createSuggestVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/suggest-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            // "hallo" is in existing keys so filtered out; "welt" appears twice so only first kept
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].originalWord").value("Welt"))
    }

    @Test
    fun `POST suggest-vocabulary should return 400 on exception`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getAiBucket(mockUser.id.toString())).thenReturn(bucket)

        `when`(wordService.getExistingTranslationKeys(mockUser.requireId(), "German")).thenReturn(emptySet())
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenThrow(RuntimeException("AI service down"))

        val request = createSuggestVocabularyRequest()

        mockMvc.perform(
            post("/api/v1/ai/suggest-vocabulary")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/ai/health ────────────────────────────────────────────────

    @Test
    fun `GET health should return 200 with service info`() {
        mockMvc.perform(
            get("/api/v1/ai/health")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.service").value("AI Service"))
            .andExpect(jsonPath("$.data.status").value("operational"))
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        currentStreak: Int = 3,
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        subscriptionStatus = subscriptionStatus,
        currentStreak = currentStreak,
        longestStreak = 5,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun createExtractVocabularyRequest(
        imageBase64: String = "dGVzdA==",
        targetLanguage: String = "German",
        extractWords: Boolean = true,
        extractSentences: Boolean = false,
    ): ExtractVocabularyRequest = ExtractVocabularyRequest(
        imageBase64 = imageBase64,
        targetLanguage = targetLanguage,
        extractWords = extractWords,
        extractSentences = extractSentences,
    )

    private fun createSuggestVocabularyRequest(
        targetLanguage: String = "German",
        currentLevel: String = "beginner",
        nativeLanguage: String = "English",
    ): SuggestVocabularyRequest = SuggestVocabularyRequest(
        targetLanguage = targetLanguage,
        currentLevel = currentLevel,
        nativeLanguage = nativeLanguage,
    )

    private fun createAllowedBucket(): Bucket {
        val bucket = org.mockito.Mockito.mock(Bucket::class.java)
        `when`(bucket.tryConsume(1)).thenReturn(true)
        return bucket
    }

    private fun createRateLimitedBucket(): Bucket {
        val bucket = org.mockito.Mockito.mock(Bucket::class.java)
        `when`(bucket.tryConsume(1)).thenReturn(false)
        return bucket
    }
}
