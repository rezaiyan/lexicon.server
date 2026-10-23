package com.alirezaiyan.vokab.server.notification.rescue

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Which side of the experiment a plan landed on, decided when it was planned (intent to treat).
 * A treated learner who studies before their send hour is never pushed but stays TREATED: dropping
 * them would leave organic returners only in the holdout and hide the rescue's real lift.
 */
enum class RescueArm {
    /** Planned to be sent (whether or not the push went out). */
    TREATED,
    /** The 10% holdout: planned, deliberately never sent. The baseline. */
    HOLDOUT,
}

/** One arm × source × angle of the rescue funnel; returned24h = studied on the planned day or the next. */
data class RescueFunnelRow(
    val arm: RescueArm,
    val source: RescueSource,
    val angle: String,
    val planned: Int,
    val sent: Int,
    val opened: Int,
    val completed: Int,
    val returned24h: Int,
)

@Component
class RescueFunnelQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** Rescues planned in the last [days] days up to and including [today]; today's return window is still open. */
    @Transactional(readOnly = true)
    fun funnel(days: Int, today: LocalDate): List<RescueFunnelRow> =
        jdbc.query(FUNNEL_SQL, mapOf("since" to today.minusDays(days.toLong() - 1))) { rs, _ ->
            RescueFunnelRow(
                arm = RescueArm.valueOf(rs.getString("arm")),
                source = RescueSource.valueOf(rs.getString("source")),
                angle = rs.getString("angle"),
                planned = rs.getInt("planned"),
                sent = rs.getInt("sent"),
                opened = rs.getInt("opened"),
                completed = rs.getInt("completed"),
                returned24h = rs.getInt("returned24h"),
            )
        }

    private companion object {
        val FUNNEL_SQL = """
            SELECT CASE WHEN r.status = 'HOLDOUT' THEN 'HOLDOUT' ELSE 'TREATED' END AS arm,
                   r.source, r.angle,
                   COUNT(*)                                                                      AS planned,
                   -- a completion proves the push arrived even if its sent mark was lost
                   COUNT(*) FILTER (WHERE r.sent_at IS NOT NULL OR r.status = 'COMPLETED')       AS sent,
                   COUNT(l.opened_at)                                                            AS opened,
                   COUNT(*) FILTER (WHERE r.status = 'COMPLETED')                                AS completed,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM daily_activities d
                       WHERE d.user_id = r.user_id
                         AND d.activity_date BETWEEN r.planned_for AND r.planned_for + 1
                   ))                                                                            AS returned24h
            FROM comeback_rescue r
            LEFT JOIN notification_log l ON l.id = r.notification_log_id
            WHERE r.planned_for >= :since
            GROUP BY 1, r.source, r.angle
            ORDER BY 1, r.source, r.angle
        """.trimIndent()
    }
}
