package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.notification.NotificationChannel
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

class RescueGuardTest {

    private val repository: RescueAutopilotRepository = mockk()
    private val guardQueries: RescueGuardQueries = mockk()
    private val channel: NotificationChannel = mockk()
    private val guard = RescueGuard(repository, guardQueries, channel, fixedClock())

    private val state = RescueAutopilot(changedAt = TEST_NOW.minus(Duration.ofDays(60)))

    init {
        every { repository.findById(RescueAutopilot.SINGLETON_ID) } returns Optional.of(state)
        every { repository.save(state) } returns state
        justRun { channel.send(any(), any()) }
    }

    @Test
    fun `keeps running while rescued learners stay subscribed`() {
        every { guardQueries.optOutsAfterRescue(any()) } returns 1

        guard.evaluate()

        assertNull(state.pausedUntil)
        verify(exactly = 0) { channel.send(any(), any()) }
    }

    @Test
    fun `pauses for two weeks and alerts when rescued learners opt out`() {
        every { guardQueries.optOutsAfterRescue(any()) } returns RescueGuard.OPT_OUTS_TO_PAUSE

        guard.evaluate()

        assertEquals(TEST_TODAY.plusDays(RescueGuard.PAUSE_DAYS), state.pausedUntil)
        assertEquals(TEST_NOW, state.changedAt)
        assertTrue(guard.paused())
        verify(exactly = 1) { channel.send(match { "paused" in it }, any()) }
    }

    @Test
    fun `only counts opt-outs since the last change, at most the last 30 days`() {
        every { guardQueries.optOutsAfterRescue(TEST_NOW.minus(Duration.ofDays(RescueGuard.OPT_OUT_WINDOW_DAYS))) } returns 0
        guard.evaluate()

        val recent = TEST_NOW.minus(Duration.ofDays(2))
        state.changedAt = recent
        every { guardQueries.optOutsAfterRescue(recent) } returns 0
        guard.evaluate()

        verify(exactly = 1) { guardQueries.optOutsAfterRescue(recent) }
    }

    @Test
    fun `resumes on its own when the pause is over and says so`() {
        state.pausedUntil = TEST_TODAY
        state.reason = "2 opt-outs"
        every { guardQueries.optOutsAfterRescue(any()) } returns 0

        guard.evaluate()

        assertNull(state.pausedUntil)
        assertFalse(guard.paused())
        verify(exactly = 1) { channel.send(match { "resumed" in it }, any()) }
    }

    @Test
    fun `a pause still running is left alone`() {
        state.pausedUntil = TEST_TODAY.plusDays(3)

        guard.evaluate()

        assertTrue(guard.paused())
        verify(exactly = 0) { guardQueries.optOutsAfterRescue(any()) }
        verify(exactly = 0) { channel.send(any(), any()) }
    }

    @Test
    fun `a failed Telegram alert still keeps the pause`() {
        every { guardQueries.optOutsAfterRescue(any()) } returns RescueGuard.OPT_OUTS_TO_PAUSE
        every { channel.send(any(), any()) } throws IllegalStateException("telegram down")

        guard.evaluate()

        assertTrue(guard.paused())
        verify(exactly = 1) { repository.save(state) }
    }
}
