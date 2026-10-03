package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.DailyActivity
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.TimeZone

class StreakServiceTest {

    private lateinit var dailyActivityRepository: DailyActivityRepository
    private lateinit var userRepository: UserRepository
    private lateinit var streakService: StreakService

    @BeforeEach
    fun setUp() {
        dailyActivityRepository = mockk()
        userRepository = mockk()
        streakService = StreakService(dailyActivityRepository, userRepository, clock = fixedClock())
    }

    @Test
    fun `getUserStreak uses the UTC date of the clock, not the JVM default zone`() {
        // 23:30 UTC on June 17 is already June 18 in Berlin
        val lateEvening = StreakService(
            dailyActivityRepository, userRepository,
            clock = fixedClock(Instant.parse("2026-06-17T23:30:00Z"))
        )
        val user = createUser(id = 1L, currentStreak = 1, longestStreak = 1)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, LocalDate.of(2026, 6, 17))
        )

        val originalZone = TimeZone.getDefault()
        val streak = try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            lateEvening.getUserStreak(1L)
        } finally {
            TimeZone.setDefault(originalZone)
        }

        assertEquals(1, streak.currentStreak)
    }

    // --- recordActivity ---

    @Test
    fun `should create new DailyActivity and update streak when first activity today`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 0, longestStreak = 0)
        val today = TEST_TODAY
        val savedUser = createUser(id = 1L, currentStreak = 1, longestStreak = 1)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today)
        )
        every { userRepository.save(any()) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(1, result.currentStreak)
        verify(exactly = 1) { dailyActivityRepository.save(any<DailyActivity>()) }
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `should increment review count when activity already exists today`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 3, longestStreak = 5)
        val today = TEST_TODAY
        val existingActivity = createDailyActivity(user, today, reviewCount = 2)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.of(existingActivity)
        every { dailyActivityRepository.save(existingActivity) } returns existingActivity

        // Act
        val result = streakService.recordActivity(1L, count = 5)

        // Assert
        // Review count should be updated; user returned as-is (no streak recalculation)
        assertEquals(7, existingActivity.reviewCount)
        assertEquals(user, result)
        verify(exactly = 1) { dailyActivityRepository.save(existingActivity) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `should throw IllegalArgumentException when user not found`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            streakService.recordActivity(99L)
        }
    }

    @Test
    fun `should set streak to 1 on first ever activity`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 0, longestStreak = 0)
        val today = TEST_TODAY
        val savedUser = createUser(id = 1L, currentStreak = 1, longestStreak = 1)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today)
        )
        every { userRepository.save(any()) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(1, result.currentStreak)
        assertEquals(1, result.longestStreak)
    }

    @Test
    fun `should calculate consecutive streak correctly`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 2, longestStreak = 2)
        val today = TEST_TODAY
        val yesterday = today.minusDays(1)
        val savedUser = createUser(id = 1L, currentStreak = 3, longestStreak = 3)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, yesterday),
            createDailyActivity(user, yesterday.minusDays(1))
        )
        every { userRepository.save(any()) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(3, result.currentStreak)
    }

    @Test
    fun `should reset streak to 1 when yesterday has no activity`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 5, longestStreak = 10)
        val today = TEST_TODAY
        // Two days ago is the most recent activity — gap yesterday breaks the chain
        val twoDaysAgo = today.minusDays(2)
        val savedUser = createUser(id = 1L, currentStreak = 1, longestStreak = 10)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, twoDaysAgo)
        )
        every { userRepository.save(any()) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(1, result.currentStreak)
    }

    @Test
    fun `should update longest streak when new record is set`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 4, longestStreak = 4)
        val today = TEST_TODAY
        val savedUser = createUser(id = 1L, currentStreak = 5, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1)),
            createDailyActivity(user, today.minusDays(2)),
            createDailyActivity(user, today.minusDays(3)),
            createDailyActivity(user, today.minusDays(4))
        )
        every { userRepository.save(match { it.longestStreak == 5 }) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(5, result.longestStreak)
        verify(exactly = 1) { userRepository.save(match { it.longestStreak == 5 }) }
    }

    @Test
    fun `should not update longest streak when current streak is below record`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 0, longestStreak = 10)
        val today = TEST_TODAY
        val savedUser = createUser(id = 1L, currentStreak = 1, longestStreak = 10)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findByUserAndActivityDate(user, today) } returns Optional.empty()
        every { dailyActivityRepository.save(any<DailyActivity>()) } returns createDailyActivity(user, today)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today)
        )
        every { userRepository.save(match { it.longestStreak == 10 }) } returns savedUser

        // Act
        val result = streakService.recordActivity(1L)

        // Assert
        assertEquals(10, result.longestStreak)
        verify(exactly = 1) { userRepository.save(match { it.longestStreak == 10 }) }
    }

    // --- getUserStreak ---

    @Test
    fun `getUserStreak should throw when user not found`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            streakService.getUserStreak(99L)
        }
    }

    @Test
    fun `getUserStreak should return calculated streak`() {
        // Arrange
        val today = TEST_TODAY
        val user = createUser(id = 1L, currentStreak = 3, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1)),
            createDailyActivity(user, today.minusDays(2))
        )

        // Act
        val result = streakService.getUserStreak(1L)

        // Assert
        assertEquals(3, result.currentStreak)
    }

    @Test
    fun `getUserStreak should return 0 when no activities exist`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 5, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns emptyList()
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = streakService.getUserStreak(1L)

        // Assert
        assertEquals(0, result.currentStreak)
    }

    @Test
    fun `getUserStreak should return 0 when last activity was not today`() {
        // Arrange
        val today = TEST_TODAY
        val yesterday = today.minusDays(1)
        val user = createUser(id = 1L, currentStreak = 3, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, yesterday),
            createDailyActivity(user, yesterday.minusDays(1)),
            createDailyActivity(user, yesterday.minusDays(2))
        )
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = streakService.getUserStreak(1L)

        // Assert
        assertEquals(0, result.currentStreak)
    }

    @Test
    fun `getUserStreak should update user when calculated streak differs from stored value`() {
        // Arrange
        val today = TEST_TODAY
        // Stored currentStreak is 5, but activities only give streak of 2
        val user = createUser(id = 1L, currentStreak = 5, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1))
        )
        every { userRepository.save(match { it.currentStreak == 2 }) } answers { firstArg() }

        // Act
        streakService.getUserStreak(1L)

        // Assert
        verify(exactly = 1) { userRepository.save(match { it.currentStreak == 2 }) }
    }

    @Test
    fun `getUserStreak should not save user when streak is unchanged`() {
        // Arrange
        val today = TEST_TODAY
        val user = createUser(id = 1L, currentStreak = 2, longestStreak = 5)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user) } returns listOf(
            createDailyActivity(user, today),
            createDailyActivity(user, today.minusDays(1))
        )

        // Act
        streakService.getUserStreak(1L)

        // Assert
        verify(exactly = 0) { userRepository.save(any()) }
    }

    // --- factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        currentStreak: Int = 0,
        longestStreak: Int = 0
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createDailyActivity(
        user: User,
        activityDate: LocalDate,
        reviewCount: Int = 1
    ): DailyActivity = DailyActivity(
        user = user,
        activityDate = activityDate,
        reviewCount = reviewCount
    )
}
