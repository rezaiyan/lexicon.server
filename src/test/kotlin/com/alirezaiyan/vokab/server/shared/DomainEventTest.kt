package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainEventTest {

    @Test
    fun `UserSignedUpEvent should carry user details and provider`() {
        val now = Instant.now()
        val event = UserSignedUpEvent(
            userId = 42L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google",
            occurredAt = now
        )

        assertEquals(42L, event.userId)
        assertEquals("Ali", event.name)
        assertEquals("ali@example.com", event.email)
        assertEquals("google", event.provider)
        assertEquals(now, event.occurredAt)
    }

    @Test
    fun `UserSignedUpEvent should default occurredAt to now`() {
        val before = Instant.now()
        val event = UserSignedUpEvent(
            userId = 1L,
            name = "Test",
            email = "test@example.com",
            provider = "apple"
        )
        val after = Instant.now()

        assert(!event.occurredAt.isBefore(before)) { "occurredAt should not be before creation time" }
        assert(!event.occurredAt.isAfter(after)) { "occurredAt should not be after creation time" }
    }

    @Test
    fun `UserSignedUpEvent should be a DomainEvent`() {
        val event: DomainEvent = UserSignedUpEvent(
            userId = 1L,
            name = "Test",
            email = "test@example.com",
            provider = "google"
        )

        assert(event is UserSignedUpEvent)
    }
}
