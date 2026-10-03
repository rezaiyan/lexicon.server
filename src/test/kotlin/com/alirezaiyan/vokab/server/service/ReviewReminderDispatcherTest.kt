package com.alirezaiyan.vokab.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.presentation.dto.NotificationResponse
import com.alirezaiyan.vokab.server.service.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ReviewReminderDispatcherTest {

    private lateinit var notificationScheduleRepository: NotificationScheduleRepository
    private lateinit var notificationContentBuilder: NotificationContentBuilder
    private lateinit var pushNotificationService: PushNotificationService

    private lateinit var dispatcher: ReviewReminderDispatcher
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        notificationScheduleRepository = mockk()
        notificationContentBuilder = mockk()
        pushNotificationService = mockk()

        dispatcher = ReviewReminderDispatcher(
            notificationScheduleRepository,
            notificationContentBuilder,
            pushNotificationService,
            clock = fixedClock(),
            meterRegistry = meterRegistry,
        )
    }

    @Test
    fun `should send review reminder when user has opt-in enabled`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user)
        val payload = createPayload()

        every { notificationScheduleRepository.findUsersForReviewReminders(any()) } returns listOf(schedule)
        every { notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER) } returns payload
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = payload.title,
                body = payload.body,
                data = payload.data
            )
        } returns listOf(NotificationResponse(success = true))
        every { notificationScheduleRepository.save(any()) } returns schedule

        // Act
        dispatcher.dispatchForCurrentHour()

        // Assert
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 1L,
                title = payload.title,
                body = payload.body,
                data = payload.data
            )
        }
        assertEquals(1.0, meterRegistry.counter("notifications.sent", "type", "REVIEW_REMINDER").count())
    }

    @Test
    fun `dispatchForCurrentHour targets the UTC hour of the injected clock`() {
        val user = createUser(id = 1L)
        val schedule = createSchedule(user)
        val dispatcherAt18 = ReviewReminderDispatcher(
            notificationScheduleRepository,
            notificationContentBuilder,
            pushNotificationService,
            clock = fixedClock(Instant.parse("2026-06-17T18:05:00Z")),
            meterRegistry = meterRegistry,
        )
        // Strict mock: only hour 18 is stubbed, any other hour throws
        every { notificationScheduleRepository.findUsersForReviewReminders(18) } returns listOf(schedule)
        every { notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER) } returns createPayload()
        every { pushNotificationService.sendNotificationToUser(1L, any(), any(), any()) } returns
            listOf(NotificationResponse(success = true))
        every { notificationScheduleRepository.save(any()) } returns schedule

        dispatcherAt18.dispatchForCurrentHour()

        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(1L, any(), any(), any()) }
    }

    @Test
    fun `should update lastSentDate after successful send`() {
        // Arrange
        val user = createUser(id = 1L)
        val schedule = createSchedule(user)
        val payload = createPayload()

        every { notificationScheduleRepository.findUsersForReviewReminders(any()) } returns listOf(schedule)
        every { notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER) } returns payload
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 1L, title = any(), body = any(), data = any()
            )
        } returns listOf(NotificationResponse(success = true))
        every { notificationScheduleRepository.save(any()) } returns schedule

        // Act
        dispatcher.dispatchForCurrentHour()

        // Assert
        verify {
            notificationScheduleRepository.save(match { it.lastSentDate == TEST_TODAY })
        }
    }

    @Test
    fun `should process nothing when no users have review reminders enabled`() {
        // Arrange
        every { notificationScheduleRepository.findUsersForReviewReminders(any()) } returns emptyList()

        // Act
        dispatcher.dispatchForCurrentHour()

        // Assert
        verify(exactly = 0) { notificationContentBuilder.build(any(), any()) }
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    @Test
    fun `should continue dispatching remaining users when one fails`() {
        // Arrange
        val user1 = createUser(id = 7L, email = "user1@test.com")
        val user2 = createUser(id = 8L, email = "user2@test.com")
        val schedule1 = createSchedule(user1, id = 1L)
        val schedule2 = createSchedule(user2, id = 2L)
        val payload = createPayload()

        every { notificationScheduleRepository.findUsersForReviewReminders(any()) } returns listOf(schedule1, schedule2)

        // First user throws, second user succeeds
        every { notificationContentBuilder.build(user1, NotificationType.REVIEW_REMINDER) } throws RuntimeException("Build failed")
        every { notificationContentBuilder.build(user2, NotificationType.REVIEW_REMINDER) } returns payload
        every {
            pushNotificationService.sendNotificationToUser(
                userId = 8L,
                title = any(),
                body = any(),
                data = any()
            )
        } returns listOf(NotificationResponse(success = true))
        every { notificationScheduleRepository.save(any()) } returns schedule2

        // Act
        dispatcher.dispatchForCurrentHour()

        // Assert — second user was still dispatched despite first user failing
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = 8L,
                title = any(),
                body = any(),
                data = any()
            )
        }
    }

    @Test
    fun `should dispatch to all users when multiple schedules exist`() {
        // Arrange
        val users = (10L..12L).map { id -> createUser(id = id, email = "user$id@test.com") }
        val schedules = users.mapIndexed { i, user -> createSchedule(user, id = i.toLong() + 10) }
        val payload = createPayload()

        every { notificationScheduleRepository.findUsersForReviewReminders(any()) } returns schedules
        every { notificationScheduleRepository.save(any()) } returnsArgument 0
        schedules.forEachIndexed { i, _ ->
            every { notificationContentBuilder.build(users[i], NotificationType.REVIEW_REMINDER) } returns payload
            every {
                pushNotificationService.sendNotificationToUser(
                    userId = users[i].id!!,
                    title = any(),
                    body = any(),
                    data = any()
                )
            } returns listOf(NotificationResponse(success = true))
        }

        // Act
        dispatcher.dispatchForCurrentHour()

        // Assert
        verify(exactly = 3) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any()) }
    }

    // ── Factory functions ──────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User"
    ): User = User(
        id = id,
        email = email,
        name = name,
        subscriptionStatus = SubscriptionStatus.FREE,
        currentStreak = 3,
        longestStreak = 3,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createSchedule(
        user: User,
        id: Long = 1L
    ): NotificationSchedule = NotificationSchedule(
        id = id,
        user = user,
        optimalSendHour = 18,
        consecutiveIgnores = 0
    )

    private fun createPayload(): NotificationPayload = NotificationPayload(
        title = "Time to Review!",
        body = "You have words due for review.",
        data = mapOf("type" to "review_reminder", "deep_link" to "vokab://review"),
        type = NotificationType.REVIEW_REMINDER
    )
}
