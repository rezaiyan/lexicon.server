package com.alirezaiyan.vokab.server.analytics.insightsscreen

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/** Minimum reviews before any percentage is shown. */
const val MIN_REVIEWS_FOR_PERCENT = 20

/** Minimum reviews in one hour/weekday bucket before claiming anything about it. */
const val MIN_REVIEWS_PER_BUCKET = 30

private val FIXED_MILESTONES = listOf(50L, 100L, 250L, 500L, 1000L)
private const val MILESTONE_STEP = 500L

data class Period(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in start..endInclusive
}

fun LearnerSnapshot.thisWeek(): Period = Period(weekStart, weekStart.plusDays(6))

fun LearnerSnapshot.lastWeek(): Period = Period(weekStart.minusWeeks(1), weekStart.minusDays(1))

fun LearnerSnapshot.lastDays(days: Long): Period = Period(today.minusDays(days - 1), today)

fun LearnerSnapshot.reviewsIn(period: Period): List<ReviewFact> = reviews.filter { it.localDate in period }

fun LearnerSnapshot.reviewsByDate(): Map<LocalDate, Int> = reviews.groupingBy { it.localDate }.eachCount()

fun LearnerSnapshot.hourBuckets(): Map<Int, List<ReviewFact>> = reviews.groupBy { it.localHour }

fun LearnerSnapshot.weekdayBuckets(): Map<DayOfWeek, List<ReviewFact>> = reviews.groupBy { it.localDate.dayOfWeek }

/** (local hour, accuracy %) of the most accurate hour with at least [MIN_REVIEWS_PER_BUCKET] reviews. */
fun LearnerSnapshot.bestHour(): Pair<Int, Int>? = hourBuckets()
    .mapNotNull { (hour, facts) -> facts.gatedAccuracyPct(MIN_REVIEWS_PER_BUCKET)?.let { hour to it } }
    .maxByOrNull { it.second }

/** Reviews grouped by the stage the word was in when reviewed. */
fun LearnerSnapshot.stageBuckets(): Map<Int, List<ReviewFact>> = reviews.groupBy { it.previousLevel }

fun List<ReviewFact>.accuracyPct(): Int? =
    if (isEmpty()) null else (count { it.correct } * 100.0 / size).roundToInt()

fun List<ReviewFact>.gatedAccuracyPct(minReviews: Int = MIN_REVIEWS_FOR_PERCENT): Int? =
    if (size < minReviews) null else accuracyPct()

fun List<ReviewFact>.leveledUpWordCount(): Int =
    filter { it.newLevel > it.previousLevel }.map { it.wordId }.distinct().size

fun List<ReviewFact>.demotedWordCount(): Int =
    filter { it.newLevel < it.previousLevel }.map { it.wordId }.distinct().size

fun nextMilestone(mastered: Long): Long =
    FIXED_MILESTONES.firstOrNull { it > mastered } ?: ((mastered / MILESTONE_STEP) + 1) * MILESTONE_STEP
