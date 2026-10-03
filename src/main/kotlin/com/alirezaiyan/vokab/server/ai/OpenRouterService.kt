package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import io.micrometer.core.instrument.MeterRegistry
import com.alirezaiyan.vokab.server.shared.AiRestClient
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.describe
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.shared.UserFacingException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import com.alirezaiyan.vokab.server.study.MilestoneDetector

private val logger = KotlinLogging.logger {}

private const val CHAT_COMPLETIONS = "/chat/completions"
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/**
 * LLM features backed by OpenRouter's chat-completions API. Every public method builds a prompt and
 * goes through [chat]; they differ only in prompt and in what an empty answer means
 * (a fallback text or an error).
 *
 * Calls are synchronous and bounded by `app.openrouter.timeout-seconds` (the [AiRestClient]
 * builder). Transport and API failures throw [UpstreamServiceException] (502).
 */
@Service
class OpenRouterService(
    @AiRestClient restClientBuilder: RestClient.Builder,
    private val appProperties: AppProperties,
    private val meterRegistry: MeterRegistry,
    private val aiCallTracker: AiCallTracker,
) {
    private val restClient: RestClient = restClientBuilder
        .baseUrl(appProperties.openrouter.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${appProperties.openrouter.apiKey}")
        .defaultHeader("HTTP-Referer", "https://vokab.app")
        .defaultHeader("X-Title", "Vokab")
        .build()

    val model: String get() = appProperties.openrouter.model

    data class OpenRouterRequest(
        val model: String,
        val messages: List<Message>
    )

    data class Message(
        val role: String,
        val content: List<Content>
    )

    data class Content(
        val type: String,
        val text: String? = null,
        val image_url: ImageUrl? = null
    )

    data class ImageUrl(
        val url: String
    )

    data class OpenRouterResponse(
        val choices: List<Choice>?,
        val error: ErrorDetail?
    )

    data class Choice(
        val message: MessageContent
    )

    data class MessageContent(
        /** Null when the model answered without text (e.g. refusal or tool call). */
        val content: String?
    )

    data class ErrorDetail(
        val message: String
    )

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
        logger.info { "[OpenRouter] Image extraction: size=~${estimatedSizeBytes / 1024}KB, language=$targetLanguage" }

        val extractionType = when {
            extractWords && extractSentences -> "both individual vocabulary words AND example sentences"
            extractSentences -> "example sentences only"
            else -> "individual vocabulary words only"
        }

        val prompt = buildString {
            appendLine("You are a vocabulary extraction specialist. Extract $extractionType from this image.")
            appendLine()
            appendLine("CRITICAL: Verify the image contains vocabulary or text before proceeding.")
            appendLine("If the image does NOT contain any vocabulary, text, or words, respond with exactly: ERROR: No vocabulary found")
            appendLine()
            appendLine("Format Requirements:")
            appendLine("1. Each entry MUST follow: originalWord,translation")
            appendLine("2. originalWord = text in source language from image")
            appendLine("3. translation = $targetLanguage translation")
            appendLine("4. Separate entries with semicolon (;)")
            appendLine("5. Optional third field for description/context")
            appendLine("6. NO markdown, NO code blocks, NO explanations - ONLY the formatted data")
            appendLine("7. Remove any special characters that might break parsing (keep only letters, numbers, spaces, commas, semicolons)")
            appendLine()
            if (extractWords && !extractSentences) {
                appendLine("Extraction Rule: ONLY individual words or short phrases (2-3 words max)")
                appendLine("SKIP: Full sentences, long phrases, paragraphs")
            }
            if (extractSentences && !extractWords) {
                appendLine("Extraction Rule: ONLY phrases")
                appendLine("SKIP: Individual words")
            }
            if (extractWords && extractSentences) {
                appendLine("Extraction Rule: Extract BOTH individual words AND phrases")
            }
            appendLine()
            appendLine("Translation Rule:")
            appendLine("- If image already shows translations to $targetLanguage, use them exactly")
            appendLine("- Otherwise, provide accurate $targetLanguage translations")
            appendLine("- Keep translations concise and natural")
            appendLine()
            appendLine("Quality Checks:")
            appendLine("- Each entry must have at least 2 fields (word,translation)")
            appendLine("- No empty fields before commas")
            appendLine("- NO duplicate entries - verify uniqueness")
            appendLine("- Minimum 1 entry")
            appendLine()
            appendLine("Valid Example:")
            appendLine("Hallo,hello;Guten Morgen,good morning;danke,thanks,thank you very much")
        }

        val content = listOf(
            Content(type = "image_url", image_url = ImageUrl(url = "data:image/jpeg;base64,$imageBase64")),
            Content(type = "text", text = prompt),
        )
        val text = chat(content, "image extraction")
            ?: throw UserFacingException("No response from AI. Please try again.")
        return when {
            text.contains("ERROR:", ignoreCase = true) || text.contains("No vocabulary found", ignoreCase = true) ->
                throw UserFacingException("No vocabulary found in the image. Please use an image with visible text.")
            !isValidVocabularyFormat(text) ->
                throw UserFacingException("Failed to extract valid vocabulary format. Please try a clearer image.")
            else -> text
        }
    }

    fun generateCelebrationInsight(stats: ProgressStatsDto, userName: String?): String {
        val prompt = buildString {
            appendLine("The user ${userName ?: "a learner"} just completed their vocabulary review session today.")
            appendLine("They have ${stats.totalWords} words total, ${stats.level6Count} fully mastered.")
            appendLine("Write a 1-sentence celebration acknowledging their consistency. Max 2 emojis. Be specific and warm, not generic.")
            appendLine("Return ONLY the message, no quotes or extra formatting.")
        }
        return chat(prompt, "celebration insight")
            ?: "Great work today! 🎉 You're building something real."
    }

    fun generateStreakResetWarning(
        currentStreak: Int,
        progressStats: ProgressStatsDto,
        userName: String
    ): String {
        val prompt = buildString {
            appendLine("You are an enthusiastic vocabulary learning coach with a friendly, motivational personality.")
            appendLine("Generate ONE brief, personalized message to motivate the user to log in and maintain their streak.")
            appendLine()
            appendLine("User Context:")
            appendLine("- Name: $userName")
            appendLine("- Current streak: $currentStreak days")
            appendLine("- Total vocabulary: ${progressStats.totalWords} words")
            appendLine("- Due for review today: ${progressStats.dueCards} cards")
            appendLine("- Words mastered: ${progressStats.level5Count + progressStats.level6Count} words")
            appendLine()
            appendLine("Message Requirements:")
            appendLine("1. Be urgent but encouraging - remind them their streak is at risk")
            appendLine("2. Mention their current streak number prominently")
            appendLine("3. Celebrate their achievement so far")
            appendLine("4. Keep it short in 1 sentence")
            appendLine("5. Make it personal and cool - reference their progress if relevant")
            appendLine("6. Create a sense of urgency but stay positive")
            appendLine()
            appendLine("Return ONLY the motivational message, no quotes or extra formatting.")
        }
        return chat(prompt, "streak reset warning")
            ?: "Don't lose your $currentStreak-day streak! 🔥 Log in now to keep it going!"
    }

    fun generateDailyInsight(ctx: DailyInsightContext): String {
        val prompt = buildString {
            append("You are writing a personal vocabulary learning insight for ${ctx.userName ?: "a learner"}.\n\n")
            append("Their current stats:\n")
            append("- Total words: ${ctx.stats.totalWords}, fully mastered: ${ctx.stats.level6Count}\n")
            append("- Words due for review: ${ctx.stats.dueCards}\n")
            append("- Current streak: ${ctx.currentStreak} days\n")
            ctx.primaryLanguage?.let { append("- Learning: $it\n") }
            ctx.topDifficultWord?.let { append("- Most challenging word recently: \"$it\"\n") }
            ctx.accuracyTrend?.let { trend ->
                val dir = if (trend > 0) "improving (up ${trend.toInt()}%)" else "declining (down ${(-trend).toInt()}%)"
                append("- Accuracy is $dir vs last week\n")
            }
            ctx.sessionCompletionRate?.let { rate -> append("- Session completion: ${(rate * 100).toInt()}%\n") }
            ctx.optimalStudyHour?.let { hour -> append("- They tend to study best around $hour:00\n") }
            append("\nWrite exactly 1–2 sentences. Be specific to their data — do NOT write generic encouragement. ")
            append("Reference one concrete number or the specific word if available. Add 1–2 relevant emojis.\n")
            append("Return ONLY the message, no quotes or extra formatting.")
        }
        return chat(prompt, "daily insight").orFail("daily insight")
    }

    fun generateMilestoneMessage(
        milestone: MilestoneDetector.MilestoneEvent,
        @Suppress("UNUSED_PARAMETER") stats: ProgressStatsDto,
        userName: String?
    ): String {
        val prompt = """
            ${userName ?: "A learner"} just hit ${milestone.description}.
            Write 1 sentence celebrating this achievement. Warm but not over-the-top. 1 emoji max.
            Do not mention any specific numbers or word counts.
            Return ONLY the message, no quotes or extra formatting.
        """.trimIndent()
        return chat(prompt, "milestone message").orFail("milestone message")
    }

    fun generateStreakReminderMessage(
        currentStreak: Int,
        userName: String,
        progressStats: ProgressStatsDto? = null
    ): String {
        val prompt = buildString {
            appendLine("You are a supportive vocabulary learning coach with an encouraging, friendly personality.")
            appendLine("Generate ONE brief, personalized push notification message to remind the user to complete their review today to maintain their streak.")
            appendLine()
            appendLine("User Context:")
            appendLine("- Name: $userName")
            appendLine("- Current streak: $currentStreak days")
            if (progressStats != null) {
                appendLine("- Total vocabulary: ${progressStats.totalWords} words")
                appendLine("- Due for review today: ${progressStats.dueCards} cards")
                appendLine("- Words mastered: ${progressStats.level5Count + progressStats.level6Count} words")
            }
            appendLine()
            appendLine("Message Requirements:")
            appendLine("1. Create urgency but stay positive and encouraging")
            appendLine("2. Mention the streak number prominently ($currentStreak days)")
            appendLine("3. Keep it concise - ideal for push notification (max 1-2 sentences)")
            appendLine("4. Make it personal and motivating")
            appendLine("5. Include 1-2 relevant emojis")
            appendLine("6. Focus on maintaining the streak achievement")
            if (progressStats != null && progressStats.dueCards > 0) {
                appendLine("7. Optionally mention they have ${progressStats.dueCards} cards waiting for review")
            }
            appendLine()
            appendLine("Return ONLY the notification message, no quotes or extra formatting.")
        }
        return chat(prompt, "streak reminder")
            ?: "You have a $currentStreak-day streak! 🔥 Complete your review today to keep it going!"
    }

    fun translateText(text: String, targetLanguage: String): String {
        val prompt = "Translate the following text to $targetLanguage. Return only the translation, no explanations, " +
            "no quotes, no additional text. Just the translation:\n\n$text"
        return chat(prompt, "translation") ?: throw UserFacingException("Translation failed. Please try again.")
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

        val prompt = buildString {
            appendLine("You are an expert language teacher and curriculum designer.")
            appendLine()
            appendLine("Task: Generate exactly $requestedCount vocabulary items for a learner with the following profile:")
            appendLine("- Language they want to learn (target): $targetLanguage")
            appendLine("- Their current level in $targetLanguage: $currentLevel")
            appendLine("- Their native or primary language (for translations): $nativeLanguage")
            if (interests.isNotEmpty()) {
                appendLine("- Their interests / focus areas: ${interests.joinToString()}")
            }
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Choose words and short phrases that are appropriate for level \"$currentLevel\" (e.g. beginner = A1, elementary; intermediate = B1-B2; advanced = C1-C2).")
            appendLine("2. Each item must be useful for real-world use: everyday vocabulary, common verbs, nouns, adjectives, and essential phrases.")
            appendLine("3. Cover a balanced mix: greetings, numbers, time, family, food, travel, work, emotions, actions, places, and common expressions.")
            if (interests.isNotEmpty()) {
                appendLine("4. Prioritize vocabulary that is especially relevant to these interests: ${interests.joinToString()}.")
                appendLine("5. Still include some general everyday vocabulary so the set feels balanced for an onboarding experience.")
            } else {
                appendLine("4. Keep the set balanced for an onboarding experience, covering everyday situations a new learner is likely to face.")
            }
            appendLine("Provide exactly $requestedCount items. No fewer, no more.")
            appendLine()
            appendLine("Output format (strict):")
            appendLine("- One entry per line.")
            appendLine("- Each line: originalWord,translation,optionalShortDescription")
            appendLine("- originalWord = word or short phrase in $targetLanguage")
            appendLine("- translation = meaning or translation in $nativeLanguage")
            appendLine("- optionalShortDescription = brief context or example (optional; can be empty after the second comma)")
            appendLine("- Use comma as separator. If a field contains a comma, do not use it or escape the content.")
            appendLine("- No numbering, no markdown, no code blocks, no extra text before or after the list.")
            appendLine()
            appendLine("Example (for German, level beginner, native English):")
            appendLine("Hallo,hello,a greeting")
            appendLine("Guten Morgen,good morning,formal morning greeting")
            appendLine("danke,thank you,")
            appendLine("Brot,bread,common noun")
            appendLine()
            appendLine("Output exactly $requestedCount lines in the format above, nothing else.")
        }

        val raw = chat(prompt, "suggest vocabulary")
            ?: throw UserFacingException("No vocabulary generated. Please try again.")
        val items = parseSuggestVocabularyResponse(raw)
        if (items.size < maxOf(20, requestedCount / 2)) {
            logger.warn { "AI returned only ${items.size} items; expected around $requestedCount" }
        }
        logger.info { "Generated ${items.size} vocabulary items for $targetLanguage ($currentLevel)" }
        return items.take(requestedCount)
    }

    // ── Transport ────────────────────────────────────────────────────────────────

    /**
     * One text prompt with the configured model; the trimmed answer, or `null` when the model
     * returned no text. For callers that build their own prompt and parse the reply.
     *
     * @throws UpstreamServiceException on HTTP, transport or API errors
     */
    fun complete(prompt: String, operation: String): String? =
        chat(listOf(Content(type = "text", text = prompt)), operation)

    private fun chat(prompt: String, operation: String): String? = complete(prompt, operation)

    /**
     * Sends one user message and returns the trimmed answer, or `null` when the model returned no
     * text. Response bodies are logged, never put into exception messages.
     */
    private fun chat(content: List<Content>, operation: String): String? {
        val result = runCatching { requestChat(content, operation) }
        val outcome = when {
            result.isFailure -> "error"
            result.getOrNull() == null -> "empty"
            else -> "success"
        }
        meterRegistry.counter("ai.requests", "operation", operation, "outcome", outcome).increment()
        // An empty answer still means OpenRouter is reachable
        aiCallTracker.record(success = result.isSuccess)
        return result.getOrThrow()
    }

    private fun requestChat(content: List<Content>, operation: String): String? {
        val request = OpenRouterRequest(model = model, messages = listOf(Message(role = "user", content = content)))
        val response = try {
            restClient.post()
                .uri(CHAT_COMPLETIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body<OpenRouterResponse>()
        } catch (e: RestClientResponseException) {
            logger.error { "[OpenRouter] $operation HTTP ${e.statusCode.value()}: ${e.responseBodyAsString}" }
            throw UpstreamServiceException("OpenRouter $operation failed: ${e.describe()}", e)
        } catch (e: RestClientException) {
            logger.error { "[OpenRouter] $operation failed: ${e.describe()}" }
            throw UpstreamServiceException("OpenRouter $operation failed: ${e.describe()}", e)
        }

        response?.error?.let { error ->
            logger.error { "[OpenRouter] $operation API error: ${error.message}" }
            throw UpstreamServiceException("OpenRouter $operation returned an error")
        }
        return response?.choices?.firstOrNull()?.message?.content?.trim()?.ifEmpty { null }
    }

    private fun String?.orFail(operation: String): String =
        this ?: throw UpstreamServiceException("OpenRouter $operation returned no text")

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
