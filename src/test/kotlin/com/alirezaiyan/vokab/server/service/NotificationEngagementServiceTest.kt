package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationLog
import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.UserSettings
import com.alirezaiyan.vokab.server.domain.repository.NotificationLogRepository
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.domain.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class NotificationEngagementServiceTest {

    private lateinit var notificationLogRepository: NotificationLogRepository
    private lateinit var notificationScheduleRepository: NotificationScheduleRepository
    private lateinit var userSettingsRepository: UserSettingsRepository

    private lateinit var notificationEngagementService: NotificationEngagementService

    @BeforeEach
    fun setUp() {
        notificationLogRepository = mockk()
        notificationScheduleRepository = mockk()
        userSettingsRepository = mockk()
        notificationEngagementService = NotificationEngagementService(
            notificationLogRepository,
            notificationScheduleRepository,
            userSettingsRepository
        )
    }

    // --- recordOpen ---

    @Test
    fun `recordOpen should mark notification as opened`() {
        // Arrange
        val userId = 1L
        val log = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val savedLog = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val schedule = createSchedule(createUser(id = userId))
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns savedLog
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        // Act
        notificationEngagementService.recordOpen(userId, 10L)

        // Assert
        assertNotNull(log.openedAt)
        verify(exactly = 1) { notificationLogRepository.save(log) }
    }

    @Test
    fun `recordOpen should not update if notification already opened`() {
        // Arrange
        val userId = 1L
        val alreadyOpenedAt = Instant.now().minusSeconds(3600)
        val log = createNotificationLog(id = 10L, userId = userId, openedAt = alreadyOpenedAt)
        val schedule = createSchedule(createUser(id = userId))
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        // Act
        notificationEngagementService.recordOpen(userId, 10L)

        // Assert
        assertEquals(alreadyOpenedAt, log.openedAt)
        verify(exactly = 0) { notificationLogRepository.save(any()) }
    }

    @Test
    fun `recordOpen should not update if notification belongs to different user`() {
        // Arrange
        val requestingUserId = 1L
        val ownerUserId = 2L
        val log = createNotificationLog(id = 10L, userId = ownerUserId, openedAt = null)
        val schedule = createSchedule(createUser(id = requestingUserId))
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationScheduleRepository.findByUserId(requestingUserId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        // Act
        notificationEngagementService.recordOpen(requestingUserId, 10L)

        // Assert
        assertNull(log.openedAt)
        verify(exactly = 0) { notificationLogRepository.save(log) }
    }

    @Test
    fun `recordOpen should reset consecutiveIgnores to 0`() {
        // Arrange
        val userId = 1L
        val log = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val schedule = createSchedule(createUser(id = userId), consecutiveIgnores = 4)
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns log
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        // Act
        notificationEngagementService.recordOpen(userId, 10L)

        // Assert
        assertEquals(0, schedule.consecutiveIgnores)
        verify(exactly = 1) { notificationScheduleRepository.save(schedule) }
    }

    @Test
    fun `recordOpen should clear suppressedUntil`() {
        // Arrange
        val userId = 1L
        val log = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val schedule = createSchedule(createUser(id = userId), suppressedUntil = LocalDate.now().plusDays(5))
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns log
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        // Act
        notificationEngagementService.recordOpen(userId, 10L)

        // Assert
        assertNull(schedule.suppressedUntil)
    }

    @Test
    fun `recordOpen should handle missing schedule gracefully`() {
        // Arrange
        val userId = 1L
        val log = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns log
        every { notificationScheduleRepository.findByUserId(userId) } returns null

        // Act — should not throw
        notificationEngagementService.recordOpen(userId, 10L)

        // Assert
        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
    }

    // --- recordSend ---

    @Test
    fun `recordSend should increment consecutiveIgnores when previous not opened`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(1, schedule.consecutiveIgnores)
    }

    @Test
    fun `recordSend should not increment when no previous log`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns null
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(0, schedule.consecutiveIgnores)
    }

    @Test
    fun `recordSend should suppress for 1 day on first ignore`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 0; after incrementing = 1 → 1-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(1, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 2 days on second ignore`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 1; after incrementing = 2 → 2-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 1)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(2, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 3 days when 3 consecutive ignores`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 2; after incrementing = 3 → 3-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 2)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(3, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 7 days when 6 consecutive ignores`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 5; after incrementing = 6 → 7-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 5)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(6, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(7)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 14 days when 10 consecutive ignores`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 9; after incrementing = 10 → 14-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 9)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(10, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(14)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 30 days when 15 or more consecutive ignores`() {
        // Arrange
        val user = createUser(id = 1L)
        // consecutiveIgnores is 14; after incrementing = 15 → 30-day suppression
        val schedule = createSchedule(user, consecutiveIgnores = 14)
        val previousLog = createNotificationLog(userId = 1L, openedAt = null)
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns previousLog
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        assertEquals(15, schedule.consecutiveIgnores)
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should apply frequency suppression for EVERY_OTHER_DAY`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val settings = createUserSettings(notificationFrequency = "EVERY_OTHER_DAY")
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns null
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns settings

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should apply frequency suppression for WEEKLY`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val settings = createUserSettings(notificationFrequency = "WEEKLY")
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns null
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns settings

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(6)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    @Test
    fun `recordSend should suppress for 365 days for OFF frequency`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user, consecutiveIgnores = 0)
        val settings = createUserSettings(notificationFrequency = "OFF")
        every { notificationLogRepository.findTopByUserIdOrderBySentAtDesc(1L) } returns null
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { userSettingsRepository.findByUserId(1L) } returns settings

        // Act
        notificationEngagementService.recordSend(schedule, "DAILY_INSIGHT")

        // Assert
        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(365)
        assertEquals(expectedDate, schedule.suppressedUntil)
    }

    // --- saveLog ---

    @Test
    fun `saveLog should persist notification log entry`() {
        // Arrange
        val savedLog = createNotificationLog(id = 1L, userId = 5L)
        every { notificationLogRepository.save(any()) } returns savedLog

        // Act
        notificationEngagementService.saveLog(5L, "DAILY_INSIGHT", "Title", "Body", """{"type":"daily_insight"}""")

        // Assert
        verify(exactly = 1) { notificationLogRepository.save(any()) }
    }

    // --- getEngagementStats ---

    @Test
    fun `getEngagementStats should return correct open rate`() {
        // Arrange
        val userId = 1L
        val since = Instant.now().minusSeconds(60 * 60 * 24 * 30)
        val logs = listOf(
            createNotificationLog(userId = userId, openedAt = Instant.now()),
            createNotificationLog(userId = userId, openedAt = Instant.now()),
            createNotificationLog(userId = userId, openedAt = null),
            createNotificationLog(userId = userId, openedAt = null)
        )
        val schedule = createSchedule(createUser(id = userId), consecutiveIgnores = 2)
        every { notificationLogRepository.findRecentByUserId(userId, any()) } returns logs
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule

        // Act
        val result = notificationEngagementService.getEngagementStats(userId)

        // Assert
        assertEquals(4, result.totalSent)
        assertEquals(2, result.totalOpened)
        assertEquals(50, result.openRatePercent)
        assertEquals(2, result.consecutiveIgnores)
    }

    @Test
    fun `getEngagementStats should return zero open rate when no notifications`() {
        // Arrange
        val userId = 1L
        every { notificationLogRepository.findRecentByUserId(userId, any()) } returns emptyList()
        every { notificationScheduleRepository.findByUserId(userId) } returns null

        // Act
        val result = notificationEngagementService.getEngagementStats(userId)

        // Assert
        assertEquals(0, result.totalSent)
        assertEquals(0, result.totalOpened)
        assertEquals(0, result.openRatePercent)
        assertEquals(0, result.consecutiveIgnores)
    }

    // --- getAdminStats ---

    @Test
    fun `getAdminStats should aggregate stats correctly`() {
        // Arrange
        val typeBreakdownRows: List<Array<Any>> = listOf(
            arrayOf("DAILY_INSIGHT", 100L, 40L),
            arrayOf("STREAK_RISK", 50L, 25L)
        )
        every { notificationLogRepository.countBySentAtAfter(any()) } returns 150L
        every { notificationLogRepository.countBySentAtAfterAndOpenedAtIsNotNull(any()) } returns 65L
        every { notificationLogRepository.findTypeBreakdownSince(any()) } returns typeBreakdownRows
        every { notificationScheduleRepository.count() } returns 200L
        every { notificationScheduleRepository.countSuppressed3Day() } returns 10L
        every { notificationScheduleRepository.countSuppressed7Day() } returns 5L
        every { notificationScheduleRepository.countSuppressed14Day() } returns 2L
        every { notificationScheduleRepository.countSuppressed30Day() } returns 1L

        // Act
        val result = notificationEngagementService.getAdminStats()

        // Assert
        assertEquals(200L, result.totalActiveSchedules)
        assertEquals(150L, result.last7Days.totalSent)
        assertEquals(65L, result.last7Days.totalOpened)
        assertEquals(43, result.last7Days.openRatePercent) // (65/150)*100 = 43
        assertEquals(10L, result.suppressedUsers.day3)
        assertEquals(5L, result.suppressedUsers.day7)
        assertEquals(2L, result.suppressedUsers.day14)
        assertEquals(1L, result.suppressedUsers.day30)
        assertEquals(2, result.last7Days.typeBreakdown.size)
        val insightStats = result.last7Days.typeBreakdown["DAILY_INSIGHT"]
        assertEquals(100L, insightStats?.sent)
        assertEquals(40L, insightStats?.opened)
        assertEquals(40, insightStats?.openRate)
    }

    // --- recordOpen: segment reset ---

    @Test
    fun `recordOpen should reset engagementSegment to WARM`() {
        val userId = 1L
        val log    = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val schedule = createSchedule(createUser(id = userId)).apply { engagementSegment = "COLD" }
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns log
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        notificationEngagementService.recordOpen(userId, 10L)

        assertEquals("WARM", schedule.engagementSegment)
    }

    @Test
    fun `recordOpen should clear aiDecidedAt`() {
        val userId   = 1L
        val log      = createNotificationLog(id = 10L, userId = userId, openedAt = null)
        val schedule = createSchedule(createUser(id = userId)).apply {
            aiDecidedAt = Instant.now().minusSeconds(3600)
        }
        every { notificationLogRepository.findById(10L) } returns Optional.of(log)
        every { notificationLogRepository.save(log) } returns log
        every { notificationScheduleRepository.findByUserId(userId) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        notificationEngagementService.recordOpen(userId, 10L)

        assertNull(schedule.aiDecidedAt)
    }

    // --- getDaysSinceLastOpen ---

    @Test
    fun `getDaysSinceLastOpen should return days since last open`() {
        val userId     = 1L
        val fiveDaysAgo = Instant.now().minusSeconds(60L * 60 * 24 * 5)
        val log        = createNotificationLog(userId = userId, openedAt = fiveDaysAgo)
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId)
        } returns log

        val result = notificationEngagementService.getDaysSinceLastOpen(userId)

        assert(result != null && result >= 4L && result <= 6L) // allow clock drift
    }

    @Test
    fun `getDaysSinceLastOpen should return null when user has never opened a notification`() {
        val userId = 1L
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId)
        } returns null

        val result = notificationEngagementService.getDaysSinceLastOpen(userId)

        assertNull(result)
    }

    // --- Factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com"
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createSchedule(
        user: User,
        consecutiveIgnores: Int = 0,
        suppressedUntil: LocalDate? = null
    ): NotificationSchedule = NotificationSchedule(
        id = 1L,
        user = user,
        consecutiveIgnores = consecutiveIgnores,
        suppressedUntil = suppressedUntil
    )

    private fun createNotificationLog(
        id: Long = 0L,
        userId: Long = 1L,
        notificationType: String = "DAILY_INSIGHT",
        openedAt: Instant? = null
    ): NotificationLog = NotificationLog(
        id = id,
        userId = userId,
        notificationType = notificationType,
        title = "Test Title",
        body = "Test Body",
        openedAt = openedAt
    )

    private fun createUserSettings(
        notificationFrequency: String = "DAILY"
    ): UserSettings = UserSettings(
        notificationFrequency = notificationFrequency
    )
}
