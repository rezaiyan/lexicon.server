package com.alirezaiyan.vokab.server.notification.rescue

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the eligibility rules need to know about one learner. */
data class LapseSignals(
    /** Study days (UTC streak days) in roughly the last four weeks, any order. */
    val studyDates: List<LocalDate>,
    val totalWords: Int,
    /** A rescue was already planned since the last study day, whatever became of it. */
    val alreadyRescuedThisLapse: Boolean,
)

/**
 * Who gets a comeback rescue: a learner who had a habit and has now missed two full days,
 * caught before the habit dies. Longer lapses stay with the COLD/DORMANT flow.
 */
object ComebackRescuePolicy {
    /** Planning runs at 00:45 UTC, so 3 days since the last study day = two full days missed. */
    const val MIN_DAYS_AWAY = 3L
    const val MAX_DAYS_AWAY = 14L
    const val HABIT_WINDOW_DAYS = 14L
    const val MIN_HABIT_DAYS = 3
    const val MIN_WORDS = 10

    /** How far back study history must reach to judge both the lapse and the habit before it. */
    const val LOOKBACK_DAYS = MAX_DAYS_AWAY + HABIT_WINDOW_DAYS

    fun daysAway(studyDates: List<LocalDate>, today: LocalDate): Long? =
        studyDates.maxOrNull()?.let { ChronoUnit.DAYS.between(it, today) }

    fun isEligible(signals: LapseSignals, today: LocalDate): Boolean {
        val lastStudy = signals.studyDates.maxOrNull() ?: return false
        val daysAway = ChronoUnit.DAYS.between(lastStudy, today)
        if (daysAway !in MIN_DAYS_AWAY..MAX_DAYS_AWAY) return false

        val windowStart = lastStudy.minusDays(HABIT_WINDOW_DAYS - 1)
        val habitDays = signals.studyDates.distinct().count { !it.isBefore(windowStart) && !it.isAfter(lastStudy) }

        return habitDays >= MIN_HABIT_DAYS &&
            signals.totalWords >= MIN_WORDS &&
            !signals.alreadyRescuedThisLapse
    }
}
