package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import io.mockk.slot
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettings
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.notification.NotificationResponse
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import com.alirezaiyan.vokab.server.analytics.AnalyticsService
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.study.UserProgressService

class DailyInsightServiceTest {

    private lateinit var dailyInsightRepository: DailyInsightRepository
    private lateinit var userSettingsRepository: UserSettingsRepository
    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var aiService: AiService
    private lateinit var userProgressService: UserProgressService
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var featureAccessService: FeatureAccessService
    private lateinit var analyticsService: AnalyticsService
    private lateinit var notificationScheduleRepository: NotificationScheduleRepository
    private lateinit var userRepository: UserRepository

    private lateinit var dailyInsightService: DailyInsightService

    @BeforeEach
    fun setUp() {
        dailyInsightRepository = mockk()
        userSettingsRepository = mockk()
        dailyActivityRepository = mockk()
        aiService = mockk()
        userProgressService = mockk()
        pushNotificationService = mockk()
        featureAccessService = mockk()
        analyticsService = mockk()
        notificationScheduleRepository = mockk()
        userRepository = mockk()

        dailyInsightService = DailyInsightService(
            dailyInsightRepository = dailyInsightRepository,
            userSettingsRepository = userSettingsRepository,
            dailyActivityRepository = dailyActivityRepository,
            aiService = aiService,
            userProgressService = userProgressService,
            pushNotificationService = pushNotificationService,
            featureAccessService = featureAccessService,
            analyticsService = analyticsService,
            notificationScheduleRepository = notificationScheduleRepository,
            userRepository = userRepository,
            clock = fixedClock()
        )
    }

    // --- generateDailyInsightForUser ---

    @Test
    fun `should return null when user lacks premium access`() {
        // Arrange
        val user = createUser()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNull(result)
        verify(exactly = 0) { dailyInsightRepository.findByUserAndDate(any(), any()) }
    }

    @Test
    fun `should return existing insight when already generated today`() {
        // Arrange
        val user = createUser()
        val today = TEST_TODAY.toString()
        val existing = createDailyInsight(user = user, date = today)
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns existing

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertEquals(existing, result)
        verify(exactly = 0) { dailyActivityRepository.existsByUserAndActivityDate(any(), any()) }
    }

    @Test
    fun `should return null when streak at risk and reminder hour is before 20`() {
        // Arrange
        val user = createUser(currentStreak = 5)
        val today = TEST_TODAY.toString()
        val settings = createUserSettings(user = user, dailyReminderTime = "18:00")
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns false
        every { userSettingsRepository.findByUser(user) } returns settings

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNull(result)
        verify(exactly = 0) { aiService.generateDailyInsight(any()) }
        verify(exactly = 0) { aiService.generateCelebrationInsight(any(), any()) }
    }

    @Test
    fun `should generate celebration insight when user has activity today`() {
        // Arrange
        val user = createUser(currentStreak = 3)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        val savedInsight = createDailyInsight(user = user, insightText = "Great work!")
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns true
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "18:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateCelebrationInsight(stats, user.name) } returns "Great work!"
        every { dailyInsightRepository.save(any()) } returns savedInsight

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNotNull(result)
        verify(exactly = 1) { aiService.generateCelebrationInsight(stats, user.name) }
        verify(exactly = 1) { dailyInsightRepository.save(any()) }
    }

