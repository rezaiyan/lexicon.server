package com.alirezaiyan.vokab.server.analytics.insightsscreen

import java.time.ZoneId
import java.time.ZonedDateTime

/** Wednesday 2026-06-17 20:00 in Berlin. This week = Mon 06-15 … Sun 06-21. */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
val TEST_LOCAL_NOW: ZonedDateTime = ZonedDateTime.of(2026, 6, 17, 20, 0, 0, 0, BERLIN)

fun snapshot(
    now: ZonedDateTime = TEST_LOCAL_NOW,
    reviews: List<ReviewFact> = emptyList(),
    totalReviewsAllTime: Long = reviews.size.toLong(),
    wordsPerLevel: Map<Int, Long> = emptyMap(),
    masteredTotal: Long = wordsPerLevel[MASTERED_LEVEL] ?: 0,
    currentStreak: Int = 0,
    remindersEnabled: Boolean = false,
    difficultWords: List<WordAccuracy> = emptyList(),
    comebackWords: List<WordRef> = emptyList(),
    wordRush: WordRushSummary? = null,
) = LearnerSnapshot(
    zone = now.zone,
    now = now,
    reviews = reviews.sortedBy { it.reviewedAt },
    totalReviewsAllTime = totalReviewsAllTime,
    wordsPerLevel = wordsPerLevel,
    masteredTotal = masteredTotal,
    currentStreak = currentStreak,
    remindersEnabled = remindersEnabled,
    difficultWords = difficultWords,
    comebackWords = comebackWords,
    wordRush = wordRush,
)

/** [localDateTime] is ISO local date-time in [zone], e.g. "2026-06-16T09:00". */
fun review(
    localDateTime: String,
    wordId: Long = 1,
    correct: Boolean = true,
    previousLevel: Int = 1,
    newLevel: Int = if (correct) previousLevel + 1 else previousLevel - 1,
    zone: ZoneId = BERLIN,
    text: String = "word$wordId",
) = ReviewFact(
    wordId = wordId,
    wordText = text,
    correct = correct,
    previousLevel = previousLevel,
    newLevel = newLevel.coerceIn(0, MASTERED_LEVEL),
    reviewedAt = java.time.LocalDateTime.parse(localDateTime).atZone(zone).toInstant().toEpochMilli(),
)

/** [count] reviews at [localDateTime], [correctCount] of them correct, distinct word ids from [firstWordId]. */
fun reviews(localDateTime: String, count: Int, correctCount: Int, firstWordId: Long = 100, previousLevel: Int = 1): List<ReviewFact> =
    (0 until count).map { i ->
        review(localDateTime, wordId = firstWordId + i, correct = i < correctCount, previousLevel = previousLevel)
    }
