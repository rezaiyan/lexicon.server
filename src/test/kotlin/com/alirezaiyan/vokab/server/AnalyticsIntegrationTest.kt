package com.alirezaiyan.vokab.server

import com.alirezaiyan.vokab.server.domain.entity.ReviewEvent
import com.alirezaiyan.vokab.server.domain.entity.StudySession
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository
import com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.AnalyticsService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsIntegrationTest {

    @Autowired lateinit var analyticsService: AnalyticsService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var studySessionRepository: StudySessionRepository
    @Autowired lateinit var reviewEventRepository: ReviewEventRepository
    @Autowired lateinit var testUserHelper: TestUserHelper

    lateinit var user: User

    @BeforeAll
    fun setupUser() {
        // Committed in its own transaction so REQUIRES_NEW inside
        // AnalyticsSessionPersister.saveSession() can see it via FK lookup.
        user = testUserHelper.saveAndCommit(User(email = "analytics@test.com", name = "Analytics User"))
    }

    @AfterAll
    fun teardownUser() {
        testUserHelper.clearUserSessions(user.id!!)
        testUserHelper.deleteByEmail("analytics@test.com")
    }

    @BeforeEach
    fun setup() {
        // Clear any REQUIRES_NEW-committed sessions left over from previous tests.
        // Runs in its own committed TX so read-only service calls (flush mode MANUAL)
        // see the clean state.
        testUserHelper.clearUserSessions(user.id!!)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    data class EventData(
        val wordId: Long = 1L,
        val wordText: String = "hello",
        val wordTranslation: String = "hola",
        val sourceLanguage: String = "en",
        val targetLanguage: String = "es",
        val rating: Int = 1,
        val previousLevel: Int = 0,
        val newLevel: Int = 1,
        val responseTimeMs: Long = 1500,
        val reviewedAt: Long? = null,
    )

    private fun createSession(
        clientSessionId: String = "session-1",
        startedAt: Long = EPOCH_NOV_14,
        events: List<EventData> = emptyList(),
        completedNormally: Boolean = true,
        durationMs: Long = 60_000,
    ): StudySession {
        val session = studySessionRepository.save(
            StudySession(
                user = user,
                clientSessionId = clientSessionId,
                startedAt = startedAt,
                endedAt = startedAt + durationMs,
                durationMs = durationMs,
                totalCards = events.size,
                correctCount = events.count { it.rating > 0 },
                incorrectCount = events.count { it.rating == 0 },
                reviewType = "REVIEW",
                completedNormally = completedNormally,
            )
        )

        if (events.isNotEmpty()) {
            reviewEventRepository.saveAll(events.map { e ->
                ReviewEvent(
                    session = session,
                    user = user,
                    wordId = e.wordId,
                    wordText = e.wordText,
                    wordTranslation = e.wordTranslation,
                    sourceLanguage = e.sourceLanguage,
                    targetLanguage = e.targetLanguage,
                    rating = e.rating,
                    previousLevel = e.previousLevel,
                    newLevel = e.newLevel,
                    responseTimeMs = e.responseTimeMs,
                    reviewedAt = e.reviewedAt ?: startedAt,
                )
            })
        }

        return session
    }

    // ── Sync ────────────────────────────────────────────────────────────────

    @Test
    fun `sync creates sessions and events`() {
        val request = SyncAnalyticsRequest(
            sessions = listOf(
                SyncSessionRequest(
                    clientSessionId = "sync-1",
                    startedAt = EPOCH_NOV_14,
                    endedAt = EPOCH_NOV_14 + 60_000,
                    durationMs = 60_000,
                    totalCards = 2,
                    correctCount = 1,
                    incorrectCount = 1,
                    reviewType = "REVIEW",
                    completedNormally = true,
                    events = listOf(
                        SyncReviewEventRequest(
                            wordId = 1, wordText = "hello", wordTranslation = "hola",
                            sourceLanguage = "en", targetLanguage = "es",
                            rating = 1, previousLevel = 0, newLevel = 1,
                            responseTimeMs = 1200, reviewedAt = EPOCH_NOV_14 + 10_000,
                        ),
                        SyncReviewEventRequest(
                            wordId = 2, wordText = "world", wordTranslation = "mundo",
                            sourceLanguage = "en", targetLanguage = "es",
                            rating = 0, previousLevel = 1, newLevel = 0,
                            responseTimeMs = 3000, reviewedAt = EPOCH_NOV_14 + 20_000,
                        ),
                    ),
                )
            )
        )

        val response = analyticsService.syncSessions(user, request)

        assertEquals(listOf("sync-1"), response.syncedSessionIds)
        assertEquals(1, studySessionRepository.countByUser(user))
        assertEquals(2, reviewEventRepository.countByUser(user))
    }

    @Test
    fun `sync is idempotent for same clientSessionId`() {
        val request = SyncAnalyticsRequest(
            sessions = listOf(
                SyncSessionRequest(
                    clientSessionId = "idempotent-1",
                    startedAt = EPOCH_NOV_14,
                    endedAt = EPOCH_NOV_14 + 60_000,
                    durationMs = 60_000,
                    totalCards = 1,
                    correctCount = 1,
                    incorrectCount = 0,
                    reviewType = "REVIEW",
                    completedNormally = true,
                    events = listOf(
                        SyncReviewEventRequest(
                            wordId = 1, wordText = "a", wordTranslation = "b",
                            sourceLanguage = "en", targetLanguage = "es",
                            rating = 1, previousLevel = 0, newLevel = 1,
                            responseTimeMs = 1000, reviewedAt = EPOCH_NOV_14 + 5_000,
                        )
                    ),
                )
            )
        )

        analyticsService.syncSessions(user, request)
        val response2 = analyticsService.syncSessions(user, request)

        assertEquals(listOf("idempotent-1"), response2.syncedSessionIds)
        assertEquals(1, studySessionRepository.countByUser(user))
        assertEquals(1, reviewEventRepository.countByUser(user))
    }

    @Test
    fun `sync handles multiple sessions in one request`() {
        val request = SyncAnalyticsRequest(
            sessions = listOf(
                SyncSessionRequest(
                    clientSessionId = "batch-1", startedAt = EPOCH_NOV_14,
                    endedAt = null, durationMs = 30_000, totalCards = 0,
                    correctCount = 0, incorrectCount = 0, reviewType = "REVIEW",
                    completedNormally = true, events = emptyList(),
                ),
                SyncSessionRequest(
                    clientSessionId = "batch-2", startedAt = EPOCH_NOV_14 + 100_000,
                    endedAt = null, durationMs = 45_000, totalCards = 0,
                    correctCount = 0, incorrectCount = 0, reviewType = "LEARN",
                    completedNormally = false, events = emptyList(),
                ),
            )
        )

        val response = analyticsService.syncSessions(user, request)

        assertEquals(2, response.syncedSessionIds.size)
        assertEquals(2, studySessionRepository.countByUser(user))
    }

    // ── Insights ─────────────────────────────────────────────────────────────

    @Test
    fun `insights returns zeros for user with no data`() {
        val insights = analyticsService.getStudyInsights(user)

        assertEquals(0L, insights.totalCardsReviewed)
        assertEquals(0L, insights.totalCorrect)
        assertEquals(0.0, insights.accuracyPercent)
        assertEquals(0L, insights.totalStudyTimeMs)
        assertEquals(0L, insights.totalSessions)
        assertEquals(0L, insights.daysStudied)
        assertEquals(0L, insights.uniqueWordsReviewed)
        assertNull(insights.averageResponseTimeMs)
        assertNull(insights.averageSessionDurationMs)
        assertNull(insights.sessionCompletionRate)
        assertEquals(0L, insights.wordsMasteredCount)
    }

    @Test
    fun `insights calculates accuracy and averages correctly`() {
        createSession(
            clientSessionId = "s1",
            durationMs = 120_000,
            events = listOf(
                EventData(wordId = 1, rating = 1, responseTimeMs = 1000),
                EventData(wordId = 2, rating = 1, responseTimeMs = 2000),
                EventData(wordId = 3, rating = 0, responseTimeMs = 3000),
                EventData(wordId = 4, rating = 0, responseTimeMs = 1000),
            ),
        )

        val insights = analyticsService.getStudyInsights(user)

        assertEquals(4L, insights.totalCardsReviewed)
        assertEquals(2L, insights.totalCorrect)
        assertEquals(50.0, insights.accuracyPercent, 0.01)
        assertEquals(4L, insights.uniqueWordsReviewed)
        assertEquals(1L, insights.totalSessions)
        assertEquals(120_000L, insights.totalStudyTimeMs)
        assertEquals(120_000L, insights.averageSessionDurationMs)
        // avg response time = (1000+2000+3000+1000) / 4 = 1750
        assertEquals(1750L, insights.averageResponseTimeMs)
    }

    @Test
    fun `insights counts mastered words correctly`() {
        createSession(
            clientSessionId = "mastered-session",
            events = listOf(
                EventData(wordId = 10, rating = 1, previousLevel = 5, newLevel = 6),
                EventData(wordId = 11, rating = 1, previousLevel = 5, newLevel = 6),
                EventData(wordId = 12, rating = 1, previousLevel = 3, newLevel = 4),
            ),
        )

        val insights = analyticsService.getStudyInsights(user)

        assertEquals(2L, insights.wordsMasteredCount)
    }

    @Test
    fun `session completion rate calculated correctly`() {
        createSession(clientSessionId = "completed", completedNormally = true)
        createSession(clientSessionId = "abandoned", completedNormally = false)

        val insights = analyticsService.getStudyInsights(user)

        assertEquals(50.0, insights.sessionCompletionRate!!, 0.01)
    }

    // ── Difficult Words ──────────────────────────────────────────────────────

    @Test
    fun `difficult words ranked by error rate`() {
        createSession(
            clientSessionId = "diff-session",
            events = listOf(
                // Word 1: 3 reviews, 2 errors -> 0.67
                EventData(wordId = 1, wordText = "hard", wordTranslation = "difícil", rating = 0),
                EventData(wordId = 1, wordText = "hard", wordTranslation = "difícil", rating = 0),
                EventData(wordId = 1, wordText = "hard", wordTranslation = "difícil", rating = 1),
                // Word 2: 3 reviews, 1 error -> 0.33
                EventData(wordId = 2, wordText = "easy", wordTranslation = "fácil", rating = 0),
                EventData(wordId = 2, wordText = "easy", wordTranslation = "fácil", rating = 1),
                EventData(wordId = 2, wordText = "easy", wordTranslation = "fácil", rating = 1),
            ),
        )

        val result = analyticsService.getDifficultWords(user, minReviews = 3, limit = 10)

        assertEquals(2, result.size)
        assertEquals("hard", result[0].wordText)
        assertEquals(2, result[0].errorCount)
        assertTrue(result[0].errorRate > result[1].errorRate)
    }

    @Test
    fun `difficult words filters by minimum reviews`() {
        createSession(
            clientSessionId = "min-review-session",
            events = listOf(
                EventData(wordId = 1, wordText = "a", wordTranslation = "b", rating = 0),
                EventData(wordId = 1, wordText = "a", wordTranslation = "b", rating = 0),
            ),
        )

        val result = analyticsService.getDifficultWords(user, minReviews = 3, limit = 10)

        assertTrue(result.isEmpty())
    }

    // ── Most Reviewed Words ──────────────────────────────────────────────────

    @Test
    fun `most reviewed words returns correct order`() {
        createSession(
            clientSessionId = "reviewed-session",
            events = listOf(
                EventData(wordId = 1, wordText = "a", wordTranslation = "aa"),
                EventData(wordId = 1, wordText = "a", wordTranslation = "aa"),
                EventData(wordId = 1, wordText = "a", wordTranslation = "aa"),
                EventData(wordId = 2, wordText = "b", wordTranslation = "bb"),
            ),
        )

        val result = analyticsService.getMostReviewedWords(user, limit = 10)

        assertEquals(2, result.size)
        assertEquals(1L, result[0].wordId)
        assertEquals(3, result[0].totalReviews)
        assertEquals(1, result[1].totalReviews)
    }

    // ── Accuracy by Level ────────────────────────────────────────────────────

    @Test
    fun `accuracy by level groups correctly`() {
        createSession(
            clientSessionId = "level-session",
            events = listOf(
                EventData(previousLevel = 0, newLevel = 1, rating = 1),
                EventData(previousLevel = 0, newLevel = 0, rating = 0),
                EventData(previousLevel = 2, newLevel = 3, rating = 1),
                EventData(previousLevel = 2, newLevel = 3, rating = 1),
            ),
        )

        val result = analyticsService.getAccuracyByLevel(user)

        val level0 = result.find { it.level == 0 }!!
        assertEquals(2L, level0.totalReviews)
        assertEquals(1L, level0.correctCount)
        assertEquals(50.0, level0.accuracyPercent, 0.01)

        val level2 = result.find { it.level == 2 }!!
        assertEquals(100.0, level2.accuracyPercent, 0.01)
    }

    // ── Daily Stats ──────────────────────────────────────────────────────────

    @Test
    fun `daily stats aggregates by date`() {
        val dayStart = EPOCH_NOV_15 // 2023-11-15T00:00:00Z
        createSession(
            clientSessionId = "day-s1", startedAt = dayStart,
            events = listOf(EventData(wordId = 1, rating = 1)),
            durationMs = 30_000,
        )
        createSession(
            clientSessionId = "day-s2", startedAt = dayStart + 3_600_000,
            events = listOf(EventData(wordId = 2, rating = 0)),
            durationMs = 45_000,
        )

        val result = analyticsService.getDailyStats(user, LocalDate.parse("2023-11-15"), LocalDate.parse("2023-11-15"))

        assertEquals(1, result.size)
        assertEquals("2023-11-15", result[0].date)
        assertEquals(2, result[0].sessionsCount)
        assertEquals(2, result[0].cardsReviewed)
        assertEquals(1, result[0].correctCount)
        assertEquals(1, result[0].incorrectCount)
        assertEquals(75_000L, result[0].studyTimeMs)
    }

    @Test
    fun `daily stats returns empty for date range with no sessions`() {
        val result = analyticsService.getDailyStats(user, LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-31"))
        assertTrue(result.isEmpty())
    }

    // ── Recent Sessions ──────────────────────────────────────────────────────

    @Test
    fun `recent sessions returns in descending order`() {
        createSession(clientSessionId = "old", startedAt = EPOCH_NOV_14)
        createSession(clientSessionId = "new", startedAt = EPOCH_NOV_15)

        val result = analyticsService.getRecentSessions(user, limit = 10)

        assertEquals(2, result.size)
        assertEquals("new", result[0].clientSessionId)
        assertEquals("old", result[1].clientSessionId)
    }

    @Test
    fun `recent sessions respects limit`() {
        createSession(clientSessionId = "s1", startedAt = EPOCH_NOV_14)
        createSession(clientSessionId = "s2", startedAt = EPOCH_NOV_15)
        createSession(clientSessionId = "s3", startedAt = EPOCH_NOV_15 + 86_400_000)

        val result = analyticsService.getRecentSessions(user, limit = 2)

        assertEquals(2, result.size)
    }

    // ── Level Transitions ────────────────────────────────────────────────────

    @Test
    fun `level transitions counted correctly`() {
        createSession(
            clientSessionId = "trans-session",
            events = listOf(
                EventData(previousLevel = 0, newLevel = 1, rating = 1),
                EventData(previousLevel = 0, newLevel = 1, rating = 1),
                EventData(previousLevel = 1, newLevel = 0, rating = 0),
            ),
        )

        val result = analyticsService.getLevelTransitions(user)

        val up = result.find { it.fromLevel == 0 && it.toLevel == 1 }!!
        assertEquals(2L, up.count)

        val down = result.find { it.fromLevel == 1 && it.toLevel == 0 }!!
        assertEquals(1L, down.count)
    }

    // ── Mastered Words ───────────────────────────────────────────────────────

    @Test
    fun `mastered words detected when reaching level 6`() {
        createSession(
            clientSessionId = "mastered-words",
            events = listOf(
                EventData(
                    wordId = 1, wordText = "mastered", wordTranslation = "dominado",
                    previousLevel = 5, newLevel = 6, rating = 1,
                    reviewedAt = EPOCH_NOV_14 + 10_000,
                ),
                EventData(
                    wordId = 2, wordText = "notyet", wordTranslation = "todavíano",
                    previousLevel = 4, newLevel = 5, rating = 1,
                ),
            ),
        )

        val result = analyticsService.getWordsMastered(user, limit = 10)

        assertEquals(1, result.size)
        assertEquals("mastered", result[0].wordText)
    }

    // ── Language Pair Stats ──────────────────────────────────────────────────

    @Test
    fun `language pair stats grouped correctly`() {
        createSession(
            clientSessionId = "lang-session",
            events = listOf(
                EventData(sourceLanguage = "en", targetLanguage = "es", rating = 1, wordId = 1),
                EventData(sourceLanguage = "en", targetLanguage = "es", rating = 0, wordId = 2),
                EventData(sourceLanguage = "en", targetLanguage = "de", rating = 1, wordId = 3),
            ),
        )

        val result = analyticsService.getStatsByLanguagePair(user)

        assertEquals(2, result.size)
        val enEs = result.find { it.targetLanguage == "es" }!!
        assertEquals(2L, enEs.totalReviews)
        assertEquals(1L, enEs.correctCount)
        assertEquals(2L, enEs.uniqueWords)
    }

    // ── Comeback Words ───────────────────────────────────────────────────────

    @Test
    fun `comeback words identified correctly`() {
        createSession(
            clientSessionId = "comeback-session",
            events = listOf(
                // Word 1: big drop (3→1) then mastered (5→6) = comeback
                EventData(
                    wordId = 1, wordText = "comeback", wordTranslation = "vuelta",
                    previousLevel = 3, newLevel = 1, rating = 0,
                ),
                EventData(
                    wordId = 1, wordText = "comeback", wordTranslation = "vuelta",
                    previousLevel = 5, newLevel = 6, rating = 1,
                ),
                // Word 2: mastered but never fell = not a comeback
                EventData(
                    wordId = 2, wordText = "steady", wordTranslation = "constante",
                    previousLevel = 5, newLevel = 6, rating = 1,
                ),
            ),
        )

        val result = analyticsService.getComebackWords(user)

        assertEquals(1, result.size)
        assertEquals("comeback", result[0].wordText)
    }

    // ── Native Query Tests ───────────────────────────────────────────────────

    @Test
    fun `accuracy by hour groups correctly`() {
        // EPOCH_NOV_14 = 1699920000000 = 2023-11-14T00:00:00Z → hour 0
        createSession(
            clientSessionId = "hour-session",
            events = listOf(
                EventData(rating = 1, reviewedAt = EPOCH_NOV_14 + 3_600_000),  // hour 1
                EventData(rating = 0, reviewedAt = EPOCH_NOV_14 + 3_601_000),  // hour 1
                EventData(rating = 1, reviewedAt = EPOCH_NOV_14 + 7_200_000),  // hour 2
            ),
        )

        val result = analyticsService.getAccuracyByHour(user)

        assertTrue(result.size >= 2, "Expected at least 2 hour groups, got ${result.size}: ${result.map { "${it.hour}→${it.totalReviews}" }}")
        // Find the group with 2 reviews (hour 1 events)
        val twoReviewGroup = result.find { it.totalReviews == 2L }
        assertNotNull(twoReviewGroup, "Expected a group with 2 reviews, got: ${result.map { "hour=${it.hour},total=${it.totalReviews}" }}")
        assertEquals(1L, twoReviewGroup!!.correctCount)
        assertEquals(50.0, twoReviewGroup.accuracyPercent, 0.01)
    }

    @Test
    fun `accuracy by day of week groups correctly`() {
        // 2023-11-14 = Tuesday
        createSession(
            clientSessionId = "dow-session",
            events = listOf(
                EventData(rating = 1, reviewedAt = EPOCH_NOV_14),
                EventData(rating = 1, reviewedAt = EPOCH_NOV_14 + 1000),
            ),
        )

        val result = analyticsService.getAccuracyByDayOfWeek(user)

        assertTrue(result.isNotEmpty())
        assertEquals(2L, result[0].totalReviews)
        assertEquals(100.0, result[0].accuracyPercent, 0.01)
    }

    @Test
    fun `heatmap returns daily counts`() {
        createSession(
            clientSessionId = "heatmap-session",
            events = listOf(
                EventData(reviewedAt = EPOCH_NOV_14 + 1000),       // Nov 14
                EventData(reviewedAt = EPOCH_NOV_14 + 2000),       // Nov 14
                EventData(reviewedAt = EPOCH_NOV_15 + 1000),       // Nov 15
            ),
        )

        val result = analyticsService.getHeatmap(
            user,
            startMs = EPOCH_NOV_14,
            endMs = EPOCH_NOV_15 + 86_400_000,
        )

        assertEquals(2, result.size)
        val nov14 = result.find { it.date == "2023-11-14" }!!
        assertEquals(2, nov14.count)
        val nov15 = result.find { it.date == "2023-11-15" }!!
        assertEquals(1, nov15.count)
    }

    @Test
    fun `monthly stats groups correctly`() {
        createSession(
            clientSessionId = "monthly-session",
            events = listOf(
                EventData(rating = 1, reviewedAt = EPOCH_NOV_14),
                EventData(rating = 0, reviewedAt = EPOCH_NOV_14 + 1000),
            ),
        )

        val result = analyticsService.getMonthlyStats(user)

        assertEquals(1, result.size)
        assertEquals(2023, result[0].year)
        assertEquals(11, result[0].month)
        assertEquals(2L, result[0].totalReviews)
        assertEquals(1L, result[0].correctCount)
        assertEquals(50.0, result[0].accuracyPercent, 0.01)
    }

    @Test
    fun `response time trend calculated correctly`() {
        createSession(
            clientSessionId = "rtt-session",
            events = listOf(
                EventData(responseTimeMs = 1000, reviewedAt = EPOCH_NOV_14),
                EventData(responseTimeMs = 3000, reviewedAt = EPOCH_NOV_14 + 1000),
            ),
        )

        val result = analyticsService.getResponseTimeTrend(user)

        assertTrue(result.isNotEmpty())
        assertEquals(2000.0, result[0].avgResponseTimeMs, 0.01)
    }

    // ── Data Isolation ───────────────────────────────────────────────────────

    @Test
    fun `queries only return data for the requesting user`() {
        val otherUser = userRepository.save(User(email = "other@test.com", name = "Other"))

        createSession(
            clientSessionId = "my-session",
            events = listOf(EventData(wordId = 1, rating = 1)),
        )

        // Create session for other user directly
        val otherSession = studySessionRepository.save(
            StudySession(
                user = otherUser, clientSessionId = "other-session",
                startedAt = EPOCH_NOV_14, durationMs = 60_000,
                totalCards = 1, correctCount = 1,
            )
        )
        reviewEventRepository.save(
            ReviewEvent(
                session = otherSession, user = otherUser,
                wordId = 99, wordText = "other", wordTranslation = "otro",
                sourceLanguage = "en", targetLanguage = "es",
                rating = 1, previousLevel = 0, newLevel = 1,
                responseTimeMs = 500, reviewedAt = EPOCH_NOV_14,
            )
        )

        val myInsights = analyticsService.getStudyInsights(user)
        val otherInsights = analyticsService.getStudyInsights(otherUser)

        assertEquals(1L, myInsights.totalCardsReviewed)
        assertEquals(1L, otherInsights.totalCardsReviewed)
    }

    companion object {
        // 2023-11-14T00:00:00Z in epoch millis
        const val EPOCH_NOV_14 = 1699920000000L
        // 2023-11-15T00:00:00Z in epoch millis
        const val EPOCH_NOV_15 = 1700006400000L
    }
}