    @Test
    fun `should generate motivational insight when no activity today and no streak risk`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        val savedInsight = createDailyInsight(user = user, insightText = "Keep it up!")
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns false
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "18:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateDailyInsight(any()) } returns "Keep it up!"
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { analyticsService.getWeeklyReport(user.requireId()) } returns createWeeklyReportResponse()
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { analyticsService.getStatsByLanguagePair(user.requireId()) } returns emptyList()
        every { analyticsService.getStudyInsights(user.requireId()) } returns createStudyInsightsResponse()
        every { dailyInsightRepository.save(any()) } returns savedInsight

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNotNull(result)
        verify(exactly = 1) { aiService.generateDailyInsight(any()) }
    }

    @Test
    fun `should generate insight when streak at risk but reminder hour is 20 or later`() {
        // Arrange
        val user = createUser(currentStreak = 10)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        val savedInsight = createDailyInsight(user = user, insightText = "Keep it up!")
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns false
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "20:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateDailyInsight(any()) } returns "Keep it up!"
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { analyticsService.getWeeklyReport(user.requireId()) } returns createWeeklyReportResponse()
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { analyticsService.getStatsByLanguagePair(user.requireId()) } returns emptyList()
        every { analyticsService.getStudyInsights(user.requireId()) } returns createStudyInsightsResponse()
        every { dailyInsightRepository.save(any()) } returns savedInsight

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNotNull(result)
        verify(exactly = 1) { dailyInsightRepository.save(any()) }
    }

    @Test
    fun `should return null when openRouter throws exception`() {
        // Arrange
        val user = createUser(currentStreak = 3)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns true
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "18:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateCelebrationInsight(any(), any()) } throws RuntimeException("AI service unavailable")

        // Act
        val result = dailyInsightService.generateDailyInsightForUser(user)

        // Assert
        assertNull(result)
        verify(exactly = 0) { dailyInsightRepository.save(any()) }
    }

    @Test
    fun `should return existing insight when concurrent save throws DataIntegrityViolationException`() {
        val user = createUser(currentStreak = 3)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        val existingInsight = createDailyInsight(user = user, date = today)
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns true
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "18:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateCelebrationInsight(stats, user.name) } returns "Great work!"
        every { dailyInsightRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")
        // Second findByUserAndDate call (fallback after constraint violation)
        every { dailyInsightRepository.findByUserAndDate(user, today) } returnsMany listOf(null, existingInsight)

        val result = dailyInsightService.generateDailyInsightForUser(user)

        assertEquals(existingInsight, result)
    }

    // --- sendDailyInsightPush ---

    @Test
    fun `should return true and save updated insight when push succeeds`() {
        // Arrange
        val user = createUser()
        val insight = createDailyInsight(user = user, sentViaPush = false)
        val successResponse = NotificationResponse(success = true, messageId = "msg-123")
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(successResponse)
        every { dailyInsightRepository.save(any()) } answers { firstArg() }

        // Act
        val result = dailyInsightService.sendDailyInsightPush(insight)

        // Assert
        assertTrue(result)
        verify(exactly = 1) { dailyInsightRepository.save(match { it.sentViaPush }) }
    }

    @Test
    fun `should return false when all push responses fail`() {
        // Arrange
        val user = createUser()
        val insight = createDailyInsight(user = user, sentViaPush = false)
        val failureResponse = NotificationResponse(success = false, error = "Token not registered")
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(failureResponse)

        // Act
        val result = dailyInsightService.sendDailyInsightPush(insight)

        // Assert
        assertFalse(result)
        verify(exactly = 0) { dailyInsightRepository.save(any()) }
    }

    @Test
    fun `should return false when push notification service throws exception`() {
        // Arrange
        val user = createUser()
        val insight = createDailyInsight(user = user, sentViaPush = false)
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } throws RuntimeException("Firebase unavailable")

        // Act
        val result = dailyInsightService.sendDailyInsightPush(insight)

        // Assert
        assertFalse(result)
        verify(exactly = 0) { dailyInsightRepository.save(any()) }
    }

    // --- generateAndSendForUser ---

    @Test
    fun `should do nothing when insight generation returns null`() {
        // Arrange
        val user = createUser()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        dailyInsightService.generateAndSendForUser(user)

        // Assert
        verify(exactly = 0) {
            pushNotificationService.sendNotificationToUser(
                userId = any(),
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        }
    }

    @Test
    fun `should send push when insight exists and has not been sent yet`() {
        // Arrange
        val user = createUser(currentStreak = 3)
        val today = TEST_TODAY.toString()
        val stats = createProgressStats()
        val unsent = createDailyInsight(user = user, date = today, sentViaPush = false)
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null
        every { dailyActivityRepository.existsByUserAndActivityDate(user, TEST_TODAY) } returns true
        every { userSettingsRepository.findByUser(user) } returns createUserSettings(user = user, dailyReminderTime = "18:00")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { aiService.generateCelebrationInsight(stats, user.name) } returns "Great work!"
        every { dailyInsightRepository.save(any()) } returns unsent
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(NotificationResponse(success = true))

        // Act
        dailyInsightService.generateAndSendForUser(user)

        // Assert
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        }
    }

    @Test
    fun `should not send push when insight was already sent via push`() {
        // Arrange
        val user = createUser()
        val today = TEST_TODAY.toString()
        val alreadySent = createDailyInsight(user = user, date = today, sentViaPush = true)
        every { featureAccessService.hasActivePremiumAccess(user) } returns true
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns alreadySent

        // Act
        dailyInsightService.generateAndSendForUser(user)

        // Assert
        verify(exactly = 0) {
            pushNotificationService.sendNotificationToUser(
                userId = any(),
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        }
    }

    // --- saveDailyInsight ---

    @Test
    fun `saveDailyInsight should save and return new insight`() {
        val user = createUser()
        val today = TEST_TODAY.toString()
        val saved = createDailyInsight(user = user, insightText = "You're on a roll!", date = today)
        every { dailyInsightRepository.save(any()) } returns saved

        val result = dailyInsightService.saveDailyInsight(user, "You're on a roll!")

        assertEquals(saved, result)
        verify(exactly = 1) { dailyInsightRepository.save(any()) }
    }

    @Test
    fun `saveDailyInsight should return existing row when concurrent write causes constraint violation`() {
        val user = createUser()
        val today = TEST_TODAY.toString()
        val existing = createDailyInsight(user = user, date = today, insightText = "Concurrent winner")
        every { dailyInsightRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns existing

        val result = dailyInsightService.saveDailyInsight(user, "Concurrent winner")

        assertEquals(existing, result)
    }

    @Test
    fun `saveDailyInsight should return null when constraint violation and no existing row found`() {
        val user = createUser()
        val today = TEST_TODAY.toString()
        every { dailyInsightRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")
        every { dailyInsightRepository.findByUserAndDate(user, today) } returns null

        val result = dailyInsightService.saveDailyInsight(user, "Lost in race")

        assertNull(result)
    }

    // --- factory functions ---

    // --- getOrGenerateTodaysInsight ---

    @Test
    fun `getOrGenerateTodaysInsight when today's insight exists returns it without calling the AI`() {
        val user = createUser()
        val existing = createDailyInsight(user = user, date = TEST_TODAY.toString())
        every { userRepository.findById(user.requireId()) } returns Optional.of(user)
        every { dailyInsightRepository.findByUserAndDate(user, TEST_TODAY.toString()) } returns existing

        val result = dailyInsightService.getOrGenerateTodaysInsight(user.requireId())

        assertEquals(existing.insightText, result.text)
        assertEquals(existing.generatedAt, result.generatedAt)
        verify(exactly = 0) { aiService.generateDailyInsight(any()) }
    }

    @Test
    fun `getOrGenerateTodaysInsight when none exists generates from the user's name and streak and stores it`() {
        val user = createUser(currentStreak = 4)
        val ctx = slot<AiService.DailyInsightContext>()
        every { userRepository.findById(user.requireId()) } returns Optional.of(user)
        every { dailyInsightRepository.findByUserAndDate(user, TEST_TODAY.toString()) } returns null
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats()
        every { aiService.generateDailyInsight(capture(ctx)) } returns "Keep going!"
        every { dailyInsightRepository.save(any()) } answers { firstArg() }

        val result = dailyInsightService.getOrGenerateTodaysInsight(user.requireId())

        assertEquals("Keep going!", result.text)
        assertEquals(TEST_NOW, result.generatedAt)
        assertEquals(user.name, ctx.captured.userName)
        assertEquals(4, ctx.captured.currentStreak)
        verify(exactly = 1) { dailyInsightRepository.save(match { it.insightText == "Keep going!" && it.user == user }) }
    }

    @Test
    fun `getOrGenerateTodaysInsight for an unknown user throws not found`() {
        every { userRepository.findById(404L) } returns Optional.empty()

        assertThrows<NoSuchElementException> { dailyInsightService.getOrGenerateTodaysInsight(404L) }
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        currentStreak: Int = 0,
        longestStreak: Int = 0
    ): User = User(
        id = id,
        email = email,
        name = name,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        subscriptionStatus = SubscriptionStatus.ACTIVE,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createDailyInsight(
        id: Long? = 1L,
        user: User = createUser(),
        insightText: String = "You are doing great!",
        date: String = TEST_TODAY.toString(),
        sentViaPush: Boolean = false,
        pushSentAt: Instant? = null
    ): DailyInsight = DailyInsight(
        id = id,
        user = user,
        insightText = insightText,
        generatedAt = TEST_NOW,
        date = date,
        sentViaPush = sentViaPush,
        pushSentAt = pushSentAt
    )

    private fun createUserSettings(
        user: User?,
        dailyReminderTime: String = "18:00",
        notificationsEnabled: Boolean = true
    ): UserSettings = UserSettings(
        id = 1L,
        user = user,
        dailyReminderTime = dailyReminderTime,
        notificationsEnabled = notificationsEnabled
    )

    private fun createProgressStats(
        totalWords: Int = 50,
        dueCards: Int = 10
    ): ProgressStatsDto = ProgressStatsDto(
        totalWords = totalWords,
        dueCards = dueCards,
        level0Count = 5,
        level1Count = 10,
        level2Count = 10,
        level3Count = 10,
        level4Count = 5,
        level5Count = 5,
        level6Count = 5
    )

    private fun createWeeklyReportResponse() = com.alirezaiyan.vokab.server.analytics.WeeklyReportResponse(
        cardsReviewed = 50,
        previousWeekCardsReviewed = 40,
        changePercent = 25.0,
        accuracyPercent = 80.0,
        wordsMastered = 5,
        totalStudyTimeMs = 3600000L,
        sessionsCount = 7,
        bestDay = null,
        weekStartDate = "2026-03-20",
        weekEndDate = "2026-03-26",
    )

    private fun createStudyInsightsResponse() = com.alirezaiyan.vokab.server.analytics.StudyInsightsResponse(
        totalCardsReviewed = 100,
        totalCorrect = 80,
        accuracyPercent = 80.0,
        totalStudyTimeMs = 3600000,
        totalSessions = 10,
        daysStudied = 7,
        uniqueWordsReviewed = 40,
        averageResponseTimeMs = null,
        averageSessionDurationMs = null,
        sessionCompletionRate = 0.9,
        wordsMasteredCount = 5
    )
}
