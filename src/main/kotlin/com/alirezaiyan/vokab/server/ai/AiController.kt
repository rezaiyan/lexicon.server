package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.credits.CreditAction
import com.alirezaiyan.vokab.server.credits.CreditCharger
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.PremiumRequiredException
import com.alirezaiyan.vokab.server.shared.RateLimitExceededException
import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import io.github.bucket4j.Bucket
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

/**
 * AI features. Photo extraction, suggestions and translation spend credits (402
 * `INSUFFICIENT_CREDITS` when short; refunded when the call fails or yields nothing); the daily
 * insight is a premium perk (402 `PREMIUM_REQUIRED`). Rate limits apply on top of both.
 */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(
    private val aiService: AiService,
    private val rateLimitConfig: RateLimitConfig,
    private val featureAccessService: FeatureAccessService,
    private val appProperties: AppProperties,
    private val dailyInsightService: DailyInsightService,
    private val vocabularySuggestionService: VocabularySuggestionService,
    private val creditCharger: CreditCharger,
) {
    
    @PostMapping("/extract-vocabulary")
    fun extractVocabulary(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: ExtractVocabularyRequest
    ): ResponseEntity<ApiResponse<VocabularyExtractionResponse>> {
        logger.info { "userId=${user.id} requesting vocabulary extraction" }

        consumeRateLimit(user, rateLimitConfig.getImageProcessingBucket(user.id.toString()), "image processing")

        val extractedText = creditCharger.charge(user.id, CreditAction.PHOTO_EXTRACTION, refundIf = String::isBlank) {
            aiService.extractVocabularyFromImage(
                imageBase64 = request.imageBase64,
                targetLanguage = request.targetLanguage,
                extractWords = request.extractWords,
                extractSentences = request.extractSentences
            )
        }

        val wordCount = extractedText.split(";").size
        logger.info { "Vocabulary extraction successful for userId=${user.id}: $wordCount words" }

        return ResponseEntity.ok(ApiResponse(success = true, data = VocabularyExtractionResponse(
            extractedText = extractedText,
            wordCount = wordCount
        )))
    }
    
    /** v2 photo extraction: explicit language pair, structured items (see [AiService.extractWordsFromImage]). */
    @PostMapping("/extract-words")
    fun extractWords(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: ExtractWordsRequest
    ): ResponseEntity<ApiResponse<ExtractWordsResponse>> {
        logger.info { "userId=${user.id} requesting word extraction (v2)" }

        consumeRateLimit(user, rateLimitConfig.getImageProcessingBucket(user.id.toString()), "image processing")

        // Nothing recognised is refunded: the user got nothing for the credits
        val items = creditCharger.charge(user.id, CreditAction.PHOTO_EXTRACTION, refundIf = { it.isEmpty() }) {
            aiService.extractWordsFromImage(
                imageBase64 = request.imageBase64,
                learningLanguage = request.learningLanguage,
                nativeLanguage = request.nativeLanguage,
                includePhrases = request.includePhrases,
            )
        }
        logger.info { "Word extraction (v2) for userId=${user.id}: ${items.size} items" }
        return ResponseEntity.ok(ApiResponse(success = true, data = ExtractWordsResponse(items)))
    }

    @GetMapping("/generate-insight")
    fun generateInsight(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<InsightResponse>> {
        logger.info { "userId=${user.id} requesting daily insight (fallback)" }

        requirePremium(user, "AI insights")
        consumeRateLimit(user, rateLimitConfig.getAiBucket(user.id.toString()), "AI insight")

        val insight = dailyInsightService.getOrGenerateTodaysInsight(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = InsightResponse(
            insight = insight.text,
            generatedAt = insight.generatedAt.toString()
        )))
    }
    
    @PostMapping("/translate-text")
    fun translateText(
        @AuthenticationPrincipal user: AuthUser,
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

        val translation = creditCharger.charge(user.id, CreditAction.TEXT_TRANSLATION, refundIf = String::isBlank) {
            aiService.translateText(
                text = text,
                targetLanguage = request.targetLanguage
            )
        }

        return ResponseEntity.ok(ApiResponse(success = true, data = TranslateTextResponse(
            originalText = text,
            translation = translation
        )))
    }
    
    @PostMapping("/suggest-vocabulary")
    fun suggestVocabulary(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SuggestVocabularyRequest
    ): ResponseEntity<ApiResponse<SuggestVocabularyResponse>> {
        logger.info { "userId=${user.id} requesting suggested vocabulary: target=${request.targetLanguage}, level=${request.currentLevel}, native=${request.nativeLanguage}" }

        consumeRateLimit(user, rateLimitConfig.getAiBucket(user.id.toString()), "suggest-vocabulary")

        val response = creditCharger.charge(user.id, CreditAction.AI_SUGGESTION, refundIf = { it.items.isEmpty() }) {
            vocabularySuggestionService.suggestForUser(
                userId = user.id,
                targetLanguage = request.targetLanguage,
                currentLevel = request.currentLevel,
                nativeLanguage = request.nativeLanguage,
                interests = request.interests,
                targetLanguageCode = request.targetLanguageCode,
            )
        }
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/health")
    fun healthCheck(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ApiResponse<Map<String, String>>> {
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
    private fun requirePremium(user: AuthUser, feature: String) {
        if (featureAccessService.hasActivePremiumAccess(user.id)) return
        logger.warn { "userId=${user.id} attempted $feature without premium access" }
        throw PremiumRequiredException(feature)
    }

    /** Takes one token from [bucket], or throws [RateLimitExceededException] (429). */
    private fun consumeRateLimit(user: AuthUser, bucket: Bucket, endpoint: String) {
        if (bucket.tryConsume(1)) return
        logger.warn { "Rate limit exceeded for userId=${user.id} on $endpoint" }
        throw RateLimitExceededException()
    }
}
