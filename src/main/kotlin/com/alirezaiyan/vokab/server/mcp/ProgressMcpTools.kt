package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.analytics.WeeklyReportResponse
import com.alirezaiyan.vokab.server.analytics.WeeklyReportService
import com.alirezaiyan.vokab.server.analytics.WordProgressQueries
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
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false),
    )
    fun getDifficultWords(
        context: McpTransportContext,
        @McpToolParam(description = "Max words, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)", required = false)
        limit: Int?,
    ): List<DifficultWord> {
        val userId = context.userIdWithScope(McpScopes.PROGRESS_READ)
        val max = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return wordProgressQueries.getDifficultWords(userId, MIN_REVIEWS, max)
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
    }

    @McpTool(
        name = "get_weekly_report",
        description = "This week's study summary: cards reviewed (and change from last week), accuracy, " +
            "words mastered, study time, sessions and the best day.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false),
    )
    fun getWeeklyReport(context: McpTransportContext): WeeklyReportResponse =
        weeklyReportService.getWeeklyReport(context.userIdWithScope(McpScopes.PROGRESS_READ))

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

    private companion object {
        const val MIN_REVIEWS = 3
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
    }
}
