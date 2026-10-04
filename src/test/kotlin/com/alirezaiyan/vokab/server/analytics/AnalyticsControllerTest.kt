package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.user.User
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.Instant

@WebMvcTest(AnalyticsController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class AnalyticsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var accuracyQueries: AccuracyQueries

    @MockitoBean
    private lateinit var analyticsSyncService: AnalyticsSyncService

    @MockitoBean
    private lateinit var studyActivityQueries: StudyActivityQueries

    @MockitoBean
    private lateinit var weeklyReportService: WeeklyReportService

    @MockitoBean
    private lateinit var wordProgressQueries: WordProgressQueries

    private val mockUser = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    // ── POST /api/v1/analytics/sync ────────────────────────────────────────────

    @Test
    fun `POST sync should return 200 with synced session ids when successful`() {
        val request = createSyncRequest()
        `when`(analyticsSyncService.syncSessions(mockUser.requireId(), request))
            .thenReturn(SyncAnalyticsResponse(syncedSessionIds = listOf("s1")))

        mockMvc.perform(
            post("/api/v1/analytics/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.syncedSessionIds[0]").value("s1"))
    }

    @Test
    fun `POST sync should return 500 when service throws exception`() {
        val request = createSyncRequest()
        `when`(analyticsSyncService.syncSessions(mockUser.requireId(), request))
            .thenThrow(RuntimeException("sync failed"))

        mockMvc.perform(
            post("/api/v1/analytics/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST sync should return 4xx when not authenticated`() {
        val request = createSyncRequest()

        mockMvc.perform(
            post("/api/v1/analytics/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `POST sync should return 400 when sessions list is empty`() {
        val invalidRequest = mapOf("sessions" to emptyList<Any>())

        mockMvc.perform(
            post("/api/v1/analytics/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/insights ────────────────────────────────────────

    @Test
    fun `GET insights should return 200 with study insights`() {
        `when`(studyActivityQueries.getStudyInsights(mockUser.requireId())).thenReturn(createStudyInsightsResponse())

        mockMvc.perform(
            get("/api/v1/analytics/insights")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalCardsReviewed").value(10))
            .andExpect(jsonPath("$.data.totalCorrect").value(8))
            .andExpect(jsonPath("$.data.accuracyPercent").value(80.0))
            .andExpect(jsonPath("$.data.wordsMasteredCount").value(5))
    }

    @Test
    fun `GET insights should return 500 when service throws exception`() {
        `when`(studyActivityQueries.getStudyInsights(mockUser.requireId()))
            .thenThrow(RuntimeException("database error"))

        mockMvc.perform(
            get("/api/v1/analytics/insights")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/daily-stats ─────────────────────────────────────

    @Test
    fun `GET daily-stats should return 200 with list of daily stats`() {
        val stats = listOf(
            DailyStatsResponse(
                date = "2024-01-01",
                sessionsCount = 2,
                cardsReviewed = 20,
                correctCount = 16,
                incorrectCount = 4,
                studyTimeMs = 60000L,
                uniqueWordsReviewed = 18,
                wordsLeveledUp = 3,
                wordsLeveledDown = 1,
            )
        )
        `when`(studyActivityQueries.getDailyStats(mockUser.requireId(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-07"))).thenReturn(stats)

        mockMvc.perform(
            get("/api/v1/analytics/daily-stats")
                .param("start", "2024-01-01")
                .param("end", "2024-01-07")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].date").value("2024-01-01"))
            .andExpect(jsonPath("$.data[0].cardsReviewed").value(20))
    }

    @Test
    fun `GET daily-stats should return 400 when a date is malformed`() {
        mockMvc.perform(
            get("/api/v1/analytics/daily-stats")
                .param("start", "bad-date")
                .param("end", "2024-01-07")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/difficult-words ─────────────────────────────────

    @Test
    fun `GET difficult-words should return 200 with list of difficult words`() {
        val words = listOf(
            DifficultWordResponse(
                wordId = 10L,
                wordText = "schadenfreude",
                wordTranslation = "pleasure from others' misfortune",
                sourceLanguage = "de",
                targetLanguage = "en",
                totalReviews = 5,
                errorCount = 4,
                errorRate = 0.8,
            )
        )
        `when`(wordProgressQueries.getDifficultWords(mockUser.requireId(), 3, 20)).thenReturn(words)

        mockMvc.perform(
            get("/api/v1/analytics/difficult-words")
                .param("minReviews", "3")
                .param("limit", "20")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].wordId").value(10))
            .andExpect(jsonPath("$.data[0].errorRate").value(0.8))
    }

    @Test
    fun `GET difficult-words should use default params when none provided`() {
        `when`(wordProgressQueries.getDifficultWords(mockUser.requireId(), 3, 20)).thenReturn(emptyList())

        mockMvc.perform(
            get("/api/v1/analytics/difficult-words")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
    }

    @Test
    fun `GET difficult-words should return 500 when service throws exception`() {
        `when`(wordProgressQueries.getDifficultWords(mockUser.requireId(), 3, 20))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/difficult-words")
                .param("minReviews", "3")
                .param("limit", "20")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/most-reviewed ───────────────────────────────────

    @Test
    fun `GET most-reviewed should return 200 with list of most reviewed words`() {
        val words = listOf(
            MostReviewedWordResponse(
                wordId = 5L,
                wordText = "hello",
                wordTranslation = "hallo",
                totalReviews = 42,
            )
        )
        `when`(wordProgressQueries.getMostReviewedWords(mockUser.requireId(), 10)).thenReturn(words)

        mockMvc.perform(
            get("/api/v1/analytics/most-reviewed")
                .param("limit", "10")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].wordId").value(5))
            .andExpect(jsonPath("$.data[0].totalReviews").value(42))
    }

    @Test
    fun `GET most-reviewed should return 500 when service throws exception`() {
        `when`(wordProgressQueries.getMostReviewedWords(mockUser.requireId(), 10))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/most-reviewed")
                .param("limit", "10")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/accuracy-by-level ───────────────────────────────

    @Test
    fun `GET accuracy-by-level should return 200 with accuracy data`() {
        val data = listOf(
            AccuracyByLevelResponse(
                level = 1,
                totalReviews = 100L,
                correctCount = 80L,
                accuracyPercent = 80.0,
            )
        )
        `when`(accuracyQueries.getAccuracyByLevel(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-level")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].level").value(1))
            .andExpect(jsonPath("$.data[0].accuracyPercent").value(80.0))
    }

    @Test
    fun `GET accuracy-by-level should return 500 when service throws exception`() {
        `when`(accuracyQueries.getAccuracyByLevel(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-level")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/accuracy-by-hour ────────────────────────────────

    @Test
    fun `GET accuracy-by-hour should return 200 with hourly accuracy data`() {
        val data = listOf(
            HourlyAccuracyResponse(hour = 9, totalReviews = 50L, correctCount = 40L, accuracyPercent = 80.0)
        )
        `when`(accuracyQueries.getAccuracyByHour(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-hour")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].hour").value(9))
    }

    @Test
    fun `GET accuracy-by-hour should return 500 when service throws exception`() {
        `when`(accuracyQueries.getAccuracyByHour(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-hour")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/accuracy-by-day-of-week ─────────────────────────

    @Test
    fun `GET accuracy-by-day-of-week should return 200 with day of week accuracy data`() {
        val data = listOf(
            DayOfWeekAccuracyResponse(dayOfWeek = 1, totalReviews = 30L, correctCount = 25L, accuracyPercent = 83.3)
        )
        `when`(accuracyQueries.getAccuracyByDayOfWeek(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-day-of-week")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].dayOfWeek").value(1))
    }

    @Test
    fun `GET accuracy-by-day-of-week should return 500 when service throws exception`() {
        `when`(accuracyQueries.getAccuracyByDayOfWeek(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/accuracy-by-day-of-week")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/sessions ────────────────────────────────────────

    @Test
    fun `GET sessions should return 200 with recent sessions`() {
        val sessions = listOf(
            StudySessionResponse(
                clientSessionId = "s1",
                startedAt = 1000L,
                endedAt = 2000L,
                durationMs = 1000L,
                totalCards = 5,
                correctCount = 4,
                incorrectCount = 1,
                reviewType = "srs",
                completedNormally = true,
            )
        )
        `when`(studyActivityQueries.getRecentSessions(mockUser.requireId(), 10)).thenReturn(sessions)

        mockMvc.perform(
            get("/api/v1/analytics/sessions")
                .param("limit", "10")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].clientSessionId").value("s1"))
            .andExpect(jsonPath("$.data[0].totalCards").value(5))
    }

    @Test
    fun `GET sessions should return 500 when service throws exception`() {
        `when`(studyActivityQueries.getRecentSessions(mockUser.requireId(), 10))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/sessions")
                .param("limit", "10")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/heatmap ─────────────────────────────────────────

    @Test
    fun `GET heatmap should return 200 with heatmap data`() {
        val data = listOf(HeatmapDayResponse(date = "2024-01-01", count = 5))
        `when`(studyActivityQueries.getHeatmap(mockUser.requireId(), 1704067200000L, 1704672000000L)).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/heatmap")
                .param("start", "1704067200000")
                .param("end", "1704672000000")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].date").value("2024-01-01"))
            .andExpect(jsonPath("$.data[0].count").value(5))
    }

    @Test
    fun `GET heatmap should return 500 when service throws exception`() {
        `when`(studyActivityQueries.getHeatmap(mockUser.requireId(), 1704067200000L, 1704672000000L))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/heatmap")
                .param("start", "1704067200000")
                .param("end", "1704672000000")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/level-transitions ───────────────────────────────

    @Test
    fun `GET level-transitions should return 200 with transition data`() {
        val data = listOf(LevelTransitionResponse(fromLevel = 1, toLevel = 2, count = 10L))
        `when`(wordProgressQueries.getLevelTransitions(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/level-transitions")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].fromLevel").value(1))
            .andExpect(jsonPath("$.data[0].toLevel").value(2))
    }

    @Test
    fun `GET level-transitions should return 500 when service throws exception`() {
        `when`(wordProgressQueries.getLevelTransitions(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/level-transitions")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/words-mastered ──────────────────────────────────

    @Test
    fun `GET words-mastered should return 200 with mastered words`() {
        val data = listOf(
            MasteredWordResponse(
                wordId = 7L,
                wordText = "Weltanschauung",
                wordTranslation = "worldview",
                masteredAt = 1704067200000L,
            )
        )
        `when`(wordProgressQueries.getWordsMastered(mockUser.requireId(), 20)).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/words-mastered")
                .param("limit", "20")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].wordId").value(7))
            .andExpect(jsonPath("$.data[0].wordText").value("Weltanschauung"))
    }

    @Test
    fun `GET words-mastered should return 500 when service throws exception`() {
        `when`(wordProgressQueries.getWordsMastered(mockUser.requireId(), 20))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/words-mastered")
                .param("limit", "20")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/language-stats ──────────────────────────────────

    @Test
    fun `GET language-stats should return 200 with language pair stats`() {
        val data = listOf(
            LanguagePairStatsResponse(
                sourceLanguage = "en",
                targetLanguage = "de",
                totalReviews = 200L,
                correctCount = 160L,
                uniqueWords = 50L,
                accuracyPercent = 80.0,
            )
        )
        `when`(accuracyQueries.getStatsByLanguagePair(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/language-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].sourceLanguage").value("en"))
            .andExpect(jsonPath("$.data[0].targetLanguage").value("de"))
    }

    @Test
    fun `GET language-stats should return 500 when service throws exception`() {
        `when`(accuracyQueries.getStatsByLanguagePair(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/language-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/monthly-stats ───────────────────────────────────

    @Test
    fun `GET monthly-stats should return 200 with monthly stats`() {
        val data = listOf(
            MonthlyStatsResponse(
                year = 2024,
                month = 1,
                totalReviews = 300L,
                correctCount = 240L,
                accuracyPercent = 80.0,
            )
        )
        `when`(studyActivityQueries.getMonthlyStats(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/monthly-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].year").value(2024))
            .andExpect(jsonPath("$.data[0].month").value(1))
    }

    @Test
    fun `GET monthly-stats should return 500 when service throws exception`() {
        `when`(studyActivityQueries.getMonthlyStats(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/monthly-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/response-time-trend ─────────────────────────────

    @Test
    fun `GET response-time-trend should return 200 with trend data`() {
        val data = listOf(
            ResponseTimeTrendResponse(year = 2024, week = 1, avgResponseTimeMs = 1200.5)
        )
        `when`(accuracyQueries.getResponseTimeTrend(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/response-time-trend")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].week").value(1))
            .andExpect(jsonPath("$.data[0].avgResponseTimeMs").value(1200.5))
    }

    @Test
    fun `GET response-time-trend should return 500 when service throws exception`() {
        `when`(accuracyQueries.getResponseTimeTrend(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/response-time-trend")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/comeback-words ──────────────────────────────────

    @Test
    fun `GET comeback-words should return 200 with comeback words`() {
        val data = listOf(ComebackWordResponse(wordId = 3L, wordText = "apple", wordTranslation = "Apfel"))
        `when`(wordProgressQueries.getComebackWords(mockUser.requireId())).thenReturn(data)

        mockMvc.perform(
            get("/api/v1/analytics/comeback-words")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].wordId").value(3))
            .andExpect(jsonPath("$.data[0].wordText").value("apple"))
    }

    @Test
    fun `GET comeback-words should return 500 when service throws exception`() {
        `when`(wordProgressQueries.getComebackWords(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/comeback-words")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/analytics/weekly-report ───────────────────────────────────

    @Test
    fun `GET weekly-report should return 200 with weekly report`() {
        `when`(weeklyReportService.getWeeklyReport(mockUser.requireId())).thenReturn(createWeeklyReportResponse())

        mockMvc.perform(
            get("/api/v1/analytics/weekly-report")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cardsReviewed").value(70))
            .andExpect(jsonPath("$.data.accuracyPercent").value(85.0))
            .andExpect(jsonPath("$.data.weekStartDate").value("2024-01-01"))
    }

    @Test
    fun `GET weekly-report should return 500 when service throws exception`() {
        `when`(weeklyReportService.getWeeklyReport(mockUser.requireId()))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/analytics/weekly-report")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createSyncRequest(): SyncAnalyticsRequest = SyncAnalyticsRequest(
        sessions = listOf(
            SyncSessionRequest(
                clientSessionId = "s1",
                startedAt = 1000L,
                endedAt = 2000L,
                durationMs = 1000L,
                totalCards = 5,
                correctCount = 4,
                incorrectCount = 1,
                reviewType = "srs",
                completedNormally = true,
                events = emptyList(),
            )
        )
    )

    private fun createStudyInsightsResponse(): StudyInsightsResponse = StudyInsightsResponse(
        totalCardsReviewed = 10L,
        totalCorrect = 8L,
        accuracyPercent = 80.0,
        totalStudyTimeMs = 60000L,
        totalSessions = 5L,
        daysStudied = 3L,
        uniqueWordsReviewed = 20L,
        averageResponseTimeMs = null,
        averageSessionDurationMs = null,
        sessionCompletionRate = null,
        wordsMasteredCount = 5L,
    )

    private fun createWeeklyReportResponse(): WeeklyReportResponse = WeeklyReportResponse(
        cardsReviewed = 70,
        previousWeekCardsReviewed = 60,
        changePercent = 16.7,
        accuracyPercent = 85.0,
        wordsMastered = 4,
        totalStudyTimeMs = 420000L,
        sessionsCount = 7,
        bestDay = null,
        weekStartDate = "2024-01-01",
        weekEndDate = "2024-01-07",
    )
}
