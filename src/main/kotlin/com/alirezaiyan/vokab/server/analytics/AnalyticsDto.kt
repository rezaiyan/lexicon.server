package com.alirezaiyan.vokab.server.analytics

import jakarta.validation.Valid
import jakarta.validation.constraints.*

// === Sync (client -> server) ===

data class SyncAnalyticsRequest(
    @field:Valid
    @field:NotEmpty
    @field:Size(max = 100, message = "Maximum 100 sessions per sync request")
    val sessions: List<SyncSessionRequest>
)

data class SyncSessionRequest(
    @field:NotBlank
    val clientSessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    @field:Min(0)
    val durationMs: Long,
    @field:Min(0)
    val totalCards: Int,
    @field:Min(0)
    val correctCount: Int,
    @field:Min(0)
    val incorrectCount: Int,
    @field:NotBlank
    val reviewType: String,
    val completedNormally: Boolean,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val triggerSource: String = "unknown",
    @field:Valid
    @field:Size(max = 1000, message = "Maximum 1000 events per session")
    val events: List<SyncReviewEventRequest>
)

data class SyncReviewEventRequest(
    val wordId: Long,
    val wordText: String,
    val wordTranslation: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    @field:Min(0)
    @field:Max(5)
    val rating: Int,
    @field:Min(0)
    val previousLevel: Int,
    @field:Min(0)
    val newLevel: Int,
    @field:Min(0)
    val responseTimeMs: Long,
    @field:Min(0)
    val reviewedAt: Long
)

data class SyncAnalyticsResponse(
    val syncedSessionIds: List<String>
)

// === Query responses ===

data class StudyInsightsResponse(
    val totalCardsReviewed: Long,
    val totalCorrect: Long,
    val accuracyPercent: Double,
    val totalStudyTimeMs: Long,
    val totalSessions: Long,
    val daysStudied: Long,
    val uniqueWordsReviewed: Long,
    val averageResponseTimeMs: Long?,
    val averageSessionDurationMs: Long?,
    val sessionCompletionRate: Double?,
    val wordsMasteredCount: Long
)

data class DifficultWordResponse(
    val wordId: Long,
    val wordText: String,
    val wordTranslation: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val totalReviews: Int,
    val errorCount: Int,
    val errorRate: Double
)

data class MostReviewedWordResponse(
    val wordId: Long,
    val wordText: String,
    val wordTranslation: String,
    val totalReviews: Int
)

data class AccuracyByLevelResponse(
    val level: Int,
    val totalReviews: Long,
    val correctCount: Long,
    val accuracyPercent: Double
)

data class HourlyAccuracyResponse(
    val hour: Int,
    val totalReviews: Long,
    val correctCount: Long,
    val accuracyPercent: Double
)

data class DayOfWeekAccuracyResponse(
    val dayOfWeek: Int,
    val totalReviews: Long,
    val correctCount: Long,
    val accuracyPercent: Double
)

data class HeatmapDayResponse(
    val date: String,
    val count: Int
)

data class LevelTransitionResponse(
    val fromLevel: Int,
    val toLevel: Int,
    val count: Long
)

data class MasteredWordResponse(
    val wordId: Long,
    val wordText: String,
    val wordTranslation: String,
    val masteredAt: Long
)

data class LanguagePairStatsResponse(
    val sourceLanguage: String,
    val targetLanguage: String,
    val totalReviews: Long,
    val correctCount: Long,
    val uniqueWords: Long,
    val accuracyPercent: Double
)

data class MonthlyStatsResponse(
    val year: Int,
    val month: Int,
    val totalReviews: Long,
    val correctCount: Long,
    val accuracyPercent: Double
)

data class ResponseTimeTrendResponse(
    val year: Int,
    val week: Int,
    val avgResponseTimeMs: Double
)

data class ComebackWordResponse(
    val wordId: Long,
    val wordText: String,
    val wordTranslation: String
)

data class StudySessionResponse(
    val clientSessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long,
    val totalCards: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val reviewType: String,
    val completedNormally: Boolean
)

data class DailyStatsResponse(
    val date: String,
    val sessionsCount: Int,
    val cardsReviewed: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val studyTimeMs: Long,
    val uniqueWordsReviewed: Int,
    val wordsLeveledUp: Int,
    val wordsLeveledDown: Int,
)

// === Weekly Report ===

data class WeeklyReportResponse(
    val cardsReviewed: Int,
    val previousWeekCardsReviewed: Int,
    val changePercent: Double?,
    val accuracyPercent: Double,
    val wordsMastered: Int,
    val totalStudyTimeMs: Long,
    val sessionsCount: Int,
    val bestDay: BestDayResponse?,
    val weekStartDate: String,
    val weekEndDate: String,
)

data class BestDayResponse(
    val dayName: String,
    val cardsReviewed: Int,
    val accuracyPercent: Double,
)
