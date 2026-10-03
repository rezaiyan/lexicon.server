package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.RateLimitConfig
import com.alirezaiyan.vokab.server.exception.PremiumRequiredException
import com.alirezaiyan.vokab.server.exception.RateLimitExceededException
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.DailyInsightService
import com.alirezaiyan.vokab.server.service.FeatureAccessService
import com.alirezaiyan.vokab.server.service.OpenRouterService
import com.alirezaiyan.vokab.server.service.UserProgressService
import com.alirezaiyan.vokab.server.service.VocabularySuggestionService
import io.github.bucket4j.Bucket
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import java.time.Clock
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/ai")
class AiController(
    private val openRouterService: OpenRouterService,
    private val rateLimitConfig: RateLimitConfig,
    private val featureAccessService: FeatureAccessService,
    private val appProperties: AppProperties,
    private val userProgressService: UserProgressService,
    private val dailyInsightService: DailyInsightService,
    private val vocabularySuggestionService: VocabularySuggestionService,
    private val clock: Clock
) {
    
    @PostMapping("/extract-vocabulary")
    fun extractVocabulary(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: ExtractVocabularyRequest
    ): ResponseEntity<ApiResponse<VocabularyExtractionResponse>> {
        logger.info { "userId=${user.id} requesting vocabulary extraction" }

        requirePremium(user, "AI image extraction")
        consumeRateLimit(user, rateLimitConfig.getImageProcessingBucket(user.id.toString()), "image processing")

        val extractedText = openRouterService.extractVocabularyFromImage(
            imageBase64 = request.imageBase64,
            targetLanguage = request.targetLanguage,
            extractWords = request.extractWords,
            extractSentences = request.extractSentences
        )

        val wordCount = extractedText.split(";").size
        logger.info { "Vocabulary extraction successful for userId=${user.id}: $wordCount words" }

        return ResponseEntity.ok(ApiResponse(success = true, data = VocabularyExtractionResponse(
            extractedText = extractedText,
            wordCount = wordCount
        )))
    }
    
    @GetMapping("/generate-insight")
    fun generateInsight(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<InsightResponse>> {
        logger.info { "userId=${user.id} requesting daily insight (fallback)" }

        requirePremium(user, "AI insights")
        consumeRateLimit(user, rateLimitConfig.getAiBucket(user.id.toString()), "AI insight")

        // Try to get today's insight first (if it was already generated)
        val todaysInsight = dailyInsightService.getTodaysInsightForUser(user)
        if (todaysInsight != null) {
            logger.info { "Returning existing daily insight for userId=${user.id}" }
            return ResponseEntity.ok(
                ApiResponse(
                    success = true,
                    data = InsightResponse(
                        insight = todaysInsight.insightText,
                        generatedAt = todaysInsight.generatedAt.toString()
                    )
                )
            )
        }
        
        // Generate new insight if none exists for today
        logger.info { "Generating new daily insight for userId=${user.id}" }
        
        val progressStats = userProgressService.calculateProgressStats(user)
        val ctx = OpenRouterService.DailyInsightContext(
            stats = progressStats,
            userName = user.name,
            optimalStudyHour = null,
            accuracyTrend = null,
            topDifficultWord = null,
            primaryLanguage = null,
            sessionCompletionRate = null,
            currentStreak = user.currentStreak
        )

        val insightText = openRouterService.generateDailyInsight(ctx)
        val saved = dailyInsightService.saveDailyInsight(user, insightText)
        return ResponseEntity.ok(ApiResponse(success = true, data = InsightResponse(
            insight = saved?.insightText ?: insightText,
            generatedAt = (saved?.generatedAt ?: Instant.now(clock)).toString()
        )))
    }
    
    @PostMapping("/translate-text")
    fun translateText(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: TranslateTextRequest
    ): ResponseEntity<ApiResponse<TranslateTextResponse>> {
        logger.info { "userId=${user.id} requesting text translation" }

        val text = request.text.trim()

        if (text.isEmpty() || text.isBlank()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = "Text cannot be empty"))
        }

        if (text.length > 200) {
            return ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = "Text cannot exceed 200 characters"))
        }

        val lineCount = text.split("\n").size
        if (lineCount > 2) {
            return ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = "Text cannot exceed 2 lines"))
        }

        consumeRateLimit(user, rateLimitConfig.getAiBucket(user.id.toString()), "text translation")

        val translation = openRouterService.translateText(
            text = text,
            targetLanguage = request.targetLanguage
        )

        return ResponseEntity.ok(ApiResponse(success = true, data = TranslateTextResponse(
            originalText = text,
            translation = translation
        )))
    }
    
    @PostMapping("/suggest-vocabulary")
    fun suggestVocabulary(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: SuggestVocabularyRequest
    ): ResponseEntity<ApiResponse<SuggestVocabularyResponse>> {
        logger.info { "userId=${user.id} requesting suggested vocabulary: target=${request.targetLanguage}, level=${request.currentLevel}, native=${request.nativeLanguage}" }

        consumeRateLimit(user, rateLimitConfig.getAiBucket(user.id.toString()), "suggest-vocabulary")

        val response = vocabularySuggestionService.suggestForUser(
            user = user,
            targetLanguage = request.targetLanguage,
            currentLevel = request.currentLevel,
            nativeLanguage = request.nativeLanguage,
        )
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/health")
    fun healthCheck(@AuthenticationPrincipal user: User): ResponseEntity<ApiResponse<Map<String, String>>> {
        return ResponseEntity.ok(
            ApiResponse(
                success = true, 
                data = mapOf(
                    "service" to "AI Service",
                    "status" to "operational",
                    "model" to appProperties.openrouter.model
                )
            )
        )
    }

    /** Throws [PremiumRequiredException] (402) unless the user has premium access. */
    private fun requirePremium(user: User, feature: String) {
        if (featureAccessService.hasActivePremiumAccess(user)) return
        logger.warn { "userId=${user.id} attempted $feature without premium access" }
        throw PremiumRequiredException(feature)
    }

    /** Takes one token from [bucket], or throws [RateLimitExceededException] (429). */
    private fun consumeRateLimit(user: User, bucket: Bucket, endpoint: String) {
        if (bucket.tryConsume(1)) return
        logger.warn { "Rate limit exceeded for userId=${user.id} on $endpoint" }
        throw RateLimitExceededException()
    }
}
