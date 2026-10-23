package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettings
import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class NotificationTimingServiceTest {

    private lateinit var reviewEventRepository: ReviewEventRepository
    private lateinit var userSettingsRepository: UserSettingsRepository
    private lateinit var notificationScheduleRepository: NotificationScheduleRepository

    private lateinit var notificationTimingService: NotificationTimingService

    @BeforeEach
    fun setUp() {
        reviewEventRepository = mockk()
        userSettingsRepository = mockk()
        notificationScheduleRepository = mockk()
        // Default: no settings stored (no reported timezone); tests override per user
        every { userSettingsRepository.findByUserId(any()) } returns null
        notificationTimingService = NotificationTimingService(
            reviewEventRepository,
            userSettingsRepository,
            notificationScheduleRepository,
            clock = fixedClock()
        )
    }

    // ── computeOptimalHour: not enough data ───────────────────────────────────

    @Test
    fun `should return confidence 0 when fewer than 10 review timestamps exist`() {
        // Arrange
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns listOf(
            epochMsAtUtcHour(14),
            epochMsAtUtcHour(14),
        )
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        val (_, confidence) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(0, confidence)
    }

    @Test
    fun `should fall back to UserSettings dailyReminderTime when fewer than 10 reviews exist`() {
        // Arrange
        val settings = createUserSettings(dailyReminderTime = "09:00")
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()
        every { userSettingsRepository.findByUserId(1L) } returns settings

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(9, hour)
    }

    @Test
    fun `should fall back to hour 18 when no review data and no user settings`() {
        // Arrange
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()
        every { userSettingsRepository.findByUserId(1L) } returns null

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(18, hour)
    }

    @Test
    fun `should fall back to hour 18 when UserSettings has no dailyReminderTime parseable as hour`() {
        // Arrange
        val settings = createUserSettings(dailyReminderTime = "invalid")
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()
        every { userSettingsRepository.findByUserId(1L) } returns settings

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(18, hour)
    }

    // ── computeOptimalHour: happy path ────────────────────────────────────────

    @Test
    fun `should return peak hour when enough review data exists`() {
        // Arrange — 30 timestamps all at UTC hour 20, plus some noise at other hours
        val timestamps = List(30) { epochMsAtUtcHour(20) } + List(5) { epochMsAtUtcHour(10) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(20, hour)
    }

    @Test
    fun `should return confidence 100 when peak count reaches high confidence threshold`() {
        // Arrange — 30 reviews all at same hour saturates confidence to 100
        val timestamps = List(30) { epochMsAtUtcHour(8) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (_, confidence) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(100, confidence)
    }

    @Test
    fun `should return partial confidence when peak count is below high confidence threshold`() {
        // Arrange — 15 reviews at hour 8 (15/30 * 100 = 50)
        val timestamps = List(15) { epochMsAtUtcHour(8) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (_, confidence) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(50, confidence)
    }

    @Test
    fun `should not exceed confidence 100 when peak count exceeds threshold`() {
        // Arrange — 60 reviews far above threshold
        val timestamps = List(60) { epochMsAtUtcHour(12) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (_, confidence) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertTrue(confidence <= 100)
    }

    @Test
    fun `should return hour in 0-23 range when enough data exists`() {
        // Arrange
        val timestamps = List(20) { epochMsAtUtcHour(23) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertTrue(hour in 0..23)
    }

    // ── computeOptimalHour: edge cases ────────────────────────────────────────

    @Test
    fun `should handle all reviews at the same hour correctly`() {
        // Arrange — exactly 10 reviews all at hour 7
        val timestamps = List(10) { epochMsAtUtcHour(7) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        // Assert
        assertEquals(7, hour)
    }

    @Test
    fun `should not query user settings when enough review data exists`() {
        // Arrange — sufficient data so fallback is not needed
        val timestamps = List(10) { epochMsAtUtcHour(14) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        notificationTimingService.computeOptimalHour(1L)

        // Assert
        verify(exactly = 0) { userSettingsRepository.findByUserId(any()) }
    }

    // ── deriveTimezoneOffset: not enough data ─────────────────────────────────

    @Test
    fun `should return 0 when fewer than 10 timestamps exist for timezone derivation`() {
        // Arrange
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns listOf(
            epochMsAtUtcHour(10),
        )

        // Act
        val offset = notificationTimingService.deriveTimezoneOffset(1L)

        // Assert
        assertEquals(0, offset)
    }

    @Test
    fun `should return 0 when no timestamps exist for timezone derivation`() {
        // Arrange
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()

        // Act
        val offset = notificationTimingService.deriveTimezoneOffset(1L)

        // Assert
        assertEquals(0, offset)
    }

    // ── deriveTimezoneOffset: happy path ──────────────────────────────────────

    @Test
    fun `should return offset within valid range of minus 12 to plus 14`() {
        // Arrange — reviews evenly spread across hours
        val timestamps = (0..11).flatMap { h -> List(3) { epochMsAtUtcHour(h) } }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val offset = notificationTimingService.deriveTimezoneOffset(1L)

        // Assert
        assertTrue(offset in -12..14)
    }

    @Test
    fun `should derive offset that maps UTC hour 3 into waking hours`() {
        // Arrange — reviews all at UTC hour 3; any offset that maps hour 3 into 6..23 is valid
        val timestamps = List(20) { epochMsAtUtcHour(3) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps

        // Act
        val offset = notificationTimingService.deriveTimezoneOffset(1L)

        // Assert — the derived offset must place UTC hour 3 inside local waking hours (6..23)
        val localHour = ((3 + offset) % 24 + 24) % 24
        assertTrue(localHour in 6..23)
    }

    // ── refreshSchedulesForAllUsers ───────────────────────────────────────────

    @Test
    fun `should save a new schedule for a user with no existing schedule`() {
        // Arrange
        val user = createUser(id = 1L)
        val timestamps = List(20) { epochMsAtUtcHour(18) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationScheduleRepository.save(any()) } answers { firstArg() }

        // Act
        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        // Assert
        verify(exactly = 1) { notificationScheduleRepository.save(any()) }
    }

    @Test
    fun `should update existing schedule when last computed is older than 7 days`() {
        // Arrange
        val user = createUser(id = 1L)
        val oldSchedule = createNotificationSchedule(
            user = user,
            lastComputedAt = TEST_NOW.minus(8, ChronoUnit.DAYS)
        )
        val timestamps = List(20) { epochMsAtUtcHour(18) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps
        every { notificationScheduleRepository.findByUser(user) } returns oldSchedule
        every { notificationScheduleRepository.save(any()) } answers { firstArg() }

        // Act
        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        // Assert
        verify(exactly = 1) { notificationScheduleRepository.save(any()) }
    }

    @Test
    fun `should skip user whose schedule was computed within last 7 days`() {
        // Arrange
        val user = createUser(id = 1L)
        val recentSchedule = createNotificationSchedule(
            user = user,
            lastComputedAt = TEST_NOW.minus(2, ChronoUnit.DAYS)
        )
        every { notificationScheduleRepository.findByUser(user) } returns recentSchedule

        // Act
        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        // Assert
        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
    }

    @Test
    fun `should persist optimal hour computed from review data`() {
        // Arrange
        val user = createUser(id = 1L)
        val timestamps = List(30) { epochMsAtUtcHour(21) }
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns timestamps
        every { notificationScheduleRepository.findByUser(user) } returns null
        val savedSlot = slot<NotificationSchedule>()
        every { notificationScheduleRepository.save(capture(savedSlot)) } answers { firstArg() }

        // Act
        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        // Assert
        assertEquals(21, savedSlot.captured.optimalSendHour)
    }

    @Test
    fun `should process remaining users even when one user throws exception`() {
        // Arrange
        val user1 = createUser(id = 1L)
        val user2 = createUser(id = 2L)
        val timestamps = List(10) { epochMsAtUtcHour(10) }

        every { notificationScheduleRepository.findByUser(user1) } throws RuntimeException("DB error")
        every { notificationScheduleRepository.findByUser(user2) } returns null
        every { reviewEventRepository.findReviewedAtByUserIdSince(2L, any()) } returns timestamps
        every { notificationScheduleRepository.save(any()) } answers { firstArg() }

        // Act — should not throw
        notificationTimingService.refreshSchedulesForAllUsers(listOf(user1, user2))

        // Assert — user2's schedule was still saved despite user1's failure
        verify(exactly = 1) { notificationScheduleRepository.save(any()) }
    }

    @Test
    fun `should process empty list without error`() {
        // Act & Assert — no exception thrown
        notificationTimingService.refreshSchedulesForAllUsers(emptyList())
        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
    }

    // ── device timezone ───────────────────────────────────────────────────────

    @Test
    fun `utcOffsetHours resolves an IANA zone with DST at the clock's instant`() {
        // TEST_NOW is in June: Berlin is on summer time (UTC+2)
        assertEquals(2, notificationTimingService.utcOffsetHours("Europe/Berlin"))
    }

    @Test
    fun `utcOffsetHours rounds half-hour zones down and ignores unknown ids`() {
        assertEquals(5, notificationTimingService.utcOffsetHours("Asia/Kolkata"))
        assertEquals(null, notificationTimingService.utcOffsetHours("Mars/Olympus"))
        assertEquals(null, notificationTimingService.utcOffsetHours(null))
    }

    @Test
    fun `fallback send hour converts the local reminder time to UTC with the reported zone`() {
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()
        every { userSettingsRepository.findByUserId(1L) } returns
            createUserSettings(dailyReminderTime = "19:00", timezone = "Asia/Tokyo")

        val (hour, _) = notificationTimingService.computeOptimalHour(1L)

        assertEquals(10, hour) // 19:00 in Tokyo (UTC+9)
    }

    @Test
    fun `new schedule uses the reported zone offset instead of guessing from reviews`() {
        val user = createUser(id = 1L)
        every { reviewEventRepository.findReviewedAtByUserIdSince(1L, any()) } returns emptyList()
        every { userSettingsRepository.findByUserId(1L) } returns createUserSettings(timezone = "America/New_York")
        every { notificationScheduleRepository.findByUser(user) } returns null
        val saved = slot<NotificationSchedule>()
        every { notificationScheduleRepository.save(capture(saved)) } answers { firstArg() }

        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        assertEquals(-4, saved.captured.timezoneOffsetHrs) // EDT in June
    }

    @Test
    fun `a fresh schedule still follows a DST change of the reported zone`() {
        val user = createUser(id = 1L)
        val schedule = createNotificationSchedule(user, lastComputedAt = TEST_NOW.minus(2, ChronoUnit.DAYS))
        schedule.timezoneOffsetHrs = 1
        every { userSettingsRepository.findByUserId(1L) } returns createUserSettings(timezone = "Europe/Berlin")
        every { notificationScheduleRepository.findByUser(user) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        notificationTimingService.refreshSchedulesForAllUsers(listOf(user))

        assertEquals(2, schedule.timezoneOffsetHrs)
        verify(exactly = 0) { reviewEventRepository.findReviewedAtByUserIdSince(any(), any()) }
    }

    @Test
    fun `applyTimezone sets the offset and invalidates the cached send hour`() {
        val user = createUser(id = 1L)
        val schedule = createNotificationSchedule(user, lastComputedAt = TEST_NOW)
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        notificationTimingService.applyTimezone(1L, "Asia/Tokyo")

        assertEquals(9, schedule.timezoneOffsetHrs)
        assertEquals(null, schedule.lastComputedAt)
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
    )

    private fun createUserSettings(
        userId: Long = 1L,
        dailyReminderTime: String = "18:00",
        notificationsEnabled: Boolean = true,
        timezone: String? = null,
    ): UserSettings = UserSettings(
        id = userId,
        dailyReminderTime = dailyReminderTime,
        notificationsEnabled = notificationsEnabled,
        timezone = timezone,
    )

    private fun createNotificationSchedule(
        user: User,
        lastComputedAt: Instant? = null,
        optimalSendHour: Int = 18,
    ): NotificationSchedule = NotificationSchedule(
        user = user,
        optimalSendHour = optimalSendHour,
        lastComputedAt = lastComputedAt,
    )

    /** Returns epoch milliseconds for a fixed point in time at the given UTC hour today. */
    private fun epochMsAtUtcHour(hour: Int): Long {
        return TEST_NOW
            .truncatedTo(ChronoUnit.DAYS)
            .atZone(ZoneOffset.UTC)
            .withHour(hour)
            .toInstant()
            .toEpochMilli()
    }
}
