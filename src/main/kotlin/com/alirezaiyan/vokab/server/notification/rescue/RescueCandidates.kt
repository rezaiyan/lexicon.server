package com.alirezaiyan.vokab.server.notification.rescue

import java.time.Duration
import java.time.Instant

/** One reviewed, not yet mastered word with its review history (see [RescueCandidateQueries]). */
data class RescueCandidateRow(
    val wordId: Long,
    val text: String,
    val translation: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val level: Int,
    /** Epoch millis. */
    val nextReviewDate: Long,
    val totalReviews: Int,
    val errors: Int,
    /** Last (up to) 5 results, newest first: '1' right, '0' wrong. */
    val recent: String,
) {
    val recentAccuracy: Double
        get() = if (recent.isEmpty()) 0.0 else recent.count { it == '1' }.toDouble() / recent.length

    val errorRate: Double
        get() = if (totalReviews == 0) 0.0 else errors.toDouble() / totalReviews

    val lastCorrect: Boolean
        get() = recent.firstOrNull() == '1'
}

/** The oldest word still in the deck, for the "callback" angle. */
data class FirstWord(val text: String, val translation: String, val addedAt: Instant)

/**
 * The words a rescue may use, in one language pair:
 * - [nearMastery]: confidence wins (level 3–5, ≥ 75% of recent answers right, due within [DUE_SOON_MS]);
 * - [weak]: words missed often (≥ 30%) that the learner has got right before, closest to cracking first.
 */
data class RescuePool(
    val sourceLanguage: String,
    val targetLanguage: String,
    val nearMastery: List<RescueCandidateRow>,
    val weak: List<RescueCandidateRow>,
) {
    val all: List<RescueCandidateRow> get() = nearMastery + weak

    /** Enough to open and close on easy wins with at least one real challenge in between. */
    val canPlan: Boolean get() = nearMastery.size >= MIN_NEAR_MASTERY && weak.isNotEmpty()

    companion object {
        const val MAX_NEAR_MASTERY = 15
        const val MAX_WEAK = 8
        const val MIN_NEAR_MASTERY = 5
        val DUE_SOON_MS: Long = Duration.ofDays(3).toMillis()

        private const val MIN_RECENT_ANSWERS = 2
        private const val NEAR_MASTERY_ACCURACY = 0.75
        private const val WEAK_MIN_REVIEWS = 3
        private const val WEAK_ERROR_RATE = 0.3

        fun from(rows: List<RescueCandidateRow>, nowMillis: Long): RescuePool? {
            val pair = rows.groupingBy { it.sourceLanguage to it.targetLanguage }.eachCount()
                .maxByOrNull { it.value }?.key ?: return null
            val inPair = rows.filter { (it.sourceLanguage to it.targetLanguage) == pair }

            val nearMastery = inPair
                .filter {
                    it.level in 3..5 &&
                        it.recent.length >= MIN_RECENT_ANSWERS &&
                        it.recentAccuracy >= NEAR_MASTERY_ACCURACY &&
                        it.nextReviewDate <= nowMillis + DUE_SOON_MS
                }
                .sortedWith(
                    compareByDescending<RescueCandidateRow> { it.level }
                        .thenByDescending { it.recentAccuracy }
                        .thenBy { it.wordId }
                )
                .take(MAX_NEAR_MASTERY)
            val nearIds = nearMastery.map { it.wordId }.toSet()

            val weak = inPair
                .filter {
                    it.wordId !in nearIds &&
                        it.totalReviews >= WEAK_MIN_REVIEWS &&
                        it.errorRate >= WEAK_ERROR_RATE &&
                        it.errors < it.totalReviews
                }
                .sortedWith(
                    compareByDescending<RescueCandidateRow> { it.recentAccuracy }
                        .thenByDescending { it.errorRate }
                        .thenBy { it.wordId }
                )
                .take(MAX_WEAK)

            return RescuePool(pair.first, pair.second, nearMastery, weak)
        }
    }
}
