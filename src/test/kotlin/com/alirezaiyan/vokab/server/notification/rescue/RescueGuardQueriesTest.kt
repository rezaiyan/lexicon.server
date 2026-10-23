package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
class RescueGuardQueriesTest {

    @Autowired lateinit var guardQueries: RescueGuardQueries
    @Autowired lateinit var rescueRepository: ComebackRescueRepository
    @Autowired lateinit var autopilotRepository: RescueAutopilotRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private val since = Instant.parse("2026-10-01T00:00:00Z")

    private fun learner(notificationsOn: Boolean = true, frequency: String = "DAILY", tokenActive: Boolean? = true): Long {
        val id = requireNotNull(userRepository.save(User(email = "guard-${System.nanoTime()}@example.com", name = "G")).id)
        jdbc.update(
            """INSERT INTO user_settings (user_id, notifications_enabled, notification_frequency)
               VALUES (:id, :on, :frequency)""",
            mapOf("id" to id, "on" to notificationsOn, "frequency" to frequency),
        )
        if (tokenActive != null) {
            jdbc.update(
                "INSERT INTO push_tokens (user_id, token, platform, active) VALUES (:id, :token, 'ANDROID', :active)",
                mapOf("id" to id, "token" to "t-$id", "active" to tokenActive),
            )
        }
        return id
    }

    private fun rescued(userId: Long, sentAt: Instant?) {
        rescueRepository.saveAndFlush(
            ComebackRescue(
                userId = userId, plannedFor = LocalDate.of(2026, 10, 5), wordIds = "[1]", title = "t", body = "b",
                angle = "rematch", source = RescueSource.AI,
                status = if (sentAt == null) RescueStatus.HOLDOUT else RescueStatus.SENT, sentAt = sentAt,
            )
        )
    }

    @Test
    fun `counts rescued learners who turned notifications off or lost every token since the push`() {
        val sent = Instant.parse("2026-10-05T18:00:00Z")
        rescued(learner(), sent)                                   // still subscribed
        rescued(learner(notificationsOn = false), sent)            // turned notifications off
        rescued(learner(frequency = "OFF"), sent)                  // frequency off
        rescued(learner(tokenActive = false), sent)                // token deactivated (uninstall)
        rescued(learner(tokenActive = null), sent)                 // no token at all
        rescued(learner(notificationsOn = false), null)            // holdout, never pushed
        rescued(learner(notificationsOn = false), Instant.parse("2026-09-20T18:00:00Z")) // before the window

        assertEquals(4, guardQueries.optOutsAfterRescue(since))
    }

    @Test
    fun `the migration seeds one running autopilot row`() {
        val state = autopilotRepository.findById(RescueAutopilot.SINGLETON_ID).orElseThrow()

        assertNull(state.pausedUntil)
        assertEquals(1L, autopilotRepository.count())
    }
}
