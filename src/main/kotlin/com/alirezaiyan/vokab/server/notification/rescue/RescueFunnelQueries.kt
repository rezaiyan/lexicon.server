package com.alirezaiyan.vokab.server.notification.rescue

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** Which side of the experiment a plan landed on. */
enum class RescueArm {
    /** Pushed to the learner. */
    SENT,
    /** Planned for the 10% holdout and deliberately never sent: the baseline. */
    HOLDOUT,
    /** Planned but not sent: shadow mode, or the learner studied before their send hour. */
    UNSENT,
}

/** One arm × source × angle of the rescue funnel; returned24h = studied on the send (or planned) day or the next. */
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

    /** Rescues planned in the [days] days up to [today], newest arms first. */
    @Transactional(readOnly = true)
    fun funnel(days: Int, today: LocalDate): List<RescueFunnelRow> =
        jdbc.query(FUNNEL_SQL, mapOf("since" to today.minusDays(days.toLong()))) { rs, _ ->
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
            WITH r AS (
                SELECT cr.*,
                       -- a completion proves the push arrived even if its sent mark was lost
                       CASE WHEN cr.status = 'HOLDOUT' THEN 'HOLDOUT'
                            WHEN cr.sent_at IS NOT NULL OR cr.status = 'COMPLETED' THEN 'SENT'
                            ELSE 'UNSENT' END AS arm,
                       COALESCE(CAST(cr.sent_at AS DATE), cr.planned_for) AS from_day
                FROM comeback_rescue cr
                WHERE cr.planned_for >= :since
            )
            SELECT r.arm, r.source, r.angle,
                   COUNT(*)                                          AS planned,
                   COUNT(*) FILTER (WHERE r.arm = 'SENT')            AS sent,
                   COUNT(l.opened_at)                                AS opened,
                   COUNT(*) FILTER (WHERE r.status = 'COMPLETED')    AS completed,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM daily_activities d
                       WHERE d.user_id = r.user_id
                         AND d.activity_date BETWEEN r.from_day AND r.from_day + 1
                   ))                                                AS returned24h
            FROM r
            LEFT JOIN notification_log l ON l.id = r.notification_log_id
            GROUP BY r.arm, r.source, r.angle
            ORDER BY r.arm, r.source, r.angle
        """.trimIndent()
    }
}
