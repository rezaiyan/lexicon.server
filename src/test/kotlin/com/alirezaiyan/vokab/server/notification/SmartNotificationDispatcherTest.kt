package com.alirezaiyan.vokab.server.notification

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.alirezaiyan.vokab.server.study.MilestoneDetector

class SmartNotificationDispatcherTest {

    private val notificationScheduleRepository: NotificationScheduleRepository = mockk()
    private val notificationTypeSelector: NotificationTypeSelector = mockk()
    private val notificationContentBuilder: NotificationContentBuilder = mockk()
    private val pushNotificationService: com.alirezaiyan.vokab.server.notification.PushNotificationService = mockk()
    private val milestoneDetector: MilestoneDetector = mockk()
    private val notificationEngagementService: NotificationEngagementService = mockk()
    private val dailyInsightService: com.alirezaiyan.vokab.server.ai.DailyInsightService = mockk(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var dispatcher: SmartNotificationDispatcher

    @BeforeEach
    fun setUp() {
        every { notificationTypeSelector.studiedToday(any()) } returns false
        dispatcher = dispatcherAt(TEST_NOW)
    }

    private fun dispatcherAt(now: Instant) = SmartNotificationDispatcher(
        notificationScheduleRepository,
        notificationTypeSelector,
        notificationContentBuilder,
        pushNotificationService,
        milestoneDetector,
        notificationEngagementService,
        dailyInsightService,
        clock = fixedClock(now),
        meterRegistry = meterRegistry,
    )

    // ── HOT/WARM — rule-based path ────────────────────────────────────────────────

    @Test
    fun `should send notification when type is selected and push succeeds`() {
        val user     = testUser(id = 1L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name, any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = any()) }
        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name, any()) }
        assertEquals(1.0, meterRegistry.counter("notifications.sent", "type", "DUE_CARDS").count())
    }

    @Test
    fun `should skip sending when type selector returns NONE`() {
        val user     = testUser(id = 2L)
        val schedule = testSchedule(user)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.NONE

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { notificationContentBuilder.build(any(), any(), any()) }
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `should not record send when push delivery fails`() {
        val user     = testUser(id = 3L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 3L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = false, error = "NOT_REGISTERED")
        )

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { notificationEngagementService.recordSend(any(), any(), any()) }
        assertEquals(1.0, meterRegistry.counter("notifications.failed", "type", "DUE_CARDS").count())
    }

    @Test
    fun `should not record send when push delivery returns empty list`() {
        val user     = testUser(id = 4L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.STREAK_RISK)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.STREAK_RISK
        every { notificationContentBuilder.build(user, NotificationType.STREAK_RISK, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 4L, title = any(), body = any(), data = any()) } returns emptyList()

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { notificationEngagementService.recordSend(any(), any(), any()) }
    }

    @Test
    fun `should record milestone snapshot on successful PROGRESS_MILESTONE send`() {
        val user     = testUser(id = 5L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.PROGRESS_MILESTONE)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.PROGRESS_MILESTONE
        every { notificationContentBuilder.build(user, NotificationType.PROGRESS_MILESTONE, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 5L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L
        justRun { milestoneDetector.recordMilestoneSnapshot(user) }

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { milestoneDetector.recordMilestoneSnapshot(user) }
    }

    @Test
    fun `should not record milestone snapshot for non-milestone notification types`() {
        val user     = testUser(id = 6L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 6L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { milestoneDetector.recordMilestoneSnapshot(any()) }
    }

    @Test
    fun `should process nothing when no schedules exist for current hour`() {
        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns emptyList()

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { notificationTypeSelector.selectType(any(), any()) }
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `should continue dispatching remaining users when one dispatch fails`() {
        val user1    = testUser(id = 7L, email = "a@test.com")
        val user2    = testUser(id = 8L, email = "b@test.com")
        val sched1   = testSchedule(user1, id = 1L)
        val sched2   = testSchedule(user2, id = 2L)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(sched1, sched2)
        every { notificationTypeSelector.selectType(user1, sched1) } throws RuntimeException("crash")
        every { notificationTypeSelector.selectType(user2, sched2) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user2, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 8L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(userId = 8L, title = any(), body = any(), data = any()) }
    }

    // ── COLD/DORMANT — AI branching ───────────────────────────────────────────────

    @Test
    fun `should set suppressedUntil and skip send when AI action is pause for COLD user`() {
        val user     = testUser(id = 10L)
        val schedule = testSchedule(user, segment = "COLD", aiAction = "pause", aiIntervalDays = 5)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationScheduleRepository.findByUserId(10L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        dispatcher.dispatchForCurrentHour()

        val expectedDate = TEST_TODAY.plusDays(5)
        assert(schedule.suppressedUntil == expectedDate)
        verify(exactly = 0) { notificationContentBuilder.build(any(), any(), any()) }
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `should send MOTIVATION type when AI action is motivate for DORMANT user`() {
        val user     = testUser(id = 11L)
        val schedule = testSchedule(user, segment = "DORMANT", aiAction = "motivate", aiContentHint = "fresh_start", aiIntervalDays = 7)
        val payload  = testPayload(type = NotificationType.MOTIVATION)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationContentBuilder.build(user, NotificationType.MOTIVATION, "fresh_start") } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 11L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(schedule, NotificationType.MOTIVATION.name, any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { notificationContentBuilder.build(user, NotificationType.MOTIVATION, "fresh_start") }
        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.MOTIVATION.name, any()) }
    }

    @Test
    fun `should fall through to rule-based selection when AI action is send for COLD user`() {
        val user     = testUser(id = 12L)
        val schedule = testSchedule(user, segment = "COLD", aiAction = "send", aiIntervalDays = 3)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 12L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { notificationTypeSelector.selectType(user, schedule) }
        verify(exactly = 1) { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) }
    }

    @Test
    fun `should fall through to rule-based selection when AI action is null for COLD user`() {
        val user     = testUser(id = 13L)
        val schedule = testSchedule(user, segment = "COLD", aiAction = null)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 13L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { notificationTypeSelector.selectType(user, schedule) }
    }

    @Test
    fun `should use default pause of 3 days when AI interval is null on pause action`() {
        val user     = testUser(id = 14L)
        val schedule = testSchedule(user, segment = "COLD", aiAction = "pause", aiIntervalDays = null)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationScheduleRepository.findByUserId(14L) } returns schedule
        every { notificationScheduleRepository.save(schedule) } returns schedule

        dispatcher.dispatchForCurrentHour()

        val expectedDate = TEST_TODAY.plusDays(3)
        assert(schedule.suppressedUntil == expectedDate)
    }

    @Test
    fun `should skip the AI pause for a COLD user who came back and studied today`() {
        val user     = testUser(id = 15L)
        val schedule = testSchedule(user, segment = "COLD", aiAction = "pause", aiIntervalDays = 5)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.studiedToday(user) } returns true
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.NONE

        dispatcher.dispatchForCurrentHour()

        assertEquals(null, schedule.suppressedUntil)
        verify(exactly = 1) { notificationTypeSelector.selectType(user, schedule) }
    }

    @Test
    fun `should refresh the insight silently for an active learner with nothing to notify`() {
        val user     = testUser(id = 16L)
        val schedule = testSchedule(user)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.studiedToday(user) } returns true
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.NONE

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { dailyInsightService.refreshInsightSilently(user) }
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    // ── Streak saver ──────────────────────────────────────────────────────────────

    @Test
    fun `streak saver sends STREAK_RISK to a streak holder who has not studied today`() {
        val user     = testUser(id = 20L, currentStreak = 6)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.STREAK_RISK)

        every { notificationScheduleRepository.findStreakSaverCandidates(any(), SmartNotificationDispatcher.MIN_STREAK_TO_SAVE) } returns listOf(schedule)
        every { notificationContentBuilder.build(user, NotificationType.STREAK_RISK, null) } returns payload
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L
        every { pushNotificationService.sendNotificationToUser(userId = 20L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(schedule, NotificationType.STREAK_RISK.name, 77L) }

        dispatcherAt(Instant.parse("2026-06-17T20:00:00Z")).dispatchStreakSaversForCurrentHour()

        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.STREAK_RISK.name, 77L) }
    }

    @Test
    fun `streak saver targets the timezones whose evening slot is the current UTC hour`() {
        val offsets = slot<Collection<Int>>()
        every { notificationScheduleRepository.findStreakSaverCandidates(capture(offsets), any()) } returns emptyList()

        dispatcherAt(Instant.parse("2026-06-17T13:00:00Z")).dispatchStreakSaversForCurrentHour()

        // 13:00 UTC is 21:00 at UTC+8 — the latest daytime hour before the UTC streak day ends
        assertEquals(listOf(8), offsets.captured.toList())
    }

    @Test
    fun `streak saver skips a user who already studied today`() {
        val user     = testUser(id = 21L, currentStreak = 6)
        val schedule = testSchedule(user)

        every { notificationScheduleRepository.findStreakSaverCandidates(any(), any()) } returns listOf(schedule)
        every { notificationTypeSelector.studiedToday(user) } returns true

        dispatcherAt(Instant.parse("2026-06-17T20:00:00Z")).dispatchStreakSaversForCurrentHour()

        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `streak saver does not warn twice in one day`() {
        val user     = testUser(id = 22L, currentStreak = 6)
        val schedule = testSchedule(user).apply {
            lastSentDate = TEST_TODAY
            lastSentType = NotificationType.STREAK_RISK.name
        }

        every { notificationScheduleRepository.findStreakSaverCandidates(any(), any()) } returns listOf(schedule)

        dispatcherAt(Instant.parse("2026-06-17T20:30:00Z")).dispatchStreakSaversForCurrentHour()

        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `every timezone gets a daytime streak saver slot`() {
        for (offset in -12..14) {
            val utcHour = SmartNotificationDispatcher.streakSaverUtcHour(offset)
            assertNotNull(utcHour, "offset $offset has no slot")
            val localHour = (utcHour!! + offset + 24) % 24
            assert(localHour in 8..21) { "offset $offset lands at local $localHour" }
        }
    }

    // ── Open tracking ─────────────────────────────────────────────────────────────

    @Test
    fun `should create log before sending and put its id into push data`() {
        val user     = testUser(id = 1L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)
        val sentData = slot<Map<String, String>>()

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L
        every { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = capture(sentData)) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }

        dispatcher.dispatchForCurrentHour()

        assertEquals("77", sentData.captured[NOTIFICATION_LOG_ID_KEY])
        assertEquals("vokab://review", sentData.captured["deep_link"])
        verifyOrder {
            notificationEngagementService.saveLog(1L, NotificationType.DUE_CARDS.name, any(), any(), any())
            pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = any())
            notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name, 77L)
        }
    }

    @Test
    fun `should delete the pre-created log when push delivery fails`() {
        val user     = testUser(id = 1L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 77L
        every { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = false, error = "NOT_REGISTERED")
        )
        justRun { notificationEngagementService.deleteLog(77L) }

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { notificationEngagementService.deleteLog(77L) }
        verify(exactly = 0) { notificationEngagementService.recordSend(any(), any(), any()) }
    }

    @Test
    fun `should still send without log id when log insert fails`() {
        val user     = testUser(id = 1L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.DUE_CARDS)
        val sentData = slot<Map<String, String>>()

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.DUE_CARDS
        every { notificationContentBuilder.build(user, NotificationType.DUE_CARDS, null) } returns payload
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } throws IllegalStateException("db down")
        every { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = capture(sentData)) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any(), any()) }

        dispatcher.dispatchForCurrentHour()

        assertFalse(sentData.captured.containsKey(NOTIFICATION_LOG_ID_KEY))
        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name, null) }
    }

    // ── Factories ─────────────────────────────────────────────────────────────────

    private fun testUser(
        id: Long = 1L,
        email: String = "test@example.com",
        currentStreak: Int = 3
    ) = User(
        id                 = id,
        email              = email,
        name               = "Test User",
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak      = currentStreak,
        longestStreak      = currentStreak,
        active             = true,
        createdAt          = TEST_NOW,
        updatedAt          = TEST_NOW
    )

    private fun testSchedule(
        user: User,
        id: Long = 1L,
        segment: String = "WARM",
        aiAction: String? = null,
        aiIntervalDays: Int? = null,
        aiContentHint: String? = null,
        consecutiveIgnores: Int = 0
    ) = NotificationSchedule(
        id                = id,
        user              = user,
        optimalSendHour   = 18,
        engagementSegment = segment,
        aiAction          = aiAction,
        aiIntervalDays    = aiIntervalDays,
        aiContentHint     = aiContentHint,
        consecutiveIgnores = consecutiveIgnores
    )

    private fun testPayload(
        title: String = "Test Title",
        body: String  = "Test Body",
        type: NotificationType = NotificationType.DUE_CARDS
    ) = NotificationPayload(
        title = title,
        body  = body,
        data  = mapOf("type" to type.name.lowercase(), "deep_link" to "vokab://review"),
        type  = type
    )
}
