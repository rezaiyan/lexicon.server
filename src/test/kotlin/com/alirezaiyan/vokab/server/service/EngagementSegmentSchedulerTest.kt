package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.service.EngagementSegmentService.EngagementSegment
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class EngagementSegmentSchedulerTest {

    private val notificationScheduleRepository: NotificationScheduleRepository = mockk()
    private val engagementSegmentService: EngagementSegmentService = mockk()
    private val notificationEngagementService: NotificationEngagementService = mockk()
    private val notificationAiAdvisor: NotificationAiAdvisor = mockk()
    private val userProgressService: UserProgressService = mockk()

    private lateinit var scheduler: EngagementSegmentScheduler

    @BeforeEach
    fun setUp() {
        scheduler = EngagementSegmentScheduler(
            notificationScheduleRepository,
            engagementSegmentService,
            notificationEngagementService,
            notificationAiAdvisor,
            userProgressService
        )
    }

    // ── saveSegment ───────────────────────────────────────────────────────────────

    @Test
    fun `saveSegment should update engagementSegment and save schedule`() {
        val user     = testUser()
        val schedule = testSchedule(user, segment = "WARM")
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        scheduler.saveSegment(1L, EngagementSegment.COLD)

        assertEquals("COLD", schedule.engagementSegment)
        verify(exactly = 1) { notificationScheduleRepository.save(schedule) }
    }

    @Test
    fun `saveSegment should do nothing when schedule does not exist`() {
        every { notificationScheduleRepository.findByUserId(99L) } returns null

        scheduler.saveSegment(99L, EngagementSegment.COLD)

        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
    }

    // ── needsAiRefresh ────────────────────────────────────────────────────────────

    @Test
    fun `needsAiRefresh should return true when aiDecidedAt is null`() {
        val schedule = testSchedule(testUser(), aiDecidedAt = null)
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule

        assert(scheduler.needsAiRefresh(1L))
    }

    @Test
    fun `needsAiRefresh should return true when aiDecidedAt is older than 7 days`() {
        val eightDaysAgo = Instant.now().minusSeconds(60L * 60 * 24 * 8)
        val schedule     = testSchedule(testUser(), aiDecidedAt = eightDaysAgo)
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule

        assert(scheduler.needsAiRefresh(1L))
    }

    @Test
    fun `needsAiRefresh should return false when aiDecidedAt is within 7 days`() {
        val twoDaysAgo = Instant.now().minusSeconds(60L * 60 * 24 * 2)
        val schedule   = testSchedule(testUser(), aiDecidedAt = twoDaysAgo)
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule

        assert(!scheduler.needsAiRefresh(1L))
    }

    @Test
    fun `needsAiRefresh should return false when schedule does not exist`() {
        every { notificationScheduleRepository.findByUserId(99L) } returns null

        assert(!scheduler.needsAiRefresh(99L))
    }

    // ── saveAiAdvice ──────────────────────────────────────────────────────────────

    @Test
    fun `saveAiAdvice should persist AI decision fields on schedule`() {
        val user     = testUser()
        val schedule = testSchedule(user)
        val advice   = NotificationAiAdvisor.AiAdvice(
            action = "motivate", intervalDays = 5, contentHint = "loss_aversion"
        )

        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        scheduler.saveAiAdvice(1L, advice)

        assertEquals("motivate", schedule.aiAction)
        assertEquals(5, schedule.aiIntervalDays)
        assertEquals("loss_aversion", schedule.aiContentHint)
        assert(schedule.aiDecidedAt != null)
        verify(exactly = 1) { notificationScheduleRepository.save(schedule) }
    }

    @Test
    fun `saveAiAdvice should do nothing when schedule does not exist`() {
        every { notificationScheduleRepository.findByUserId(99L) } returns null

        scheduler.saveAiAdvice(99L, NotificationAiAdvisor.AiAdvice("send", 3, null))

        verify(exactly = 0) { notificationScheduleRepository.save(any()) }
    }

    // ── refreshAll ────────────────────────────────────────────────────────────────

    @Test
    fun `refreshAll should call AI for COLD user with stale AI cache`() {
        val user     = testUser()
        val schedule = testSchedule(user, segment = "COLD", aiDecidedAt = null)
        val advice   = NotificationAiAdvisor.AiAdvice("motivate", 4, "curiosity")

        every { engagementSegmentService.computeSegment(1L) } returns EngagementSegment.COLD
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule
        every { notificationEngagementService.getEngagementStats(1L, windowDays = 7) } returns engagementStats()
        every { notificationEngagementService.getEngagementStats(1L, windowDays = 30) } returns engagementStats()
        every { notificationEngagementService.getDaysSinceLastOpen(1L) } returns 10L
        every { userProgressService.calculateProgressStats(user) } returns mockk(relaxed = true)
        every { notificationAiAdvisor.advise(any()) } returns advice

        scheduler.refreshAll(listOf(user))

        verify(exactly = 1) { notificationAiAdvisor.advise(any()) }
        verify(exactly = 2) { notificationScheduleRepository.save(schedule) }
    }

    @Test
    fun `refreshAll should skip AI for HOT user`() {
        val user     = testUser()
        val schedule = testSchedule(user, segment = "HOT")

        every { engagementSegmentService.computeSegment(1L) } returns EngagementSegment.HOT
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        scheduler.refreshAll(listOf(user))

        verify(exactly = 0) { notificationAiAdvisor.advise(any()) }
    }

    @Test
    fun `refreshAll should skip AI for COLD user when cache is fresh`() {
        val oneDayAgo = Instant.now().minusSeconds(60L * 60 * 24)
        val user      = testUser()
        val schedule  = testSchedule(user, segment = "COLD", aiDecidedAt = oneDayAgo)

        every { engagementSegmentService.computeSegment(1L) } returns EngagementSegment.COLD
        every { notificationScheduleRepository.findByUserId(1L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        scheduler.refreshAll(listOf(user))

        verify(exactly = 0) { notificationAiAdvisor.advise(any()) }
    }

    @Test
    fun `refreshAll should continue processing other users when one fails`() {
        val user1 = testUser(id = 1L, email = "a@test.com")
        val user2 = testUser(id = 2L, email = "b@test.com")

        every { engagementSegmentService.computeSegment(1L) } throws RuntimeException("boom")
        every { engagementSegmentService.computeSegment(2L) } returns EngagementSegment.WARM
        val schedule2 = testSchedule(user2, segment = "WARM")
        every { notificationScheduleRepository.findByUserId(2L) } returns schedule2
        every { notificationScheduleRepository.save(schedule2) } returns schedule2

        scheduler.refreshAll(listOf(user1, user2))

        // user2 was still processed despite user1 failing
        verify(exactly = 1) { notificationScheduleRepository.save(schedule2) }
    }

    // ── Factories ────────────────────────────────────────────────────────────────

    private fun testUser(id: Long = 1L, email: String = "test@example.com") = User(
        id               = id,
        email            = email,
        name             = "Test",
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak    = 5,
        longestStreak    = 20,
        active           = true,
        createdAt        = Instant.now().minusSeconds(60L * 60 * 24 * 90),
        updatedAt        = Instant.now()
    )

    private fun testSchedule(
        user: User,
        segment: String = "WARM",
        aiDecidedAt: Instant? = null
    ) = NotificationSchedule(
        id                = 1L,
        user              = user,
        engagementSegment = segment,
        aiDecidedAt       = aiDecidedAt
    )

    private fun engagementStats() = NotificationEngagementService.EngagementStats(
        totalSent          = 5,
        totalOpened        = 1,
        openRatePercent    = 20,
        consecutiveIgnores = 3
    )
}
