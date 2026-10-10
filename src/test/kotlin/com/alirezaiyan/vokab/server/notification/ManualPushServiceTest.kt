package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class ManualPushServiceTest {

    private val pushTokenRepository: PushTokenRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val userSettingsRepository: UserSettingsRepository = mockk()
    private val pushNotificationService: PushNotificationService = mockk()
    private val notificationEngagementService: NotificationEngagementService = mockk()
    private val meterRegistry = SimpleMeterRegistry()

    private val service = ManualPushService(
        pushTokenRepository,
        userRepository,
        userSettingsRepository,
        pushNotificationService,
        notificationEngagementService,
        meterRegistry,
    )

    @BeforeEach
    fun setUp() {
        every { pushTokenRepository.findReachableUserIds() } returns listOf(1L, 2L, 3L)
        every { userSettingsRepository.findNotificationOptedOutUserIds() } returns setOf(3L)
        every { userRepository.existsById(any()) } answers { firstArg<Long>() in 1L..4L }
        every { userRepository.findByEmail(any()) } returns Optional.empty()
        every { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) } returns 99L
        justRun { notificationEngagementService.deleteLog(any()) }
    }

    private fun request(
        audience: ManualPushAudience = ManualPushAudience.USERS,
        userIds: List<Long> = emptyList(),
        emails: List<String> = emptyList(),
        respectOptOut: Boolean = true,
        dryRun: Boolean = false,
    ) = ManualPushRequest(
        audience = audience,
        userIds = userIds,
        emails = emails,
        title = "Hello",
        body = "World",
        data = mapOf("deep_link" to "lexicon://review"),
        respectOptOut = respectOptOut,
        dryRun = dryRun,
    )

    private fun stubSend(userId: Long, vararg results: NotificationResponse) {
        every {
            pushNotificationService.sendNotificationToUser(userId, "Hello", "World", any(), null, any())
        } returns results.toList()
    }

    @Test
    fun `sends to a user, logs it as MANUAL and puts the log id in the payload`() {
        val data = slot<Map<String, String>>()
        every {
            pushNotificationService.sendNotificationToUser(1L, "Hello", "World", capture(data), null, any())
        } returns listOf(NotificationResponse(success = true), NotificationResponse(success = true))

        val result = service.send(request(userIds = listOf(1L)))

        assertEquals(1, result.sent)
        assertEquals(ManualPushOutcome.SENT, result.recipients.single().outcome)
        assertEquals(2, result.recipients.single().devicesReached)
        assertEquals("99", data.captured[NOTIFICATION_LOG_ID_KEY])
        assertEquals("lexicon://review", data.captured["deep_link"])
        verify { notificationEngagementService.saveLog(1L, MANUAL_NOTIFICATION_TYPE, "Hello", "World", any()) }
        assertEquals(1.0, meterRegistry.counter("notifications.sent", "type", MANUAL_NOTIFICATION_TYPE).count())
    }

    @Test
    fun `dry run resolves outcomes without sending or logging`() {
        val result = service.send(request(userIds = listOf(1L, 3L, 4L, 5L), dryRun = true))

        assertEquals(
            listOf(
                ManualPushOutcome.WOULD_SEND, // reachable
                ManualPushOutcome.OPTED_OUT,  // notifications off
                ManualPushOutcome.NO_TOKENS,  // exists but no active device
                ManualPushOutcome.NOT_FOUND,  // no such user
            ),
            result.recipients.map { it.outcome },
        )
        assertEquals(1, result.sent)
        assertEquals(3, result.skipped)
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { notificationEngagementService.saveLog(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `overriding opt-out reaches users who turned notifications off`() {
        stubSend(3L, NotificationResponse(success = true))

        val result = service.send(request(userIds = listOf(3L), respectOptOut = false))

        assertEquals(ManualPushOutcome.SENT, result.recipients.single().outcome)
    }

    @Test
    fun `broadcast targets every reachable user except opted-out ones`() {
        stubSend(1L, NotificationResponse(success = true))
        stubSend(2L, NotificationResponse(success = true))

        val result = service.send(request(audience = ManualPushAudience.ALL))

        assertEquals(3, result.targeted)
        assertEquals(2, result.sent)
        assertEquals(1, result.skipped)
        verify(exactly = 0) { pushNotificationService.sendNotificationToUser(3L, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `undelivered push discards its log`() {
        stubSend(1L, NotificationResponse(success = false, error = "unregistered"))

        val result = service.send(request(userIds = listOf(1L)))

        val recipient = result.recipients.single()
        assertEquals(ManualPushOutcome.FAILED, recipient.outcome)
        assertEquals("unregistered", recipient.error)
        assertEquals(1, result.failed)
        verify { notificationEngagementService.deleteLog(99L) }
    }

    @Test
    fun `a throwing send is reported as failed and the rest still go out`() {
        every {
            pushNotificationService.sendNotificationToUser(1L, any(), any(), any(), any(), any())
        } throws IllegalStateException("firebase down")
        stubSend(2L, NotificationResponse(success = true))

        val result = service.send(request(userIds = listOf(1L, 2L)))

        assertEquals(listOf(ManualPushOutcome.FAILED, ManualPushOutcome.SENT), result.recipients.map { it.outcome })
        verify { notificationEngagementService.deleteLog(99L) }
    }

    @Test
    fun `an id and an email for the same account push once`() {
        val user = User(id = 1L, email = "a@example.com", name = "A")
        every { userRepository.findByEmail("a@example.com") } returns Optional.of(user)
        stubSend(1L, NotificationResponse(success = true))

        val result = service.send(request(userIds = listOf(1L), emails = listOf("a@example.com", "nobody@example.com")))

        assertEquals(2, result.targeted)
        assertEquals(ManualPushOutcome.SENT, result.recipients[0].outcome)
        assertNull(result.recipients[1].userId)
        assertEquals(ManualPushOutcome.NOT_FOUND, result.recipients[1].outcome)
        verify(exactly = 1) { pushNotificationService.sendNotificationToUser(1L, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `specific-user audience needs at least one target`() {
        assertThrows<IllegalArgumentException> { service.send(request()) }
    }
}
