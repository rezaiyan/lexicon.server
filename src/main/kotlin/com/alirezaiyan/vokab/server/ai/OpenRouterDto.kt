package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.NotNull

data class ExtractVocabularyRequest(
    @field:NotBlank(message = "Image data is required")
    val imageBase64: String,
    
    @field:NotBlank(message = "Target language is required")
    val targetLanguage: String,
    
    val extractWords: Boolean = true,
    val extractSentences: Boolean = false
)

data class GenerateInsightRequest(
    @field:NotNull(message = "Progress stats are required")
    val stats: ProgressStatsDto
)

data class VocabularyExtractionResponse(
    val extractedText: String,
    val wordCount: Int
)

data class InsightResponse(
    val insight: String,
    val generatedAt: String
)

data class TranslateTextRequest(
    @field:NotBlank(message = "Text is required")
    val text: String,
    
    @field:NotBlank(message = "Target language is required")
    val targetLanguage: String
)

data class TranslateTextResponse(
    val originalText: String,
    val translation: String
)

// --- Suggest vocabulary by language preferences ---

data class SuggestVocabularyRequest(
    @field:NotBlank(message = "Target language (language to learn) is required")
    val targetLanguage: String,

    @field:NotBlank(message = "Current level in the target language is required")
    val currentLevel: String,

    @field:NotBlank(message = "Native or current language is required")
    val nativeLanguage: String,

    /** Optional focus topics; older clients omit it. */
    @field:Size(max = 10, message = "At most 10 interests")
    val interests: List<@Size(max = 40) String> = emptyList(),

    /** ISO code of [targetLanguage] (e.g. "de"), used to skip words the user already has. */
    @field:Size(max = 8)
    val targetLanguageCode: String? = null,
)

/**
 * Public onboarding preferences payload.
 *
 * This is used by the unauthenticated onboarding flow to generate
 * a first batch of vocabulary based on the learner profile and interests.
 */
data class OnboardingPreferencesRequest(
    @field:NotBlank(message = "Target language (language to learn) is required")
    val targetLanguage: String,

    @field:NotBlank(message = "Native or current language is required")
    val nativeLanguage: String,

    @field:NotBlank(message = "Current level in the target language is required")
    val currentLevel: String,

    /**
     * Optional list of interests or topics (e.g., "travel", "business", "university")
     * that should guide the generated vocabulary set.
     */
    val interests: List<String> = emptyList()
)

/**
 * Single vocabulary item for display and optional import.
 * originalWord is in the target language; translation is in the user's native language.
 */
data class SuggestVocabularyItemResponse(
    val originalWord: String,
    val translation: String,
    val description: String = ""
)

data class SuggestVocabularyResponse(
    val targetLanguage: String,
    val nativeLanguage: String,
    val currentLevel: String,
    val items: List<SuggestVocabularyItemResponse>
)

/** v2 photo extraction: explicit language pair in, structured items out (no text format to parse). */
data class ExtractWordsRequest(
    @field:NotBlank(message = "Image data is required")
    val imageBase64: String,

    @field:NotBlank(message = "Learning language is required")
    val learningLanguage: String,

    @field:NotBlank(message = "Native language is required")
    val nativeLanguage: String,

    /** Also return short phrases, not only single words. */
    val includePhrases: Boolean = false,
)

data class ExtractedWordItem(
    val term: String,
    val translation: String,
    val note: String = "",
)

data class ExtractWordsResponse(
    val items: List<ExtractedWordItem>,
)
