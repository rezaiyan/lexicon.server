package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.notification.NotificationChannel
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RescueDigestTest {

    private val funnelQueries: RescueFunnelQueries = mockk()
    private val guardQueries: RescueGuardQueries = mockk()
    private val guard: RescueGuard = mockk()
    private val channel: NotificationChannel = mockk()
    private val digest = RescueDigest(funnelQueries, guardQueries, guard, channel, fixedClock())

    private val body = slot<String>()

    init {
        justRun { channel.send(any(), capture(body)) }
        every { guardQueries.optOutsAfterRescue(TEST_NOW.minus(Duration.ofDays(7))) } returns 1
        every { guard.state() } returns RescueAutopilot()
    }

    private fun row(arm: RescueArm, source: RescueSource, planned: Int, sent: Int, completed: Int, returned: Int) =
        RescueFunnelRow(arm, source, "rematch", planned, sent, opened = sent, completed, returned)

    @Test
    fun `sums last week's arms, sources and opt-outs into one message`() {
        every { funnelQueries.funnel(7, TEST_TODAY.minusDays(1)) } returns listOf(
            row(RescueArm.TREATED, RescueSource.AI, planned = 3, sent = 3, completed = 1, returned = 2),
            row(RescueArm.TREATED, RescueSource.FALLBACK, planned = 1, sent = 1, completed = 0, returned = 0),
            row(RescueArm.HOLDOUT, RescueSource.AI, planned = 2, sent = 0, completed = 0, returned = 1),
        )

        digest.send()

        val text = body.captured
        assertTrue("Sent 4" in text, text)
        assertTrue("AI copy 3, template 1" in text, text)
        assertTrue("completed 1" in text, text)
        assertTrue("Back within 24h: pushed 2/4, holdout 1/2" in text, text)
        assertTrue("Opt-outs after a rescue, these 7 days: 1" in text, text)
        assertTrue("running" in text, text)
    }

    @Test
    fun `a quiet week still reports, and shows a running pause`() {
        every { funnelQueries.funnel(7, TEST_TODAY.minusDays(1)) } returns emptyList()
        every { guard.state() } returns RescueAutopilot(pausedUntil = TEST_TODAY.plusDays(5), reason = "2 learners opted out")

        digest.send()

        val text = body.captured
        assertTrue("No rescues planned" in text, text)
        assertTrue("paused until ${TEST_TODAY.plusDays(5)}" in text, text)
        assertTrue("2 learners opted out" in text, text)
    }
}
