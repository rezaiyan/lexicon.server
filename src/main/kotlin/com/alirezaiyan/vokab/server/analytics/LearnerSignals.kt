package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.listening.ListeningSessionRepository
import com.alirezaiyan.vokab.server.wordrush.WordRushGameRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * What other features (AI coaching, notifications) read about a learner's study history. The
 * dashboard query services stay internal to analytics; this is the published surface.
 */
@Service
class LearnerSignals(
    private val studyActivityQueries: StudyActivityQueries,
    private val accuracyQueries: AccuracyQueries,
    private val wordProgressQueries: WordProgressQueries,
    private val weeklyReportService: WeeklyReportService,
    private val wordRushGameRepository: WordRushGameRepository,
    private val listeningSessionRepository: ListeningSessionRepository,
) {

    /** The word the learner gets wrong most often (at least 3 reviews), if any. */
    fun topDifficultWord(userId: Long): DifficultWordResponse? =
        wordProgressQueries.getDifficultWords(userId, minReviews = 3, limit = 1).firstOrNull()

    /** The target language of the learner's most-reviewed language pair, if any. */
    fun primaryTargetLanguage(userId: Long): String? =
        accuracyQueries.getStatsByLanguagePair(userId).firstOrNull()?.targetLanguage

    /** Percentage of study sessions completed rather than abandoned; null before the first session. */
    fun sessionCompletionRate(userId: Long): Double? =
        studyActivityQueries.getStudyInsights(userId).sessionCompletionRate

    fun weeklyReport(userId: Long): WeeklyReportResponse = weeklyReportService.getWeeklyReport(userId)

    /** When the learner last used the practice modes outside spaced-repetition review. */
    fun practiceHistory(userId: Long): PracticeHistory =
        PracticeHistory(
            lastWordRushAt = wordRushGameRepository.findLastPlayedAt(userId)?.let(Instant::ofEpochMilli),
            bestWordRushScore = wordRushGameRepository.findBestScoreByUserId(userId),
            lastListeningAt = listeningSessionRepository.findLastStartedAt(userId)?.let(Instant::ofEpochMilli),
        )
}

/** Last use of Word Rush and Listening (null = never), and the best Word Rush score (0 = none). */
data class PracticeHistory(
    val lastWordRushAt: Instant?,
    val bestWordRushScore: Int,
    val lastListeningAt: Instant?,
)
