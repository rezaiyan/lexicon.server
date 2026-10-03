package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.NotificationsConfig
import com.alirezaiyan.vokab.server.shared.UserSignedInEvent
import com.alirezaiyan.vokab.server.shared.UserSignedUpEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdminNotificationListenerTest {

    private lateinit var channel: NotificationChannel
    private lateinit var listener: AdminNotificationListener

    @BeforeEach
    fun setUp() {
        channel = mockk(relaxed = true)
        val appProperties = AppProperties(
            notifications = NotificationsConfig(
                admin = NotificationsConfig.AdminConfig(enabled = true)
            )
        )
        listener = AdminNotificationListener(channel, appProperties)
    }

    @Test
    fun `should send notification when user signs up`() {
        val event = UserSignedUpEvent(
            userId = 42L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google"
        )

        listener.onUserSignedUp(event)

        verify(exactly = 1) {
            channel.send(
                match { it.contains("New Signup") },
                match { it.contains("Ali") && it.contains("google") }
            )
        }
    }

    @Test
    fun `should not send notification when admin notifications are disabled`() {
        val disabledProps = AppProperties(
            notifications = NotificationsConfig(
                admin = NotificationsConfig.AdminConfig(enabled = false)
            )
        )
        val disabledListener = AdminNotificationListener(channel, disabledProps)

        val event = UserSignedUpEvent(
            userId = 1L,
            name = "Test",
            email = "test@example.com",
            provider = "apple"
        )

        disabledListener.onUserSignedUp(event)

        verify(exactly = 0) { channel.send(any(), any()) }
    }

    @Test
    fun `should not propagate channel exceptions`() {
        val failingChannel = mockk<NotificationChannel>()
        val listener = AdminNotificationListener(
            failingChannel,
            AppProperties(
                notifications = NotificationsConfig(
                    admin = NotificationsConfig.AdminConfig(enabled = true)
                )
            )
        )

        io.mockk.every { failingChannel.send(any(), any()) } throws RuntimeException("channel down")

        val event = UserSignedUpEvent(
            userId = 1L,
            name = "Test",
            email = "test@example.com",
            provider = "google"
        )

        // Must not throw
        listener.onUserSignedUp(event)
    }

    @Test
    fun `should send notification when user signs in`() {
        val event = UserSignedInEvent(
            userId = 1L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google"
        )

        listener.onUserSignedIn(event)

        verify(exactly = 1) {
            channel.send(
                match { it.contains("Login") },
                match { it.contains("Ali") && it.contains("google") }
            )
        }
    }

    @Test
    fun `should include platform and country in signup notification when present`() {
        val event = UserSignedUpEvent(
            userId = 1L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google",
            platform = "android",
            country = "IR"
        )

        listener.onUserSignedUp(event)

        verify(exactly = 1) {
            channel.send(
                any(),
                match { it.contains("android") && it.contains("IR") }
            )
        }
    }

    @Test
    fun `should include platform and country in login notification when present`() {
        val event = UserSignedInEvent(
            userId = 1L,
            name = "Ali",
            email = "ali@example.com",
            provider = "apple",
            platform = "ios",
            country = "US"
        )

        listener.onUserSignedIn(event)

        verify(exactly = 1) {
            channel.send(
                any(),
                match { it.contains("ios") && it.contains("US") }
            )
        }
    }

    @Test
    fun `should omit meta separator when platform and country are null`() {
        val event = UserSignedInEvent(
            userId = 1L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google"
        )

        listener.onUserSignedIn(event)

        verify(exactly = 1) {
            channel.send(
                any(),
                match { !it.contains("|") }
            )
        }
    }
}
