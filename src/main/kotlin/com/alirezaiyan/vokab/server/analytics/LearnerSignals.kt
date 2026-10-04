package com.alirezaiyan.vokab.server.analytics

import org.springframework.stereotype.Service

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
}
