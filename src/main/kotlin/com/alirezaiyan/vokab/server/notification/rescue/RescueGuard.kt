package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Component
class RescueGuardQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Learners pushed a rescue since [since] who have since turned notifications off or lost every
     * push token (uninstalled). A rescue only goes to someone with notifications on, so either state
     * now means they opted out after it.
     */
    @Transactional(readOnly = true)
    fun optOutsAfterRescue(since: Instant): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT r.user_id)
            FROM comeback_rescue r
            LEFT JOIN user_settings s ON s.user_id = r.user_id
            WHERE r.sent_at >= :since
              AND (s.notifications_enabled = false
                   OR s.notification_frequency = 'OFF'
                   OR NOT EXISTS (SELECT 1 FROM push_tokens pt WHERE pt.user_id = r.user_id AND pt.active))
            """.trimIndent(),
            mapOf("since" to Timestamp.from(since)),
            Int::class.java,
        ) ?: 0
}

/**
 * Keeps the rescue autopilot safe without anyone watching it: pauses sends for [PAUSE_DAYS] days
 * when rescued learners start opting out, resumes on its own afterwards, and tells the admin both
 * times over Telegram. Runs nightly before planning.
 */
@Service
class RescueGuard(
    private val repository: RescueAutopilotRepository,
    private val guardQueries: RescueGuardQueries,
    private val adminChannel: NotificationChannel,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun paused(): Boolean = state().pausedOn(LocalDate.now(clock))

    @Transactional(readOnly = true)
    fun state(): RescueAutopilot =
        repository.findById(RescueAutopilot.SINGLETON_ID).orElseGet { RescueAutopilot() }

    /** Not one transaction: each save commits before its Telegram alert, so an alert never describes unsaved state. */
    fun evaluate() {
        val state = state()
        val today = LocalDate.now(clock)
        val now = Instant.now(clock)
        val pausedUntil = state.pausedUntil
        if (pausedUntil != null && state.pausedOn(today)) return

        if (pausedUntil != null) {
            state.pausedUntil = null
            state.reason = null
            state.changedAt = now
            repository.save(state)
            alert("Comeback rescue resumed", "The $PAUSE_DAYS-day pause is over; rescues are sending again.")
            return
        }

        val windowStart = now.minus(Duration.ofDays(OPT_OUT_WINDOW_DAYS))
        val optOuts = guardQueries.optOutsAfterRescue(maxOf(windowStart, state.changedAt))
        if (optOuts >= OPT_OUTS_TO_PAUSE) {
            state.pausedUntil = today.plusDays(PAUSE_DAYS)
            state.reason = "$optOuts learners opted out after a rescue push"
            state.changedAt = now
            repository.save(state)
            alert(
                "Comeback rescue paused",
                "${state.reason}. Sends stop until ${state.pausedUntil} and then resume on their own.",
            )
        }
    }

    private fun alert(title: String, body: String) {
        logger.warn { "$title: $body" }
        runCatching { adminChannel.send("⚠️ $title", body) }
            .onFailure { e -> logger.warn(e) { "Rescue autopilot alert not delivered" } }
    }

    companion object {
        const val OPT_OUTS_TO_PAUSE = 2
        const val OPT_OUT_WINDOW_DAYS = 30L
        const val PAUSE_DAYS = 14L
    }
}
