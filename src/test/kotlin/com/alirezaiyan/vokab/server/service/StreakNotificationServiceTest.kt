package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.Platform
import com.alirezaiyan.vokab.server.domain.entity.PushToken
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.DailyInsightRepository
import com.alirezaiyan.vokab.server.domain.repository.NotificationLogRepository
import com.alirezaiyan.vokab.server.domain.repository.PushTokenRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StreakNotificationServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var dailyInsightRepository: DailyInsightRepository
    private lateinit var pushTokenRepository: PushTokenRepository
    private lateinit var streakService: StreakService
    private lateinit var openRouterService: OpenRouterService
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var userProgressService: UserProgressService
    private lateinit var notificationLogRepository: NotificationLogRepository

    private lateinit var streakNotificationService: StreakNotificationService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        dailyActivityRepository = mockk()
        dailyInsightRepository = mockk()
        pushTokenRepository = mockk()
        streakService = mockk()
        openRouterService = mockk()
        pushNotificationService = mockk()
        userProgressService = mockk()
        notificationLogRepository = mockk()

        streakNotificationService = StreakNotificationService(
            userRepository = userRepository,
            dailyActivityRepository = dailyActivityRepository,
            dailyInsightRepository = dailyInsightRepository,
            pushTokenRepository = pushTokenRepository,
            streakService = streakService,
            openRouterService = openRouterService,
            pushNotificationService = pushNotificationService,
            userProgressService = userProgressService,
            notificationLogRepository = notificationLogRepository,
            clock = fixedClock()
        )
    }

    // --- isMilestoneStreak ---

    @Test
    fun `should return true for early milestones`() {
        assertTrue(streakNotificationService.isMilestoneStreak(1))
        assertTrue(streakNotificationService.isMilestoneStreak(3))
        assertTrue(streakNotificationService.isMilestoneStreak(5))
    }

    @Test
    fun `should return true for power-of-10 milestones`() {
        assertTrue(streakNotificationService.isMilestoneStreak(10))
        assertTrue(streakNotificationService.isMilestoneStreak(20))
        assertTrue(streakNotificationService.isMilestoneStreak(50))
        assertTrue(streakNotificationService.isMilestoneStreak(100))
        assertTrue(streakNotificationService.isMilestoneStreak(200))
        assertTrue(streakNotificationService.isMilestoneStreak(300))
        assertTrue(streakNotificationService.isMilestoneStreak(500))
    }

    @Test
    fun `should return false for non-milestone streaks`() {
        assertFalse(streakNotificationService.isMilestoneStreak(4))
        assertFalse(streakNotificationService.isMilestoneStreak(6))
        assertFalse(streakNotificationService.isMilestoneStreak(7))
        assertFalse(streakNotificationService.isMilestoneStreak(15))
    }

    @Test
    fun `should return false for zero or negative streak`() {
        assertFalse(streakNotificationService.isMilestoneStreak(0))
        assertFalse(streakNotificationService.isMilestoneStreak(-1))
    }

    // --- sendStreakResetWarning ---

    @Test
    fun `should return false when user has no push tokens`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 5)
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns emptyList()

        // Act
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertFalse(result)
        verify(exactly = 0) { streakService.getUserStreak(any()) }
    }

    @Test
    fun `should return false when streak is no longer a milestone`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 5)
        val token = createPushToken(user = user)
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 4)

        // Act
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertFalse(result)
        verify(exactly = 0) { openRouterService.generateStreakResetWarning(any(), any(), any()) }
    }

    @Test
    fun `should return true and send notification when all conditions are met`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val token = createPushToken(user = user)
        val stats = createProgressStats()
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 10)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { openRouterService.generateStreakResetWarning(10, stats, user.name) } returns "Keep your streak!"
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
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertTrue(result)
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
    fun `should return false when all push notification responses fail`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val token = createPushToken(user = user)
        val stats = createProgressStats()
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 10)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { openRouterService.generateStreakResetWarning(10, stats, user.name) } returns "Keep going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(NotificationResponse(success = false, error = "Token expired"))

        // Act
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `should return false when push notification service throws exception`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val token = createPushToken(user = user)
        val stats = createProgressStats()
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 10)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { openRouterService.generateStreakResetWarning(10, stats, user.name) } returns "Keep going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } throws RuntimeException("Firebase connection failed")

        // Act
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `should return false when push notification service returns empty list`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val token = createPushToken(user = user)
        val stats = createProgressStats()
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 10)
        every { userProgressService.calculateProgressStats(user.requireId()) } returns stats
        every { openRouterService.generateStreakResetWarning(10, stats, user.name) } returns "Keep going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns emptyList()

        // Act
        val result = streakNotificationService.sendStreakResetWarning(user)

        // Assert
        assertFalse(result)
    }

    // --- processStreakResetNotifications ---

    @Test
    fun `should return count of successfully sent notifications`() {
        // Arrange
        val user1 = createUser(id = 1L, currentStreak = 10)
        val user2 = createUser(id = 2L, email = "user2@example.com", currentStreak = 5)
        val today = TEST_TODAY
        val todayStr = today.toString()
        val token1 = createPushToken(user = user1)
        val token2 = createPushToken(user = user2, id = 2L)
        val stats = createProgressStats()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user1, user2)
        every { dailyActivityRepository.existsByUserAndActivityDate(any(), today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user1) } returns listOf(token1)
        every { pushTokenRepository.findByUserAndActiveTrue(user2) } returns listOf(token2)
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(any(), todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(any(), "STREAK_RISK", any())
        } returns false
        every { streakService.getUserStreak(user1.id!!) } returns StreakInfo(currentStreak = 10)
        every { streakService.getUserStreak(user2.id!!) } returns StreakInfo(currentStreak = 5)
        every { userProgressService.calculateProgressStats(any()) } returns stats
        every { openRouterService.generateStreakResetWarning(any(), any(), any()) } returns "Keep going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = any(),
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(NotificationResponse(success = true))

        // Act
        val result = streakNotificationService.processStreakResetNotifications()

        // Assert
        assertEquals(2, result)
    }

    @Test
    fun `should handle exception for individual user and continue processing others`() {
        // Arrange
        val user1 = createUser(id = 1L, currentStreak = 10)
        val user2 = createUser(id = 2L, email = "user2@example.com", currentStreak = 5)
        val today = TEST_TODAY
        val todayStr = today.toString()
        val token1 = createPushToken(user = user1)
        val token2 = createPushToken(user = user2, id = 2L)
        val stats = createProgressStats()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user1, user2)
        every { dailyActivityRepository.existsByUserAndActivityDate(any(), today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user1) } returns listOf(token1)
        every { pushTokenRepository.findByUserAndActiveTrue(user2) } returns listOf(token2)
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(any(), todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(any(), "STREAK_RISK", any())
        } returns false
        // user1 causes exception during streakService call
        every { streakService.getUserStreak(user1.id!!) } throws RuntimeException("DB timeout")
        every { streakService.getUserStreak(user2.id!!) } returns StreakInfo(currentStreak = 5)
        every { userProgressService.calculateProgressStats(user2.requireId()) } returns stats
        every { openRouterService.generateStreakResetWarning(any(), any(), any()) } returns "Keep going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = user2.id!!,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        } returns listOf(NotificationResponse(success = true))

        // Act
        val result = streakNotificationService.processStreakResetNotifications()

        // Assert
        assertEquals(1, result)
    }

    // --- getUsersNeedingNotifications ---

    @Test
    fun `should filter out users who already had activity today`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns true
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(createPushToken(user))
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns false

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should filter out users without active push tokens`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns emptyList()
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns false

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should filter out users who already received an insight push today`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(createPushToken(user))
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns true
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns false

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should include users who meet all notification criteria`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(createPushToken(user))
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns false

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertEquals(1, result.size)
        assertEquals(user.id, result.first().id)
    }

    @Test
    fun `should filter out users with non-milestone streak`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 4)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(createPushToken(user))
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns false

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should filter out users who already received a streak risk notification today`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 10)
        val today = TEST_TODAY
        val todayStr = today.toString()
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(createPushToken(user))
        every { dailyInsightRepository.existsByUserAndDateAndSentViaPushTrue(user, todayStr) } returns false
        every {
            notificationLogRepository.existsByUserIdAndNotificationTypeAndSentAtAfter(user.id!!, "STREAK_RISK", any())
        } returns true

        // Act
        val result = streakNotificationService.getUsersNeedingNotifications()

        // Assert
        assertTrue(result.isEmpty())
    }

    // --- factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        currentStreak: Int = 5,
        longestStreak: Int = 10
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

    private fun createPushToken(
        user: User,
        id: Long = 1L,
        token: String = "fcm-token-${user.id}",
        platform: Platform = Platform.ANDROID,
        active: Boolean = true
    ): PushToken = PushToken(
        id = id,
        user = user,
        token = token,
        platform = platform,
        active = active,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createProgressStats(
        totalWords: Int = 100,
        dueCards: Int = 20
    ): ProgressStatsDto = ProgressStatsDto(
        totalWords = totalWords,
        dueCards = dueCards,
        level0Count = 10,
        level1Count = 20,
        level2Count = 20,
        level3Count = 20,
        level4Count = 10,
        level5Count = 10,
        level6Count = 10
    )
}
