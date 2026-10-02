package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.RateLimitConfig
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.OnboardingPreferencesRequest
import com.alirezaiyan.vokab.server.presentation.dto.SuggestVocabularyResponse
import com.alirezaiyan.vokab.server.service.OpenRouterService
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
    private val openRouterService: OpenRouterService,
    private val rateLimitConfig: RateLimitConfig,
    private val appProperties: AppProperties
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
            return ResponseEntity.status(429)
                .body(ApiResponse(success = false, message = "Rate limit exceeded. Please try again in a minute."))
        }

        return try {
            val cleanedInterests = request.interests
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val rawItems = openRouterService.generateVocabularyFromPreferences(
                request.targetLanguage.trim(),
                request.currentLevel.trim(),
                request.nativeLanguage.trim(),
                cleanedInterests
            ).block() ?: emptyList()

            // Deduplicate within the AI response itself by normalized originalWord
            val seen = mutableSetOf<String>()
            val dedupedItems = rawItems.filter { item ->
                val key = item.originalWord.trim().lowercase()
                if (key.isBlank()) return@filter false
                seen.add(key)
            }

            val baseCount = appProperties.vocabulary.suggestionCount
            val limitedItems = dedupedItems.take(baseCount)

            val response = SuggestVocabularyResponse(
                targetLanguage = request.targetLanguage.trim(),
                nativeLanguage = request.nativeLanguage.trim(),
                currentLevel = request.currentLevel.trim(),
                items = limitedItems
            )
            logger.info { "Onboarding: returning ${limitedItems.size} vocabulary items to $clientIp (raw=${rawItems.size}, deduped=${dedupedItems.size}, targetCount=$baseCount)" }
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (error: Exception) {
            logger.error(error) { "Onboarding vocabulary generation failed for $clientIp" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = error.clientMessage("Failed to generate vocabulary")))
        }
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
