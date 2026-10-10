package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.ai.DailyInsight
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.analytics.DifficultWordResponse
import com.alirezaiyan.vokab.server.analytics.LanguagePairStatsResponse
import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import com.alirezaiyan.vokab.server.analytics.WeeklyReportResponse
import com.alirezaiyan.vokab.server.study.MilestoneDetector.MilestoneEvent
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.analytics.PracticeHistory
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import com.alirezaiyan.vokab.server.TEST_NOW
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import java.time.Instant
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.ai.DailyInsightService
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.ai.AiService
import com.alirezaiyan.vokab.server.study.UserProgressService

class NotificationContentBuilderTest {

    private lateinit var aiService: AiService
    private lateinit var userProgressService: UserProgressService
    private lateinit var learnerSignals: LearnerSignals
    private lateinit var dailyInsightService: DailyInsightService
    private lateinit var milestoneDetector: MilestoneDetector

    private lateinit var notificationContentBuilder: NotificationContentBuilder

    @BeforeEach
    fun setUp() {
        aiService = mockk()
        userProgressService = mockk()
        learnerSignals = mockk()
        dailyInsightService = mockk()
        milestoneDetector = mockk()

        notificationContentBuilder = NotificationContentBuilder(
            aiService,
            userProgressService,
            learnerSignals,
            dailyInsightService,
            milestoneDetector,
            fixedClock(),
        )
    }

    // ── STREAK_RISK ───────────────────────────────────────────────────────────

    @Test
    fun `should return STREAK_RISK payload with AI-generated body when openRouter returns message`() {
        // Arrange
        val user = createUser(currentStreak = 7)
        val stats = createProgressStats(totalWords = 30, dueCards = 5)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every {
            aiService.generateStreakReminderMessage(7, user.name, stats)
        } returns "Don't lose it!"

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.STREAK_RISK)

