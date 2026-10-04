package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.alirezaiyan.vokab.server.user.requireId

/** Overall study activity: totals, per-day stats, recent sessions, heatmap and monthly history. Read-only; backs the analytics dashboards. */
@Service
class StudyActivityQueries(
    private val studySessionRepository: StudySessionRepository,
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getStudyInsights(userId: Long): StudyInsightsResponse {
        val user = userRepository.getReferenceById(userId)
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
        val completionRate =
            if (totalSessions > 0) ((totalSessions - abandoned).toDouble() / totalSessions * 100) else null

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
    fun getDailyStats(userId: Long, startDate: LocalDate, endDate: LocalDate): List<DailyStatsResponse> {
        val user = userRepository.getReferenceById(userId)
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
    fun getRecentSessions(userId: Long, limit: Int): List<StudySessionResponse> {
        val user = userRepository.getReferenceById(userId)
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
    fun getHeatmap(userId: Long, startMs: Long, endMs: Long): List<HeatmapDayResponse> {
        return reviewEventRepository.getHeatmapData(userId, startMs, endMs).map { p ->
            HeatmapDayResponse(
                date = p.day,
                count = p.count.toInt()
            )
        }
    }

    @Transactional(readOnly = true)
    fun getMonthlyStats(userId: Long): List<MonthlyStatsResponse> {
        return reviewEventRepository.getMonthlyStats(userId).map { p ->
            MonthlyStatsResponse(
                year = p.yr,
                month = p.mo,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }
}
