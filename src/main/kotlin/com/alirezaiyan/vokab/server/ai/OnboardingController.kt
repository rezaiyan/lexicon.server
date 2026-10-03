package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.RateLimitExceededException
import com.alirezaiyan.vokab.server.shared.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * Public onboarding API: submit language preferences and receive suggested vocabulary.
 * No authentication required. Rate limited by IP.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
class OnboardingController(
    private val vocabularySuggestionService: VocabularySuggestionService,
    private val rateLimitConfig: RateLimitConfig,
) {

    /**
     * POST /api/v1/onboarding/preferences
     * Body:
     * {
     *   "targetLanguage": "German",
     *   "nativeLanguage": "English",
     *   "currentLevel": "beginner",
     *   "interests": ["travel", "work", "daily life"] // optional
     * }
     * Returns: { "success": true, "data": { "targetLanguage", "nativeLanguage", "currentLevel", "items": [...] } }
     * Items are up to the configured vocabulary suggestion count (default 50)
     * vocabulary entries (originalWord, translation, description) for the user to review and import.
     */
    @PostMapping("/preferences")
    fun submitPreferences(
        @Valid @RequestBody request: OnboardingPreferencesRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<SuggestVocabularyResponse>> {
        val clientIp = getClientIp(httpRequest)
        logger.info {
            val interestsSummary = if (request.interests.isEmpty()) {
                "none"
            } else {
                request.interests.joinToString(limit = 5)
            }
            "Onboarding preferences from $clientIp: " +
                "target=${request.targetLanguage}, level=${request.currentLevel}, " +
                "native=${request.nativeLanguage}, interests=$interestsSummary"
        }

        val bucket = rateLimitConfig.getOnboardingBucket(clientIp)
        if (!bucket.tryConsume(1)) {
            logger.warn { "Onboarding rate limit exceeded for IP $clientIp" }
            throw RateLimitExceededException("Rate limit exceeded. Please try again in a minute.")
        }

        val response = vocabularySuggestionService.suggestForOnboarding(
            targetLanguage = request.targetLanguage,
            currentLevel = request.currentLevel,
            nativeLanguage = request.nativeLanguage,
            interests = request.interests,
        )
        logger.info { "Onboarding: returning ${response.items.size} vocabulary items to $clientIp" }
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return if (!forwarded.isNullOrBlank()) {
            forwarded.split(",").firstOrNull()?.trim() ?: request.remoteAddr
        } else {
            request.remoteAddr ?: "unknown"
        }
    }
}
