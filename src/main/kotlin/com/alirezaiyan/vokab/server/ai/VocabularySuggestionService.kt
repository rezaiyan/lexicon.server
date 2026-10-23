package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.AppProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import com.alirezaiyan.vokab.server.words.WordService

private val logger = KotlinLogging.logger {}

/**
 * AI vocabulary suggestions: as many as the learner asked for, otherwise
 * `app.vocabulary.suggestion-count`, which is also the most a request can get.
 *
 * The model returns extra items (see [AiService.generateVocabularyFromPreferences]) so
 * that duplicates can be dropped and the list still reaches the count.
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
        targetLanguage = targetLanguage.asPromptLabel(),
        currentLevel = currentLevel.asPromptLabel(),
        nativeLanguage = nativeLanguage.asPromptLabel(),
        interests = interests.toPromptLabels(),
        existingKeys = emptySet(),
    )

    /**
     * Signed-in user: also drops words the user already has. Words store language codes ("de") while
     * prompts use names ("German"), so [targetLanguageCode] is used for that lookup when given.
     */
    fun suggestForUser(
        userId: Long,
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
        interests: List<String> = emptyList(),
        targetLanguageCode: String? = null,
        preferences: LearnerPreferences = LearnerPreferences(),
    ): SuggestVocabularyResponse {
        val target = targetLanguage.asPromptLabel()
        return suggest(
            targetLanguage = target,
            currentLevel = currentLevel.asPromptLabel(),
            nativeLanguage = nativeLanguage.asPromptLabel(),
            interests = interests.toPromptLabels(),
            existingKeys = wordService.getExistingTermKeys(userId, targetLanguageCode?.trim() ?: target),
            preferences = preferences,
        )
    }

    private fun suggest(
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
        interests: List<String>,
        existingKeys: Set<String>,
        preferences: LearnerPreferences = LearnerPreferences(),
    ): SuggestVocabularyResponse {
        // The learner's own number wins over the default, up to the configured maximum
        val maxCount = appProperties.vocabulary.suggestionCount
        val targetCount = preferences.requestedItemCount?.coerceAtMost(maxCount) ?: maxCount
        val rawItems = aiService.generateVocabularyFromPreferences(
            targetLanguage = targetLanguage,
            currentLevel = currentLevel,
            nativeLanguage = nativeLanguage,
            interests = interests,
            preferences = preferences,
            count = targetCount,
        )

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

    /** Client-supplied topics end up in the prompt, so they get the same treatment as language names. */
    private fun List<String>.toPromptLabels(): List<String> =
        take(MAX_INTERESTS).map { it.asPromptLabel() }.filter { it.isNotBlank() }

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
