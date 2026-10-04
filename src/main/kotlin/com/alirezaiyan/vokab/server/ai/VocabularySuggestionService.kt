package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import com.alirezaiyan.vokab.server.words.WordService

private val logger = KotlinLogging.logger {}

/**
 * AI vocabulary suggestions, trimmed to the configured count.
 *
 * The model returns extra items (see [AiService.generateVocabularyFromPreferences]) so
 * that duplicates can be dropped and the list still reaches `app.vocabulary.suggestion-count`.
 */
@Service
class VocabularySuggestionService(
    private val aiService: AiService,
    private val wordService: WordService,
    private val appProperties: AppProperties,
) {

    /** Onboarding: no account yet, so only duplicates inside the AI response are removed. */
    fun suggestForOnboarding(
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
        interests: List<String>,
    ): SuggestVocabularyResponse = suggest(
        targetLanguage = targetLanguage.trim(),
        currentLevel = currentLevel.trim(),
        nativeLanguage = nativeLanguage.trim(),
        interests = interests.map { it.trim() }.filter { it.isNotBlank() },
        existingKeys = emptySet(),
    )

    /** Signed-in user: also drops words the user already has in [targetLanguage]. */
    fun suggestForUser(
        userId: Long,
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
    ): SuggestVocabularyResponse {
        val target = targetLanguage.trim()
        return suggest(
            targetLanguage = target,
            currentLevel = currentLevel.trim(),
            nativeLanguage = nativeLanguage.trim(),
            interests = emptyList(),
            existingKeys = wordService.getExistingTranslationKeys(userId, target),
        )
    }

    private fun suggest(
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
        interests: List<String>,
        existingKeys: Set<String>,
    ): SuggestVocabularyResponse {
        val rawItems = aiService.generateVocabularyFromPreferences(
            targetLanguage = targetLanguage,
            currentLevel = currentLevel,
            nativeLanguage = nativeLanguage,
            interests = interests,
        )

        val targetCount = appProperties.vocabulary.suggestionCount
        val items = uniqueNewItems(rawItems, existingKeys).take(targetCount)
        logger.info {
            "Vocabulary suggestions: raw=${rawItems.size}, existing=${existingKeys.size}, " +
                "returned=${items.size}, targetCount=$targetCount"
        }

        return SuggestVocabularyResponse(
            targetLanguage = targetLanguage,
            nativeLanguage = nativeLanguage,
            currentLevel = currentLevel,
            items = items,
        )
    }

    /** Items with a non-blank word not in [existingKeys], first occurrence only (case-insensitive). */
    private fun uniqueNewItems(
        items: List<SuggestVocabularyItemResponse>,
        existingKeys: Set<String>,
    ): List<SuggestVocabularyItemResponse> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            val key = item.originalWord.trim().lowercase()
            key.isNotBlank() && key !in existingKeys && seen.add(key)
        }
    }
}
