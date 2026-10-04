package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.UserRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The weekly progress report shown on the home screen and in the Monday push. */
@Service
class WeeklyReportService(
    private val studySessionRepository: StudySessionRepository,
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
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
    fun getWeeklyReport(userId: Long): WeeklyReportResponse {
        val user = userRepository.getReferenceById(userId)
        val today = LocalDate.now(clock)

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

        val bestDay = bestDay(currentSessions)

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

    private fun bestDay(sessions: List<StudySession>): BestDayResponse? = sessions
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

    private fun dateToMs(date: LocalDate): Long = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun endOfDayMs(date: LocalDate): Long = dateToMs(date.plusDays(1)) - 1
}
