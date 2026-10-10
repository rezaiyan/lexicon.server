package com.alirezaiyan.vokab.server.analytics.insightsscreen

import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.words.WordRepository
import com.alirezaiyan.vokab.server.wordrush.WordRushGameRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Component
class LearnerSnapshotLoader(
    private val reviewEventRepository: ReviewEventRepository,
    private val wordRepository: WordRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val wordRushGameRepository: WordRushGameRepository,
    private val clock: Clock,
) {
    /** Must run inside a read-only transaction (lazy user reference). */
    fun load(userId: Long, zone: ZoneId): LearnerSnapshot {
        val user = userRepository.getReferenceById(userId)
        val now = ZonedDateTime.now(clock.withZone(zone))
        val sinceMs = now.toLocalDate().minusDays(LearnerSnapshot.HISTORY_DAYS - 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val reviews = reviewEventRepository.findFactsByUserIdSince(userId, sinceMs).map {
            ReviewFact(it.wordId, it.wordText, it.rating >= 1, it.previousLevel, it.newLevel, it.reviewedAt)
        }
        val wordsPerLevel = wordRepository.findProgressRowsByUserId(userId, now.toInstant().toEpochMilli())
            .associate { it.getLevel() to it.getWordCount() }
        val difficult = reviewEventRepository
            .findDifficultWords(user, DIFFICULT_MIN_REVIEWS, PageRequest.of(0, DIFFICULT_LIMIT))
            .map {
                val accuracy = ((it.total - it.errors) * 100.0 / it.total).roundToInt()
                WordAccuracy(it.wordId, it.wordText, accuracy, it.total.toInt())
            }
        val comebacks = reviewEventRepository.findComebackWords(user).map { WordRef(it.wordId, it.wordText) }
        val gamesPlayed = wordRushGameRepository.countByUser(user)
        val wordRush = if (gamesPlayed == 0L) null else WordRushSummary(
            gamesPlayed = gamesPlayed,
            bestScore = wordRushGameRepository.findBestScore(user),
            recentScores = wordRushGameRepository.findTop20ByUserOrderByPlayedAtDesc(user)
                .take(RECENT_GAMES).map { it.score }.reversed(),
        )

        return LearnerSnapshot(
            zone = zone,
            now = now,
            reviews = reviews,
            totalReviewsAllTime = reviewEventRepository.countByUser(user),
            wordsPerLevel = wordsPerLevel,
            masteredTotal = wordsPerLevel[MASTERED_LEVEL] ?: 0,
            currentStreak = user.currentStreak,
            remindersEnabled = userSettingsRepository.findByUserId(userId)?.reviewRemindersEnabled ?: true,
            difficultWords = difficult,
            comebackWords = comebacks,
            wordRush = wordRush,
        )
    }

    private companion object {
        const val DIFFICULT_MIN_REVIEWS = 3
        const val DIFFICULT_LIMIT = 20
        const val RECENT_GAMES = 5
    }
}
