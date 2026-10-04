package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.shared.UserFacingException
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/**
 * LLM features: each renders a [Prompt] template, calls the [AiClient] and decides what an empty
 * answer means (a fallback text or an error). Transport and API failures surface as
 * [UpstreamServiceException] (502).
 */
@Service
class AiService(
    private val aiClient: AiClient,
    private val promptTemplates: PromptTemplates,
    private val appProperties: AppProperties,
) {

    data class DailyInsightContext(
        val stats: ProgressStatsDto,
        val userName: String?,
        val optimalStudyHour: Int?,
        val accuracyTrend: Float?,
        val topDifficultWord: String?,
        val primaryLanguage: String?,
        val sessionCompletionRate: Float?,
        val currentStreak: Int
    )

    fun extractVocabularyFromImage(
        imageBase64: String,
        targetLanguage: String,
        extractWords: Boolean = true,
        extractSentences: Boolean = false
    ): String {
        // Base64 inflates by ~4/3.
        val estimatedSizeBytes = (imageBase64.length * 0.75).toInt()
        require(estimatedSizeBytes <= MAX_IMAGE_BYTES) { "Image too large. Maximum size is 5MB." }
        logger.info { "[AI] Image extraction: size=~${estimatedSizeBytes / 1024}KB, language=$targetLanguage" }

        val (extractionType, extractionRule) = when {
            extractWords && extractSentences ->
                "both individual vocabulary words AND example sentences" to
                    "Extraction Rule: Extract BOTH individual words AND phrases"
            extractSentences ->
                "example sentences only" to "Extraction Rule: ONLY phrases\nSKIP: Individual words"
            else ->
                "individual vocabulary words only" to
                    "Extraction Rule: ONLY individual words or short phrases (2-3 words max)\n" +
                    "SKIP: Full sentences, long phrases, paragraphs"
        }
        val prompt = promptTemplates.render(
            Prompt.IMAGE_EXTRACTION,
            mapOf(
                "extractionType" to extractionType,
                "targetLanguage" to targetLanguage,
                "extractionRule" to extractionRule,
            ),
        )

        val text = aiClient.completeWithImage(imageBase64, prompt, AiOperation.IMAGE_EXTRACTION)
            ?: throw UserFacingException("No response from AI. Please try again.")
        unusableExtraction(text)?.let { throw UserFacingException(it) }
        return text
    }

    /** Why an extraction answer can't be imported, as a user-facing message; null when it can. */
    private fun unusableExtraction(text: String): String? = when {
        text.contains("ERROR:", ignoreCase = true) || text.contains("No vocabulary found", ignoreCase = true) ->
            "No vocabulary found in the image. Please use an image with visible text."
        !isValidVocabularyFormat(text) ->
            "Failed to extract valid vocabulary format. Please try a clearer image."
        else -> null
    }

    fun generateCelebrationInsight(stats: ProgressStatsDto, userName: String?): String {
        val prompt = promptTemplates.render(
            Prompt.CELEBRATION_INSIGHT,
            mapOf(
                "userName" to (userName ?: "a learner"),
                "totalWords" to stats.totalWords,
                "masteredWords" to stats.level6Count,
            ),
        )
        return aiClient.complete(prompt, AiOperation.CELEBRATION_INSIGHT)
            ?: "Great work today! 🎉 You're building something real."
    }

    fun generateStreakResetWarning(
        currentStreak: Int,
        progressStats: ProgressStatsDto,
        userName: String
    ): String {
        val prompt = promptTemplates.render(
            Prompt.STREAK_RESET_WARNING,
            mapOf(
                "userName" to userName,
                "currentStreak" to currentStreak,
                "totalWords" to progressStats.totalWords,
                "dueCards" to progressStats.dueCards,
                "masteredWords" to progressStats.level5Count + progressStats.level6Count,
            ),
        )
        return aiClient.complete(prompt, AiOperation.STREAK_RESET_WARNING)
            ?: "Don't lose your $currentStreak-day streak! 🔥 Log in now to keep it going!"
    }

    fun generateDailyInsight(ctx: DailyInsightContext): String {
        val optionalStats = listOfNotNull(
            ctx.primaryLanguage?.let { "- Learning: $it" },
            ctx.topDifficultWord?.let { "- Most challenging word recently: \"$it\"" },
            ctx.accuracyTrend?.let { trend ->
                val dir = if (trend > 0) "improving (up ${trend.toInt()}%)" else "declining (down ${(-trend).toInt()}%)"
                "- Accuracy is $dir vs last week"
            },
            ctx.sessionCompletionRate?.let { rate -> "- Session completion: ${(rate * 100).toInt()}%" },
            ctx.optimalStudyHour?.let { hour -> "- They tend to study best around $hour:00" },
        )
        val prompt = promptTemplates.render(
            Prompt.DAILY_INSIGHT,
            mapOf(
                "userName" to (ctx.userName ?: "a learner"),
                "totalWords" to ctx.stats.totalWords,
                "masteredWords" to ctx.stats.level6Count,
                "dueCards" to ctx.stats.dueCards,
                "currentStreak" to ctx.currentStreak,
                "optionalStats" to optionalStats.joinToString("\n"),
            ),
        )
        return aiClient.complete(prompt, AiOperation.DAILY_INSIGHT).orFail(AiOperation.DAILY_INSIGHT)
    }

    fun generateMilestoneMessage(milestone: MilestoneDetector.MilestoneEvent, userName: String?): String {
        val prompt = promptTemplates.render(
            Prompt.MILESTONE_MESSAGE,
            mapOf("userName" to (userName ?: "A learner"), "milestone" to milestone.description),
        )
        return aiClient.complete(prompt, AiOperation.MILESTONE_MESSAGE).orFail(AiOperation.MILESTONE_MESSAGE)
    }

    fun generateStreakReminderMessage(
        currentStreak: Int,
        userName: String,
        progressStats: ProgressStatsDto? = null
    ): String {
        val progressLines = progressStats?.let {
            "- Total vocabulary: ${it.totalWords} words\n" +
                "- Due for review today: ${it.dueCards} cards\n" +
                "- Words mastered: ${it.level5Count + it.level6Count} words"
        }.orEmpty()
        val dueCardsRequirement = progressStats?.dueCards?.takeIf { it > 0 }
            ?.let { "7. Optionally mention they have $it cards waiting for review" }
            .orEmpty()
        val prompt = promptTemplates.render(
            Prompt.STREAK_REMINDER,
            mapOf(
                "userName" to userName,
                "currentStreak" to currentStreak,
                "progressLines" to progressLines,
                "dueCardsRequirement" to dueCardsRequirement,
            ),
        )
        return aiClient.complete(prompt, AiOperation.STREAK_REMINDER)
            ?: "You have a $currentStreak-day streak! 🔥 Complete your review today to keep it going!"
    }

    fun translateText(text: String, targetLanguage: String): String {
        val prompt = promptTemplates.render(Prompt.TRANSLATE, mapOf("targetLanguage" to targetLanguage, "text" to text))
        return aiClient.complete(prompt, AiOperation.TRANSLATION)
            ?: throw UserFacingException("Translation failed. Please try again.")
    }

    /**
     * Generates `app.vocabulary.suggestionCount` (+10 headroom for dedup) vocabulary items in
     * [targetLanguage] with [nativeLanguage] translations, pitched at [currentLevel].
     */
    fun generateVocabularyFromPreferences(
        targetLanguage: String,
        currentLevel: String,
        nativeLanguage: String,
        interests: List<String> = emptyList()
    ): List<SuggestVocabularyItemResponse> {
        val requestedCount = appProperties.vocabulary.suggestionCount + 10
        logger.info {
            "Generating vocabulary: target=$targetLanguage, level=$currentLevel, native=$nativeLanguage, " +
                "itemsRequested=$requestedCount, interests=${interests.joinToString(limit = 5).ifEmpty { "none" }}"
        }

        val focusRequirements = if (interests.isNotEmpty()) {
            "4. Prioritize vocabulary that is especially relevant to these interests: ${interests.joinToString()}.\n" +
                "5. Still include some general everyday vocabulary so the set feels balanced for an " +
                "onboarding experience."
        } else {
            "4. Keep the set balanced for an onboarding experience, covering everyday situations a new " +
                "learner is likely to face."
        }
        val prompt = promptTemplates.render(
            Prompt.SUGGEST_VOCABULARY,
            mapOf(
                "requestedCount" to requestedCount,
                "targetLanguage" to targetLanguage,
                "currentLevel" to currentLevel,
                "nativeLanguage" to nativeLanguage,
                "interestsLine" to
                    if (interests.isEmpty()) "" else "- Their interests / focus areas: ${interests.joinToString()}",
                "focusRequirements" to focusRequirements,
            ),
        )

        val raw = aiClient.complete(prompt, AiOperation.SUGGEST_VOCABULARY)
            ?: throw UserFacingException("No vocabulary generated. Please try again.")
        val items = parseSuggestVocabularyResponse(raw)
        if (items.size < maxOf(20, requestedCount / 2)) {
            logger.warn { "AI returned only ${items.size} items; expected around $requestedCount" }
        }
        logger.info { "Generated ${items.size} vocabulary items for $targetLanguage ($currentLevel)" }
        return items.take(requestedCount)
    }

    private fun String?.orFail(operation: AiOperation): String =
        this ?: throw UpstreamServiceException("OpenRouter ${operation.tag} returned no text")

    // ── Parsing ──────────────────────────────────────────────────────────────────

    private fun parseSuggestVocabularyResponse(raw: String): List<SuggestVocabularyItemResponse> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 3)
                val originalWord = parts.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val translation = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SuggestVocabularyItemResponse(
                    originalWord = originalWord,
                    translation = translation,
                    description = parts.getOrNull(2)?.trim().orEmpty(),
                )
            }
            .toList()

    private fun isValidVocabularyFormat(text: String): Boolean {
        if (text.isBlank() || !text.contains(",")) return false
        val entries = text.split(";")
        val validEntries = entries.count { VOCAB_ENTRY.matches(it.trim()) }
        return validEntries > 0 && validEntries.toFloat() / entries.size >= 0.5f
    }

    private companion object {
        val VOCAB_ENTRY = Regex("^[^,]+,[^,]+(?:,[^,]*)?$")
    }
}
