package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.study.ProgressStatsDto
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.alirezaiyan.vokab.server.ai.AiService
import com.alirezaiyan.vokab.server.study.UserProgressService

class StreakReminderServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var pushNotificationService: PushNotificationService
    private lateinit var aiService: AiService
    private lateinit var userProgressService: UserProgressService
    private lateinit var streakReminderService: StreakReminderService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        dailyActivityRepository = mockk()
        pushNotificationService = mockk<PushNotificationService>()
        aiService = mockk()
        userProgressService = mockk()
        streakReminderService = StreakReminderService(
            userRepository,
            dailyActivityRepository,
            pushNotificationService,
            aiService,
            userProgressService,
            clock = fixedClock()
        )
    }

    @Test
    fun `findUsersNeedingReminder should return users with active streaks who have no activity today`() {
        val today = TEST_TODAY
        val user1 = createUser(id = 1L, currentStreak = 5, name = "User One")
        val user2 = createUser(id = 2L, currentStreak = 10, name = "User Two")
        val user3 = createUser(id = 3L, currentStreak = 0, name = "User Three")
        val user4 = createUser(id = 4L, currentStreak = 7, name = "User Four")

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user1, user2, user4)
        every { dailyActivityRepository.existsByUserAndActivityDate(user1, today) } returns false
        every { dailyActivityRepository.existsByUserAndActivityDate(user2, today) } returns false
        every { dailyActivityRepository.existsByUserAndActivityDate(user4, today) } returns true

        val result = streakReminderService.findUsersNeedingReminder()

        assert(result.size == 2)
        assert(result.contains(user1))
        assert(result.contains(user2))
        assert(!result.contains(user4))
        assert(!result.contains(user3))
    }

    @Test
    fun `findUsersNeedingReminder should return empty list when no users need reminders`() {
        val today = TEST_TODAY
        val user1 = createUser(id = 1L, currentStreak = 5, name = "User One")

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user1)
        every { dailyActivityRepository.existsByUserAndActivityDate(user1, today) } returns true

        val result = streakReminderService.findUsersNeedingReminder()

        assert(result.isEmpty())
    }

    @Test
    fun `sendReminderNotifications should send notifications to users needing reminders`() {
        val today = TEST_TODAY
        val user1 = createUser(id = 1L, currentStreak = 5, name = "Alice")
        val user2 = createUser(id = 2L, currentStreak = 10, name = "Bob")

        val progressStats1 = ProgressStatsDto(
            totalWords = 100,
            dueCards = 5,
            level0Count = 10,
            level1Count = 20,
            level2Count = 15,
            level3Count = 15,
            level4Count = 15,
            level5Count = 15,
            level6Count = 10
        )

        val progressStats2 = ProgressStatsDto(
            totalWords = 200,
            dueCards = 10,
            level0Count = 20,
            level1Count = 30,
            level2Count = 25,
            level3Count = 25,
            level4Count = 25,
            level5Count = 25,
            level6Count = 30
        )

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user1, user2)
        every { dailyActivityRepository.existsByUserAndActivityDate(user1, today) } returns false
        every { dailyActivityRepository.existsByUserAndActivityDate(user2, today) } returns false
        every { userProgressService.calculateProgressStats(user1.requireId()) } returns progressStats1
        every { userProgressService.calculateProgressStats(user2.requireId()) } returns progressStats2
        every {
            aiService.generateStreakReminderMessage(
                currentStreak = 5,
                userName = "Alice",
                progressStats = progressStats1
            )
        } returns "Hey Alice! Your 5-day streak is on fire! 🔥 Keep it going!"
        every {
            aiService.generateStreakReminderMessage(
                currentStreak = 10,
                userName = "Bob",
                progressStats = progressStats2
            )
        } returns "Bob, don't let your amazing 10-day streak slip away! 💪"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "Hey Alice! Your 5-day streak is on fire! 🔥 Keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        } returns listOf(NotificationResponse(success = true, messageId = "msg1"))
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 2L,
                title = "Don't lose your streak!",
                body = "Bob, don't let your amazing 10-day streak slip away! 💪",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "10"),
                category = NotificationCategory.USER
            )
        } returns listOf(NotificationResponse(success = true, messageId = "msg2"))

        streakReminderService.sendReminderNotifications()

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "Hey Alice! Your 5-day streak is on fire! 🔥 Keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        }
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 2L,
                title = "Don't lose your streak!",
                body = "Bob, don't let your amazing 10-day streak slip away! 💪",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "10"),
                category = NotificationCategory.USER
            )
        }
    }

    @Test
    fun `sendReminderNotifications should use fallback message when AI generation returns null`() {
        val today = TEST_TODAY
        val user = createUser(id = 1L, currentStreak = 5, name = "Alice")

        val progressStats = ProgressStatsDto(
            totalWords = 100,
            dueCards = 5,
            level0Count = 10,
            level1Count = 20,
            level2Count = 15,
            level3Count = 15,
            level4Count = 15,
            level5Count = 15,
            level6Count = 10
        )

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { userProgressService.calculateProgressStats(user.requireId()) } returns progressStats
        every {
            aiService.generateStreakReminderMessage(
                currentStreak = 5,
                userName = "Alice",
                progressStats = progressStats
            )
        } returns "You have a 5-day streak! 🔥 Complete your review today to keep it going!"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "You have a 5-day streak! 🔥 Complete your review today to keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        } returns listOf(NotificationResponse(success = true, messageId = "msg1"))

        streakReminderService.sendReminderNotifications()

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "You have a 5-day streak! 🔥 Complete your review today to keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        }
    }

    @Test
    fun `sendReminderNotifications should use fallback when AI generation fails`() {
        val today = TEST_TODAY
        val user = createUser(id = 1L, currentStreak = 5, name = "Alice")

        val progressStats = ProgressStatsDto(
            totalWords = 100,
            dueCards = 5,
            level0Count = 10,
            level1Count = 20,
            level2Count = 15,
            level3Count = 15,
            level4Count = 15,
            level5Count = 15,
            level6Count = 10
        )

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { userProgressService.calculateProgressStats(user.requireId()) } returns progressStats
        every {
            aiService.generateStreakReminderMessage(
                currentStreak = 5,
                userName = "Alice",
                progressStats = progressStats
            )
        } throws RuntimeException("AI service unavailable")
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "You have a 5-day streak. Complete your review today to keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        } returns listOf(NotificationResponse(success = true, messageId = "msg1"))

        streakReminderService.sendReminderNotifications()

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "You have a 5-day streak. Complete your review today to keep it going!",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        }
    }

    @Test
    fun `sendReminderNotifications should handle notification send failures gracefully`() {
        val today = TEST_TODAY
        val user = createUser(id = 1L, currentStreak = 5, name = "Alice")

        val progressStats = ProgressStatsDto(
            totalWords = 100,
            dueCards = 5,
            level0Count = 10,
            level1Count = 20,
            level2Count = 15,
            level3Count = 15,
            level4Count = 15,
            level5Count = 15,
            level6Count = 10
        )

        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns listOf(user)
        every { dailyActivityRepository.existsByUserAndActivityDate(user, today) } returns false
        every { userProgressService.calculateProgressStats(user.requireId()) } returns progressStats
        every {
            aiService.generateStreakReminderMessage(
                currentStreak = 5,
                userName = "Alice",
                progressStats = progressStats
            )
        } returns "Personalized message"
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "Personalized message",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        } returns listOf(NotificationResponse(success = false, error = "Failed to send"))

        streakReminderService.sendReminderNotifications()

        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = "Don't lose your streak!",
                body = "Personalized message",
                data = mapOf("type" to "streak_reminder", "currentStreak" to "5"),
                category = NotificationCategory.USER
            )
        }
    }

    @Test
    fun `sendReminderNotifications should return early when no users need reminders`() {
        every { userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0) } returns emptyList()

        streakReminderService.sendReminderNotifications()

        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { aiService.generateStreakReminderMessage(any(), any(), any()) }
    }

    private fun createUser(
        id: Long,
        currentStreak: Int,
        name: String,
        active: Boolean = true
    ): User {
        return User(
            id = id,
            email = "$name@example.com",
            name = name,
            currentStreak = currentStreak,
            longestStreak = currentStreak,
            active = active,
            createdAt = TEST_NOW,
            updatedAt = TEST_NOW
        )
    }


}

