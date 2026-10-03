package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.DailyActivity
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ProfileStatsServiceTest {

    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var wordRepository: WordRepository
    private lateinit var streakService: StreakService

    private lateinit var profileStatsService: ProfileStatsService
    private val knownUsers = mutableListOf<User>()

    @BeforeEach
    fun setUp() {
        dailyActivityRepository = mockk()
        wordRepository = mockk()
        streakService = mockk()
        profileStatsService = ProfileStatsService(dailyActivityRepository, wordRepository, streakService, clock = fixedClock(), userRepository = mockk<UserRepository>().answerReferences(knownUsers))
    }

    @Test
    fun `getProfileStats should return correct current streak`() {
        // Arrange
        val user = createUser(currentStreak = 5, longestStreak = 5)
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 7)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns emptyList()
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns emptyList()
        every { wordRepository.findLanguagePairsWithCount(user) } returns emptyList()

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        assertEquals(7, result.currentStreak)
    }

    @Test
    fun `getProfileStats should calculate longest streak from consecutive activities`() {
        // Arrange
        val user = createUser(currentStreak = 0, longestStreak = 0)
        val today = TEST_TODAY
        val activities = listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1)),
            createDailyActivity(user, today.minusDays(2)),
            createDailyActivity(user, today.minusDays(3)),
            createDailyActivity(user, today.minusDays(4))
        )
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 5)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns activities
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns emptyList()
        every { wordRepository.findLanguagePairsWithCount(user) } returns emptyList()

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        assertEquals(5, result.longestStreak)
    }

    @Test
    fun `getProfileStats should return weekly activity for last 7 days`() {
        // Arrange
        val user = createUser()
        val today = TEST_TODAY
        val yesterday = today.minusDays(1)
        val recentActivities = listOf(
            createDailyActivity(user, yesterday, reviewCount = 10)
        )
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 1)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns recentActivities
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns recentActivities
        every { wordRepository.findLanguagePairsWithCount(user) } returns emptyList()

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        assertEquals(7, result.weeklyActivity.size)
        val yesterdayActivity = result.weeklyActivity.find { it.date == yesterday.toString() }
        assertEquals(10, yesterdayActivity?.reviewCount)
    }

    @Test
    fun `getProfileStats should return empty activity count for days with no activity`() {
        // Arrange
        val user = createUser()
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 0)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns emptyList()
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns emptyList()
        every { wordRepository.findLanguagePairsWithCount(user) } returns emptyList()

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        assertEquals(7, result.weeklyActivity.size)
        result.weeklyActivity.forEach { day ->
            assertEquals(0, day.reviewCount)
        }
    }

    @Test
    fun `getProfileStats should return language pairs from words`() {
        // Arrange
        val user = createUser()
        val languagePairRows: List<Array<Any>> = listOf(
            arrayOf("en", "de", 42L),
            arrayOf("en", "fr", 17L)
        )
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 0)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns emptyList()
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns emptyList()
        every { wordRepository.findLanguagePairsWithCount(user) } returns languagePairRows

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        assertEquals(2, result.languages.size)
        val enDe = result.languages.find { it.sourceLanguage == "en" && it.targetLanguage == "de" }
        assertEquals(42, enDe?.wordCount)
        val enFr = result.languages.find { it.sourceLanguage == "en" && it.targetLanguage == "fr" }
        assertEquals(17, enFr?.wordCount)
    }

    @Test
    fun `getProfileStats should return longestStreak as max of stored and calculated`() {
        // Arrange
        // user.longestStreak = 10, currentStreak from service = 3, calculated from activities = 4
        val user = createUser(currentStreak = 3, longestStreak = 10)
        val today = TEST_TODAY
        val activities = listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1)),
            createDailyActivity(user, today.minusDays(2)),
            createDailyActivity(user, today.minusDays(3))
        )
        every { streakService.getUserStreak(user.id!!) } returns StreakInfo(currentStreak = 3)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns activities
        every { dailyActivityRepository.findRecentActivities(user, any()) } returns emptyList()
        every { wordRepository.findLanguagePairsWithCount(user) } returns emptyList()

        // Act
        val result = profileStatsService.getProfileStats(user.requireId())

        // Assert
        // max(4 calculated, 3 currentStreak, 10 stored) == 10
        assertEquals(10, result.longestStreak)
    }

    // --- Factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        currentStreak: Int = 0,
        longestStreak: Int = 0
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    ).also { knownUsers += it }

    private fun createDailyActivity(
        user: User,
        date: LocalDate,
        reviewCount: Int = 1
    ): DailyActivity = DailyActivity(
        user = user,
        activityDate = date,
        reviewCount = reviewCount
    )
}
