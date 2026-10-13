package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class ComebackRescueServiceTest {

    private val repository: ComebackRescueRepository = mockk()
    private val guard: RescueGuard = mockk { every { paused() } returns false }

    private fun service(sendEnabled: Boolean = true) = ComebackRescueService(repository, fixedClock(), guard, sendEnabled)

    private fun rescue(status: RescueStatus, userId: Long = 1L) = ComebackRescue(
        id = 42L, userId = userId, plannedFor = TEST_TODAY, wordIds = "[1]", title = "t", body = "b",
        angle = "rematch", source = RescueSource.AI, status = status,
    )

    @Test
    fun `today's planned rescue is sendable when sending is enabled`() {
        every { repository.findByUserIdAndPlannedFor(1L, TEST_TODAY) } returns rescue(RescueStatus.PLANNED)

        assertEquals(42L, service().sendablePlan(1L)?.id)
    }

    @Test
    fun `nothing is sendable in shadow mode`() {
        every { repository.findByUserIdAndPlannedFor(1L, TEST_TODAY) } returns rescue(RescueStatus.PLANNED)

        assertNull(service(sendEnabled = false).sendablePlan(1L))
    }

    @Test
    fun `nothing is sendable while the autopilot is paused`() {
        every { repository.findByUserIdAndPlannedFor(1L, TEST_TODAY) } returns rescue(RescueStatus.PLANNED)
        every { guard.paused() } returns true

        assertNull(service().sendablePlan(1L))
    }

    @Test
    fun `the autopilot view shows a running pause and hides one that is over`() {
        every { guard.state() } returns RescueAutopilot(pausedUntil = TEST_TODAY.plusDays(2), reason = "2 opted out")
        val paused = service().autopilot()
        assertEquals(TEST_TODAY.plusDays(2), paused.pausedUntil)
        assertEquals("2 opted out", paused.reason)

        every { guard.state() } returns RescueAutopilot(pausedUntil = TEST_TODAY, reason = "2 opted out")
        val over = service(sendEnabled = false).autopilot()
        assertNull(over.pausedUntil)
        assertNull(over.reason)
        assertFalse(over.sendEnabled)
    }

    @Test
    fun `holdout, sent and expired plans are not sendable`() {
        listOf(RescueStatus.HOLDOUT, RescueStatus.SENT, RescueStatus.EXPIRED).forEach { status ->
            every { repository.findByUserIdAndPlannedFor(1L, TEST_TODAY) } returns rescue(status)
            assertNull(service().sendablePlan(1L), "status=$status")
        }
    }

    @Test
    fun `marking sent records the time and notification log`() {
        val r = rescue(RescueStatus.PLANNED)
        every { repository.findById(42L) } returns Optional.of(r)
        every { repository.save(r) } returns r

        service().markSent(42L, notificationLogId = 7L)

        assertEquals(RescueStatus.SENT, r.status)
        assertEquals(TEST_NOW, r.sentAt)
        assertEquals(7L, r.notificationLogId)
    }

    @Test
    fun `completing a sent rescue is idempotent`() {
        val r = rescue(RescueStatus.SENT)
        every { repository.findById(42L) } returns Optional.of(r)
        every { repository.save(r) } returns r

        assertTrue(service().complete(userId = 1L, rescueId = 42L))
        assertTrue(service().complete(userId = 1L, rescueId = 42L))

        assertEquals(RescueStatus.COMPLETED, r.status)
        assertEquals(TEST_NOW, r.completedAt)
        verify(exactly = 1) { repository.save(r) }
    }

    @Test
    fun `a pushed rescue whose sent mark was lost still completes, a holdout never does`() {
        listOf(RescueStatus.PLANNED, RescueStatus.EXPIRED).forEach { status ->
            val r = rescue(status)
            every { repository.findById(42L) } returns Optional.of(r)
            every { repository.save(r) } returns r

            service().complete(userId = 1L, rescueId = 42L)

            assertEquals(RescueStatus.COMPLETED, r.status)
        }

        val holdout = rescue(RescueStatus.HOLDOUT)
        every { repository.findById(42L) } returns Optional.of(holdout)
        service().complete(userId = 1L, rescueId = 42L)
        assertEquals(RescueStatus.HOLDOUT, holdout.status)
    }

    @Test
    fun `another user's or a missing rescue cannot be completed`() {
        every { repository.findById(42L) } returns Optional.of(rescue(RescueStatus.SENT, userId = 2L))
        every { repository.findById(43L) } returns Optional.empty()

        assertFalse(service().complete(userId = 1L, rescueId = 42L))
        assertFalse(service().complete(userId = 1L, rescueId = 43L))
    }
}
