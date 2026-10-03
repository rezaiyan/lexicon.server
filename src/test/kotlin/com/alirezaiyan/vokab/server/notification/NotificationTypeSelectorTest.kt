package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.analytics.DifficultWordResponse
import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import com.alirezaiyan.vokab.server.analytics.WeeklyReportResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import com.alirezaiyan.vokab.server.analytics.AnalyticsService
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.study.UserProgressService

class NotificationTypeSelectorTest {

    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var userProgressService: UserProgressService
    private lateinit var analyticsService: AnalyticsService
    private lateinit var featureAccessService: FeatureAccessService
    private lateinit var milestoneDetector: MilestoneDetector

    private lateinit var notificationTypeSelector: NotificationTypeSelector

    @BeforeEach
    fun setUp() {
        dailyActivityRepository = mockk()
        userProgressService = mockk()
        analyticsService = mockk()
        featureAccessService = mockk()
        milestoneDetector = mockk()
        notificationTypeSelector = NotificationTypeSelector(
            dailyActivityRepository,
            userProgressService,
            analyticsService,
            featureAccessService,
            milestoneDetector,
            clock = fixedClock()
        )
    }

    @Test
    fun `selectType should return STREAK_RISK when user has streak and local hour is at least 20 and has not reviewed today`() {
        // Arrange
        // Compute an offset that guarantees (utcHour + offset + 24) % 24 >= 20.
        // We pick offset so that localHour is always exactly 20 by deriving it from the current UTC hour.
        val utcHour = java.time.LocalTime.ofInstant(TEST_NOW, ZoneOffset.UTC).hour
        // We want (utcHour + offset + 24) % 24 == 20, so offset = (20 - utcHour + 24) % 24
        val offset = (20 - utcHour + 24) % 24
        val user = createUser(currentStreak = 5)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = offset)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), any(), any()) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.STREAK_RISK, result)
    }

    @Test
    fun `selectType should not return STREAK_RISK when user has no streak even at late local hour`() {
        // Arrange
        val utcHour = java.time.LocalTime.ofInstant(TEST_NOW, ZoneOffset.UTC).hour
        val offset = (20 - utcHour + 24) % 24
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = offset)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } throws RuntimeException("not monday")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), any(), any()) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        val notStreakRisk = result != NotificationTypeSelector.NotificationType.STREAK_RISK
        assert(notStreakRisk) { "Expected any type other than STREAK_RISK but got $result" }
    }

    @Test
    fun `selectType should return PROGRESS_MILESTONE when pending milestone and reviewed today`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns true
        every { milestoneDetector.hasPendingMilestone(user) } returns true

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.PROGRESS_MILESTONE, result)
    }

    @Test
    fun `selectType should return DAILY_INSIGHT when premium user reviewed today and no milestone`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns true
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { featureAccessService.hasActivePremiumAccess(user) } returns true

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.DAILY_INSIGHT, result)
    }

    @Test
    fun `selectType should return NONE when non-premium user reviewed today and no milestone`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns true
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.NONE, result)
    }

    @Test
    fun `selectType should return DUE_CARDS when 5 or more due cards and no streak risk`() {
        // Arrange
        // currentStreak = 0 so streak risk branch is skipped
        val user = createUser(currentStreak = 0)
        // timezoneOffsetHrs = 0 so localHour = UTC hour, which is < 20 at most times;
        // use offset=-4 to further reduce effective hour away from 20
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = -4)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } throws RuntimeException("not monday")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 5)

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.DUE_CARDS, result)
    }

    @Test
    fun `selectType should return COMEBACK_ALERT when difficult words exist`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = -4)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } throws RuntimeException("not monday")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns listOf(createDifficultWord())

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.COMEBACK_ALERT, result)
    }

    @Test
    fun `selectType should return DAILY_INSIGHT when premium user and no other triggers`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = -4)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } throws RuntimeException("not monday")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns true

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.DAILY_INSIGHT, result)
    }

    @Test
    fun `selectType should return NONE when free user and no triggers`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = -4)
        val today = TEST_TODAY
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } throws RuntimeException("not monday")
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.NONE, result)
    }

    @Test
    fun `selectType should use re-engagement logic when consecutiveIgnores is at least 3`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 3)
        every { milestoneDetector.hasPendingMilestone(user) } returns true

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert — re-engagement path: milestone present → PROGRESS_MILESTONE
        assertEquals(NotificationTypeSelector.NotificationType.PROGRESS_MILESTONE, result)
    }

    @Test
    fun `selectReEngagementType should prioritize PROGRESS_MILESTONE`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 5)
        every { milestoneDetector.hasPendingMilestone(user) } returns true

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.PROGRESS_MILESTONE, result)
    }

    @Test
    fun `selectReEngagementType should return DUE_CARDS when milestone not pending and 5 or more due cards`() {
        // Arrange
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 4)
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 5)
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert
        assertEquals(NotificationTypeSelector.NotificationType.DUE_CARDS, result)
    }

    @Test
    fun `selectType should return WEEKLY_PREVIEW on Monday when user has activity`() {
        // This test only fires reliably when the test runs on a Monday.
        // We verify the code path works by checking the happy-path conditions; if not Monday
        // the test degrades gracefully to the fallback NONE path which is also verified.
        val user = createUser(currentStreak = 0)
        val schedule = createSchedule(user, consecutiveIgnores = 0, timezoneOffsetHrs = -4)
        val today = TEST_TODAY
        val weeklyReport = WeeklyReportResponse(
            cardsReviewed = 50,
            previousWeekCardsReviewed = 30,
            changePercent = 66.0,
            accuracyPercent = 80.0,
            wordsMastered = 5,
            totalStudyTimeMs = 120_000L,
            sessionsCount = 3,
            bestDay = null,
            weekStartDate = today.minusDays(6).toString(),
            weekEndDate = today.toString()
        )
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { milestoneDetector.hasPendingMilestone(user) } returns false
        every { analyticsService.getWeeklyReport(user.requireId()) } returns weeklyReport
        every { userProgressService.calculateProgressStats(user.requireId()) } returns createProgressStats(dueCards = 0)
        every { analyticsService.getDifficultWords(user.requireId(), minReviews = 3, limit = 1) } returns emptyList()
        every { featureAccessService.hasActivePremiumAccess(user) } returns false

        // Act
        val result = notificationTypeSelector.selectType(user, schedule)

        // Assert — on Monday with activity this is WEEKLY_PREVIEW; on other days it falls through to NONE
        val isMonday = today.dayOfWeek == java.time.DayOfWeek.MONDAY
        val expected = if (isMonday) NotificationTypeSelector.NotificationType.WEEKLY_PREVIEW
                       else NotificationTypeSelector.NotificationType.NONE
        assertEquals(expected, result)
    }

    // --- Factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        currentStreak: Int = 0,
        longestStreak: Int = 0,
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        subscriptionStatus = subscriptionStatus,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createSchedule(
        user: User,
        consecutiveIgnores: Int = 0,
        timezoneOffsetHrs: Int = 0
    ): NotificationSchedule = NotificationSchedule(
        id = 1L,
        user = user,
        consecutiveIgnores = consecutiveIgnores,
        timezoneOffsetHrs = timezoneOffsetHrs
    )

    private fun createProgressStats(
        totalWords: Int = 0,
        dueCards: Int = 0,
        level6Count: Int = 0
    ): ProgressStatsDto = ProgressStatsDto(
        totalWords = totalWords,
        dueCards = dueCards,
        level0Count = 0,
        level1Count = 0,
        level2Count = 0,
        level3Count = 0,
        level4Count = 0,
        level5Count = 0,
        level6Count = level6Count
    )

    private fun createDifficultWord(): DifficultWordResponse = DifficultWordResponse(
        wordId = 1L,
        wordText = "Haus",
        wordTranslation = "house",
        sourceLanguage = "de",
        targetLanguage = "en",
        totalReviews = 5,
        errorCount = 3,
        errorRate = 0.6
    )
}
