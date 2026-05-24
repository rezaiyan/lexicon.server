package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationLog
import com.alirezaiyan.vokab.server.domain.repository.NotificationLogRepository
import com.alirezaiyan.vokab.server.service.EngagementSegmentService.EngagementSegment
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class EngagementSegmentServiceTest {

    private val notificationLogRepository: NotificationLogRepository = mockk()
    private lateinit var service: EngagementSegmentService

    @BeforeEach
    fun setUp() {
        service = EngagementSegmentService(notificationLogRepository)
    }

    // ── HOT ──────────────────────────────────────────────────────────────────────

    @Test
    fun `should return HOT when 2 of last 3 notifications were opened`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = Instant.now()),
            log(openedAt = Instant.now()),
            log(openedAt = null)
        )

        assertEquals(EngagementSegment.HOT, service.computeSegment(1L))
    }

    @Test
    fun `should return HOT when all 3 of last 3 notifications were opened`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = Instant.now()),
            log(openedAt = Instant.now()),
            log(openedAt = Instant.now())
        )

        assertEquals(EngagementSegment.HOT, service.computeSegment(1L))
    }

    // ── WARM ─────────────────────────────────────────────────────────────────────

    @Test
    fun `should return WARM when no notification history exists`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns emptyList()

        assertEquals(EngagementSegment.WARM, service.computeSegment(1L))
    }

    @Test
    fun `should return WARM when exactly 1 of last 3 notifications was opened`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = Instant.now()),
            log(openedAt = null),
            log(openedAt = null)
        )

        assertEquals(EngagementSegment.WARM, service.computeSegment(1L))
    }

    // ── COOLING ───────────────────────────────────────────────────────────────────

    @Test
    fun `should return COOLING when 0 of last 3 opened but last open was less than 7 days ago`() {
        val fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS)
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = null),
            log(openedAt = null),
            log(openedAt = null)
        )
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(1L)
        } returns log(openedAt = fiveDaysAgo)

        assertEquals(EngagementSegment.COOLING, service.computeSegment(1L))
    }

    // ── COLD ──────────────────────────────────────────────────────────────────────

    @Test
    fun `should return COLD when last open was between 7 and 30 days ago`() {
        val fifteenDaysAgo = Instant.now().minus(15, ChronoUnit.DAYS)
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = null),
            log(openedAt = null),
            log(openedAt = null)
        )
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(1L)
        } returns log(openedAt = fifteenDaysAgo)

        assertEquals(EngagementSegment.COLD, service.computeSegment(1L))
    }

    @Test
    fun `should return COLD when user has fewer than 3 sent notifications and never opened`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = null),
            log(openedAt = null)  // only 2 sent, never opened
        )
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(1L)
        } returns null

        assertEquals(EngagementSegment.COLD, service.computeSegment(1L))
    }

    // ── DORMANT ───────────────────────────────────────────────────────────────────

    @Test
    fun `should return DORMANT when last open was more than 30 days ago`() {
        val fortyDaysAgo = Instant.now().minus(40, ChronoUnit.DAYS)
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = null),
            log(openedAt = null),
            log(openedAt = null)
        )
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(1L)
        } returns log(openedAt = fortyDaysAgo)

        assertEquals(EngagementSegment.DORMANT, service.computeSegment(1L))
    }

    @Test
    fun `should return DORMANT when 3 or more sent notifications and user has never opened`() {
        every { notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(1L) } returns listOf(
            log(openedAt = null),
            log(openedAt = null),
            log(openedAt = null)
        )
        every {
            notificationLogRepository.findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(1L)
        } returns null

        assertEquals(EngagementSegment.DORMANT, service.computeSegment(1L))
    }

    // ── Factory ───────────────────────────────────────────────────────────────────

    private fun log(openedAt: Instant? = null): NotificationLog = NotificationLog(
        userId = 1L,
        notificationType = "DAILY_INSIGHT",
        openedAt = openedAt
    )
}
