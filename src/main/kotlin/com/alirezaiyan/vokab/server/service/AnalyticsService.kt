package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository
import com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import com.alirezaiyan.vokab.server.domain.entity.requireId

private val logger = KotlinLogging.logger {}

@Service
class AnalyticsService(
    private val studySessionRepository: StudySessionRepository,
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
    private val sessionPersister: AnalyticsSessionPersister,
    private val clock: Clock,
) {

    // Not @Transactional at the batch level — each session is saved in its own
    // REQUIRES_NEW transaction via sessionPersister so one bad session in the
    // client's retry queue cannot roll back the entire batch forever.
    fun syncSessions(user: User, request: SyncAnalyticsRequest): SyncAnalyticsResponse {
        val syncedIds = mutableListOf<String>()

        for (sessionReq in request.sessions) {
            if (sessionPersister.saveSession(user, sessionReq)) {
                syncedIds.add(sessionReq.clientSessionId)
            }
        }

        logger.info { "Synced ${syncedIds.size}/${request.sessions.size} sessions for user ${user.id}" }
        return SyncAnalyticsResponse(syncedSessionIds = syncedIds)
    }

    @Transactional(readOnly = true)
    fun getStudyInsights(user: User): StudyInsightsResponse {
        val totalCards = reviewEventRepository.countByUser(user)
        val totalCorrect = reviewEventRepository.countCorrectByUser(user)
        val totalStudyTime = studySessionRepository.totalStudyTimeByUser(user)
        val totalSessions = studySessionRepository.countByUser(user)
        val daysStudied = studySessionRepository.countDistinctStudyDays(user.requireId())
        val uniqueWords = reviewEventRepository.countDistinctWordsReviewed(user)
        val abandoned = studySessionRepository.countAbandonedByUser(user)
        val wordsMastered = reviewEventRepository.countWordsMastered(user)
        val avgResponseTime = reviewEventRepository.getAverageResponseTime(user)

        val accuracy = if (totalCards > 0) (totalCorrect.toDouble() / totalCards * 100) else 0.0
        val avgSession = if (totalSessions > 0) totalStudyTime / totalSessions else null
        val completionRate = if (totalSessions > 0) ((totalSessions - abandoned).toDouble() / totalSessions * 100) else null

        return StudyInsightsResponse(
            totalCardsReviewed = totalCards,
            totalCorrect = totalCorrect,
            accuracyPercent = accuracy,
            totalStudyTimeMs = totalStudyTime,
            totalSessions = totalSessions,
            daysStudied = daysStudied,
            uniqueWordsReviewed = uniqueWords,
            averageResponseTimeMs = avgResponseTime?.toLong(),
            averageSessionDurationMs = avgSession,
            sessionCompletionRate = completionRate,
            wordsMasteredCount = wordsMastered
        )
    }

    @Transactional(readOnly = true)
    fun getDailyStats(user: User, startDate: LocalDate, endDate: LocalDate): List<DailyStatsResponse> {
        val start = startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1
        val sessions = studySessionRepository.findByUserAndDateRange(user, start, end)
        val eventStats = reviewEventRepository.getDailyEventStats(user.requireId(), start, end)
            .associateBy { it.day }

        return sessions.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
        }.map { (date, daySessions) ->
            val ev = eventStats[date]
            DailyStatsResponse(
                date = date,
                sessionsCount = daySessions.size,
                cardsReviewed = daySessions.sumOf { it.totalCards },
                correctCount = daySessions.sumOf { it.correctCount },
                incorrectCount = daySessions.sumOf { it.incorrectCount },
                studyTimeMs = daySessions.sumOf { it.durationMs },
                uniqueWordsReviewed = ev?.uniqueWords?.toInt() ?: 0,
                wordsLeveledUp = ev?.leveledUp?.toInt() ?: 0,
                wordsLeveledDown = ev?.leveledDown?.toInt() ?: 0,
            )
        }.sortedBy { it.date }
    }

    @Transactional(readOnly = true)
    fun getDifficultWords(user: User, minReviews: Int, limit: Int): List<DifficultWordResponse> {
        return reviewEventRepository.findDifficultWords(user, minReviews, PageRequest.of(0, limit))
            .map { p ->
                DifficultWordResponse(
                    wordId = p.wordId,
                    wordText = p.wordText,
                    wordTranslation = p.wordTranslation,
                    sourceLanguage = p.sourceLanguage,
                    targetLanguage = p.targetLanguage,
                    totalReviews = p.total.toInt(),
                    errorCount = p.errors.toInt(),
                    errorRate = if (p.total > 0) p.errors.toDouble() / p.total else 0.0
                )
            }
    }

    @Transactional(readOnly = true)
    fun getMostReviewedWords(user: User, limit: Int): List<MostReviewedWordResponse> {
        return reviewEventRepository.findMostReviewedWords(user, PageRequest.of(0, limit))
            .map { p ->
                MostReviewedWordResponse(
                    wordId = p.wordId,
                    wordText = p.wordText,
                    wordTranslation = p.wordTranslation,
                    totalReviews = p.total.toInt()
                )
            }
    }

    @Transactional(readOnly = true)
    fun getAccuracyByLevel(user: User): List<AccuracyByLevelResponse> {
        return reviewEventRepository.getAccuracyByLevel(user).map { p ->
            AccuracyByLevelResponse(
                level = p.level,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getAccuracyByHour(user: User): List<HourlyAccuracyResponse> {
        return reviewEventRepository.getAccuracyByHour(user.requireId()).map { p ->
            HourlyAccuracyResponse(
                hour = p.hour,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getAccuracyByDayOfWeek(user: User): List<DayOfWeekAccuracyResponse> {
        return reviewEventRepository.getAccuracyByDayOfWeek(user.requireId()).map { p ->
            DayOfWeekAccuracyResponse(
                dayOfWeek = p.dayOfWeek,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getRecentSessions(user: User, limit: Int): List<StudySessionResponse> {
        return studySessionRepository.findByUserOrderByStartedAtDesc(user, PageRequest.of(0, limit))
            .map { session ->
                StudySessionResponse(
                    clientSessionId = session.clientSessionId,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    durationMs = session.durationMs,
                    totalCards = session.totalCards,
                    correctCount = session.correctCount,
                    incorrectCount = session.incorrectCount,
                    reviewType = session.reviewType,
                    completedNormally = session.completedNormally
                )
            }
    }

    @Transactional(readOnly = true)
    fun getHeatmap(user: User, startMs: Long, endMs: Long): List<HeatmapDayResponse> {
        return reviewEventRepository.getHeatmapData(user.requireId(), startMs, endMs).map { p ->
            HeatmapDayResponse(
                date = p.day,
                count = p.count.toInt()
            )
        }
    }

    @Transactional(readOnly = true)
    fun getLevelTransitions(user: User): List<LevelTransitionResponse> {
        return reviewEventRepository.getLevelTransitions(user).map { p ->
            LevelTransitionResponse(
                fromLevel = p.fromLevel,
                toLevel = p.toLevel,
                count = p.count
            )
        }
    }

    @Transactional(readOnly = true)
    fun getWordsMastered(user: User, limit: Int): List<MasteredWordResponse> {
        return reviewEventRepository.findWordsMastered(user, PageRequest.of(0, limit)).map { p ->
            MasteredWordResponse(
                wordId = p.wordId,
                wordText = p.wordText,
                wordTranslation = p.wordTranslation,
                masteredAt = p.masteredAt
            )
        }
    }

    @Transactional(readOnly = true)
    fun getStatsByLanguagePair(user: User): List<LanguagePairStatsResponse> {
        return reviewEventRepository.getStatsByLanguagePair(user).map { p ->
            LanguagePairStatsResponse(
                sourceLanguage = p.sourceLanguage,
                targetLanguage = p.targetLanguage,
                totalReviews = p.total,
                correctCount = p.correct,
                uniqueWords = p.uniqueWords,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getMonthlyStats(user: User): List<MonthlyStatsResponse> {
        return reviewEventRepository.getMonthlyStats(user.requireId()).map { p ->
            MonthlyStatsResponse(
                year = p.yr,
                month = p.mo,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getResponseTimeTrend(user: User): List<ResponseTimeTrendResponse> {
        return reviewEventRepository.getResponseTimeTrend(user.requireId()).map { p ->
            ResponseTimeTrendResponse(
                year = p.yr,
                week = p.wk,
                avgResponseTimeMs = p.avgMs
            )
        }
    }

    @Transactional(readOnly = true)
    fun getComebackWords(user: User): List<ComebackWordResponse> {
        return reviewEventRepository.findComebackWords(user).map { p ->
            ComebackWordResponse(
                wordId = p.wordId,
                wordText = p.wordText,
                wordTranslation = p.wordTranslation
            )
        }
    }

    /**
     * Returns the weekly progress report, or null when the card should be hidden.
     *
     * Visibility rule: the user must have at least one study session in
     * the **last 7 days** (rolling window ending today). This ensures:
     * - New users with no history → 204 (no card)
     * - Lapsed users (no study for 8+ days) → 204 (no card)
     * - Active users → 200 with current-week stats + previous-week comparison
     *
     * Display uses calendar weeks (Mon–Sun) for consistency:
     * - "This week" = Monday to Sunday containing today
     * - "Last week" = the 7 calendar days before this week's Monday
     */
    @Transactional(readOnly = true)
    fun getWeeklyReport(user: User): WeeklyReportResponse {
        val today = LocalDate.now(clock)

        fun dateToMs(date: LocalDate): Long = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        fun endOfDayMs(date: LocalDate): Long = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1

        // Gate: any session in the rolling last 7 days?
        val recencyStartMs = dateToMs(today.minusDays(6))
        val recencyEndMs = endOfDayMs(today)
        val recentSessions = studySessionRepository.findByUserAndDateRange(user, recencyStartMs, recencyEndMs)
        if (recentSessions.isEmpty()) return WeeklyReportResponse(
            cardsReviewed = 0,
            previousWeekCardsReviewed = 0,
            changePercent = null,
            accuracyPercent = 0.0,
            wordsMastered = 0,
            totalStudyTimeMs = 0,
            sessionsCount = 0,
            bestDay = null,
            weekStartDate = "",
            weekEndDate = "",
        )

        // Display windows: calendar weeks
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val prevWeekStart = weekStart.minusWeeks(1)

        val currentStartMs = dateToMs(weekStart)
        val currentEndMs = endOfDayMs(weekEnd)
        val prevStartMs = dateToMs(prevWeekStart)
        val prevEndMs = dateToMs(weekStart) - 1

        val currentSessions = studySessionRepository.findByUserAndDateRange(user, currentStartMs, currentEndMs)
        val prevSessions = studySessionRepository.findByUserAndDateRange(user, prevStartMs, prevEndMs)

        val cardsReviewed = currentSessions.sumOf { it.totalCards }
        val correctCount = currentSessions.sumOf { it.correctCount }
        val totalStudyTimeMs = currentSessions.sumOf { it.durationMs }
        val sessionsCount = currentSessions.size
        val accuracyPercent = if (cardsReviewed > 0) (correctCount.toDouble() / cardsReviewed * 100) else 0.0

        val prevCardsReviewed = prevSessions.sumOf { it.totalCards }
        val changePercent = if (prevCardsReviewed == 0) null
        else ((cardsReviewed - prevCardsReviewed).toDouble() / prevCardsReviewed * 100)

        val wordsMastered = reviewEventRepository.countWordsMasteredInRange(user, currentStartMs, currentEndMs)

        val bestDay = currentSessions
            .groupBy { session ->
                Instant.ofEpochMilli(session.startedAt).atZone(ZoneOffset.UTC).toLocalDate()
            }
            .maxByOrNull { (_, daySessions) -> daySessions.sumOf { it.totalCards } }
            ?.let { (date, daySessions) ->
                val dayCards = daySessions.sumOf { it.totalCards }
                val dayCorrect = daySessions.sumOf { it.correctCount }
                val dayAccuracy = if (dayCards > 0) (dayCorrect.toDouble() / dayCards * 100) else 0.0
                BestDayResponse(
                    dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    cardsReviewed = dayCards,
                    accuracyPercent = dayAccuracy,
                )
            }

        return WeeklyReportResponse(
            cardsReviewed = cardsReviewed,
            previousWeekCardsReviewed = prevCardsReviewed,
            changePercent = changePercent,
            accuracyPercent = accuracyPercent,
            wordsMastered = wordsMastered.toInt(),
            totalStudyTimeMs = totalStudyTimeMs,
            sessionsCount = sessionsCount,
            bestDay = bestDay,
            weekStartDate = weekStart.toString(),
            weekEndDate = weekEnd.toString(),
        )
    }
}
