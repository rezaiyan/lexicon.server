package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationChannel
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Weekly Telegram summary of the rescue autopilot, so nobody has to open the admin page to know it works. */
@Service
class RescueDigest(
    private val funnelQueries: RescueFunnelQueries,
    private val guardQueries: RescueGuardQueries,
    private val guard: RescueGuard,
    private val adminChannel: NotificationChannel,
    private val clock: Clock,
) {
    fun send() {
        adminChannel.send("📬 Comeback rescue, last $DAYS days", body())
    }

    private fun body(): String {
        // Last full week: today's plans haven't gone out yet
        val rows = funnelQueries.funnel(DAYS.toInt(), LocalDate.now(clock).minusDays(1))
        val optOuts = guardQueries.optOutsAfterRescue(Instant.now(clock).minus(Duration.ofDays(DAYS)))
        val state = guard.state()
        val status = state.pausedUntil
            ?.takeIf { state.pausedOn(LocalDate.now(clock)) }
            ?.let { "paused until $it (${state.reason})" }
            ?: "running"

        val treated = rows.filter { it.arm == RescueArm.TREATED }
        val holdout = rows.filter { it.arm == RescueArm.HOLDOUT }
        val activity = if (rows.isEmpty()) {
            "No rescues planned."
        } else {
            val sent = treated.sumOf { it.sent }
            val aiSent = treated.filter { it.source == RescueSource.AI }.sumOf { it.sent }
            listOf(
                "Sent $sent (AI copy $aiSent, template ${sent - aiSent}), " +
                    "opened ${treated.sumOf { it.opened }}, completed ${treated.sumOf { it.completed }}",
                "Back within 24h: pushed ${treated.sumOf { it.returned24h }}/${treated.sumOf { it.planned }}, " +
                    "holdout ${holdout.sumOf { it.returned24h }}/${holdout.sumOf { it.planned }}",
            ).joinToString("\n")
        }
        return listOf(
            activity,
            "Opt-outs after a rescue, these $DAYS days: $optOuts",
            "Autopilot: $status",
        ).joinToString("\n")
    }

    companion object {
        const val DAYS = 7L
    }
}
