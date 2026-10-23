package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.analytics.WeeklyReportResponse
import com.alirezaiyan.vokab.server.analytics.WeeklyReportService
import com.alirezaiyan.vokab.server.analytics.WordProgressQueries
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.modelcontextprotocol.common.McpTransportContext
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** The learner's study progress, from recorded reviews. Plain queries: no AI calls, no credits. */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class ProgressMcpTools(
    private val wordProgressQueries: WordProgressQueries,
    private val weeklyReportService: WeeklyReportService,
) {

    @McpTool(
        name = "get_difficult_words",
        description = "The words the learner gets wrong most often in reviews (at least $MIN_REVIEWS reviews), " +
            "hardest first. Good material for extra practice.",
        title = "Get my difficult Lexicon words",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun getDifficultWords(
        context: McpTransportContext,
        @McpToolParam(description = "Max words, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)", required = false)
        limit: Int?,
    ): DifficultWords {
        val userId = context.userIdWithScope(McpScopes.PROGRESS_READ)
        val max = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val words = wordProgressQueries.getDifficultWords(userId, MIN_REVIEWS, max)
            .map {
                DifficultWord(
                    id = it.wordId,
                    word = it.wordText,
                    translation = it.wordTranslation,
                    language = it.targetLanguage,
                    reviews = it.totalReviews,
                    mistakes = it.errorCount,
                    errorRate = it.errorRate,
                )
            }
        return DifficultWords(words)
    }

    @McpTool(
        name = "get_weekly_report",
        description = "This week's study summary: cards reviewed (and change from last week), accuracy, " +
            "words mastered, study time, sessions and the best day.",
        title = "Get my Lexicon weekly report",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun getWeeklyReport(context: McpTransportContext): WeeklyReport =
        weeklyReportService.getWeeklyReport(context.userIdWithScope(McpScopes.PROGRESS_READ)).toReport()

    private fun WeeklyReportResponse.toReport() = WeeklyReport(
        weekStartDate = weekStartDate,
        weekEndDate = weekEndDate,
        cardsReviewed = cardsReviewed,
        previousWeekCardsReviewed = previousWeekCardsReviewed,
        changePercent = changePercent,
        accuracyPercent = accuracyPercent,
        wordsMastered = wordsMastered,
        studyMinutes = totalStudyTimeMs / MILLIS_PER_MINUTE,
        sessions = sessionsCount,
        bestDay = bestDay?.let { BestDay(it.dayName, it.cardsReviewed, it.accuracyPercent) },
    )

    /** [language] is the language being learned. */
    data class DifficultWord(
        val id: Long,
        val word: String,
        val translation: String,
        val language: String,
        val reviews: Int,
        val mistakes: Int,
        val errorRate: Double,
    )

    data class DifficultWords(val words: List<DifficultWord>)

    /** Optional fields are left out when unknown (no reviews last week, no study days yet). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WeeklyReport(
        val weekStartDate: String,
        val weekEndDate: String,
        val cardsReviewed: Int,
        val previousWeekCardsReviewed: Int,
        @field:JsonProperty(required = false)
        val changePercent: Double?,
        val accuracyPercent: Double,
        val wordsMastered: Int,
        val studyMinutes: Long,
        val sessions: Int,
        @field:JsonProperty(required = false)
        val bestDay: BestDay?,
    )

    data class BestDay(val dayName: String, val cardsReviewed: Int, val accuracyPercent: Double)

    private companion object {
        const val MIN_REVIEWS = 3
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