        // Assert
        assertEquals(NotificationType.STREAK_RISK, result.type)
        assertEquals("Don't lose it!", result.body)
        assertTrue(result.title.contains("7"))
        assertNotNull(result.data["current_streak"])
        assertEquals("7", result.data["current_streak"])
    }

    @Test
    fun `should use default body when AI is unavailable for STREAK_RISK`() {
        // Arrange
        val user = createUser(currentStreak = 3)
        val stats = createProgressStats(totalWords = 10, dueCards = 2)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every {
            aiService.generateStreakReminderMessage(3, user.name, stats)
        } throws UpstreamServiceException("OpenRouter down")

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.STREAK_RISK)

        // Assert
        assertEquals(NotificationType.STREAK_RISK, result.type)
        // TEST_NOW is 10:00 UTC; streak days end at UTC midnight
        assertTrue(result.body.contains("resets in 14h"))
        assertTrue(result.body.contains("3"))
    }

    @Test
    fun `should include deep_link and type in STREAK_RISK data map`() {
        // Arrange
        val user = createUser(currentStreak = 5)
        val stats = createProgressStats()
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every {
            aiService.generateStreakReminderMessage(any(), any(), any())
        } returns "Keep going!"

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.STREAK_RISK)

        // Assert
        assertEquals("streak_risk", result.data["type"])
        assertEquals("vokab://review", result.data["deep_link"])
    }

    @Test
    fun `should say the streak resets within the hour late in the UTC day`() {
        val builder = NotificationContentBuilder(
            aiService, userProgressService, learnerSignals, dailyInsightService, milestoneDetector,
            fixedClock(Instant.parse("2026-06-17T23:30:00Z")),
        )
        val user = createUser(currentStreak = 4)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats()
        every { aiService.generateStreakReminderMessage(any(), any(), any()) } returns "Go!"

        val result = builder.build(user, NotificationType.STREAK_RISK)

        assertTrue(result.title.contains("resets within the hour"))
    }

    // ── ADD_WORDS ─────────────────────────────────────────────────────────────

    @Test
    fun `should return ADD_WORDS payload pointing to the add-words screen`() {
        val result = notificationContentBuilder.build(createUser(), NotificationType.ADD_WORDS)

        assertEquals(NotificationType.ADD_WORDS, result.type)
        assertEquals("add_words", result.data["type"])
        assertEquals("vokab://words/add", result.data["deep_link"])
    }

    // ── WORD_RUSH / LISTENING ─────────────────────────────────────────────────

    @Test
    fun `should challenge a regular Word Rush player to beat their best`() {
        val user = createUser()
        every { learnerSignals.practiceHistory(user.requireId()) } returns
            PracticeHistory(lastWordRushAt = TEST_NOW.minusSeconds(3 * 86_400), bestWordRushScore = 1240, lastListeningAt = null)

        val result = notificationContentBuilder.build(user, NotificationType.WORD_RUSH)

        assertEquals(NotificationType.WORD_RUSH, result.type)
        assertTrue(result.title.contains("1240"))
        assertEquals("word_rush", result.data["type"])
        assertNull(result.data["deep_link"])
    }

    @Test
    fun `should introduce Word Rush to someone who never played`() {
        val user = createUser()
        every { learnerSignals.practiceHistory(user.requireId()) } returns
            PracticeHistory(lastWordRushAt = null, bestWordRushScore = 0, lastListeningAt = null)

        val result = notificationContentBuilder.build(user, NotificationType.WORD_RUSH)

        assertTrue(result.body.contains("Word Rush"))
        assertFalse(result.title.contains("best"))
    }

    @Test
    fun `should invite a regular listener back to Listening`() {
        val user = createUser()
        every { learnerSignals.practiceHistory(user.requireId()) } returns
            PracticeHistory(lastWordRushAt = null, bestWordRushScore = 0, lastListeningAt = TEST_NOW.minusSeconds(5 * 86_400))

        val result = notificationContentBuilder.build(user, NotificationType.LISTENING)

        assertEquals("Practice by ear 🎧", result.title)
        assertEquals("listening", result.data["type"])
    }

    // ── DUE_CARDS ─────────────────────────────────────────────────────────────

    @Test
    fun `should return DUE_CARDS payload with target language and estimated minutes`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats(totalWords = 20, dueCards = 10)
        val langStats = listOf(createLanguagePairStats(targetLanguage = "Spanish"))
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { learnerSignals.primaryTargetLanguage(user.requireId()) } returns langStats.firstOrNull()?.targetLanguage

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DUE_CARDS)

        // Assert
        assertEquals(NotificationType.DUE_CARDS, result.type)
        assertTrue(result.body.contains("Spanish"))
        assertTrue(result.title.contains("10"))
        assertEquals("10", result.data["due_count"])
    }

    @Test
    fun `should calculate estimated minutes as at least 1 even when due cards are very few`() {
        // Arrange - 5 cards * 8 / 60 = 0, so maxOf(1, 0) = 1
        val user = createUser()
        val stats = createProgressStats(dueCards = 5)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { learnerSignals.primaryTargetLanguage(user.requireId()) } returns "French"

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DUE_CARDS)

        // Assert
        assertTrue(result.body.contains("1 min"))
    }

    @Test
    fun `should fall back to vocabulary label when getStatsByLanguagePair throws`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats(dueCards = 8)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { learnerSignals.primaryTargetLanguage(user.requireId()) } throws RuntimeException("DB error")

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DUE_CARDS)

        // Assert
        assertEquals(NotificationType.DUE_CARDS, result.type)
        assertTrue(result.body.contains("vocabulary"))
    }

    @Test
    fun `should fall back to vocabulary label when getStatsByLanguagePair returns empty list`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats(dueCards = 8)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { learnerSignals.primaryTargetLanguage(user.requireId()) } returns null

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DUE_CARDS)

        // Assert
        assertTrue(result.body.contains("vocabulary"))
    }

    // ── COMEBACK_ALERT ────────────────────────────────────────────────────────

    @Test
    fun `should return COMEBACK_ALERT payload containing difficult word text`() {
        // Arrange
        val user = createUser()
        val difficultWords = listOf(createDifficultWord(wordId = 42L, wordText = "apple"))
        every { learnerSignals.topDifficultWord(user.requireId()) } returns difficultWords.firstOrNull()

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.COMEBACK_ALERT)

        // Assert
        assertEquals(NotificationType.COMEBACK_ALERT, result.type)
        assertTrue(result.title.contains("apple"))
        assertEquals("42", result.data["word_id"])
        assertEquals("apple", result.data["word_text"])
        assertEquals("vokab://word/42", result.data["deep_link"])
    }

    @Test
    fun `should fall back to fallback insight when getDifficultWords returns empty list`() {
        // Arrange
        val user = createUser()
        every { learnerSignals.topDifficultWord(user.requireId()) } returns null

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.COMEBACK_ALERT)

        // Assert
        assertEquals(NotificationType.DAILY_INSIGHT, result.type)
        assertTrue(result.body.contains("fluency"))
    }

    // ── WEEKLY_PREVIEW ────────────────────────────────────────────────────────

    @Test
    fun `should return WEEKLY_PREVIEW payload with positive trend when changePercent is above 5`() {
        // Arrange
        val user = createUser()
        val report = createWeeklyReport(cardsReviewed = 50, accuracyPercent = 75.0, changePercent = 10.0)
        every { learnerSignals.weeklyReport(user.requireId()) } returns report

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.WEEKLY_PREVIEW)

        // Assert
        assertEquals(NotificationType.WEEKLY_PREVIEW, result.type)
        assertTrue(result.body.contains("▲ 10% more"))
        assertTrue(result.body.contains("50"))
        assertTrue(result.body.contains("75%"))
    }

    @Test
    fun `should include negative trend when changePercent is below minus 5`() {
        // Arrange
        val user = createUser()
        val report = createWeeklyReport(cardsReviewed = 30, accuracyPercent = 60.0, changePercent = -10.0)
        every { learnerSignals.weeklyReport(user.requireId()) } returns report

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.WEEKLY_PREVIEW)

        // Assert
        assertTrue(result.body.contains("▼ 10% less"))
    }

    @Test
    fun `should include steady pace when changePercent is within -5 to 5 range`() {
        // Arrange
        val user = createUser()
        val report = createWeeklyReport(cardsReviewed = 40, accuracyPercent = 70.0, changePercent = 0.0)
        every { learnerSignals.weeklyReport(user.requireId()) } returns report

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.WEEKLY_PREVIEW)

        // Assert
        assertTrue(result.body.contains("steady pace"))
    }

    @Test
    fun `should include steady pace when changePercent is null`() {
        // Arrange
        val user = createUser()
        val report = createWeeklyReport(changePercent = null)
        every { learnerSignals.weeklyReport(user.requireId()) } returns report

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.WEEKLY_PREVIEW)

        // Assert
        assertTrue(result.body.contains("steady pace"))
    }

    @Test
    fun `should set deep_link and type in WEEKLY_PREVIEW data map`() {
        // Arrange
        val user = createUser()
        val report = createWeeklyReport()
        every { learnerSignals.weeklyReport(user.requireId()) } returns report

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.WEEKLY_PREVIEW)

        // Assert
        assertEquals("weekly_preview", result.data["type"])
        assertEquals("vokab://stats/weekly", result.data["deep_link"])
    }

    // ── PROGRESS_MILESTONE ────────────────────────────────────────────────────

    @Test
    fun `should return PROGRESS_MILESTONE payload with AI-generated body when milestone exists`() {
        // Arrange
        val user = createUser()
        val milestone = createMilestoneEvent(
            type = "words_added",
            title = "100 words!",
            description = "100 words in your collection"
        )
        val stats = createProgressStats(totalWords = 100)
        every { milestoneDetector.getPendingMilestone(user) } returns milestone
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every {
            aiService.generateMilestoneMessage(milestone, user.name)
        } returns "Amazing milestone!"

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.PROGRESS_MILESTONE)

        // Assert
        assertEquals(NotificationType.PROGRESS_MILESTONE, result.type)
        assertEquals("100 words!", result.title)
        assertEquals("Amazing milestone!", result.body)
        assertEquals("milestone", result.data["type"])
        assertEquals("words_added", result.data["milestone_type"])
    }

    @Test
    fun `should use default milestone body when AI is unavailable`() {
        // Arrange
        val user = createUser()
        val milestone = createMilestoneEvent(description = "100 words in your collection")
        val stats = createProgressStats()
        every { milestoneDetector.getPendingMilestone(user) } returns milestone
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every {
            aiService.generateMilestoneMessage(any(), any())
        } throws UpstreamServiceException("OpenRouter down")

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.PROGRESS_MILESTONE)

        // Assert
        assertTrue(result.body.contains("100 words in your collection"))
    }

    @Test
    fun `should fall back to fallback insight when no pending milestone`() {
        // Arrange
        val user = createUser()
        every { milestoneDetector.getPendingMilestone(user) } returns null

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.PROGRESS_MILESTONE)

        // Assert
        assertEquals(NotificationType.DAILY_INSIGHT, result.type)
        assertTrue(result.body.contains("fluency"))
    }

    // ── DAILY_INSIGHT ─────────────────────────────────────────────────────────

    @Test
    fun `should return DAILY_INSIGHT payload with insight text`() {
        // Arrange
        val user = createUser()
        val insight = createDailyInsight(user, id = 1L, insightText = "Learn every day!")
        every { dailyInsightService.generateDailyInsightForUser(user) } returns insight

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DAILY_INSIGHT)

        // Assert
        assertEquals(NotificationType.DAILY_INSIGHT, result.type)
        assertEquals("Learn every day!", result.body)
        assertEquals("1", result.data["insight_id"])
        assertEquals("vokab://insights", result.data["deep_link"])
    }

    @Test
    fun `should fall back to fallback insight when generateDailyInsightForUser returns null`() {
        // Arrange
        val user = createUser()
        every { dailyInsightService.generateDailyInsightForUser(user) } returns null

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.DAILY_INSIGHT)

        // Assert
        assertEquals(NotificationType.DAILY_INSIGHT, result.type)
        assertTrue(result.title.contains("great work"))
        assertTrue(result.body.contains("fluency"))
    }

    // ── REVIEW_REMINDER ────────────────────────────────────────────────────────

    @Test
    fun `should return REVIEW_REMINDER payload with due card count and estimated time when cards are due`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats(dueCards = 15)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER)

        // Assert
        assertEquals(NotificationType.REVIEW_REMINDER, result.type)
        assertEquals("📚 Time to review!", result.title)
        assertTrue(result.body.contains("15"))
        assertTrue(result.body.contains("min"))
        assertEquals("review_reminder", result.data["type"])
        assertEquals("vokab://review", result.data["deep_link"])
    }

    @Test
    fun `should return REVIEW_REMINDER payload with generic message when no cards are due`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats(dueCards = 0)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        // Act
        val result = notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER)

        // Assert
        assertEquals(NotificationType.REVIEW_REMINDER, result.type)
        assertEquals("📚 Time to review!", result.title)
        assertTrue(result.body.contains("streak"))
        assertEquals("review_reminder", result.data["type"])
        assertEquals("vokab://review", result.data["deep_link"])
    }

    // ── NONE ──────────────────────────────────────────────────────────────────

    @Test
    fun `should throw IllegalStateException when type is NONE`() {
        val user = createUser()

        assertThrows<IllegalStateException> {
            notificationContentBuilder.build(user, NotificationType.NONE)
        }
    }

    // ── MOTIVATION ────────────────────────────────────────────────────────────

    @Test
    fun `should return loss_aversion payload with streak count when user has a current streak`() {
        val user  = createUser(currentStreak = 7)
        val stats = createProgressStats(dueCards = 5)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "loss_aversion")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.title.contains("7") || result.body.contains("7"))
        assertEquals("motivation", result.data["type"])
        assertEquals("vokab://review", result.data["deep_link"])
    }

    @Test
    fun `should return loss_aversion payload about fading vocabulary when streak is 0`() {
        val user  = createUser(currentStreak = 0)
        val stats = createProgressStats(dueCards = 5)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "loss_aversion")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    @Test
    fun `should return curiosity payload with due card count when cards are due`() {
        val user  = createUser()
        val stats = createProgressStats(dueCards = 12)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "curiosity")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.title.contains("12") || result.body.contains("12"))
    }

    @Test
    fun `should return curiosity payload with generic message when no cards are due`() {
        val user  = createUser()
        val stats = createProgressStats(dueCards = 0)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "curiosity")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    @Test
    fun `should return social_proof payload`() {
        val user  = createUser()
        val stats = createProgressStats()
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "social_proof")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    @Test
    fun `should return fresh_start payload`() {
        val user  = createUser()
        val stats = createProgressStats()
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "fresh_start")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    @Test
    fun `should return achievement payload with due card count`() {
        val user  = createUser()
        val stats = createProgressStats(dueCards = 3)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "achievement")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    @Test
    fun `should return generic fallback payload for unknown contentHint`() {
        val user  = createUser()
        val stats = createProgressStats()
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, "unknown_hint")

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
        assertEquals("vokab://review", result.data["deep_link"])
    }

    @Test
    fun `should return generic fallback payload when contentHint is null`() {
        val user  = createUser()
        val stats = createProgressStats()
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats

        val result = notificationContentBuilder.build(user, NotificationType.MOTIVATION, null)

        assertEquals(NotificationType.MOTIVATION, result.type)
        assertTrue(result.body.isNotBlank())
    }

    // ── Factory functions ──────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        currentStreak: Int = 5
    ): User = User(
        id = id,
        email = email,
        name = name,
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak = currentStreak,
        longestStreak = currentStreak,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createProgressStats(
        totalWords: Int = 10,
        dueCards: Int = 3,
        level0Count: Int = 0,
        level1Count: Int = 2,
        level2Count: Int = 2,
        level3Count: Int = 2,
        level4Count: Int = 2,
        level5Count: Int = 1,
        level6Count: Int = 1
    ): ProgressStatsDto = ProgressStatsDto(
        totalWords = totalWords,
        dueCards = dueCards,
        level0Count = level0Count,
        level1Count = level1Count,
        level2Count = level2Count,
        level3Count = level3Count,
        level4Count = level4Count,
        level5Count = level5Count,
        level6Count = level6Count
    )

    private fun createLanguagePairStats(
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        totalReviews: Long = 50L,
        correctCount: Long = 40L,
        uniqueWords: Long = 30L,
        accuracyPercent: Double = 80.0
    ): LanguagePairStatsResponse = LanguagePairStatsResponse(
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        totalReviews = totalReviews,
        correctCount = correctCount,
        uniqueWords = uniqueWords,
        accuracyPercent = accuracyPercent
    )

    private fun createDifficultWord(
        wordId: Long = 1L,
        wordText: String = "apple",
        wordTranslation: String = "Apfel",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        totalReviews: Int = 5,
        errorCount: Int = 3,
        errorRate: Double = 0.6
    ): DifficultWordResponse = DifficultWordResponse(
        wordId = wordId,
        wordText = wordText,
        wordTranslation = wordTranslation,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        totalReviews = totalReviews,
        errorCount = errorCount,
        errorRate = errorRate
    )

    private fun createWeeklyReport(
        cardsReviewed: Int = 40,
        previousWeekCardsReviewed: Int = 35,
        changePercent: Double? = 5.0,
        accuracyPercent: Double = 72.0,
        wordsMastered: Int = 3,
        totalStudyTimeMs: Long = 120_000L,
        sessionsCount: Int = 5
    ): WeeklyReportResponse = WeeklyReportResponse(
        cardsReviewed = cardsReviewed,
        previousWeekCardsReviewed = previousWeekCardsReviewed,
        changePercent = changePercent,
        accuracyPercent = accuracyPercent,
        wordsMastered = wordsMastered,
        totalStudyTimeMs = totalStudyTimeMs,
        sessionsCount = sessionsCount,
        bestDay = null,
        weekStartDate = "2026-03-16",
        weekEndDate = "2026-03-22"
    )

    private fun createMilestoneEvent(
        type: String = "words_added",
        title: String = "100 words!",
        description: String = "100 words in your collection",
        value: Long = 100L
    ): MilestoneEvent = MilestoneEvent(
        type = type,
        title = title,
        description = description,
        value = value
    )

    private fun createDailyInsight(
        user: User,
        id: Long? = 1L,
        insightText: String = "Learn every day!",
        date: String = "2026-03-26"
    ): DailyInsight = DailyInsight(
        id = id,
        user = user,
        insightText = insightText,
        generatedAt = Instant.now(),
        date = date,
        sentViaPush = false
    )
}
