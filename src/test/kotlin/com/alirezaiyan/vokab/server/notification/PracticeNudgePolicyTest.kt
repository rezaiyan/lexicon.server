package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.analytics.PracticeHistory
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PracticeNudgePolicyTest {

    private val learnerSignals: LearnerSignals = mockk()
    private val notificationLogRepository: NotificationLogRepository = mockk()
    private lateinit var policy: PracticeNudgePolicy

    @BeforeEach
    fun setUp() {
        // Default: no earlier nudge of either type
        every { notificationLogRepository.findTopByUserIdAndNotificationTypeOrderBySentAtDesc(any(), any()) } returns null
        policy = PracticeNudgePolicy(learnerSignals, notificationLogRepository, fixedClock())
    }

    // ── who is eligible ──────────────────────────────────────────────────────────

    @Test
    fun `should suggest nothing with too few words to practice`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastWordRushAt = daysAgo(3))

        assertNull(policy.pick(user, testSchedule(user), totalWords = PracticeNudgePolicy.MIN_WORDS - 1))
    }

    @Test
    fun `should suggest Word Rush to a regular player who has had a break`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastWordRushAt = daysAgo(3))

        assertEquals(NotificationType.WORD_RUSH, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should not suggest a mode used in the last day`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns
            history(lastWordRushAt = TEST_NOW.minus(Duration.ofHours(5)), lastListeningAt = TEST_NOW.minus(Duration.ofHours(2)))

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should not introduce modes during a new account's first days`() {
        val user = testUser(createdAt = TEST_NOW.minus(Duration.ofDays(1)))
        every { learnerSignals.practiceHistory(1L) } returns history()

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should introduce a never-used mode to an established learner`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history()

        assertEquals(NotificationType.WORD_RUSH, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    // ── preference ───────────────────────────────────────────────────────────────

    @Test
    fun `should prefer the mode the learner already uses over an introduction`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastListeningAt = daysAgo(4))

        assertEquals(NotificationType.LISTENING, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should prefer the regular mode unused for longer`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns
            history(lastWordRushAt = daysAgo(2), lastListeningAt = daysAgo(10))

        assertEquals(NotificationType.LISTENING, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should never repeat the previous push type`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastWordRushAt = daysAgo(3))
        val schedule = testSchedule(user, lastSentType = NotificationType.WORD_RUSH.name)

        // Word Rush is out; Listening gets its introduction instead
        assertEquals(NotificationType.LISTENING, policy.pick(user, schedule, totalWords = 40))
    }

    // ── cooldowns ────────────────────────────────────────────────────────────────

    @Test
    fun `should wait the regular cooldown after an answered nudge`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns
            history(lastWordRushAt = daysAgo(2), lastListeningAt = TEST_NOW.minus(Duration.ofHours(1)))
        nudge(NotificationType.WORD_RUSH, sentAt = daysAgo(2), opened = true)

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should suggest again once the regular cooldown has passed`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastWordRushAt = daysAgo(2))
        nudge(NotificationType.WORD_RUSH, sentAt = daysAgo(4), opened = true)

        assertEquals(NotificationType.WORD_RUSH, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should double the wait after a nudge that got no reaction`() {
        val user = testUser()
        // Played 20 days ago, so still a regular; nudged 4 days ago, not tapped, not played since
        every { learnerSignals.practiceHistory(1L) } returns
            history(lastWordRushAt = daysAgo(20), lastListeningAt = TEST_NOW.minus(Duration.ofHours(1)))
        nudge(NotificationType.WORD_RUSH, sentAt = daysAgo(4), opened = false)

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should treat playing after an untapped nudge as a reaction`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history(lastWordRushAt = daysAgo(3))
        nudge(NotificationType.WORD_RUSH, sentAt = daysAgo(4), opened = false)

        assertEquals(NotificationType.WORD_RUSH, policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should introduce a mode at most every two weeks`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } returns history()
        nudge(NotificationType.WORD_RUSH, sentAt = daysAgo(10), opened = true)
        nudge(NotificationType.LISTENING, sentAt = daysAgo(10), opened = true)

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    @Test
    fun `should suggest nothing when practice history is unavailable`() {
        val user = testUser()
        every { learnerSignals.practiceHistory(1L) } throws IllegalStateException("db down")

        assertNull(policy.pick(user, testSchedule(user), totalWords = 40))
    }

    // ── factories ────────────────────────────────────────────────────────────────

    private fun daysAgo(days: Long): Instant = TEST_NOW.minus(Duration.ofDays(days))

    private fun nudge(type: NotificationType, sentAt: Instant, opened: Boolean) {
        every { notificationLogRepository.findTopByUserIdAndNotificationTypeOrderBySentAtDesc(1L, type.name) } returns
            NotificationLog(
                id = 5L,
                userId = 1L,
                notificationType = type.name,
                title = "t",
                body = "b",
                sentAt = sentAt,
                openedAt = if (opened) sentAt.plusSeconds(60) else null,
            )
    }

    private fun history(
        lastWordRushAt: Instant? = null,
        lastListeningAt: Instant? = null,
        bestScore: Int = 0,
    ) = PracticeHistory(lastWordRushAt = lastWordRushAt, bestWordRushScore = bestScore, lastListeningAt = lastListeningAt)

    private fun testUser(createdAt: Instant = TEST_NOW.minus(Duration.ofDays(60))) = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        active = true,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun testSchedule(user: User, lastSentType: String? = null) =
        NotificationSchedule(id = 1L, user = user, lastSentType = lastSentType)
}
