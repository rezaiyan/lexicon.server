package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bucket4j.Bucket
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class OnboardingControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var aiService: AiService

    @MockitoBean
    private lateinit var rateLimitConfig: RateLimitConfig

    // NOTE: Do NOT add @MockBean AppProperties here. ControllerTestSecurityConfig already
    // provides @Primary AppProperties() with correct defaults (including VocabularyConfig).
    // Mocking AppProperties causes RS256JwtTokenProvider to NPE on appProperties.jwt.privateKey
    // during Spring context startup before any @BeforeEach stubs can run.

    // ── POST /api/v1/onboarding/preferences ──────────────────────────────────

    @Test
    fun `POST preferences should return 200 with vocabulary items on success`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        val items = listOf(
            SuggestVocabularyItemResponse(originalWord = "Hallo", translation = "hello"),
            SuggestVocabularyItemResponse(originalWord = "Welt", translation = "world"),
        )
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(items)


        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.targetLanguage").value("German"))
            .andExpect(jsonPath("$.data.nativeLanguage").value("English"))
            .andExpect(jsonPath("$.data.currentLevel").value("beginner"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
    }

    @Test
    fun `POST preferences should return 429 when rate limit exceeded`() {
        val bucket = createRateLimitedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `POST preferences should return 400 when request body is missing required fields`() {
        val invalidBody = """{"targetLanguage": "German"}"""

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST preferences should return 500 when AI generation fails`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenThrow(RuntimeException("AI service unavailable"))


        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST preferences should deduplicate vocabulary items from AI response`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        val itemsWithDuplicates = listOf(
            SuggestVocabularyItemResponse(originalWord = "Hallo", translation = "hello"),
            SuggestVocabularyItemResponse(originalWord = "Hallo", translation = "hello again"),
            SuggestVocabularyItemResponse(originalWord = "Welt", translation = "world"),
        )
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(itemsWithDuplicates)


        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(2))
    }

    @Test
    fun `POST preferences should forward interests to AI service`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        val interests = listOf("travel", "work")
        val items = listOf(
            SuggestVocabularyItemResponse(originalWord = "Reise", translation = "trip"),
        )
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = interests
        )).thenReturn(items)


        val request = createPreferencesRequest(interests = interests)

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(1))
    }

    @Test
    fun `POST preferences should respect X-Forwarded-For header for rate limiting`() {
        val bucket = createAllowedBucket()
        // The bucket is fetched with the forwarded IP
        `when`(rateLimitConfig.getOnboardingBucket("203.0.113.10")).thenReturn(bucket)

        val items = listOf(
            SuggestVocabularyItemResponse(originalWord = "Hallo", translation = "hello"),
        )
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(items)


        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `POST preferences should limit items to configured suggestion count`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        // 55 items — real default suggestionCount is 50, so result should be capped at 50
        val manyItems = (1..55).map { i ->
            SuggestVocabularyItemResponse(originalWord = "Word$i", translation = "translation$i")
        }
        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(manyItems)

        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(50))
    }

    @Test
    fun `POST preferences should return 200 with empty items when AI returns no items`() {
        val bucket = createAllowedBucket()
        `when`(rateLimitConfig.getOnboardingBucket(org.mockito.ArgumentMatchers.anyString())).thenReturn(bucket)

        `when`(aiService.generateVocabularyFromPreferences(
            targetLanguage = "German",
            currentLevel = "beginner",
            nativeLanguage = "English",
            interests = emptyList()
        )).thenReturn(emptyList())


        val request = createPreferencesRequest()

        mockMvc.perform(
            post("/api/v1/onboarding/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(0))
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createPreferencesRequest(
        targetLanguage: String = "German",
        nativeLanguage: String = "English",
        currentLevel: String = "beginner",
        interests: List<String> = emptyList(),
    ): OnboardingPreferencesRequest = OnboardingPreferencesRequest(
        targetLanguage = targetLanguage,
        nativeLanguage = nativeLanguage,
        currentLevel = currentLevel,
        interests = interests,
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
