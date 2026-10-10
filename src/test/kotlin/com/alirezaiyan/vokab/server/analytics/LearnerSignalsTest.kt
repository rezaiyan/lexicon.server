package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.listening.ListeningSessionRepository
import com.alirezaiyan.vokab.server.wordrush.WordRushGameRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class LearnerSignalsTest {

    private val studyActivityQueries: StudyActivityQueries = mockk()
    private val accuracyQueries: AccuracyQueries = mockk()
    private val wordProgressQueries: WordProgressQueries = mockk()
    private val wordRushGameRepository: WordRushGameRepository = mockk()
    private val listeningSessionRepository: ListeningSessionRepository = mockk()
    private val signals = LearnerSignals(
        studyActivityQueries, accuracyQueries, wordProgressQueries, mockk(), wordRushGameRepository, listeningSessionRepository,
    )

    @Test
    fun `practice history maps last use and best score`() {
        every { wordRushGameRepository.findLastPlayedAt(7L) } returns 1_700_000_000_000L
        every { wordRushGameRepository.findBestScoreByUserId(7L) } returns 1240
        every { listeningSessionRepository.findLastStartedAt(7L) } returns null

        val history = signals.practiceHistory(7L)

        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), history.lastWordRushAt)
        assertEquals(1240, history.bestWordRushScore)
        assertNull(history.lastListeningAt)
    }

    @Test
    fun `top difficult word is the first word with at least 3 reviews`() {
        val word = DifficultWordResponse(1L, "Eichhörnchen", "squirrel", "de", "en", 6, 4, 0.66)
        every { wordProgressQueries.getDifficultWords(7L, minReviews = 3, limit = 1) } returns listOf(word)

        assertEquals(word, signals.topDifficultWord(7L))
    }

    @Test
    fun `no difficult word and no language pair yield null`() {
        every { wordProgressQueries.getDifficultWords(7L, 3, 1) } returns emptyList()
        every { accuracyQueries.getStatsByLanguagePair(7L) } returns emptyList()

        assertNull(signals.topDifficultWord(7L))
        assertNull(signals.primaryTargetLanguage(7L))
    }

    @Test
    fun `primary target language comes from the first language pair`() {
        every { accuracyQueries.getStatsByLanguagePair(7L) } returns listOf(
            LanguagePairStatsResponse("en", "de", 40, 30, 12, 75.0),
            LanguagePairStatsResponse("en", "fr", 10, 5, 4, 50.0),
        )

        assertEquals("de", signals.primaryTargetLanguage(7L))
    }

    @Test
    fun `session completion rate comes from the study insights`() {
        every { studyActivityQueries.getStudyInsights(7L) } returns StudyInsightsResponse(
            totalCardsReviewed = 10, totalCorrect = 8, accuracyPercent = 80.0, totalStudyTimeMs = 1000,
            totalSessions = 4, daysStudied = 2, uniqueWordsReviewed = 5, averageResponseTimeMs = null,
            averageSessionDurationMs = 250, sessionCompletionRate = 75.0, wordsMasteredCount = 1,
        )

        assertEquals(75.0, signals.sessionCompletionRate(7L))
    }
}
