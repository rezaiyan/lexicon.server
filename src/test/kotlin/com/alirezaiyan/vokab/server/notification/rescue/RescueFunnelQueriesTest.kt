package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RescueFunnelQueriesTest {

    @Autowired lateinit var funnelQueries: RescueFunnelQueries
    @Autowired lateinit var rescueRepository: ComebackRescueRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private val today = LocalDate.of(2026, 10, 13)

    private fun userId(): Long =
        requireNotNull(userRepository.save(User(email = "funnel-${System.nanoTime()}@example.com", name = "F")).id)

    private fun rescue(
        userId: Long,
        day: LocalDate,
        status: RescueStatus,
        source: RescueSource = RescueSource.AI,
        angle: String = "rematch",
        sentAt: Instant? = null,
        logId: Long? = null,
    ) = rescueRepository.saveAndFlush(
        ComebackRescue(
            userId = userId, plannedFor = day, wordIds = "[1,2]", title = "t", body = "b", angle = angle,
            source = source, status = status, sentAt = sentAt, notificationLogId = logId,
        )
    )

    private fun log(userId: Long, opened: Boolean): Long = requireNotNull(
        jdbc.queryForObject(
            """INSERT INTO notification_log (user_id, notification_type, opened_at)
               VALUES (:userId, 'COMEBACK_RESCUE', CASE WHEN :opened THEN NOW() END) RETURNING id""",
            mapOf("userId" to userId, "opened" to opened),
            Long::class.java,
        )
    )

    private fun studied(userId: Long, day: LocalDate) {
        jdbc.update(
            "INSERT INTO daily_activities (user_id, activity_date) VALUES (:userId, :day)",
            mapOf("userId" to userId, "day" to day),
        )
    }

    private fun row(rows: List<RescueFunnelRow>, arm: RescueArm, source: RescueSource, angle: String) =
        rows.single { it.arm == arm && it.source == source && it.angle == angle }

    @Test
    fun `counts each arm, source and angle from plan to return`() {
        val sentDay = today.minusDays(2)
        val sentAt = Instant.parse("2026-10-11T18:00:00Z")

        // sent, opened, completed and back the next day
        val a = userId()
        rescue(a, sentDay, RescueStatus.COMPLETED, sentAt = sentAt, logId = log(a, opened = true))
        studied(a, sentDay.plusDays(1))
        // sent, ignored, never back
        val b = userId()
        rescue(b, sentDay, RescueStatus.SENT, sentAt = sentAt, logId = log(b, opened = false))
        // holdout who came back on their own on the planned day
        val c = userId()
        rescue(c, sentDay, RescueStatus.HOLDOUT)
        studied(c, sentDay)
        // shadow plan, never sent; returned two days later only (outside 24h)
        val d = userId()
        rescue(d, sentDay, RescueStatus.EXPIRED, source = RescueSource.FALLBACK)
        studied(d, sentDay.plusDays(2))
        // too old for a 7-day window
        rescue(userId(), today.minusDays(9), RescueStatus.SENT, sentAt = sentAt)

        val rows = funnelQueries.funnel(days = 7, today = today)

        assertEquals(RescueFunnelRow(RescueArm.SENT, RescueSource.AI, "rematch", 2, 2, 1, 1, 1), row(rows, RescueArm.SENT, RescueSource.AI, "rematch"))
        assertEquals(RescueFunnelRow(RescueArm.HOLDOUT, RescueSource.AI, "rematch", 1, 0, 0, 0, 1), row(rows, RescueArm.HOLDOUT, RescueSource.AI, "rematch"))
        assertEquals(RescueFunnelRow(RescueArm.UNSENT, RescueSource.FALLBACK, "rematch", 1, 0, 0, 0, 0), row(rows, RescueArm.UNSENT, RescueSource.FALLBACK, "rematch"))
    }
}
