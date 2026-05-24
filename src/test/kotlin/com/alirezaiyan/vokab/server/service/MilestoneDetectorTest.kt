package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class MilestoneDetectorTest {

    private lateinit var userProgressService: UserProgressService
    private lateinit var notificationScheduleRepository: NotificationScheduleRepository
    private val objectMapper: ObjectMapper = ObjectMapper()

    private lateinit var milestoneDetector: MilestoneDetector

    @BeforeEach
    fun setUp() {
        userProgressService = mockk()
        notificationScheduleRepository = mockk()
        milestoneDetector = MilestoneDetector(userProgressService, notificationScheduleRepository, objectMapper)
    }

    @Test
    fun `getPendingMilestone should return null when no schedule found`() {
        // Arrange
        val user = createUser()
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 15)
        every { notificationScheduleRepository.findByUser(user) } returns null

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNull(result)
    }

    @Test
    fun `getPendingMilestone should return words_added milestone when crossing 25 words threshold`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, lastMilestoneSnapshot = null)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 25)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNotNull(result)
        assertEquals("words_added", result!!.type)
        assertEquals(25L, result.value)
    }

    @Test
    fun `getPendingMilestone should return words_added milestone when crossing 100 words threshold`() {
        // Arrange
        val user = createUser()
        val snapshotJson = objectMapper.writeValueAsString(mapOf("total_words" to 99L, "mastered_words" to 0L, "longest_streak" to 0L))
        val schedule = createSchedule(user, lastMilestoneSnapshot = snapshotJson)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 100)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNotNull(result)
        assertEquals("words_added", result!!.type)
        assertEquals(100L, result.value)
    }

    @Test
    fun `getPendingMilestone should return words_mastered milestone when crossing mastered threshold`() {
        // Arrange
        val user = createUser()
        // Already past word milestones (e.g. snapshot has total_words >= all thresholds won't work cleanly,
        // but snapshot has total_words=5000 so none trigger). level6Count crosses 1 (mastered).
        val snapshotJson = objectMapper.writeValueAsString(mapOf("total_words" to 5000L, "mastered_words" to 0L, "longest_streak" to 0L))
        val schedule = createSchedule(user, lastMilestoneSnapshot = snapshotJson)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 5000, level6Count = 1)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNotNull(result)
        assertEquals("words_mastered", result!!.type)
        assertEquals(1L, result.value)
    }

    @Test
    fun `getPendingMilestone should return streak_record milestone when new streak record`() {
        // Arrange
        // User has currentStreak == longestStreak (new record); snapshot has a lower longest_streak
        val user = createUser(currentStreak = 10, longestStreak = 10)
        val snapshotJson = objectMapper.writeValueAsString(mapOf("total_words" to 5000L, "mastered_words" to 500L, "longest_streak" to 9L))
        val schedule = createSchedule(user, lastMilestoneSnapshot = snapshotJson)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 5000, level6Count = 500)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNotNull(result)
        assertEquals("streak_record", result!!.type)
        assertEquals(10L, result.value)
    }

    @Test
    fun `getPendingMilestone should return null when all milestones already seen`() {
        // Arrange
        val user = createUser(currentStreak = 5, longestStreak = 5)
        // Snapshot reflects current state — no threshold is being crossed
        val snapshotJson = objectMapper.writeValueAsString(
            mapOf("total_words" to 5000L, "mastered_words" to 500L, "longest_streak" to 5L)
        )
        val schedule = createSchedule(user, lastMilestoneSnapshot = snapshotJson)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 5000, level6Count = 500)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNull(result)
    }

    @Test
    fun `getPendingMilestone should return null when snapshot not parseable`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, lastMilestoneSnapshot = "not valid json {{{")
        // With unparseable snapshot, lastSnapshot becomes emptyMap, so prevTotal=0 and any totalWords>=10 triggers.
        // We use totalWords=0 to ensure nothing triggers.
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 0)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.getPendingMilestone(user)

        // Assert
        assertNull(result)
    }

    @Test
    fun `hasPendingMilestone should return true when milestone pending`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user, lastMilestoneSnapshot = null)
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 25)
        every { notificationScheduleRepository.findByUser(user) } returns schedule

        // Act
        val result = milestoneDetector.hasPendingMilestone(user)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `hasPendingMilestone should return false when no milestone pending`() {
        // Arrange
        val user = createUser()
        every { notificationScheduleRepository.findByUser(user) } returns null
        every { userProgressService.calculateProgressStats(user) } returns createProgressStats(totalWords = 0)

        // Act
        val result = milestoneDetector.hasPendingMilestone(user)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `recordMilestoneSnapshot should save snapshot to schedule`() {
        // Arrange
        val user = createUser()
        val schedule = createSchedule(user)
        val stats = createProgressStats(totalWords = 50, level6Count = 5)
        every { notificationScheduleRepository.findByUser(user) } returns schedule
        every { notificationScheduleRepository.save(any()) } returns schedule

        // Act
        milestoneDetector.recordMilestoneSnapshot(user, stats)

        // Assert
        verify(exactly = 1) { notificationScheduleRepository.save(match { it.lastMilestoneSnapshot != null }) }
        assertNotNull(schedule.lastMilestoneSnapshot)
        val parsed = objectMapper.readValue(schedule.lastMilestoneSnapshot, Map::class.java)
        assertEquals(50, parsed["total_words"])
        assertEquals(5, parsed["mastered_words"])
        assertEquals(0, parsed["longest_streak"])
    }

    @Test
    fun `recordMilestoneSnapshot should do nothing when no schedule`() {
        // Arrange
        val user = createUser()
        val stats = createProgressStats()
        every { notificationScheduleRepository.findByUser(user) } returns null

        // Act
        milestoneDetector.recordMilestoneSnapshot(user, stats)

        // Assert
        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
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
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createSchedule(
        user: User,
        lastMilestoneSnapshot: String? = null
    ): NotificationSchedule = NotificationSchedule(
        id = 1L,
        user = user,
        lastMilestoneSnapshot = lastMilestoneSnapshot
    )

    private fun createProgressStats(
        totalWords: Int = 0,
        dueCards: Int = 0,
        level0Count: Int = 0,
        level1Count: Int = 0,
        level2Count: Int = 0,
        level3Count: Int = 0,
        level4Count: Int = 0,
        level5Count: Int = 0,
        level6Count: Int = 0
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
}
