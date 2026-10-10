package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.notification.NotificationSchedule
import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import com.alirezaiyan.vokab.server.notification.NotificationEngagementService
import com.alirezaiyan.vokab.server.notification.NotificationTimingService
import io.mockk.justRun

class UserSettingsServiceTest {

    private lateinit var repo: UserSettingsRepository
    private lateinit var notificationScheduleRepository: NotificationScheduleRepository
    private lateinit var notificationEngagementService: NotificationEngagementService
    private lateinit var notificationTimingService: NotificationTimingService
    private lateinit var userSettingsService: UserSettingsService

    @BeforeEach
    fun setUp() {
        repo = mockk()
        notificationScheduleRepository = mockk()
        notificationEngagementService = mockk()
        notificationTimingService = mockk()
        userSettingsService = UserSettingsService(
            repo,
            notificationScheduleRepository,
            notificationEngagementService,
            mockk<UserRepository>().answerReferences(),
            notificationTimingService,
        )
    }

    // --- get ---

    @Test
    fun `get should return existing settings when found`() {
        // Arrange
        val user = createUser()
        val settings = createUserSettings(user = user, languageCode = "fr", themeMode = "DARK")
        every { repo.findByUser(user) } returns settings
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationEngagementService.getEngagementStats(user.id!!) } returns createEngagementStats()

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        assertEquals("fr", result.languageCode)
        assertEquals("DARK", result.themeMode)
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `get should create default settings when not found`() {
        // Arrange
        val user = createUser()
        val defaultSettings = createUserSettings(user = user)
        every { repo.findByUser(user) } returns null
        every { repo.save(any<UserSettings>()) } returns defaultSettings
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationEngagementService.getEngagementStats(user.id!!) } returns createEngagementStats()

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        assertEquals("en", result.languageCode)
        verify(exactly = 1) { repo.save(any<UserSettings>()) }
    }

    @Test
    fun `get should include schedule data when available`() {
        // Arrange
        val user = createUser()
        val settings = createUserSettings(user = user)
        val schedule = createNotificationSchedule(user = user, optimalSendHour = 9, dataConfidence = 75)
        every { repo.findByUser(user) } returns settings
        every { notificationScheduleRepository.findByUser(user) } returns schedule
        every { notificationEngagementService.getEngagementStats(user.id!!) } returns createEngagementStats()

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        assertEquals(9, result.optimalSendHour)
        assertEquals(75, result.dataConfidence)
    }

    @Test
    fun `get should include engagement stats when userId is available`() {
        // Arrange
        val user = createUser(id = 10L)
        val settings = createUserSettings(user = user)
        val engagementStats = NotificationEngagementService.EngagementStats(
            totalSent = 20,
            totalOpened = 10,
            openRatePercent = 50,
            consecutiveIgnores = 2
        )
        every { repo.findByUser(user) } returns settings
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationEngagementService.getEngagementStats(10L) } returns engagementStats

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        val stats = requireNotNull(result.engagementStats)
        assertEquals(50, stats.openRatePercent)
        assertEquals(2, stats.consecutiveIgnores)
    }

    @Test
    fun `get should return null engagementStats when engagement service fails`() {
        // Arrange
        val user = createUser(id = 10L)
        val settings = createUserSettings(user = user)
        every { repo.findByUser(user) } returns settings
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationEngagementService.getEngagementStats(10L) } throws RuntimeException("service down")

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        assertNull(result.engagementStats)
    }

    // --- update ---

    @Test
    fun `update should update settings fields and save`() {
        // Arrange
        val user = createUser()
        val existing = createUserSettings(user = user, languageCode = "en", themeMode = "AUTO")
        val dto = createSettingsDto(languageCode = "de", themeMode = "DARK", notificationsEnabled = false)
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing

        // Act
        val result = userSettingsService.update(user.requireId(), dto)

        // Assert
        assertEquals("de", result.languageCode)
        assertEquals("DARK", result.themeMode)
        assertEquals(false, result.notificationsEnabled)
        verify(exactly = 1) { repo.save(existing) }
    }

    @Test
    fun `update should store a reported timezone and apply it to the notification schedule`() {
        val user = createUser()
        val existing = createUserSettings(user = user)
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing
        every { notificationTimingService.utcOffsetHours("Europe/Berlin") } returns 2
        justRun { notificationTimingService.applyTimezone(1L, "Europe/Berlin") }

        val result = userSettingsService.update(user.requireId(), createSettingsDto(timezone = "Europe/Berlin"))

        assertEquals("Europe/Berlin", result.timezone)
        verify(exactly = 1) { notificationTimingService.applyTimezone(1L, "Europe/Berlin") }
    }

    @Test
    fun `update should keep the stored timezone when the client omits it`() {
        val user = createUser()
        val existing = createUserSettings(user = user).apply { timezone = "Asia/Tokyo" }
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing

        val result = userSettingsService.update(user.requireId(), createSettingsDto(timezone = null))

        assertEquals("Asia/Tokyo", result.timezone)
        verify(exactly = 0) { notificationTimingService.applyTimezone(any(), any()) }
    }

    @Test
    fun `update should ignore an unknown timezone id`() {
        val user = createUser()
        val existing = createUserSettings(user = user)
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing
        every { notificationTimingService.utcOffsetHours("Not/AZone") } returns null

        val result = userSettingsService.update(user.requireId(), createSettingsDto(timezone = "Not/AZone"))

        assertNull(result.timezone)
        verify(exactly = 0) { notificationTimingService.applyTimezone(any(), any()) }
    }

    @Test
    fun `updateTimezone should store the zone without touching other settings`() {
        val user = createUser()
        val existing = createUserSettings(user = user, languageCode = "fr")
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing
        every { notificationTimingService.utcOffsetHours("America/Chicago") } returns -5
        justRun { notificationTimingService.applyTimezone(1L, "America/Chicago") }

        userSettingsService.updateTimezone(user.requireId(), "America/Chicago")

        assertEquals("America/Chicago", existing.timezone)
        assertEquals("fr", existing.languageCode)
        verify(exactly = 1) { notificationTimingService.applyTimezone(1L, "America/Chicago") }
    }

    @Test
    fun `updateTimezone should do nothing when the zone is unchanged`() {
        val user = createUser()
        val existing = createUserSettings(user = user).apply { timezone = "America/Chicago" }
        every { repo.findByUser(user) } returns existing
        every { notificationTimingService.utcOffsetHours("America/Chicago") } returns -5

        userSettingsService.updateTimezone(user.requireId(), "America/Chicago")

        verify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { notificationTimingService.applyTimezone(any(), any()) }
    }

    @Test
    fun `updateTimezone should reject an unknown zone id`() {
        every { notificationTimingService.utcOffsetHours("Not/AZone") } returns null

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            userSettingsService.updateTimezone(1L, "Not/AZone")
        }
    }

    @Test
    fun `update should create new settings when none exist`() {
        // Arrange
        val user = createUser()
        val dto = createSettingsDto(languageCode = "es", themeMode = "LIGHT")
        every { repo.findByUser(user) } returns null
        every { repo.save(any<UserSettings>()) } answers { firstArg() }

        // Act
        val result = userSettingsService.update(user.requireId(), dto)

        // Assert
        assertEquals("es", result.languageCode)
        assertEquals("LIGHT", result.themeMode)
        verify(exactly = 1) { repo.save(any<UserSettings>()) }
    }

    @Test
    fun `update should persist dailyReminderTime and notificationFrequency`() {
        // Arrange
        val user = createUser()
        val existing = createUserSettings(user = user)
        val dto = createSettingsDto(dailyReminderTime = "09:00", notificationFrequency = "WEEKLY")
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing

        // Act
        val result = userSettingsService.update(user.requireId(), dto)

        // Assert
        assertEquals("09:00", result.dailyReminderTime)
        assertEquals("WEEKLY", result.notificationFrequency)
    }

    @Test
    fun `update should persist reviewRemindersEnabled`() {
        // Arrange
        val user = createUser()
        val existing = createUserSettings(user = user)
        val dto = createSettingsDto(reviewRemindersEnabled = false)
        every { repo.findByUser(user) } returns existing
        every { repo.save(existing) } returns existing

        // Act
        val result = userSettingsService.update(user.requireId(), dto)

        // Assert
        assertEquals(false, result.reviewRemindersEnabled)
        verify(exactly = 1) { repo.save(existing) }
    }

    @Test
    fun `get should return reviewRemindersEnabled in response`() {
        // Arrange
        val user = createUser()
        val settings = createUserSettings(user = user, reviewRemindersEnabled = false)
        every { repo.findByUser(user) } returns settings
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { notificationEngagementService.getEngagementStats(user.id!!) } returns createEngagementStats()

        // Act
        val result = userSettingsService.get(user.requireId())

        // Assert
        assertEquals(false, result.reviewRemindersEnabled)
    }

    // --- Factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com"
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createUserSettings(
        user: User,
        languageCode: String = "en",
        themeMode: String = "AUTO",
        notificationsEnabled: Boolean = true,
        dailyReminderTime: String = "18:00",
        notificationFrequency: String = "DAILY",
        reviewRemindersEnabled: Boolean = true
    ): UserSettings = UserSettings(
        user = user,
        languageCode = languageCode,
        themeMode = themeMode,
        notificationsEnabled = notificationsEnabled,
        dailyReminderTime = dailyReminderTime,
        notificationFrequency = notificationFrequency,
        reviewRemindersEnabled = reviewRemindersEnabled
    )

    private fun createNotificationSchedule(
        user: User,
        optimalSendHour: Int = 18,
        dataConfidence: Int = 0
    ): NotificationSchedule = NotificationSchedule(
        user = user,
        optimalSendHour = optimalSendHour,
        dataConfidence = dataConfidence
    )

    private fun createEngagementStats(
        totalSent: Int = 10,
        totalOpened: Int = 5,
        openRatePercent: Int = 50,
        consecutiveIgnores: Int = 0
    ): NotificationEngagementService.EngagementStats = NotificationEngagementService.EngagementStats(
        totalSent = totalSent,
        totalOpened = totalOpened,
        openRatePercent = openRatePercent,
        consecutiveIgnores = consecutiveIgnores
    )

    private fun createSettingsDto(
        languageCode: String = "en",
        themeMode: String = "AUTO",
        notificationsEnabled: Boolean = true,
        dailyReminderTime: String = "18:00",
        notificationFrequency: String = "DAILY",
        reviewRemindersEnabled: Boolean = true,
        timezone: String? = null
    ): SettingsDto = SettingsDto(
        languageCode = languageCode,
        themeMode = themeMode,
        notificationsEnabled = notificationsEnabled,
        dailyReminderTime = dailyReminderTime,
        notificationFrequency = notificationFrequency,
        reviewRemindersEnabled = reviewRemindersEnabled,
        timezone = timezone
    )
}
