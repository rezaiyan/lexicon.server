package com.alirezaiyan.vokab.server.analytics.insightsscreen

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

const val MASTERED_LEVEL = 6

data class ReviewFact(
    val wordId: Long,
    val wordText: String,
    val correct: Boolean,
    val previousLevel: Int,
    val newLevel: Int,
    val reviewedAt: Long,
)

data class WordRef(val id: Long, val text: String)

data class WordAccuracy(val id: Long, val text: String, val accuracyPct: Int, val reviews: Int)

data class WordRushSummary(val gamesPlayed: Long, val bestScore: Int, val recentScores: List<Int>)

/** Everything the insights screen needs, loaded once per request. Times are interpreted in [zone]. */
data class LearnerSnapshot(
    val zone: ZoneId,
    val now: ZonedDateTime,
    /** Review facts from the last [HISTORY_DAYS] local days, oldest first. */
    val reviews: List<ReviewFact>,
    val totalReviewsAllTime: Long,
    val wordsPerLevel: Map<Int, Long>,
    val masteredTotal: Long,
    val currentStreak: Int,
    val remindersEnabled: Boolean,
    val difficultWords: List<WordAccuracy>,
    val comebackWords: List<WordRef>,
    val wordRush: WordRushSummary?,
) {
    val today: LocalDate get() = now.toLocalDate()
    val weekStart: LocalDate get() = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun localDate(fact: ReviewFact): LocalDate = Instant.ofEpochMilli(fact.reviewedAt).atZone(zone).toLocalDate()
    fun localHour(fact: ReviewFact): Int = Instant.ofEpochMilli(fact.reviewedAt).atZone(zone).hour

    companion object {
        /** 12 weeks: the heatmap window and the history every rule reads from. */
        const val HISTORY_DAYS = 84L
    }
}
