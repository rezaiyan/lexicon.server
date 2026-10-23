package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class NotificationControllerHandlerTest {

    private val engagementService: NotificationEngagementService = mockk()
    private val handler = NotificationControllerHandler(
        pushTokenService = mockk<PushTokenService>(),
        pushNotificationService = mockk<PushNotificationService>(),
        notificationEngagementService = engagementService,
    )
    private val user = User(id = 7L, email = "open@example.com", name = "Open")

    @Test
    fun `markOpened returns 200 when the log belongs to the caller`() {
        every { engagementService.recordOpen(7L, 42L) } returns true

        val response = handler.markOpened(user.requireId(), 42L)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.success)
    }

    @Test
    fun `markOpened throws not-found when the log is unknown or someone else's`() {
        every { engagementService.recordOpen(7L, 42L) } returns false

        assertThrows<NoSuchElementException> { handler.markOpened(user.requireId(), 42L) }
    }
}
