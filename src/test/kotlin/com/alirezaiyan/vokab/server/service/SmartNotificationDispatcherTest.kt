package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import com.alirezaiyan.vokab.server.service.NotificationTypeSelector.NotificationType
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class SmartNotificationDispatcherTest {

    private val notificationScheduleRepository: NotificationScheduleRepository = mockk()
    private val notificationTypeSelector: NotificationTypeSelector = mockk()
    private val notificationContentBuilder: NotificationContentBuilder = mockk()
    private val pushNotificationService: com.alirezaiyan.vokab.server.service.push.PushNotificationService = mockk()
    private val milestoneDetector: MilestoneDetector = mockk()
    private val userProgressService: UserProgressService = mockk()
    private val notificationEngagementService: NotificationEngagementService = mockk()
    private val objectMapper: ObjectMapper = ObjectMapper()

    private lateinit var dispatcher: SmartNotificationDispatcher

    @BeforeEach
    fun setUp() {
        dispatcher = SmartNotificationDispatcher(
            notificationScheduleRepository,
            notificationTypeSelector,
            notificationContentBuilder,
            pushNotificationService,
            milestoneDetector,
            userProgressService,
            notificationEngagementService,
            objectMapper
        )
    }

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
        justRun { notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(userId = 1L, title = any(), body = any(), data = any()) }
        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.DUE_CARDS.name) }
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

        verify(exactly = 0) { notificationEngagementService.recordSend(any(), any()) }
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

        verify(exactly = 0) { notificationEngagementService.recordSend(any(), any()) }
    }

    @Test
    fun `should record milestone snapshot on successful PROGRESS_MILESTONE send`() {
        val user     = testUser(id = 5L)
        val schedule = testSchedule(user)
        val payload  = testPayload(type = NotificationType.PROGRESS_MILESTONE)
        val stats    = testProgressStats()

        every { notificationScheduleRepository.findUsersToNotifyAtHour(any()) } returns listOf(schedule)
        every { notificationTypeSelector.selectType(user, schedule) } returns NotificationType.PROGRESS_MILESTONE
        every { notificationContentBuilder.build(user, NotificationType.PROGRESS_MILESTONE, null) } returns payload
        every { pushNotificationService.sendNotificationToUser(userId = 5L, title = any(), body = any(), data = any()) } returns listOf(
            NotificationResponse(success = true)
        )
        justRun { notificationEngagementService.recordSend(any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit
        every { userProgressService.calculateProgressStats(user) } returns stats
        justRun { milestoneDetector.recordMilestoneSnapshot(user, stats) }

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { milestoneDetector.recordMilestoneSnapshot(user, stats) }
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
        justRun { notificationEngagementService.recordSend(any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 0) { milestoneDetector.recordMilestoneSnapshot(any(), any()) }
        verify(exactly = 0) { userProgressService.calculateProgressStats(any()) }
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
        justRun { notificationEngagementService.recordSend(any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

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

        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(5)
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
        justRun { notificationEngagementService.recordSend(schedule, NotificationType.MOTIVATION.name) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

        dispatcher.dispatchForCurrentHour()

        verify(exactly = 1) { notificationContentBuilder.build(user, NotificationType.MOTIVATION, "fresh_start") }
        verify(exactly = 1) { notificationEngagementService.recordSend(schedule, NotificationType.MOTIVATION.name) }
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
        justRun { notificationEngagementService.recordSend(any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

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
        justRun { notificationEngagementService.recordSend(any(), any()) }
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns Unit

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

        val expectedDate = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3)
        assert(schedule.suppressedUntil == expectedDate)
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
        createdAt          = Instant.now(),
        updatedAt          = Instant.now()
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

    private fun testProgressStats() = ProgressStatsDto(
        totalWords  = 50,
        dueCards    = 5,
        level0Count = 0,
        level1Count = 5,
        level2Count = 10,
        level3Count = 10,
        level4Count = 10,
        level5Count = 10,
        level6Count = 5
    )
}
